<script setup lang="ts">
/**
 * 我的额度（v3.5 Trunkline UI 改造：按原型实现员工版额度首页）
 *
 * <p>原型的核心叙事：「这个月我用了多少、还剩多少、钱花在哪了、我常用的模型是哪个」。
 * 因此页面结构是：</p>
 * <ol>
 *   <li><b>Hero 左</b>——本月已用额度（金额 + 进度条 + 剩余 + 申请按钮）</li>
 *   <li><b>Hero 右</b>——我的接入密钥（一眼看到密钥、能复制）</li>
 *   <li><b>3 张统计卡</b>——本月调用次数 / 可用虚拟模型 / 本周最常用</li>
 *   <li><b>底部两栏</b>——本月每天花了多少钱（折线图）+ 最近几次使用（表）</li>
 * </ol>
 *
 * <p><b>额度上限的口径</b>：系统里密钥配的是 <b>token 限额</b>（{@code monthlyTokenLimit}），
 * 没有独立的金额预算。这里按「全部密钥」页的同一口径折算成金额
 * （token 限额 × 该密钥可用虚拟模型的加权均价），进度条按 token 用量/限额计算——
 * 两处数字自洽，不会出现「金额进度条」与「token 进度条」打架。</p>
 *
 * <p>数据源：
 * <ul>
 *   <li>本月花费与每日曲线 —— {@code GET /api/billing/my}（byDay 字段）</li>
 *   <li>本周最常用 —— {@code GET /api/billing/my/recent?days=7}</li>
 *   <li>我的密钥 —— {@code GET /api/auth/api-keys/mine}</li>
 *   <li>最近几次使用 —— {@code GET /api/call-history/mine}（服务端按当前登录用户强制过滤）</li>
 * </ul></p>
 */
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import echarts from '@/utils/echarts'
import type { ECharts } from '@/utils/echarts'
import { getMyBills, getMyRecentUsage, type MyBillsVO, type MyRecentUsageVO } from '@/api/billing'
import { getMyApiKeys } from '@/api/apiKey'
import { queryCallHistoryMine } from '@/api/callHistory'
import type { ApiKeyVO } from '@/types'
import type { ApiCallHistoryRecord } from '@/types/callHistory'
import { employeeTerm } from '@/constants/employeeTerms'
import { friendlyError } from '@/utils/friendlyError'

const router = useRouter()

/**
 * 员工侧用量单位（替代「tokens」）。
 *
 * 本页所有数量列都从这里取单位，确保「改一处生效全页」——散在各处的
 * 「XXX tokens」正是 S3 要清理的对象。
 */
const amountUnit = employeeTerm('amountUnit')

const loading = ref(false)
const monthValue = ref<string>(currentMonth())
const bills = ref<MyBillsVO>(emptyBills())
const recent = ref<MyRecentUsageVO>(emptyRecent())
const myKeys = ref<ApiKeyVO[]>([])
const recentCalls = ref<ApiCallHistoryRecord[]>([])

function emptyBills(): MyBillsVO {
  return {
    month: '', currency: 'CNY', totalAmount: 0, totalTokens: 0, promptTokens: 0,
    completionTokens: 0, requestCount: 0, previousMonthAmount: 0, previousMonthRequestCount: 0,
    byModel: [], byDay: []
  }
}

function emptyRecent(): MyRecentUsageVO {
  return { startDate: '', endDate: '', totalAmount: 0, totalTokens: 0, requestCount: 0, byVirtualModel: [] }
}

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

/* ---------------- 额度：已用 / 上限 / 进度 ---------------- */

/** 本月 token 用量（额度进度按 token 口径，与「全部密钥」页一致） */
const usedTokens = computed(() => Number(bills.value.totalTokens || 0))

/** 我的密钥的月度 token 限额合计（多把密钥相加；0 = 不限） */
const tokenLimit = computed(() => {
  const sum = myKeys.value.reduce((acc, k) => acc + Number(k.monthlyTokenLimit || 0), 0)
  return sum
})

/**
 * 本月均价（¥ / 千 token）；无法计算时返回 null。
 *
 * <p>「我的额度」页的核心换算基准：金额上限、单条调用花费都由它反推，
 * 集中一处避免多处各算一遍导致数字打架。</p>
 */
const averagePrice = computed<number | null>(() => {
  const tokens = usedTokens.value
  if (tokens <= 0) return null
  const amount = Number(bills.value.totalAmount || 0)
  if (amount <= 0) return null
  const price = amount / (tokens / 1000)
  return Number.isFinite(price) && price > 0 ? price : null
})

/**
 * 额度上限折算成金额（按本月实际均价反推，与「全部密钥」页同一换算口径）。
 *
 * <p>本月零消耗时无法反推均价 → 返回 null。此时<b>不代表没有上限</b>，
 * 模板会改用 token 口径展示（见 hero 区），不能写成「未设上限」。
 */
const limitAmount = computed<number | null>(() => {
  if (tokenLimit.value <= 0 || averagePrice.value === null) return null
  return (tokenLimit.value / 1000) * averagePrice.value
})

/** 已用占上限的百分比（0~100）；无上限时返回 null */
const usedPercent = computed<number | null>(() => {
  if (tokenLimit.value <= 0) return null
  return Math.min(100, Math.round((usedTokens.value / tokenLimit.value) * 1000) / 10)
})

/** 剩余金额（¥） */
const remainingAmount = computed<number | null>(() => {
  if (limitAmount.value === null) return null
  return Math.max(0, limitAmount.value - Number(bills.value.totalAmount || 0))
})

/* ---------------- 统计卡 ---------------- */

/**
 * 可用虚拟模型（我的密钥的分组并集）。
 *
 * <p><b>白名单为空的语义</b>：后端 {@code ApiKey.isModelAllowed} 判定
 * {@code allowedGroups} 为空即「不限制」（放行所有模型）。所以这里为空时
 * 应当显示「不限制」而不是「没有可用模型」—— 否则会骗员工以为自己一把密钥都用不了。</p>
 */
const availableVirtualModels = computed<string[]>(() => {
  const set = new Set<string>()
  for (const k of myKeys.value) {
    for (const g of k.allowedGroups || []) {
      if (g) set.add(g)
    }
  }
  return Array.from(set)
})

/** 是否有密钥「不限制模型」（白名单为空） */
const hasUnrestrictedKey = computed(() =>
  myKeys.value.some(k => !k.allowedGroups || k.allowedGroups.length === 0)
)

/** 本周最常用（按花费降序的第一个） */
const topVirtualModel = computed(() => recent.value.byVirtualModel?.[0] || null)

/**
 * P1 员工侧②：「我能用的模型」区块里每个模型的一句说明。
 *
 * <p><b>刻意不去拉全量池列表</b>（{@code GET /api/config/pools/list}）拿池的描述：
 * 那个接口返回公司<b>所有</b>虚拟模型及成员，员工读它等于越权看到全公司的模型构成
 * （已记为 P0-L，第 4 批收窄）。这里只用员工自己已有的数据 —— 名下密钥的白名单 —— 推导说明，
 * 既不扩大可见面，也能回答员工真正关心的问题：「这个模型我能用吗、拿哪把密钥用」。</p>
 */
const modelDesc = (model: string): string => {
  const owners = myKeys.value.filter(k => (k.allowedGroups || []).includes(model))
  if (owners.length === 0) return '由管理员分配给你'
  const names = owners.map(k => k.description || '未命名密钥')
  if (owners.length === 1) return `用密钥「${names[0]}」调用`
  return `可用密钥：${names.join('、')}`
}

/* ---------------- 顶部欢迎语：按用户实际状态分支 ---------------- */

/**
 * 员工进入页面时的三种状态。
 *
 * <p>原型要求「顶部欢迎语 + 状态感知」：不同处境的人需要不同的下一步动作，
 * 一句通用的「欢迎回来」等于什么也没说。</p>
 * <ul>
 *   <li>{@code noKey}   —— 名下没有密钥，任何调用都做不了 → 引导去申请</li>
 *   <li>{@code unused}  —— 有密钥但本月零消耗，可能配好了却没开始用 → 引导去测试场</li>
 *   <li>{@code normal}  —— 正常使用中 → 直接报出已用与剩余</li>
 * </ul>
 */
type WelcomeState = 'noKey' | 'unused' | 'normal'
const welcomeState = computed<WelcomeState>(() => {
  if (myKeys.value.length === 0) return 'noKey'
  if (usedTokens.value <= 0 && Number(bills.value.requestCount || 0) === 0) return 'unused'
  return 'normal'
})

/** 本月均价（¥ / 千 token）；无法计算时返回 null */
/* averagePrice 已在「额度」小节顶部定义，此处不再重复 */

/**
 * 单条调用记录的花费估算（¥）。
 *
 * <p>调用历史表里只落了 token 数、没有金额（金额是事后按价格目录折算的），
 * 因此这里用<b>本月实际均价</b>反推单条花费——这样列出来的一组金额之和
 * 与本页顶部「本月已用」自洽，不会出现「明细加起来 ≠ 总数」。
 * 精确到分的账单在「我的账单」页。</p>
 *
 * @returns 无法估算（本月无花费/无单价）时返回 null，页面显示 token 数而非 ¥0
 */
const callCost = (row: ApiCallHistoryRecord): number | null => {
  if (averagePrice.value === null) return null
  const tokens = Number(row.totalTokens || 0)
  if (tokens <= 0) return null
  return (tokens / 1000) * averagePrice.value
}

/* ---------------- 每日花费曲线 ---------------- */

/* ---------------- 每日花费曲线 ---------------- */

const chartEl = ref<HTMLElement | null>(null)
let trendChart: ECharts | null = null

const renderTrendChart = () => {
  const el = chartEl.value
  if (!el) return
  const days = [...(bills.value.byDay || [])].reverse() // 按日期升序
  trendChart = trendChart || echarts.init(el)
  trendChart.setOption({
    grid: { left: 48, right: 16, top: 16, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v: number) => `¥${Number(v || 0).toFixed(2)}`
    },
    xAxis: {
      type: 'category',
      data: days.map(d => shortDate(d.usageDate)),
      axisLine: { lineStyle: { color: 'var(--ja-border, #e6e6e6)' } },
      axisLabel: { color: 'var(--ja-text-secondary, #6b7d7c)', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: '元',
      nameTextStyle: { color: 'var(--ja-text-secondary, #6b7d7c)', fontSize: 11 },
      splitLine: { lineStyle: { color: 'rgba(16,38,38,.06)' } },
      axisLabel: { color: 'var(--ja-text-secondary, #6b7d7c)', fontSize: 11 }
    },
    series: [{
      type: 'line',
      smooth: true,
      data: days.map(d => Number(d.amount || 0)),
      lineStyle: { width: 2.5, color: 'var(--ja-primary, #176C6B)' },
      itemStyle: { color: 'var(--ja-primary, #176C6B)' },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(64,158,255,.18)' },
          { offset: 1, color: 'rgba(64,158,255,.01)' }
        ])
      }
    }]
  })
}

/** '2026-10-30' → '10/30' */
const shortDate = (d?: string): string => {
  if (!d) return ''
  const parts = d.split('-')
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : d
}

/* ---------------- 数据加载 ---------------- */

const loadAll = async () => {
  loading.value = true
  try {
    const [y, m] = (monthValue.value || currentMonth()).split('-')
    const tasks: Promise<any>[] = [
      getMyBills(Number(y), Number(m)),
      getMyRecentUsage(7),
      getMyApiKeys()
    ]
    // 最近几次使用：走员工自助端点，归属由服务端从登录态强制解析
    tasks.push(queryCallHistoryMine({ page: 0, size: 5 }))

    const [billsRes, recentRes, keysRes, callsRes] = await Promise.all(tasks)

    bills.value = { ...emptyBills(), ...(billsRes?.data?.data || {}) }
    recent.value = { ...emptyRecent(), ...(recentRes?.data?.data || {}) }
    myKeys.value = keysRes?.items || []
    recentCalls.value = (callsRes?.content || []).slice(0, 5)

    await nextTick()
    renderTrendChart()
  } catch (error: any) {
    ElMessage.error(friendlyError(error, '加载额度信息'))
  } finally {
    loading.value = false
  }
}

/** 复制密钥的展示串（脱敏串无法用于认证——完整密钥只在创建时给过一次，这里复制的是展示串） */
const copied = ref('')
const copyKey = async (text?: string) => {
  if (!text) {
    ElMessage.warning('没有可复制的密钥串')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    copied.value = text
    setTimeout(() => (copied.value = ''), 1200)
    // 脱敏串复制出去也调不通，必须说清楚，否则用户会以为「复制了就配好了」
    ElMessage.warning('复制的是脱敏串，不能直接用于调用。完整密钥只在创建时显示一次，如已遗失请联系管理员重置')
  } catch {
    ElMessage.error('复制失败，请手动选中复制')
  }
}

const applyVisible = ref(false)
const applyAmount = ref<number | null>(null)

const submitApply = () => {
  ElMessage.info('申请功能尚未接入，请联系管理员为你调整额度')
  applyVisible.value = false
}

const formatAmount = (value?: number | null): string =>
  value === null || value === undefined ? '—' : `¥${Number(value).toFixed(2)}`

const formatNumber = (value?: number | null): string => (Number(value) || 0).toLocaleString('zh-CN')

/** 最近使用的展示：时间（HH:mm 或 昨天 HH:mm） */
const callTime = (iso?: string): string => {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  const now = new Date()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) return `${hh}:${mm}`
  const isYesterday = new Date(now.getTime() - 86400000).toDateString() === d.toDateString()
  if (isYesterday) return `昨天 ${hh}:${mm}`
  return `${d.getMonth() + 1}/${d.getDate()} ${hh}:${mm}`
}

onMounted(loadAll)
</script>

<template>
  <div class="my-quota" v-loading="loading">
    <!-- ============ 欢迎语：按实际状态给出不同的下一步 ============ -->
    <div class="welcome" :class="`welcome--${welcomeState}`">
      <div class="welcome__text">
        <h2 class="welcome__title">
          <template v-if="welcomeState === 'noKey'">你还没有接入密钥</template>
          <template v-else-if="welcomeState === 'unused'">额度已经准备好，还没开始用</template>
          <template v-else>
            本月已用 {{ formatAmount(bills.totalAmount) }}
            <template v-if="remainingAmount !== null">，还剩 {{ formatAmount(remainingAmount) }}</template>
          </template>
        </h2>
        <p class="welcome__desc">
          <template v-if="welcomeState === 'noKey'">
            密钥就像公司 AI 的"门卡"，领到之后才能在自己的工具里调用。
          </template>
          <template v-else-if="welcomeState === 'unused'">
            你名下有 {{ myKeys.length }} 把密钥，这个月还没产生消耗。可以先去测试场试试手。
          </template>
          <template v-else>
            共 {{ formatNumber(bills.requestCount) }} 次调用 · 用量 {{ formatNumber(bills.totalTokens) }}
            <template v-if="usedPercent !== null"> · 额度已用 {{ usedPercent }}%</template>
          </template>
        </p>
      </div>
      <div class="welcome__action">
        <el-button
          v-if="welcomeState === 'noKey'"
          type="primary"
          @click="router.push('/my-keys')"
        >
          去领一个密钥
        </el-button>
        <el-button
          v-else-if="welcomeState === 'unused'"
          type="primary"
          @click="router.push('/playground')"
        >
          去测试场试试
        </el-button>
        <el-button v-else @click="router.push('/quickstart')">
          查看接入教程
        </el-button>
      </div>
    </div>

    <!-- ============ Hero：额度 + 我的密钥 ============ -->
    <div class="hero">
      <!-- 左：本月已用额度 -->
      <div class="quota-card">
        <div class="q-head">
          <h2>本月已用额度</h2>
          <span class="month">{{ (bills.month || '').replace('-', ' 年 ') }} 月 · 每月 1 号重置</span>
        </div>

        <div class="q-amount">
          <span class="cur">¥</span>{{ formatNumber(Math.round((bills.totalAmount || 0) * 100) / 100) }}
          <em v-if="limitAmount !== null">/ 共 {{ formatAmount(limitAmount) }}</em>
          <!-- 有额度上限但本月零消耗 → 均价无法反推 → 用数量口径说清，不能写成「未设上限」 -->
          <em v-else-if="tokenLimit > 0">/ 上限 {{ formatNumber(tokenLimit) }} {{ amountUnit }}</em>
          <em v-else>· 未设上限</em>
        </div>

        <!-- 进度条只按 token 口径画：usedPercent 只依赖 tokenLimit/usedTokens，
             与金额换算无关，因此「有 token 限额」时总是有进度可看 -->
        <div v-if="usedPercent !== null" class="q-bar">
          <i :style="{ width: Math.min(100, usedPercent) + '%' }" />
        </div>
        <div v-else class="q-bar q-bar--empty">
          <i style="width: 100%" />
        </div>

        <div class="q-meta">
          <span v-if="usedPercent !== null">
            已用 <b>{{ usedPercent }}%</b>
            <template v-if="remainingAmount !== null"> · 剩余 <b>{{ formatAmount(remainingAmount) }}</b></template>
          </span>
          <!-- 有额度上限但金额无法折算 -->
          <span v-else-if="tokenLimit > 0">
            本月已用 <b>{{ formatNumber(usedTokens) }}</b> / {{ formatNumber(tokenLimit) }} {{ amountUnit }}
          </span>
          <span v-else>
            本月已用 <b>{{ formatNumber(usedTokens) }}</b> {{ amountUnit }} · 未设月度上限
          </span>
        </div>

        <div class="q-actions">
          <el-button type="primary" @click="applyVisible = true">申请更多额度</el-button>
          <el-button @click="router.push('/my-bills')">查看怎么花的 →</el-button>
        </div>
      </div>

      <!-- 右：我的接入密钥 -->
      <div class="keys-card">
        <h3>我的接入密钥</h3>

        <template v-if="myKeys.length > 0">
          <div v-for="k in myKeys.slice(0, 2)" :key="k.keyId" class="key-row">
            <div class="k-ico">
              <el-icon><Key /></el-icon>
            </div>
            <div class="k-info">
              <b>{{ k.description || '未命名密钥' }}</b>
              <span>{{ k.keyMasked || '（密钥串不可展示）' }}</span>
            </div>
            <el-button size="small" @click="copyKey(k.keyMasked)">
              {{ copied === k.keyMasked ? '✓ 已复制' : '复制' }}
            </el-button>
          </div>

          <div class="k-hint">
            把这串密钥填到你用的 AI 工具里就能直接调用。
            <el-link type="primary" underline="never" @click="router.push('/quickstart')">查看接入教程 →</el-link>
          </div>

          <div v-if="myKeys.length > 2" class="k-more">
            <el-link type="primary" underline="never" @click="router.push('/my-keys')">
              还有 {{ myKeys.length - 2 }} 个密钥 →
            </el-link>
          </div>
        </template>

        <div v-else class="k-empty">
          你名下还没有密钥。
          <el-link type="primary" underline="never" @click="router.push('/my-keys')">去申请 →</el-link>
        </div>

        <el-button size="small" style="margin-top:10px" @click="router.push('/my-keys')">+ 申请新密钥</el-button>
      </div>
    </div>

    <!-- ============ 3 张统计卡 ============ -->
    <el-row :gutter="12" class="stats-row">
      <el-col :xs="24" :sm="8">
        <el-card shadow="never">
          <div class="stat-k">本月调用次数</div>
          <div class="stat-v">{{ formatNumber(bills.requestCount) }}<em>次</em></div>
          <div class="stat-foot">{{ formatNumber(bills.totalTokens) }} {{ amountUnit }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="never">
          <div class="stat-k">可用模型</div>
          <div class="stat-v">{{ availableVirtualModels.length }}<em>个</em></div>
          <div class="stat-foot">
            {{ availableVirtualModels.length > 0 ? availableVirtualModels.join(' / ') : (hasUnrestrictedKey ? '不限制模型' : '未分配模型') }}
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="never">
          <div class="stat-k">本周最常用</div>
          <div v-if="topVirtualModel" class="stat-v stat-v--text">{{ topVirtualModel.virtualModel }}</div>
          <div v-else class="stat-v stat-v--text">—</div>
          <div class="stat-foot">
            <template v-if="topVirtualModel">
              占你本周花费的 {{ Math.round(topVirtualModel.amountRatio * 100) }}%
            </template>
            <template v-else>最近 7 天还没有调用</template>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ============ P1 员工侧②：我能用的模型 ============ -->
    <el-card shadow="never" class="models-card">
      <template #header>
        <div class="panel-head">
          <span>我能用的模型</span>
          <span class="hint">在 AI 工具里 model 那一栏就填这些名字</span>
        </div>
      </template>

      <div v-if="availableVirtualModels.length > 0" class="model-list">
        <div v-for="m in availableVirtualModels" :key="m" class="model-item">
          <div class="model-item__name">
            <span class="model-chip"><i></i>{{ m }}</span>
          </div>
          <div class="model-item__desc">{{ modelDesc(m) }}</div>
        </div>
      </div>
      <!-- 白名单为空 = 不限制（后端 ApiKey.isModelAllowed 语义），不是「没有可用模型」 -->
      <div v-else-if="hasUnrestrictedKey" class="models-empty">
        <el-empty description="你的密钥不限制模型" :image-size="56" />
        <div class="empty-hint">
          管理员没有给你的密钥限定模型范围，也就是公司建出来的可用模型你都能用。
          想知道具体有哪些，可以在「我的密钥」里看，或直接问管理员。
        </div>
      </div>

      <div v-else class="models-empty">
        <el-empty description="还没有分配任何模型" :image-size="56" />
        <div class="empty-hint">
          管理员分配后这里会列出你能用的模型名。需要某个模型的话，找管理员开通即可。
        </div>
        <el-button size="small" type="primary" plain @click="router.push('/my-keys')">
          去「我的密钥」看看 →
        </el-button>
      </div>

      <div class="tip-bar">
        💡 这些是公司统一准备的模型名，背后可能挂了好几个真实模型（系统会自动切换），
        <b>你不需要知道背后是 GPT 还是 Claude</b>。
      </div>
    </el-card>

    <!-- ============ 底部两栏 ============ -->
    <div class="bottom">
      <el-card shadow="never">
        <template #header>
          <div class="panel-head">
            <span>本月每天花了多少钱</span>
            <span class="hint">单位：元</span>
          </div>
        </template>
        <div ref="chartEl" class="chart" />
        <div v-if="!loading && (bills.byDay || []).length === 0" class="empty-with-action">
          <el-empty description="本月还没有调用记录" :image-size="56" />
          <el-button size="small" type="primary" plain @click="router.push('/quickstart')">
            看怎么接入
          </el-button>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="panel-head">
            <span>最近几次使用</span>
            <el-link type="primary" underline="never" @click="router.push('/my-bills')">全部 →</el-link>
          </div>
        </template>
        <el-table :data="recentCalls" size="small" :show-header="true">
          <el-table-column prop="createdAt" label="时间" width="86">
            <template #default="{ row }">{{ callTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="用了哪个模型" min-width="120">
            <template #default="{ row }">
              <span v-if="row.virtualModel" class="model-chip"><i></i>{{ row.virtualModel }}</span>
              <span v-else class="cell-none">-</span>
            </template>
          </el-table-column>
          <!-- 花费：按本月实际均价折算，保证与顶部「本月已用」自洽 -->
          <el-table-column label="花了多少" width="110" align="right">
            <template #default="{ row }">
              <span v-if="callCost(row) !== null" class="cost">{{ formatAmount(callCost(row)) }}</span>
              <!-- 折算不出金额时说明「这是用量不是钱」，避免 ¥0 与空值的歧义 -->
              <span v-else class="cost cost--tokens">用量 {{ formatNumber(row.totalTokens) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="成败" width="72" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isSuccess ? 'success' : 'danger'" size="small">
                {{ row.isSuccess ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <!-- 空态必须有出口：只说「还没有记录」等于把用户困住 -->
        <div v-if="!loading && recentCalls.length === 0" class="empty-with-action">
          <el-empty description="还没有调用记录" :image-size="56" />
          <el-button size="small" type="primary" plain @click="router.push('/playground')">
            去测试场试一次
          </el-button>
        </div>
      </el-card>
    </div>

    <!-- 申请额度弹窗 -->
    <el-dialog v-model="applyVisible" title="申请更多额度" width="min(480px, 92vw)">
      <p class="dialog-tip">当前额度由管理员在「全部密钥」里配置。填写期望的月度额度，我们会在 1 个工作日内处理。</p>
      <el-input-number v-model="applyAmount" :min="0" :step="100" placeholder="期望的月度额度（¥）" style="width:100%" />
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ---- 欢迎语（状态感知） ---- */
.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 16px 20px;
  margin-bottom: 12px;
  border-radius: var(--ja-radius-lg, 12px);
  border: 1px solid var(--ja-border, #e6e6e6);
  background: var(--ja-bg-card, #fff);
}

/* 无密钥：需要行动，给一层淡黄提示色 */
.welcome--noKey {
  background: var(--el-color-warning-light-9, #fdf6ec);
  border-color: var(--el-color-warning-light-7, #f5dab1);
}

/* 有密钥但未使用：鼓励尝试，淡蓝 */
.welcome--unused {
  background: var(--ja-primary-light-9, #ecf5ff);
  border-color: var(--ja-primary-light-7, #c6e2ff);
}

.welcome__title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.welcome__desc {
  margin: 5px 0 0;
  font-size: 12.5px;
  color: var(--ja-text-secondary);
  line-height: 1.55;
}

.welcome__action {
  flex-shrink: 0;
}

/* ---- Hero 两栏 ---- */
.hero {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}

@media (max-width: 1100px) {
  .hero { grid-template-columns: 1fr; }
}

.quota-card,
.keys-card {
  background: var(--ja-bg-card, #fff);
  border: 1px solid var(--ja-border, #e6e6e6);
  border-radius: var(--ja-radius-lg, 12px);
  padding: 20px 22px;
}

.q-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
}

.q-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.q-head .month {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.q-amount {
  margin: 14px 0 12px;
  font-size: 34px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--ja-text-primary);
  font-variant-numeric: tabular-nums;
}

.q-amount .cur {
  font-size: 20px;
  margin-right: 2px;
}

.q-amount em {
  font-size: 14px;
  font-weight: 400;
  font-style: normal;
  color: var(--ja-text-secondary);
  margin-left: 6px;
}

.q-bar {
  height: 8px;
  border-radius: 999px;
  background: var(--ja-fill-color-light, #f0f2f5);
  overflow: hidden;
}

.q-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--ja-primary, #176C6B);
  transition: width 0.4s ease;
}

/* 未设上限：整条浅色，无「进度」暗示 */
.q-bar--empty i {
  background: var(--ja-fill-color, #ebeef5);
}

.q-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--ja-text-secondary);
}

.q-meta b {
  color: var(--ja-text-primary);
}

.q-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

/* ---- 我的密钥卡 ---- */
.keys-card h3 {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.key-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--ja-border-lighter, #ebeef5);
}

.key-row:last-of-type {
  border-bottom: none;
}

.k-ico {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--ja-primary-light-9, #ecf5ff);
  color: var(--ja-primary, #176C6B);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.k-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.45;
}

.k-info b {
  font-size: 13px;
  color: var(--ja-text-primary);
}

.k-info span {
  font-family: var(--ja-font-mono);
  font-size: 11.5px;
  color: var(--ja-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.k-hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.55;
}

.k-more {
  margin-top: 6px;
  font-size: 12px;
}

.k-empty {
  font-size: 12.5px;
  color: var(--ja-text-secondary);
  padding: 12px 0;
}

/* ---- 统计卡 ---- */
.stats-row {
  margin-bottom: 12px;
}

.stat-k {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.stat-v {
  font-size: 26px;
  font-weight: 700;
  margin-top: 6px;
  color: var(--ja-text-primary);
  font-variant-numeric: tabular-nums;
}

.stat-v em {
  font-size: 13px;
  font-weight: 400;
  font-style: normal;
  color: var(--ja-text-secondary);
  margin-left: 3px;
}

.stat-v--text {
  font-size: 18px;
  margin-top: 10px;
}

.stat-foot {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 底部两栏 ---- */

/* ---- P1 员工侧②：我能用的模型 ---- */
.models-card { margin-bottom: 12px; }
.model-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px;
}
.model-item {
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--ja-fill-color-light, #f5f7fa);
  border: 1px solid var(--ja-border, #e6e6e6);
}
.model-item__name { margin-bottom: 4px; }
.model-item__desc {
  font-size: 11.5px;
  color: var(--ja-text-secondary);
  line-height: 1.5;
}
.models-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 0 8px;
}

.bottom {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

@media (max-width: 1100px) {
  .bottom { grid-template-columns: 1fr; }
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  color: var(--ja-text-primary);
}

.panel-head .hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.chart {
  width: 100%;
  height: 220px;
}

/* ---- 虚拟模型 chip ---- */
.model-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--ja-bg-secondary, var(--ja-main-bg));
  color: var(--ja-text-secondary);
}

.model-chip i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ja-primary, #176C6B);
}

.cell-none {
  color: var(--ja-text-placeholder);
}

/* 空态 + 出口按钮：视觉上居中成一组，避免按钮孤零零挂在表格下方 */
.empty-with-action {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding-bottom: 8px;
}

/* 最近使用表的花费列 */
.cost {
  font-variant-numeric: tabular-nums;
  color: var(--ja-text-primary);
}

.cost--tokens {
  font-size: 11.5px;
  color: var(--ja-text-secondary);
}

.dialog-tip {
  color: var(--ja-text-secondary);
  font-size: 13px;
  margin-top: 0;
}
</style>
