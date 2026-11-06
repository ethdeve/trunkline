<script setup lang="ts">
/**
 * 快速上手：三步把公司 AI 接到你常用的工具里
 *
 * <p><b>v3.5 改造（S2）</b>：此前这里是硬编码的假数据
 * （`https://ai.yourcompany.com/v1` + `sk-trunkline-9fK2••4aQz`），
 * 员工照着做第一步就失败。现在：</p>
 * <ul>
 *   <li>地址 ← {@code GET /api/gateway/info}，由请求上下文推导，反代/多副本都能拿到真实可达地址</li>
 *   <li>密钥 ← {@code GET /api/auth/api-keys/mine}，只展示前缀 + 引导去「我的密钥」复制</li>
 * </ul>
 *
 * <p><b>为什么不直接展示密钥明文</b>：本库密钥明文<b>仅在创建时返回一次</b>，
 * 之后只存哈希。任何「回显明文」的做法都只能造假或降级存储安全性，
 * 因此这里改为「列出你名下的密钥 + 去复制」的入口。</p>
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
import { getGatewayInfo, type GatewayInfo } from '@/api/gateway'
import { getMyApiKeys } from '@/api/apiKey'
import { copyToClipboard } from '@/utils/clipboard'
import type { ApiKeyVO } from '@/types'

const router = useRouter()

const loading = ref(true)
const gateway = ref<GatewayInfo | null>(null)
const keys = ref<ApiKeyVO[]>([])

/** 可直接用于工具配置的接入地址（后端已保证带 /v1） */
const baseUrl = computed(() => gateway.value?.baseUrl || '')
/** 推荐优先使用的那把密钥：优先未过期、且描述非空的第一把 */
const primaryKey = computed<ApiKeyVO | null>(() => {
  const usable = keys.value.filter(k => !k.expired)
  return usable[0] || keys.value[0] || null
})
/** 常用虚拟模型名：从密钥的 allowedGroups 汇总，供「model 那一栏填什么」示例 */
const sampleModels = computed(() => {
  const set = new Set<string>()
  for (const k of keys.value) {
    for (const g of k.allowedGroups || []) {
      if (g) set.add(g)
    }
  }
  return [...set].slice(0, 3)
})

const copied = ref('')
/**
 * 复制地址。
 *
 * <p>用共享的 {@link copyToClipboard} 而不是直接 {@code navigator.clipboard} ——
 * 企业内网常见 http（非安全上下文），此时 {@code navigator.clipboard} 为 undefined，
 * 直接调用会静默失败，员工点了以为复制成功了。</p>
 */
async function copy(text: string, tag: string) {
  if (!text) return
  const ok = await copyToClipboard(text)
  if (!ok) {
    ElMessage.warning('复制失败，请手动选中上面的地址')
    return
  }
  copied.value = tag
  setTimeout(() => (copied.value = ''), 1200)
}

const goMyKeys = () => router.push('/my-keys')
const goPlayground = () => router.push('/playground')

/**
 * P1 员工侧①：快速上手「双分支」。
 *
 * <p>原来是一张卡片里第 2 步分叉（有密钥/没密钥），读者要自己判断走哪条路。
 * 现在按「你有没有密钥」分成两条独立路径：</p>
 * <ul>
 *   <li><b>分支 A（有密钥）</b>：填工具 → 验证能用 → 完成。3 步。</li>
 *   <li><b>分支 B（没密钥）</b>：先领密钥 → 等开通 → 再回到填工具。领不到就联系管理员。</li>
 * </ul>
 */
const branch = computed<'hasKey' | 'noKey'>(() => (keys.value.length > 0 ? 'hasKey' : 'noKey'))

/**
 * P1 员工侧①：验证步骤的示例命令。
 *
 * <p>给出可直接粘贴的 curl —— 让员工「跑一下看返回」而不是「照着配然后猜」。
 * 密钥明文只在创建时给过一次，这里用占位符，由员工自己替换成真实密钥。</p>
 */
const verifyCommand = computed(() => {
  const model = sampleModels.value[0] || 'company-chat'
  const url = baseUrl.value || 'https://your-gateway/v1'
  return `curl ${url}/chat/completions \\\n  -H "Content-Type: application/json" \\\n  -H "Authorization: Bearer <你的密钥>" \\\n  -d '{"model":"${model}","messages":[{"role":"user","content":"你好"}]}'`
})

const copiedVerify = ref(false)
async function copyVerify() {
  const ok = await copyToClipboard(verifyCommand.value)
  if (!ok) {
    ElMessage.warning('复制失败，请手动选中上面的命令')
    return
  }
  copiedVerify.value = true
  setTimeout(() => (copiedVerify.value = false), 1500)
}

/**
 * P1 员工侧②：接入配置二维码。
 *
 * <p>员工常在手机上被拉进「AI 工具交流群」再回到电脑前配工具——扫码把接入配置
 * 带走，比手抄地址少错一步。内容是纯文本配置卡（地址 / 可用模型 / 密钥指引）。</p>
 *
 * <p><b>刻意不放密钥</b>：密钥明文只在创建时返回一次（见文件头注释），这里本就
 * 拿不到明文；且截图/转发场景下二维码里的密钥就是泄漏面。扫码得到的文案引导
 * 员工回到「我的密钥」页复制，与页面主路径一致。</p>
 */
const qrDataUrl = ref('')
const qrPayload = computed(() => {
  if (!baseUrl.value) return ''
  const models = sampleModels.value.length ? sampleModels.value.join('、') : '（以管理员分配为准）'
  return [
    'Trunkline AI 接入配置',
    `地址：${baseUrl.value}`,
    `模型：${models}`,
    '密钥：登录控制台 → 我的密钥 → 复制',
    'API Key 一栏填密钥，接口地址一栏填上面的地址'
  ].join('\n')
})

async function renderQr() {
  if (!qrPayload.value) {
    qrDataUrl.value = ''
    return
  }
  try {
    qrDataUrl.value = await QRCode.toDataURL(qrPayload.value, { width: 168, margin: 1 })
  } catch {
    // 二维码生成失败不影响主流程（复制地址仍可用）
    qrDataUrl.value = ''
  }
}

const load = async () => {
  loading.value = true
  // 两个请求相互独立：任一失败都不应让整页空白
  const [gw, myKeys] = await Promise.allSettled([getGatewayInfo(), getMyApiKeys()])

  if (gw.status === 'fulfilled') {
    gateway.value = gw.value
  } else {
    ElMessage.error('获取接入地址失败，请刷新页面重试')
  }

  if (myKeys.status === 'fulfilled') {
    keys.value = myKeys.value?.items || []
  } else {
    keys.value = []
  }

  loading.value = false
  await renderQr()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <!-- ============ P1 员工侧①：双分支引导 ============ -->
    <el-card shadow="never" class="qs-card">
      <h2 class="qs-title">
        {{ branch === 'hasKey' ? '三步把密钥装进你常用的 AI 工具' : '先领一把密钥' }}
      </h2>
      <p class="qs-sub">
        {{
          branch === 'hasKey'
            ? '不需要自己去 OpenAI/Anthropic 充值，把公司给你的密钥填到你常用的 AI 工具里就行。'
            : '公司 AI 的调用需要一把密钥。先找管理员申请，拿到之后按下面三步接进你的工具。'
        }}
      </p>

      <!-- ============ 分支 A：已有密钥 ============ -->
      <template v-if="branch === 'hasKey'">
        <el-row :gutter="16" class="qs-steps">
          <el-col :span="12">
            <div class="qs-step">
              <div class="qs-step__label">第 1 步 · 复制地址</div>
              <div class="qs-code" :class="{ 'is-empty': !baseUrl }">
                {{ baseUrl || '地址暂时获取不到' }}
              </div>
              <el-button size="small" :disabled="!baseUrl" @click="copy(baseUrl, 'url')">
                {{ copied === 'url' ? '✓ 已复制' : '📋 复制地址' }}
              </el-button>
            </div>
          </el-col>
          <el-col :span="12">
            <div class="qs-step">
              <div class="qs-step__label">第 2 步 · 拿到密钥</div>
              <div class="qs-code">
                {{ primaryKey?.keyMasked || '••••••••' }}
                <span v-if="primaryKey?.description" class="qs-key-desc">{{ primaryKey.description }}</span>
              </div>
              <el-button size="small" type="primary" @click="goMyKeys">去「我的密钥」复制</el-button>
              <div v-if="keys.length > 1" class="qs-hint">
                你名下一共有 {{ keys.length }} 把密钥，可在「我的密钥」里挑选
              </div>
            </div>
          </el-col>
        </el-row>

        <el-divider>第 3 步 · 填到工具里，然后验证一下</el-divider>

        <div class="qs-tools">
          <div class="qs-tool">
            <div class="qs-tool__name">Cherry Studio</div>
            <div class="qs-tool__desc">设置 → 模型服务 → 添加 → 选 OpenAI，地址和密钥粘进去</div>
          </div>
          <div class="qs-tool">
            <div class="qs-tool__name">Cursor</div>
            <div class="qs-tool__desc">设置 → 模型 → OpenAI，把地址填到接口地址那一栏，密钥填到 API Key</div>
          </div>
          <div class="qs-tool">
            <div class="qs-tool__name">ChatBox / NextChat</div>
            <div class="qs-tool__desc">设置 → 接口地址填上面的 URL，API Key 填密钥</div>
          </div>
        </div>

        <!-- model 字段示例跟随该员工实际可用的虚拟模型，避免给一个他用不了的例子 -->
        <el-alert
          type="info"
          :closable="false"
          style="margin-top: 16px"
        >
          <template #title>
            在工具里 model 那一栏填
            <b v-if="sampleModels.length === 0">可用模型名</b>
            <template v-else>
              <code v-for="m in sampleModels" :key="m" class="qs-model">{{ m }}</code>
            </template>
            这种可用模型名，不要填 gpt-4o。
          </template>
        </el-alert>

        <!-- ============ P1 员工侧①：验证步骤 ============ -->
        <div class="qs-verify">
          <h3 class="qs-verify__title">验证一下真的通了</h3>
          <p class="qs-verify__desc">
            配好之后不想在工具里试？把下面这条命令粘到终端里跑一下，
            <b>能返回一段文字就说明配对了</b>；报错就把错误整段发给管理员。
          </p>
          <pre class="qs-verify__code">{{ verifyCommand }}</pre>
          <div class="qs-verify__ops">
            <el-button size="small" @click="copyVerify">
              {{ copiedVerify ? '✓ 已复制' : '📋 复制命令' }}
            </el-button>
            <el-button size="small" type="primary" plain @click="goPlayground">
              也可以直接在测试场试一句 →
            </el-button>
          </div>
        </div>

        <!-- ============ P1 员工侧②：接入配置二维码 ============ -->
        <div v-if="qrDataUrl" class="qs-qr">
          <img :src="qrDataUrl" alt="接入配置二维码" class="qs-qr__img" />
          <div class="qs-qr__text">
            <b>手机扫码，配置带走</b>
            <p>扫出来是接入地址和模型名。密钥不在二维码里（防止截图泄漏），拿到手机上也回「我的密钥」复制。</p>
          </div>
        </div>
      </template>

      <!-- ============ 分支 B：还没有密钥 ============ -->
      <template v-else>
        <ol class="qs-path">
          <li class="qs-path__item">
            <div class="qs-path__head">
              <span class="qs-path__idx">1</span>
              <b>告诉管理员你要做什么</b>
            </div>
            <p class="qs-path__desc">
              在「我的密钥」页点「申请新密钥」，写一句用途（比如「Q4 出差客户拜访，需要临时用 AI 写方案」），
              内容会自动复制好，粘贴给管理员或发到企业微信即可。
            </p>
            <el-button size="small" type="primary" @click="goMyKeys">去「我的密钥」申请 →</el-button>
          </li>
          <li class="qs-path__item">
            <div class="qs-path__head">
              <span class="qs-path__idx">2</span>
              <b>等管理员开通</b>
            </div>
            <p class="qs-path__desc">
              通常 1 个工作日内完成。开通后你会拿到一把密钥，同时管理员会告诉你
              <b>能用哪几个模型名</b>（比如 company-chat）和你有多少额度。
            </p>
            <div class="qs-path__note">
              还没等到？直接在企业微信里催一下管理员就行，不用重复申请。
            </div>
          </li>
          <li class="qs-path__item">
            <div class="qs-path__head">
              <span class="qs-path__idx">3</span>
              <b>拿到密钥后回来照着配</b>
            </div>
            <p class="qs-path__desc">
              密钥到手后本页会变成「三步接入」的样子：复制地址 → 复制密钥 → 填进工具 → 验证。
            </p>
          </li>
        </ol>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin-top: 16px"
          title="为什么需要密钥？"
          description="密钥就像公司 AI 的「门卡」，系统靠它判断谁、用哪些模型、花多少额度。没有密钥任何工具都调不通。"
        />
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.qs-card { max-width: 860px; margin: 0 auto; }
.qs-title { margin: 0 0 6px; font-size: 20px; }
.qs-sub { color: var(--ja-text-secondary); font-size: 13px; margin: 0 0 20px; }
.qs-steps { margin-bottom: 8px; }
.qs-step {
  background: var(--ja-fill-color-light, #f5f7fa);
  border-radius: 12px;
  padding: 16px;
  height: 100%;
}
.qs-step__label { font-size: 12px; font-weight: 700; color: var(--ja-primary); margin-bottom: 8px; }
.qs-code {
  font-family: var(--ja-font-mono);
  font-size: 13px;
  background: var(--ja-bg-card, #fff);
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--ja-border, #e6e6e6);
  word-break: break-all;
  margin-bottom: 10px;
  min-height: 20px;
}
.qs-code.is-empty { color: var(--ja-text-secondary); }
.qs-key-desc {
  display: block;
  margin-top: 4px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ja-text-secondary);
}
.qs-hint {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--ja-text-secondary);
  line-height: 1.6;
}
/* ---- P1 员工侧②：接入配置二维码 ---- */
.qs-qr {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
  padding: 14px 16px;
  background: var(--ja-fill-color-light, #f5f7fa);
  border-radius: 12px;
}
.qs-qr__img {
  width: 128px;
  height: 128px;
  border-radius: 8px;
  /* 二维码功能性白底：扫码识别需要浅底，刻意不随暗色切换 */
  background: #fff; /* verify-disable */
  padding: 6px;
  flex-shrink: 0;
}
.qs-qr__text b { font-size: 13.5px; color: var(--ja-text-primary); }
.qs-qr__text p {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.7;
}
.qs-tools { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.qs-tool {
  border: 1px solid var(--ja-border, #e6e6e6);
  border-radius: 10px;
  padding: 14px;
}
.qs-tool__name { font-weight: 600; font-size: 14px; margin-bottom: 4px; }
.qs-tool__desc { font-size: 12px; color: var(--ja-text-secondary); line-height: 1.6; }

/* ---- P1 员工侧①：验证步骤 ---- */
.qs-verify {
  margin-top: 20px;
  padding: 16px;
  border-radius: 12px;
  background: var(--ja-fill-color-light, #f5f7fa);
  border: 1px solid var(--ja-border, #e6e6e6);
}
.qs-verify__title { margin: 0 0 6px; font-size: 14px; font-weight: 700; }
.qs-verify__desc { margin: 0 0 10px; font-size: 12.5px; color: var(--ja-text-secondary); line-height: 1.7; }
.qs-verify__code {
  margin: 0 0 10px;
  padding: 12px;
  border-radius: 8px;
  background: var(--ja-bg-card, #fff);
  border: 1px solid var(--ja-border, #e6e6e6);
  font-family: var(--ja-font-mono);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ja-text-primary);
  white-space: pre-wrap;
  word-break: break-all;
}
.qs-verify__ops { display: flex; gap: 8px; flex-wrap: wrap; }

/* ---- P1 员工侧①：分支 B 路径 ---- */
.qs-path { list-style: none; margin: 0; padding: 0; }
.qs-path__item {
  position: relative;
  padding: 14px 16px 14px 48px;
  margin-bottom: 10px;
  border-radius: 12px;
  background: var(--ja-fill-color-light, #f5f7fa);
}
.qs-path__head { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.qs-path__idx {
  position: absolute;
  left: 14px;
  top: 14px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ja-primary, #176C6B);
  /* 暗色下主色提亮为浅青，白字对比不足；用卡片底色文字（亮=白 / 暗=深） */
  color: var(--ja-bg-card);
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.qs-path__desc { margin: 6px 0 10px; font-size: 12.5px; color: var(--ja-text-secondary); line-height: 1.7; }
.qs-path__note {
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--ja-text-secondary);
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--ja-bg-card, #fff);
}

.qs-model {
  display: inline-block;
  padding: 1px 6px;
  margin: 0 4px;
  border-radius: 4px;
  background: var(--ja-bg-card, #fff);
  font-size: 12px;
}
</style>
