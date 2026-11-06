<template>
  <el-dialog
    v-model="visible"
    title="批量导入账号"
    width="min(800px, 92vw)"
    :close-on-click-modal="false"
    @closed="resetState"
  >
    <!-- ========== 第 1 步：粘贴名单 ========== -->
    <template v-if="phase === 'input'">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="bi-hint"
        title="每行一个账号：用户名, 部门, 角色（后两项可省略，角色默认 USER）"
        description="示例：zhangsan, 研发部, USER。系统会为每个账号自动生成随机初始密码，导入完成后可一次性复制或下载，分发给对应员工。"
      />
      <el-input
        v-model="rawInput"
        type="textarea"
        :rows="8"
        placeholder="zhangsan, 研发部, USER&#10;lisi, 市场部&#10;wangwu"
        class="bi-textarea"
      />

      <el-alert
        v-if="parseErrors.length"
        type="warning"
        :closable="false"
        show-icon
        class="bi-hint"
        :title="`有 ${parseErrors.length} 行格式有问题，导入时会跳过`"
        :description="parseErrors.slice(0, 5).join('；')"
      />

      <div v-if="parsedRows.length" class="bi-summary">
        将导入 <b>{{ parsedRows.length }}</b> 个账号
        <span v-if="dupWithExisting.length" class="bi-dup">
          （{{ dupWithExisting.length }} 个与现有账号重名，将被跳过：{{ dupWithExisting.join('、') }}）
        </span>
      </div>
    </template>

    <!-- ========== 第 2 步：导入结果 ========== -->
    <template v-else>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="bi-hint"
        title="初始密码只显示这一次，请立即复制或下载保存，然后分发给对应员工"
        description="建议提醒员工登录后尽快修改为自己的密码（当前版本员工可在「账号管理」外自行保管；服务端强制改密在后续版本提供）。"
      />
      <el-table :data="results" size="small" max-height="360" style="width: 100%">
        <el-table-column prop="username" label="用户名" min-width="110" />
        <el-table-column prop="department" label="部门" min-width="90">
          <template #default="{ row }">{{ row.department || '—' }}</template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="password" label="初始密码" min-width="140">
          <template #default="{ row }">
            <code v-if="row.password" class="bi-pwd">{{ row.password }}</code>
            <span v-else class="bi-fail" :title="row.error">{{ row.error || '失败' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="80" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.password"
              size="small"
              text
              @click="copyOne(row.password)"
            >
              复制
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="bi-result-bar">
        <span>
          成功 <b class="is-ok">{{ successCount }}</b> 个
          <template v-if="failCount">，失败 <b class="is-fail">{{ failCount }}</b> 个</template>
        </span>
        <div>
          <el-button size="small" @click="copyAll">复制全部</el-button>
          <el-button size="small" type="primary" plain @click="downloadCsv">下载 CSV</el-button>
        </div>
      </div>
    </template>

    <template #footer>
      <template v-if="phase === 'input'">
        <el-button @click="visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="importing"
          :disabled="importableRows.length === 0"
          @click="runImport"
        >
          {{ importing ? `导入中 ${doneCount}/${importableRows.length}…` : `开始导入（${importableRows.length} 个）` }}
        </el-button>
      </template>
      <template v-else>
        <el-button type="primary" @click="visible = false">完成</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 批量导入账号（P1 员工激活包①，无 SSO 过渡方案）
 *
 * <p><b>为什么只做前端循环</b>：后端已有单账号创建接口（POST /security/jwt/accounts），
 * 批量只是 N 次调用 + 进度展示，不新增后端面、不碰认证流，零后端风险。
 * 「首次登录强制改密」需要实体列 + 改密接口 + 登录流改造，作为独立后续任务
 * （见 docs/product/社区版完善规划.md）。</p>
 *
 * <p><b>初始密码策略</b>：前端用 crypto.getRandomValues 生成 12 位随机密码
 * （大小写 + 数字 + 符号，剔除易混淆字符），只展示一次——与「密钥明文只在
 * 创建时返回」同一安全口径。支持一键复制全部 / 下载 CSV 分发。</p>
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createJwtAccount } from '@/api/account'
import { copyToClipboard } from '@/utils/clipboard'

const props = defineProps<{
  /** 现有账号名列表（用于重名跳过提示） */
  existingUsernames: string[]
}>()

const emit = defineEmits<{
  /** 任一账号导入成功后触发（父组件刷新列表） */
  (e: 'imported'): void
}>()

const visible = defineModel<boolean>({ required: true })

type Phase = 'input' | 'result'
const phase = ref<Phase>('input')
const rawInput = ref('')
const importing = ref(false)

interface ImportRow {
  username: string
  department: string
  role: string
}
interface ImportResult extends ImportRow {
  password: string
  ok: boolean
  error?: string
}
const results = ref<ImportResult[]>([])

/** 允许的角色（与后端 JwtAccountRoleMigration 的角色集一致） */
const ALLOWED_ROLES = ['USER', 'OPERATOR', 'ADMIN']

/** 解析输入：一行 = 用户名[,部门[,角色]]；跳过空行与注释行 */
const parsed = computed<{ rows: ImportRow[]; errors: string[] }>(() => {
  const rows: ImportRow[] = []
  const errors: string[] = []
  const seen = new Set<string>()
  rawInput.value.split('\n').forEach((line, idx) => {
    const text = line.trim()
    if (!text || text.startsWith('#')) return
    const [username, department = '', role = 'USER'] = text.split(/[,，]/).map(s => s.trim())
    if (!/^[a-zA-Z0-9_.-]{2,64}$/.test(username || '')) {
      errors.push(`第 ${idx + 1} 行「${text}」：用户名只允许字母/数字/点/下划线/连字符，2-64 位`)
      return
    }
    if (seen.has(username)) {
      errors.push(`第 ${idx + 1} 行：用户名 ${username} 在名单里重复`)
      return
    }
    const upperRole = role.toUpperCase()
    if (!ALLOWED_ROLES.includes(upperRole)) {
      errors.push(`第 ${idx + 1} 行：角色必须是 ${ALLOWED_ROLES.join('/')} 之一`)
      return
    }
    seen.add(username)
    rows.push({ username, department, role: upperRole })
  })
  return { rows, errors }
})

const parseErrors = computed(() => parsed.value.errors)
const parsedRows = computed(() => parsed.value.rows)

/** 与现有账号重名的行（跳过，后端会报重复，提前拦住体验更好） */
const dupWithExisting = computed(() => {
  const existing = new Set(props.existingUsernames)
  return parsedRows.value.filter(r => existing.has(r.username)).map(r => r.username)
})

const importableRows = computed(() =>
  parsedRows.value.filter(r => !dupWithExisting.value.includes(r.username)))

const doneCount = ref(0)
const successCount = computed(() => results.value.filter(r => r.ok).length)
const failCount = computed(() => results.value.filter(r => !r.ok).length)

/* ---------- 初始密码生成 ---------- */

/** 剔除易混淆字符（0O1lI|`'";,.:）后的字符集 */
const CHARSETS = {
  lower: 'abcdefghijkmnpqrstuvwxyz',
  upper: 'ABCDEFGHJKLMNPQRSTUVWXYZ',
  digit: '23456789',
  symbol: '!@#$%^&*+-=?'
}

/** crypto 安全随机整数 */
const randInt = (max: number): number => {
  const buf = new Uint32Array(1)
  crypto.getRandomValues(buf)
  return buf[0] % max
}

const pick = (set: string): string => set[randInt(set.length)]

/** 生成 12 位初始密码：保证每类字符至少 1 个，其余随机补齐后洗牌 */
function generatePassword(): string {
  const parts = [
    pick(CHARSETS.lower), pick(CHARSETS.upper),
    pick(CHARSETS.digit), pick(CHARSETS.symbol)
  ]
  const all = Object.values(CHARSETS).join('')
  while (parts.length < 12) parts.push(pick(all))
  // Fisher-Yates 洗牌，避免「前四位固定是各类字符」的模式
  for (let i = parts.length - 1; i > 0; i--) {
    const j = randInt(i + 1)
    ;[parts[i], parts[j]] = [parts[j], parts[i]]
  }
  return parts.join('')
}

/* ---------- 导入执行 ---------- */

async function runImport() {
  importing.value = true
  doneCount.value = 0
  results.value = []
  phase.value = 'result'
  for (const row of importableRows.value) {
    const password = generatePassword()
    try {
      await createJwtAccount({
        username: row.username,
        password,
        roles: [row.role],
        department: row.department || undefined,
        enabled: true
      })
      results.value.push({ ...row, password, ok: true })
      emit('imported')
    } catch (e: any) {
      results.value.push({
        ...row,
        password: '',
        ok: false,
        error: e?.response?.data?.message || e?.message || '创建失败'
      })
    } finally {
      doneCount.value++
    }
  }
  importing.value = false
}

/* ---------- 结果导出 ---------- */

async function copyOne(password: string) {
  const ok = await copyToClipboard(password)
  ok ? ElMessage.success('已复制') : ElMessage.warning('复制失败，请手动选中复制')
}

async function copyAll() {
  const text = results.value
    .filter(r => r.ok)
    .map(r => `${r.username}  ${r.password}  (${r.role}${r.department ? ' / ' + r.department : ''})`)
    .join('\n')
  const ok = await copyToClipboard(text)
  ok ? ElMessage.success(`已复制 ${successCount.value} 个账号的初始密码`) : ElMessage.warning('复制失败')
}

function downloadCsv() {
  const lines = ['username,department,role,initialPassword']
  for (const r of results.value.filter(r => r.ok)) {
    // CSV 转义：部门名可能含逗号（理论上不会，但防御一下）
    const esc = (s: string) => `"${(s || '').replace(/"/g, '""')}"`
    lines.push([r.username, esc(r.department), r.role, r.password].join(','))
  }
  const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `账号初始密码-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

function resetState() {
  phase.value = 'input'
  rawInput.value = ''
  results.value = []
  doneCount.value = 0
  importing.value = false
}

// 弹窗打开时清空上一轮状态
watch(visible, v => {
  if (v) resetState()
})
</script>

<style scoped>
.bi-hint {
  margin-bottom: 12px;
}
.bi-textarea {
  font-family: var(--ja-font-mono);
}
.bi-summary {
  margin-top: 10px;
  font-size: 13px;
  color: var(--ja-text-primary);
}
.bi-dup {
  color: var(--el-color-warning);
}
.bi-pwd {
  font-family: var(--ja-font-mono);
  font-size: 12.5px;
  background: var(--ja-fill-color-light, #f5f7fa);
  padding: 2px 8px;
  border-radius: 6px;
}
.bi-fail {
  color: var(--el-color-danger);
  font-size: 12px;
}
.bi-result-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  font-size: 13px;
  color: var(--ja-text-primary);
}
.bi-result-bar .is-ok { color: var(--el-color-success); }
.bi-result-bar .is-fail { color: var(--el-color-danger); }
</style>
