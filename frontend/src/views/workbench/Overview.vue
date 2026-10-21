<script setup lang="ts">
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
import { useChartTheme } from '@/composables/useChartTheme'
import { useUserStore } from '@/stores/user'
import {
  getMonitoringOverview,
  getServiceTypes,
  getServiceInstances
} from '@/api/dashboard'
import { getCallHistoryDashboard } from '@/api/callHistory'
import type { DashboardOverview } from '@/types'
import type { ApiCallHistoryRecord, CallHistoryStatistics } from '@/types/callHistory'
import type { ServiceInstance } from '@/types/common'

const userStore = useUserStore()

/* ---------- 数据 ---------- */
const loading = ref(false)
const overview = ref<DashboardOverview | null>(null)
const stats = ref<CallHistoryStatistics | null>(null)
const recentCalls = ref<ApiCallHistoryRecord[]>([])
const topoGraph = ref<{ nodes: any[]; links: any[] } | null>(null)

/* ---------- 图表 ---------- */
const { chartTheme: palette } = useChartTheme()
const trendEl = ref<HTMLDivElement>()
const topoEl = ref<HTMLDivElement>()
let trendChart: ECharts | null = null
let topoChart: ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let refreshTimer: ReturnType<typeof setInterval> | null = null

const trendTab = ref<'calls' | 'tokens'>('calls')

/* ---------- KPI ---------- */
const kpis = computed(() => {
  const o = overview.value
  const s = stats.value
  return [
    { label: '今日调用', value: s?.totalRequests ?? 0, unit: '次', hint: `成功率 ${fmt(s?.successRate ?? 0, '%')}` },
    { label: '当前 QPS', value: o?.requestStats?.requestsPerSecond ?? 0, unit: 'req/s', hint: `平均耗时 ${fmt(s?.avgResponseTimeMs ?? 0, 'ms')}` },
    { label: 'Token 消耗', value: s?.totalTokens ?? 0, unit: 'tokens', hint: `均次 ${fmt(s?.avgTokensPerRequest ?? 0)}` },
    { label: '实例健康', value: `${o?.serviceStats?.activeServices ?? 0}/${o?.serviceStats?.totalServices ?? 0}`, unit: '个', hint: `异常 ${o?.serviceStats?.failedServices ?? 0} 个` }
  ]
})

function fmt(v: number, unit = ''): string {
  if (v == null || Number.isNaN(v)) return '-'
  const s = v >= 10000 ? (v / 10000).toFixed(1) + 'w' : Math.round(v * 100) / 100 + ''
  return unit ? `${s} ${unit}` : s
}

/* ---------- 加载 ---------- */
async function load() {
  loading.value = true
  try {
    const [ov, ch] = await Promise.all([
      getMonitoringOverview().catch(() => null),
      getCallHistoryDashboard().catch(() => null)
    ])
    overview.value = (ov as any)?.data?.data ?? null
    if (ch?.statistics) {
      stats.value = ch.statistics
      recentCalls.value = ch.recentCalls || []
    }
    await loadTopology()
    renderTrend()
    renderTopo()
  } finally {
    loading.value = false
  }
}

/** 拓扑数据：网关 → 服务类型 → 实例（健康信息在模型旁边看） */
async function loadTopology() {
  try {
    const types = ((await getServiceTypes()) as any)?.data?.data || []
    const nodes: any[] = [
      {
        id: 'gateway', name: 'JAiRouter 网关', category: 0, symbolSize: 56,
        itemStyle: { color: palette.value.primary },
        label: { show: true, fontWeight: 600 }
      }
    ]
    const links: any[] = []
    for (const type of types) {
      const instances: ServiceInstance[] = ((await getServiceInstances(type).catch(() => null)) as any)?.data?.data || []
      const healthy = instances.filter(i => i.health !== false).length
      const typeNodeColor =
        instances.length === 0 || healthy === 0
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
        nodes.push({
          id: `inst:${type}:${inst.instanceId}`,
          name: inst.name || inst.instanceId,
          category: 2,
          symbolSize: 22,
          itemStyle: { color: inst.health === false ? palette.value.info : palette.value.success },
          raw: inst
        })
        links.push({ source: `type:${type}`, target: `inst:${type}:${inst.instanceId}` })
      }
    }
    topoGraph.value = { nodes, links }
  } catch {
    topoGraph.value = { nodes: [], links: [] }
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
          categories: [{ name: '网关' }, { name: '服务类型' }, { name: '实例' }],
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
</script>

<template>
  <div class="overview" v-loading="loading">
    <PageHeader title="总览" subtitle="调用 · 用量 · 健康一屏看清">
      <template #actions>
        <span class="overview-user">{{ userStore.userInfo?.username }}</span>
      </template>
    </PageHeader>

    <!-- ① KPI 条 -->
    <div class="kpi-grid">
      <el-card v-for="k in kpis" :key="k.label" shadow="never" class="kpi-card">
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-value">{{ k.value }}<span class="kpi-unit">{{ k.unit }}</span></div>
        <div class="kpi-hint">{{ k.hint }}</div>
      </el-card>
    </div>

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
            <span>实例健康拓扑</span>
            <span class="card-head-hint">点击实例节点查看详情</span>
          </div>
        </template>
        <div ref="topoEl" class="chart chart-topo" />
        <div class="topo-legend">
          <StatusDot status="ok" /><StatusDot status="warn" /><StatusDot status="error" /><StatusDot status="off" />
        </div>
      </el-card>
    </div>

    <!-- ④ 最近调用 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>最近调用</span>
          <span class="card-head-hint">30 秒自动刷新</span>
        </div>
      </template>
      <el-table :data="recentCalls" size="small" stripe>
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="模型" min-width="160">
          <template #default="{ row }">{{ row.modelName || row.serviceType }}</template>
        </el-table-column>
        <el-table-column prop="instanceName" label="实例" min-width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusDot :status="statusOf(row)" :label="String(row.httpStatusCode || (row.isSuccess ? '成功' : '失败'))" />
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
    <el-drawer v-model="topoDrawer" title="实例详情" size="360px">
      <el-descriptions v-if="topoDetail" :column="1" border>
        <el-descriptions-item label="实例名">{{ topoDetail.name }}</el-descriptions-item>
        <el-descriptions-item label="实例 ID">{{ topoDetail.instanceId }}</el-descriptions-item>
        <el-descriptions-item label="Base URL">{{ topoDetail.baseUrl }}</el-descriptions-item>
        <el-descriptions-item label="适配器">{{ topoDetail.adapter }}</el-descriptions-item>
        <el-descriptions-item label="权重">{{ topoDetail.weight }}</el-descriptions-item>
        <el-descriptions-item label="健康状态">
          <StatusDot :status="topoDetail.health === false ? 'off' : 'ok'" />
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
