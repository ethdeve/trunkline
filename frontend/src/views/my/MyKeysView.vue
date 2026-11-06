<script setup lang="ts">
/**
 * 我的密钥（v3.5 Trunkline UI 改造：按原型实现 + 接真实数据）
 *
 * <p>数据源：GET /api/auth/api-keys/mine（服务端按当前登录用户解析归属，
 * 普通员工只能拿到自己名下的密钥），不再使用 mock 数据。</p>
 *
 * <p>「本月已用」取配额账本的 KEY 桶（自然月），金额为按可用虚拟模型的加权均价折算的<b>估算值</b>
 * ——与「全部密钥」页同一口径（金额只是估算视图，最终配额以 token 计）。</p>
 *
 * <p>原型的理念：员工不需要知道背后是哪个厂商模型，「可用虚拟模型」列展示的是
 * 虚拟模型名（公司主力 / 便宜日常…），而非厂商模型名。</p>
 *
 * <p><b>P0-L 越权泄漏收敛</b>：本页此前会调用 {@code GET /api/config/pools/list}
 * 拉取<b>全公司资源池</b>（含成员 instanceId / weight）来计算友好名与均价 ——
 * 该接口已对员工关闭（{@code config:pools:read} 不再授予 USER 角色）。
 * 现在友好名与加权均价改由「我的密钥」响应中的 {@code modelScope} 直接提供，
 * 本页<b>不再拉取任何跨员工数据</b>。</p>
 *
 * <p>i18n：文案走 {@code myKeys} 命名空间（zh-CN / en-US 各一份），
 * 静态 key 不动态拼接，避免拼错 key 静默回退显原始 key。</p>
 */
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { getMyApiKeys, revealMyApiKeyValue } from '@/api/apiKey'
import { getQuotaUsage } from '@/api/quota'
import type { ApiKeyVO } from '@/types'
import { employeeTerm } from '@/constants/employeeTerms'
import { friendlyError } from '@/utils/friendlyError'
import { copyToClipboard } from '@/utils/clipboard'
import { menuSubtitle } from '@/config/menu'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'

const { t } = useI18n()

const loading = ref(false)
const keys = ref<ApiKeyVO[]>([])

// 主列表表格内部滚动（自适应 max-height）
const keysTableRef = ref()
const { maxHeight: keysMaxHeight } = useTableMaxHeight(keysTableRef)
/** keyId → 本月已用 token 数（无记录按 0 计） */
const monthlyUsage = ref<Record<string, number>>({})
/** 用量查询失败的密钥（显示「—」区分「查询失败」与「没用过」） */
const usageUnknown = ref<Set<string>>(new Set())

const loadKeys = async () => {
  loading.value = true
  try {
    const list = await getMyApiKeys()
    keys.value = list?.items || []
    await loadMonthlyUsage()
  } catch (error: any) {
    ElMessage.error(friendlyError(error, t('myKeys.loadFailed')))
  } finally {
    loading.value = false
  }
}

/** 拉取每把密钥本月已用的 token（账本 KEY 桶，自然月）；单把失败不影响其他密钥 */
const loadMonthlyUsage = async () => {
  const usage: Record<string, number> = {}
  const unknown = new Set<string>()
  for (const key of keys.value) {
    try {
      const rows = await getQuotaUsage({ apiKeyId: key.keyId, window: 'MONTH' })
      usage[key.keyId] = rows.reduce((sum, r) => sum + (r.tokenCount || 0), 0)
    } catch {
      unknown.add(key.keyId)
    }
  }
  monthlyUsage.value = usage
  usageUnknown.value = unknown
}

/**
 * P1 员工侧④：白名单模型名的友好展示。
 *
 * <p>数据源为「我的密钥」响应里的 {@code modelScope}（服务端按白名单解析后下发）——
 * 不再需要拉取全公司资源池。</p>
 *
 * @param key 当前密钥（提供该密钥可见的分组信息）
 * @param poolName 分组名（池名）
 * @returns 友好名（与英文名不同才显示）；没有友好名时返回空串
 */
const friendlyModelName = (key: ApiKeyVO, poolName: string): string => {
  const entry = (key?.modelScope || []).find(s => s.poolName === poolName)
  return entry?.displayName || ''
}

/**
 * 该密钥可用虚拟模型的加权均价（¥ / 千 token）。
 *
 * <p>P0-L：均价由服务端随「我的密钥」一起下发（{@code ApiKeyVO.mixedPrice}）。
 * 前端不再自行拉取资源池成员与价格目录计算。</p>
 *
 * <p><b>应修8 口径统一</b>：此前前端对分组均价再做算术平均，与后端（成员权重加权）
 * 口径分叉。现改为直接读服务端下发的密钥级合计均价（与 {@code BillingService}
 * 计价口径一致）。白名单为空（不限制模型）或未配价时为 {@code null}，前端显示「—」。</p>
 */
const keyMixedPrice = (key: ApiKeyVO): number | null => {
  // 直接读服务端下发的密钥级合计均价，前端不做任何聚合计算。
  return key?.mixedPrice ?? null
}

/** token 数 → 金额估算（¥） */
const tokenToAmount = (tokens: number | null | undefined, price: number | null): number | null => {
  if (tokens === null || tokens === undefined || price === null) return null
  return (tokens / 1000) * price
}

/** 本月已用 token；该密钥用量查询失败时返回 null（页面显示「—」），没用过按 0 计 */
const usedTokens = (keyId: string): number | null => {
  if (usageUnknown.value.has(keyId)) return null
  return monthlyUsage.value[keyId] ?? 0
}

/** 到期状态：正常 / 即将到期 / 已过期 / 永不过期 */
type KeyStatus = 'normal' | 'expiring' | 'expired' | 'never'
const keyStatus = (key: ApiKeyVO): KeyStatus => {
  if (key.expired) return 'expired'
  if (!key.expiresAt) return 'never'
  const days = Math.ceil((new Date(key.expiresAt).getTime() - Date.now()) / 86400000)
  if (days <= 30) return 'expiring'
  return 'normal'
}

/** 状态文案走 i18n（不动态拼 key，避免拼错静默回退） */
const statusText = (s: KeyStatus): string => {
  switch (s) {
    case 'normal': return t('myKeys.statusNormal')
    case 'expiring': return t('myKeys.statusExpiring')
    case 'expired': return t('myKeys.statusExpired')
    case 'never': return t('myKeys.statusNever')
  }
}

const STATUS_TAG_TYPE: Record<KeyStatus, 'success' | 'warning' | 'danger' | 'info'> = {
  normal: 'success',
  expiring: 'warning',
  expired: 'danger',
  never: 'info'
}

const formatAmount = (value: number | null): string => (value === null ? '—' : `¥${value.toFixed(2)}`)
const formatTokens = (value: number | null): string => (value === null ? '—' : value.toLocaleString('zh-CN'))

/**
 * 数量列的单位（替代「tokens」）。
 *
 * 本页只有「本月已用」一处数量列，用日期前缀 + 统一单位即可说清，
 * 不需要解释 token 是什么——员工关心的是金额，数量只是折算不出金额时的兜底。
 */
const amountUnit = employeeTerm('amountUnit')

/** 到期日期（仅日期） */
const formatExpiry = (key: ApiKeyVO): string => {
  if (!key.expiresAt) return t('myKeys.neverExpires')
  return key.expiresAt.replace('T', ' ').substring(0, 10)
}

/**
 * P0-J：申请是「真出口」：「申请新密钥」→ 弹窗里填用途 → 一键复制现成的申请文本 → 发给管理员。
 * 后端申请流程仍未接入，但「复制文本发给管理员」是当下就能用的真路径。
 * （原「续期」操作列已移除：到期状态由状态列承载，续期走「申请新密钥」同一入口。）
 */
const applyVisible = ref(false)
const applyPurpose = ref('')

/** 申请文本模板（i18n 占位符 {purpose}） */
const buildApplyText = (purpose: string) =>
  t('myKeys.applyTextTemplate', { purpose: purpose || t('myKeys.notSpecified') })

/**
 * 复制密钥明文（2026-11 用户决策：明文加密存储，员工可随时取回自己的密钥）。
 *
 * 点击后调 reveal 接口解密真密钥 → 写入剪贴板。后端校验归属（仅本人）；
 * 存量未加密密钥（2026-11-05 之前创建）解密失败 → 提示先轮换。
 */
const copyingKeyId = ref<string | null>(null)
const copyRealKey = async (key: ApiKeyVO) => {
  copyingKeyId.value = key.keyId
  try {
    const plain = await revealMyApiKeyValue(key.keyId)
    const ok = await copyToClipboard(plain)
    if (ok) ElMessage.success(t('myKeys.keyCopied'))
    else ElMessage.warning(t('myKeys.copyFailed'))
  } catch (error: any) {
    ElMessage.warning(friendlyError(error, t('myKeys.revealFailed')))
  } finally {
    copyingKeyId.value = null
  }
}

/** 复制申请文本并提示 */
const copyRequest = async (text: string) => {
  const ok = await copyToClipboard(text)
  if (ok) ElMessage.success(t('myKeys.applyCopied'))
  else ElMessage.warning(t('myKeys.copyFailed'))
}

/** 提交申请 → 实际动作是「复制申请文本」 */
const submitApply = async () => {
  if (!applyPurpose.value.trim()) {
    ElMessage.warning(t('myKeys.purposeRequired'))
    return
  }
  await copyRequest(buildApplyText(applyPurpose.value.trim()))
  applyVisible.value = false
  applyPurpose.value = ''
}

onMounted(loadKeys)
</script>

<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <div class="card-head__text">
            <div class="card-head__title">{{ t('myKeys.title') }}</div>
            <!-- P0-I：页面目标说明统一取自 menu.ts（单一文案源），与侧边栏 tooltip 同源 -->
            <div class="card-head__sub">{{ menuSubtitle('/my-keys') }}</div>
          </div>
          <el-button type="primary" size="small" @click="applyVisible = true">{{ t('myKeys.applyNewKey') }}</el-button>
        </div>
      </template>
      <el-table ref="keysTableRef" :data="keys" :max-height="keysMaxHeight" v-loading="loading" stripe>
        <el-table-column prop="description" :label="t('myKeys.purpose')" min-width="140">
          <template #default="{ row }">
            <b>{{ row.description || t('myKeys.unnamed') }}</b>
          </template>
        </el-table-column>
        <el-table-column prop="keyMasked" :label="t('myKeys.keyPrefix')" min-width="210">
          <template #default="{ row }">
            <span v-if="row.keyMasked" class="key-prefix">
              <span>{{ row.keyMasked }}</span>
              <el-button
                text
                size="small"
                class="key-copy"
                :loading="copyingKeyId === row.keyId"
                :aria-label="t('myKeys.keyCopied')"
                @click="copyRealKey(row)"
              >
                <el-icon v-if="copyingKeyId !== row.keyId"><CopyDocument /></el-icon>
              </el-button>
            </span>
            <span v-else class="cell-none">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('myKeys.availableModels')" min-width="180">
          <template #default="{ row }">
            <template v-if="row.allowedGroups && row.allowedGroups.length > 0">
              <!-- P1 员工侧④：显示友好名（无友好名时只显示英文名） -->
              <span v-for="g in row.allowedGroups" :key="g" class="model-chip">
                <i></i>
                <span class="model-chip__text">{{ friendlyModelName(row, g) || g }}</span>
                <code v-if="friendlyModelName(row, g)" class="model-chip__raw">{{ g }}</code>
              </span>
            </template>
            <span v-else class="cell-none">{{ t('myKeys.allModelsAvailable') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('myKeys.monthlyUsage')" width="150" align="right">
          <template #default="{ row }">
            <div class="dual-cell">
              <span class="dual-cell__main">{{ formatTokens(usedTokens(row.keyId)) }} {{ amountUnit }}</span>
              <span class="dual-cell__sub">
                ≈ {{ formatAmount(tokenToAmount(usedTokens(row.keyId), keyMixedPrice(row))) }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('myKeys.expiry')" width="120">
          <template #default="{ row }">{{ formatExpiry(row) }}</template>
        </el-table-column>
        <el-table-column :label="t('myKeys.status')" width="110" fixed="right">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG_TYPE[keyStatus(row)]" size="small">
              {{ statusText(keyStatus(row)) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空态必须有出口：只说「还没有密钥」等于把员工困住 -->
      <div v-if="!loading && keys.length === 0" class="empty-with-action">
        <el-empty :description="t('myKeys.emptyTitle')" :image-size="70" />
        <el-button type="primary" size="small" @click="applyVisible = true">{{ t('myKeys.applyOne') }}</el-button>
        <div class="empty-hint">{{ t('myKeys.emptyHint') }}</div>
      </div>
    </el-card>

    <!-- 说明卡片（原型同款） -->
    <el-card shadow="never" style="margin-top: 12px; background: var(--ja-fill-color-light, #f5f7fa)">
      <h3 style="margin:0 0 8px;font-size:15px">{{ t('myKeys.helpTitle') }}</h3>
      <p style="margin:0;color:var(--ja-text-secondary);font-size:13px;line-height:1.7">
        {{ t('myKeys.helpBody') }}<br/>
        <b>{{ t('myKeys.helpFillName') }}</b>{{ t('myKeys.helpPlaceholder') }}
        <b>{{ t('myKeys.helpNoNeedToKnow') }}</b>{{ t('myKeys.helpNoFill') }}
      </p>
    </el-card>

    <el-dialog v-model="applyVisible" :title="t('myKeys.applyDialogTitle')" width="min(480px, 92vw)">
      <p style="color:var(--ja-text-secondary);font-size:13px">
        {{ t('myKeys.applyDialogDesc') }}
      </p>
      <el-input v-model="applyPurpose" type="textarea" :rows="3" :placeholder="t('myKeys.purposePlaceholder')" />
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-top:12px"
        :title="t('myKeys.applyAlertTitle')"
        :description="t('myKeys.applyAlertDesc')"
      />
      <template #footer>
        <el-button @click="applyVisible = false">{{ t('myKeys.cancel') }}</el-button>
        <el-button type="primary" @click="submitApply">{{ t('myKeys.copyApply') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* P0-I：卡片头（标题 + 页面目标说明） */
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-head__title {
  font-size: 15px;
  font-weight: 600;
}
.card-head__sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.key-prefix {
  font-family: var(--ja-font-mono);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.key-copy {
  padding: 2px;
  color: var(--ja-text-secondary);
}

/* 虚拟模型 chip（与「调用观测」页同一视觉） */
.model-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  margin: 2px 4px 2px 0;
  border-radius: 999px;
  font-size: 12px;
  background: var(--ja-bg-secondary, var(--ja-main-bg));
  color: var(--ja-text-secondary);
}

.model-chip i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ja-primary);
}

/* P1 员工侧④：友好名 + 英文名并列 */
.model-chip__text {
  font-weight: 600;
}

.model-chip__raw {
  font-family: var(--ja-font-mono);
  font-size: 11px;
  color: var(--ja-text-tertiary);
  padding: 0 4px;
  border-radius: 4px;
  background: var(--ja-bg-card, #fff);
}

.dual-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.35;
}

.dual-cell__main {
  font-size: 13px;
  color: var(--ja-text-primary);
}

.dual-cell__sub {
  font-size: 11.5px;
  color: var(--ja-text-secondary);
}

.cell-none {
  color: var(--ja-text-placeholder);
}

/* 空态 + 出口按钮 */
.empty-with-action {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding-bottom: 8px;
}

.empty-hint {
  font-size: 11.5px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}
</style>
