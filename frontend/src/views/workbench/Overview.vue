<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 工作台总览（一期重写：四区布局）
 * ① KPI 条 ② 趋势区(调用量24h / Token近7天) ③ 实例健康拓扑(新资产) ④ 最近调用
 * 数据源与旧台 Dashboard 同源：/monitoring/overview + /call-history/dashboard + /config/*
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { ECharts } from 'echarts'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusDot from '@/components/common/StatusDot.vue'
import AnomalyBar, { type AnomalyGroup } from '@/components/common/AnomalyBar.vue'
import { useChartTheme } from '@/composables/useChartTheme'
import { fetchCircuitStates, circuitStateOf } from '@/composables/useInstanceAvailability'
import { useUserStore } from '@/stores/user'
import {
  getServiceTypes,
  getServiceInstances
} from '@/api/dashboard'
import { getCallHistoryDashboard } from '@/api/callHistory'
import { getApiKeys, getQuotaAlerts } from '@/api/apiKey'
import { useRouter } from 'vue-router'
import type { ApiCallHistoryRecord, CallHistoryStatistics } from '@/types/callHistory'
import type { ServiceInstance } from '@/types/common'
import { getServiceTypeLabel } from '@/constants/serviceTypes'

const { t } = useI18n()

const router = useRouter()
const userStore = useUserStore()

/* ---------- 数据 ---------- */
const loading = ref(false)
const stats = ref<CallHistoryStatistics | null>(null)
const recentCalls = ref<ApiCallHistoryRecord[]>([])
const topoGraph = ref<{ nodes: any[]; links: any[] } | null>(null)
/* U2-4 需要关注：配额告警 Key / 7 天内过期 Key / 异常实例 */
const quotaAlertCount = ref(0)
const expiringKeyCount = ref(0)
const unhealthyInstances = ref<ServiceInstance[]>([])
/* U2-2 异常聚合条：按状态归类的实例 */
const instGroups = ref<{ healthy: number; circuitOpen: number; unhealthy: number; inactive: number; rateLimited: number }>({
  healthy: 0, circuitOpen: 0, unhealthy: 0, inactive: 0, rateLimited: 0
})
/** 熔断中 / 不健康的实例名（供聚合条明细展示） */
const anomalyNames = ref<{ circuitOpen: string[]; unhealthy: string[]; inactive: string[] }>({
  circuitOpen: [], unhealthy: [], inactive: []
})

/* ---------- 图表 ---------- */
const { chartTheme: palette } = useChartTheme()
const trendEl = ref<HTMLDivElement>()
const topoEl = ref<HTMLDivElement>()
let trendChart: ECharts | null = null
let topoChart: ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let refreshTimer: ReturnType<typeof setInterval> | null = null

const trendTab = ref<'calls' | 'tokens'>('calls')

/* ---------- KPI（U2-1：整卡可点，直达对应明细页） ---------- */
/**
 * 数据源说明：
 * - 「当前 QPS」「实例健康」原先读 /monitoring/overview 的 requestStats / serviceStats，
 *   但该接口实际**不返回**这两个字段（实测顶层键只有 enabled/healthy/errorStats 等），
 *   导致 QPS 恒为 0、实例健康恒为 0/0。现改为：
 *   - QPS：由调用统计的时间窗口与总请求数折算（窗口内平均 QPS）
 *   - 实例健康：用聚合条统计出的真实实例状态（正常 / 总数）
 */
const kpis = computed(() => {
  const s = stats.value
  /**
   * QPS 口径修正：
   * 原先读 /monitoring/overview 的 requestStats —— 实测该接口**不返回**此字段，
   * 导致「当前 QPS」恒为 0（看起来像坏了）。
   * 现改为展示「近 1 小时请求数」：网关实际 QPS 很低（折算后接近 0），
   * 直接给运营看「每小时多少次调用」更能判断是否有流量。
   */
  const hours = s?.byHour || []
  const recentHourCount = hours.length ? hours[hours.length - 1]?.requestCount ?? 0 : 0
  const g = instGroups.value
  const totalInstances = g.healthy + g.circuitOpen + g.unhealthy + g.inactive
  const abnormalInstances = g.circuitOpen + g.unhealthy
  return [
    {
      label: t('overview.kpi.todayCalls'),
      value: s?.totalRequests ?? 0,
      unit: t('overview.kpi.unitTimes'),
      hint: t('overview.kpi.hintSuccessRate', { v: fmt(s?.successRate ?? 0, '%') }),
      to: '/observe?tab=history'
    },
    {
      label: t('overview.kpi.recentHourCalls'),
      value: recentHourCount,
      unit: t('overview.kpi.unitTimes'),
      hint: t('overview.kpi.hintAvgDuration', { v: fmt(s?.avgResponseTimeMs ?? 0, 'ms') }),
      to: '/observe?tab=history'
    },
    {
      label: t('overview.kpi.tokenUsage'),
      value: s?.totalTokens ?? 0,
      unit: t('overview.kpi.unitTokens'),
      hint: t('overview.kpi.hintAvgPerCall', { v: fmt(s?.avgTokensPerRequest ?? 0) }),
      to: '/observe?tab=tokens'
    },
    {
      label: t('overview.kpi.instanceHealth'),
      value: `${g.healthy}/${totalInstances}`,
      unit: t('overview.kpi.unitCount'),
      hint: t('overview.kpi.hintAbnormal', { v: abnormalInstances }),
      to: '/models'
    }
  ]
})

/* ---------- 需要关注（U2-4） ---------- */
const attentionItems = computed(() => {
  const items: { key: string; text: string; count: number; to: string; type: 'warning' | 'danger' | 'info' }[] = []
  if (quotaAlertCount.value > 0) {
    items.push({
      key: 'quota',
      text: t('overview.attention.quotaAlert'),
      count: quotaAlertCount.value,
      to: '/keys',
      type: 'danger'
    })
  }
  if (expiringKeyCount.value > 0) {
    items.push({
      key: 'expire',
      text: t('overview.attention.expiringKey'),
      count: expiringKeyCount.value,
      to: '/keys',
      type: 'warning'
    })
  }
  if (unhealthyInstances.value.length > 0) {
    items.push({
      key: 'unhealthy',
      text: t('overview.attention.unhealthyInstance'),
      count: unhealthyInstances.value.length,
      to: '/strategy?tab=circuit',
      type: 'danger'
    })
  }
  return items
})

/* ---------- U2-2 异常聚合条 ---------- */
const anomalyGroups = computed<AnomalyGroup[]>(() => {
  const g = instGroups.value
  const n = anomalyNames.value
  const detail = (arr: string[]) =>
    arr.length
      ? arr.slice(0, 3).join(t('overview.anomaly.nameSeparator')) +
        (arr.length > 3 ? t('overview.anomaly.moreCount', { n: arr.length }) : '')
      : ''
  return [
    { key: 'healthy', label: t('overview.anomaly.healthy'), count: g.healthy, tone: 'ok' as const },
    { key: 'circuitOpen', label: t('overview.anomaly.circuitOpen'), count: g.circuitOpen, tone: 'danger' as const, detail: detail(n.circuitOpen), to: '/strategy?tab=circuit' },
    { key: 'unhealthy', label: t('overview.anomaly.unhealthy'), count: g.unhealthy, tone: 'danger' as const, detail: detail(n.unhealthy), to: '/models?tab=instances' },
    { key: 'inactive', label: t('overview.anomaly.inactive'), count: g.inactive, tone: 'warning' as const, detail: detail(n.inactive), to: '/models?tab=instances' },
    { key: 'rateLimited', label: t('overview.anomaly.rateLimited'), count: g.rateLimited, tone: 'warning' as const, to: '/observe?tab=rate' }
  ]
})

/* ---------- 最近调用筛选（U2-3） ---------- */
const onlyFailed = ref(false)
const filteredCalls = computed(() =>
  onlyFailed.value ? recentCalls.value.filter(r => !r.isSuccess) : recentCalls.value
)

function rowClass({ row }: { row: ApiCallHistoryRecord }): string {
  return !row.isSuccess ? 'row-failed' : ''
}

function fmt(v: number, unit = ''): string {
  if (v == null || Number.isNaN(v)) return '-'
  const s = v >= 10000 ? (v / 10000).toFixed(1) + 'w' : Math.round(v * 100) / 100 + ''
  return unit ? `${s} ${unit}` : s
}

/* ---------- 加载 ---------- */
async function load() {
  loading.value = true
  try {
    // 统计口径统一取 /call-history/dashboard（调用量/Token/耗时/成功率）
    const ch = await getCallHistoryDashboard().catch(() => null)
    if (ch?.statistics) {
      stats.value = ch.statistics
      recentCalls.value = ch.recentCalls || []
    }
    await Promise.all([loadTopology(), loadAttention()])
    renderTrend()
    renderTopo()
  } finally {
    loading.value = false
  }
}

/** U2-4：加载需要关注的数据（配额告警 / 即将过期密钥） */
async function loadAttention() {
  const [keys, alerts] = await Promise.all([
    getApiKeys().catch(() => null),
    getQuotaAlerts().catch(() => [])
  ])
  const list: any[] = (keys as any)?.items || []
  const now = Date.now()
  const in7 = now + 7 * 24 * 3600 * 1000
  expiringKeyCount.value = list.filter((k: any) => {
    if (k.enabled === false || k.expired) return false
    const t = k.expiresAt ? new Date(k.expiresAt).getTime() : NaN
    return !Number.isNaN(t) && t > now && t <= in7
  }).length
  quotaAlertCount.value = (alerts as any[])?.length || 0
}

/** 拓扑数据：网关 → 服务类型 → 实例（健康信息在模型旁边看） */
async function loadTopology() {
  try {
    // 熔断运行态（与实例管理/测试场共用同一份数据源）
    await fetchCircuitStates()
    const types = ((await getServiceTypes()) as any)?.data?.data || []
    const nodes: any[] = [
      {
        id: 'gateway', name: t('overview.topo.gateway'), category: 0, symbolSize: 56,
        itemStyle: { color: palette.value.primary },
        label: { show: true, fontWeight: 600 }
      }
    ]
    const links: any[] = []
    const bad: ServiceInstance[] = []
    // U2-2 异常聚合统计
    const groups = { healthy: 0, circuitOpen: 0, unhealthy: 0, inactive: 0, rateLimited: 0 }
    const names = { circuitOpen: [] as string[], unhealthy: [] as string[], inactive: [] as string[] }

    for (const type of types) {
      const instances: ServiceInstance[] = ((await getServiceInstances(type).catch(() => null)) as any)?.data?.data || []
      for (const inst of instances) {
        const label = inst.name || inst.instanceId || type
        const circuit = circuitStateOf(inst as any)
        const health = (inst.healthStatus || 'UNKNOWN').toUpperCase()
        const active = (inst.status || 'active').toLowerCase() === 'active'

        if (!active) {
          groups.inactive++
          names.inactive.push(label)
        } else if (circuit === 'OPEN') {
          groups.circuitOpen++
          names.circuitOpen.push(label)
          bad.push(inst)
        } else if (health === 'UNHEALTHY') {
          groups.unhealthy++
          names.unhealthy.push(label)
          bad.push(inst)
        } else {
          groups.healthy++
        }
        // 实例级限流已开启 → 提示可能触顶
        if ((inst as any).rateLimit?.enabled) groups.rateLimited++
      }

      // 类型节点配色语义：
      //   无任何实例 = 该类型尚未接入（灰，非故障）
      //   有实例但全部不健康 = 故障（红）
      //   部分不健康 = 部分故障（黄）
      const healthy = instances.filter(i => (i.healthStatus || 'UNKNOWN').toUpperCase() !== 'UNHEALTHY').length
      const typeNodeColor =
        instances.length === 0
          ? palette.value.info
          : healthy === 0
            ? palette.value.danger
            : healthy < instances.length
              ? palette.value.warning
              : palette.value.success
      nodes.push({
        id: `type:${type}`, name: type, category: 1, symbolSize: 38,
        itemStyle: { color: typeNodeColor }
      })
      links.push({ source: 'gateway', target: `type:${type}` })
      for (const inst of instances) {
        const circuit = circuitStateOf(inst as any)
        const health = (inst.healthStatus || 'UNKNOWN').toUpperCase()
        const active = (inst.status || 'active').toLowerCase() === 'active'
        const nodeColor = !active
          ? palette.value.info
          : circuit === 'OPEN'
            ? palette.value.danger
            : health === 'UNHEALTHY'
              ? palette.value.warning
              : palette.value.success
        nodes.push({
          id: `inst:${type}:${inst.instanceId}`,
          name: inst.name || inst.instanceId,
          category: 2,
          symbolSize: 22,
          itemStyle: { color: nodeColor },
          raw: inst
        })
        links.push({ source: `type:${type}`, target: `inst:${type}:${inst.instanceId}` })
      }
    }
    topoGraph.value = { nodes, links }
    unhealthyInstances.value = bad
    instGroups.value = groups
    anomalyNames.value = names
  } catch {
    topoGraph.value = { nodes: [], links: [] }
    unhealthyInstances.value = []
    instGroups.value = { healthy: 0, circuitOpen: 0, unhealthy: 0, inactive: 0, rateLimited: 0 }
    anomalyNames.value = { circuitOpen: [], unhealthy: [], inactive: [] }
  }
}

/* ---------- 渲染 ---------- */
function renderTrend() {
  if (!trendEl.value) return
  trendChart = trendChart || echarts.init(trendEl.value)
  const textColor = palette.value.textColor
  const isCalls = trendTab.value === 'calls'
  const hours = stats.value?.byHour || []
  const days = stats.value?.byDay || []
  const option: echarts.EChartsOption = isCalls
    ? {
        tooltip: { trigger: 'axis' },
        grid: { left: 48, right: 16, top: 24, bottom: 28 },
        xAxis: { type: 'category', data: hours.map(h => h.label), axisLabel: { color: textColor, fontSize: 11 } },
        yAxis: { type: 'value', axisLabel: { color: textColor, fontSize: 11 }, splitLine: { lineStyle: { color: textColor, opacity: 0.12 } } },
        series: [{ type: 'line', smooth: true, data: hours.map(h => h.requestCount), areaStyle: { opacity: 0.15 }, itemStyle: { color: palette.value.primary } }]
      }
    : {
        tooltip: { trigger: 'axis' },
        grid: { left: 48, right: 16, top: 24, bottom: 28 },
        xAxis: { type: 'category', data: days.map(d => d.date), axisLabel: { color: textColor, fontSize: 11 } },
        yAxis: { type: 'value', axisLabel: { color: textColor, fontSize: 11 }, splitLine: { lineStyle: { color: textColor, opacity: 0.12 } } },
        series: [{ type: 'bar', data: days.map(d => d.totalTokens), itemStyle: { color: palette.value.success, borderRadius: [3, 3, 0, 0] } }]
      }
  trendChart.setOption(option, true)
}

const topoDrawer = ref(false)
const topoDetail = ref<ServiceInstance | null>(null)

function renderTopo() {
  if (!topoEl.value) return
  topoChart = topoChart || echarts.init(topoEl.value)
  const g = topoGraph.value || { nodes: [], links: [] }
  topoChart.setOption(
    {
      tooltip: { formatter: (p: any) => (p.dataType === 'node' ? p.data.name : '') },
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          data: g.nodes,
          links: g.links,
          categories: [
            { name: t('overview.topo.catGateway') },
            { name: t('overview.topo.catType') },
            { name: t('overview.topo.catInstance') }
          ],
          force: { repulsion: 180, edgeLength: [40, 90], gravity: 0.08 },
          label: { show: true, position: 'bottom', fontSize: 11, color: palette.value.textColor },
          lineStyle: { color: palette.value.textColor, opacity: 0.25, curveness: 0 },
          emphasis: { focus: 'adjacency' }
        }
      ]
    },
    true
  )
  topoChart.off('click')
  topoChart.on('click', (p: any) => {
    if (p.data?.raw) {
      topoDetail.value = p.data.raw as ServiceInstance
      topoDrawer.value = true
    }
  })
}

watch(trendTab, renderTrend)

/* ---------- 生命周期 ---------- */
onMounted(() => {
  load()
  refreshTimer = setInterval(load, 30_000)
  resizeObserver = new ResizeObserver(() => {
    trendChart?.resize()
    topoChart?.resize()
  })
  if (trendEl.value) resizeObserver.observe(trendEl.value)
  if (topoEl.value) resizeObserver.observe(topoEl.value)
})

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  resizeObserver?.disconnect()
  trendChart?.dispose()
  topoChart?.dispose()
})

function statusOf(rec: ApiCallHistoryRecord): 'ok' | 'error' | 'warn' {
  if (rec.circuitBroken) return 'error'
  if (rec.rateLimited) return 'warn'
  return rec.isSuccess ? 'ok' : 'error'
}

/* ---------- 实例健康判定（后端返回 healthStatus 字符串，不是 health 布尔） ---------- */
const healthOf = (inst: ServiceInstance): string => (inst.healthStatus || 'UNKNOWN').toUpperCase()

const topoHealthDot = (inst: ServiceInstance): 'ok' | 'off' | 'warn' => {
  const h = healthOf(inst)
  if (h === 'HEALTHY') return 'ok'
  if (h === 'UNHEALTHY') return 'off'
  return 'warn'
}

const topoHealthLabel = (inst: ServiceInstance): string => {
  const h = healthOf(inst)
  if (h === 'HEALTHY') return t('instance.healthOk')
  if (h === 'UNHEALTHY') return inst.errorMessage ? inst.errorMessage : t('instance.healthOff')
  return t('instance.healthUnknown')
}
</script>

<template>
  <div class="overview" v-loading="loading">
    <PageHeader :title="t('shell.overview.title')" :subtitle="t('shell.overview.subtitle')">
      <template #actions>
        <span class="overview-user">{{ userStore.userInfo?.username }}</span>
      </template>
    </PageHeader>

    <!-- ① KPI 条 -->
    <div class="kpi-grid">
      <el-card
        v-for="k in kpis"
        :key="k.label"
        shadow="never"
        class="kpi-card is-clickable"
        @click="router.push(k.to)"
      >
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-value">{{ k.value }}<span class="kpi-unit">{{ k.unit }}</span></div>
        <div class="kpi-hint">{{ k.hint }}</div>
        <div class="kpi-go">{{ t('overview.kpi.viewDetail') }}</div>
      </el-card>
    </div>

    <!-- 需要关注（U2-4） -->
    <el-card v-if="attentionItems.length > 0" shadow="never" class="attention-card">
      <template #header>
        <div class="card-head">
          <span>{{ t('overview.attention.title') }}</span>
          <span class="card-head-hint">{{ t('overview.attention.pending', { n: attentionItems.length }) }}</span>
        </div>
      </template>
      <div class="attention-list">
        <div
          v-for="a in attentionItems"
          :key="a.key"
          class="attention-item"
          :class="`is-${a.type}`"
          @click="router.push(a.to)"
        >
          <el-tag :type="a.type === 'danger' ? 'danger' : 'warning'" effect="light" size="small">
            {{ a.count }}
          </el-tag>
          <span class="attention-text">{{ a.text }}</span>
          <span class="attention-go">{{ t('overview.attention.goHandle') }}</span>
        </div>
      </div>
    </el-card>

    <div class="mid-grid">
      <!-- ② 趋势区 -->
      <el-card shadow="never" class="trend-card">
        <el-tabs v-model="trendTab">
          <el-tab-pane label="调用量（24h）" name="calls" />
          <el-tab-pane label="Token（近 7 天）" name="tokens" />
        </el-tabs>
        <div ref="trendEl" class="chart chart-trend" />
      </el-card>

      <!-- ③ 实例健康拓扑 -->
      <el-card shadow="never" class="topo-card">
        <template #header>
          <div class="card-head">
            <span>{{ t('overview.topo.title') }}</span>
            <span class="card-head-hint">{{ t('overview.topo.hint') }}</span>
          </div>
        </template>
        <!-- U2-2 异常聚合条：一行看清有多少正常、多少熔断/限流 -->
        <AnomalyBar :groups="anomalyGroups" />
        <div ref="topoEl" class="chart chart-topo" />
        <div class="topo-legend">
          <StatusDot status="ok" :label="t('overview.topo.legend.healthy')" />
          <StatusDot status="warn" :label="t('overview.topo.legend.degraded')" />
          <StatusDot status="error" :label="t('overview.topo.legend.broken')" />
          <StatusDot status="off" :label="t('overview.topo.legend.notConnected')" />
        </div>
      </el-card>
    </div>

    <!-- ④ 最近调用 -->
    <el-card shadow="never">
      <template #header>
          <div class="card-head">
            <span>{{ t('overview.calls.title') }}</span>
            <div style="display: flex; align-items: center; gap: 10px">
              <el-radio-group v-model="onlyFailed" size="small">
                <el-radio-button :value="false">{{ t('overview.calls.all') }}</el-radio-button>
                <el-radio-button :value="true">{{ t('overview.calls.onlyFailed') }}</el-radio-button>
              </el-radio-group>
              <span class="card-head-hint">{{ t('overview.calls.autoRefresh') }}</span>
            </div>
          </div>
      </template>
      <el-table :data="filteredCalls" size="small" stripe :row-class-name="rowClass">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="模型" min-width="160">
          <template #default="{ row }">{{ row.modelName || getServiceTypeLabel(row.serviceType) }}</template>
        </el-table-column>
        <el-table-column prop="instanceName" label="实例" min-width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusDot
              :status="statusOf(row)"
              :label="String(row.httpStatusCode || (row.isSuccess ? t('overview.status.success') : t('overview.status.failed')))"
            />
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ fmt(row.responseTimeMs, 'ms') }}</template>
        </el-table-column>
        <el-table-column label="Token" width="100">
          <template #default="{ row }">{{ fmt(row.totalTokens) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 实例详情抽屉 -->
    <el-drawer v-model="topoDrawer" :title="t('overview.drawer.title')" size="360px">
      <el-descriptions v-if="topoDetail" :column="1" border>
        <el-descriptions-item :label="t('overview.drawer.instanceName')">{{ topoDetail.name }}</el-descriptions-item>
        <el-descriptions-item :label="t('overview.drawer.instanceId')">{{ topoDetail.instanceId }}</el-descriptions-item>
        <el-descriptions-item :label="t('overview.drawer.baseUrl')">{{ topoDetail.baseUrl }}</el-descriptions-item>
        <el-descriptions-item :label="t('overview.drawer.adapter')">{{ topoDetail.adapter }}</el-descriptions-item>
        <el-descriptions-item :label="t('overview.drawer.weight')">{{ topoDetail.weight }}</el-descriptions-item>
        <el-descriptions-item :label="t('overview.drawer.healthStatus')">
          <StatusDot
            :status="topoHealthDot(topoDetail)"
            :label="topoHealthLabel(topoDetail)"
          />
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--ja-card-gap);
  margin-bottom: var(--ja-card-gap);
}
@media (max-width: 1280px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
.kpi-card :deep(.el-card__body) {
  padding: 16px;
}
.kpi-label {
  font-size: 12px;
  color: var(--ja-text-secondary);
}
.kpi-value {
  font-size: 26px;
  font-weight: 600;
  color: var(--ja-text-primary);
  margin: 6px 0 2px;
}
.kpi-unit {
  font-size: 12px;
  font-weight: 400;
  color: var(--ja-text-placeholder);
  margin-left: 4px;
}
.kpi-hint {
  font-size: 12px;
  color: var(--ja-text-placeholder);
}
.kpi-card.is-clickable {
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.kpi-card.is-clickable:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 14px rgb(0 0 0 / 12%);
}
.kpi-go {
  margin-top: 6px;
  font-size: 12px;
  color: var(--ja-primary);
  opacity: 0;
  transition: opacity 0.15s ease;
}
.kpi-card.is-clickable:hover .kpi-go {
  opacity: 1;
}
.attention-card {
  margin-bottom: var(--ja-card-gap);
}
.attention-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.attention-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  background: var(--el-fill-color-light);
  transition: background 0.15s ease;
}
.attention-item:hover {
  background: var(--el-fill-color);
}
.attention-item .attention-text {
  flex: 1;
  font-size: 13px;
  color: var(--ja-text-primary);
}
.attention-item .attention-go {
  font-size: 12px;
  color: var(--ja-primary);
  white-space: nowrap;
}
:deep(.el-table .row-failed) {
  background-color: var(--el-color-danger-light-9) !important;
}
.mid-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: var(--ja-card-gap);
  margin-bottom: var(--ja-card-gap);
}
@media (max-width: 1280px) {
  .mid-grid {
    grid-template-columns: 1fr;
  }
}
.chart-trend {
  height: 280px;
}
.chart-topo {
  height: 300px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-head-hint {
  font-size: 12px;
  color: var(--ja-text-placeholder);
  font-weight: 400;
}
.topo-legend {
  display: flex;
  gap: 16px;
  justify-content: center;
  padding-top: 4px;
}
.overview-user {
  font-size: 13px;
  color: var(--ja-text-secondary);
}
</style>
