<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<template>
  <div>
    <!-- ---------- 维度与月份 ---------- -->
    <div class="toolbar">
      <el-radio-group v-model="dimension" size="small" @change="loadUsage">
        <el-radio-button value="COMPANY">{{ t('v.tu.t001') }}</el-radio-button>
        <el-radio-button value="DEPARTMENT">{{ t('v.tu.t002') }}</el-radio-button>
      </el-radio-group>

      <el-select
        v-if="dimension === 'DEPARTMENT'"
        v-model="department"
        :placeholder="t('v.tu.t015')"
        clearable
        size="small"
        style="width: 180px"
        @change="loadUsage"
      >
        <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
      </el-select>

      <el-date-picker
        v-model="monthValue"
        type="month"
        size="small"
        value-format="YYYY-MM"
        :placeholder="t('v.tu.t016')"
        style="width: 140px"
        @change="loadUsage"
      />

      <el-button size="small" :loading="loading" @click="loadUsage">
        <el-icon><Refresh /></el-icon>
        {{ t('v.tu.t003') }}
      </el-button>
    </div>

    <!-- ---------- 部门汇总卡 ---------- -->
    <el-row :gutter="12" class="dept-row">
      <el-col
        v-for="d in usage.departments"
        :key="d.department"
        :xs="12"
        :sm="8"
        :md="6"
        :lg="4"
      >
        <el-card shadow="never" class="dept-card" :class="{ 'dept-card--company': d.company }">
          <div class="dept-name">{{ d.department }}</div>
          <div class="dept-amount">¥{{ formatAmount(d.amount) }}</div>
          <div class="dept-foot">
            {{ d.userCount }} {{ t('v.tu.x001') }} {{ formatNumber(d.requestCount) }} {{ t('v.tu.x002') }}
          </div>
          <el-progress
            :percentage="Math.round((d.amountRatio || 0) * 100)"
            :stroke-width="4"
            :show-text="false"
            class="dept-bar"
          />
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="!hasAnyPrice"
      type="info"
      :closable="false"
      show-icon
      class="price-hint"
      :title="t('v.tu.t017')"
    />

    <!-- ---------- Token 用量 4 KPI ---------- -->
    <el-row :gutter="12" class="kpi-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never">
          <div class="kpi-label">{{ t('v.tu.t004') }}</div>
          <div class="kpi-value">{{ formatCompact(usage.totalTokens) }}</div>
          <div class="kpi-foot">{{ t('v.tu.t005') }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never">
          <div class="kpi-label">{{ t('v.tu.t006') }}</div>
          <div class="kpi-value kpi-value--primary">{{ formatCompact(usage.promptTokens) }}</div>
          <div class="kpi-foot">{{ t('v.tu.x003') }} {{ percentOf(usage.promptTokens, usage.totalTokens) }}%</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never">
          <div class="kpi-label">{{ t('v.tu.t007') }}</div>
          <div class="kpi-value kpi-value--accent">{{ formatCompact(usage.completionTokens) }}</div>
          <div class="kpi-foot">{{ t('v.tu.x003') }} {{ percentOf(usage.completionTokens, usage.totalTokens) }}%</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never">
          <div class="kpi-label">{{ t('v.tu.t008') }}</div>
          <div class="kpi-value kpi-value--ok">{{ formatPercent(usage.successRate) }}</div>
          <div class="kpi-foot">{{ t('v.tu.x004') }} {{ formatNumber(usage.failedCount) }} {{ t('v.tu.x005') }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ---------- 虚拟模型排行 + 用途分布 ---------- -->
    <el-row :gutter="12" class="chart-row">
      <el-col :xs="24" :md="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>{{ t('v.tu.t009') }}</span>
              <span class="card-head__hint">{{ t('v.tu.t010') }}</span>
            </div>
          </template>
          <div v-show="usage.byVirtualModel.length > 0" ref="vmChartRef" class="chart-box" />
          <el-empty v-if="!loading && usage.byVirtualModel.length === 0" :description="t('v.tu.t018')" :image-size="70" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>{{ t('v.tu.t011') }}</span>
              <span class="card-head__hint">{{ t('v.tu.t012') }}</span>
            </div>
          </template>
          <div v-show="usage.byServiceType.length > 0" ref="stChartRef" class="chart-box" />
          <el-empty v-if="!loading && usage.byServiceType.length === 0" :description="t('v.tu.t018')" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- ---------- 明细表 ---------- -->
    <el-row :gutter="12" class="detail-row">
      <el-col :xs="24" :md="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>{{ dimension === 'DEPARTMENT' ? t('v.tu.t025') : t('v.tu.t026') }}</span>
              <span class="card-head__hint">{{ t('v.tu.t013') }}</span>
            </div>
          </template>
          <el-table ref="groupUsageTableRef" :data="usage.groups" :max-height="groupUsageMaxHeight" v-loading="loading" size="small">
            <el-table-column :label="dimension === 'DEPARTMENT' ? t('v.tu.t027') : t('v.tu.t028')" min-width="110">
              <template #default="{ row }">
                <span class="cell-title">{{ row.label }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="dimension === 'DEPARTMENT'" :label="t('v.tu.t019')" width="60" align="right">
              <template #default="{ row }">{{ row.userCount }}</template>
            </el-table-column>
            <el-table-column :label="t('v.tu.t020')" width="80" align="right">
              <template #default="{ row }">{{ formatNumber(row.requestCount) }}</template>
            </el-table-column>
            <el-table-column :label="t('v.tu.t021')" width="110" align="right">
              <template #default="{ row }">{{ formatCompact(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column :label="t('v.tu.t022')" width="90" align="right">
              <template #default="{ row }">¥{{ formatAmount(row.amount) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && usage.groups.length === 0" :description="t('v.tu.t018')" :image-size="70" />
        </el-card>
      </el-col>

      <el-col :xs="24" :md="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>{{ t('v.tu.t014') }}</span>
              <span class="card-head__hint">{{ t('v.tu.t012') }}</span>
            </div>
          </template>
          <el-table ref="vmUsageTableRef" :data="usage.byVirtualModel" :max-height="vmUsageMaxHeight" v-loading="loading" size="small">
            <el-table-column :label="t('v.tu.t023')" min-width="90">
              <template #default="{ row }">
                <span class="cell-title">{{ row.virtualModel }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('v.tu.t024')" min-width="130">
              <template #default="{ row }">
                <span class="model-tags">
                  <el-tag
                    v-for="m in row.models"
                    :key="m.modelName"
                    size="small"
                    type="info"
                    effect="plain"
                    class="model-tag"
                  >
                    {{ m.modelName }}
                  </el-tag>
                  <span v-if="row.models.length === 0" class="model-empty">—</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('v.tu.t022')" width="80" align="right">
              <template #default="{ row }">¥{{ formatAmount(row.amount) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && usage.byVirtualModel.length === 0" :description="t('v.tu.t018')" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
/**
 * 团队用量（v3.5 Trunkline UI 改造）
 *
 * 三个维度：
 * - 全公司：按成员汇总
 * - 按部门：按账号表里的部门汇总（账号未填部门归入「未分配部门」）
 *
 * <b>虚拟模型口径</b>：页面主展示维度是<b>虚拟模型</b>（模型分组，如「公司主力」「便宜日常」），
 * 即员工填进 AI 工具里的模型名；背后实际落到哪个厂商模型（gpt-4o / claude）只作下钻参考。
 *
 * <b>归属在调用时确定</b>：后端在路由决策后把命中的虚拟模型名写入 token_usage.virtual_model，
 * 一次请求只命中一个虚拟模型，因此归属无歧义——不能用「实例 → 分组」反查代替
 * （同一厂商模型实例可能同时被多个虚拟模型引用）。
 *
 * 计价口径：token_usage 的成功调用，按「用户 × 厂商模型」聚合后与 model_price 相乘计价，
 * 与「我的账单」同源（账单按厂商模型名计价）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import echarts from '@/utils/echarts'
import { getBillingDepartments, getTeamUsage, type TeamUsageVO } from '@/api/billing'
import { useChartTheme } from '@/composables/useChartTheme'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const loading = ref(false)
const dimension = ref('COMPANY')
const department = ref('')
const monthValue = ref<string>(currentMonth())
const departments = ref<string[]>([])

const usage = ref<TeamUsageVO>(emptyUsage())

const groupUsageTableRef = ref()
const { maxHeight: groupUsageMaxHeight } = useTableMaxHeight(groupUsageTableRef)
const vmUsageTableRef = ref()
const { maxHeight: vmUsageMaxHeight } = useTableMaxHeight(vmUsageTableRef)

/** 空态（接口无数据时兜底，避免 undefined 参与计算） */
function emptyUsage(): TeamUsageVO {
  return {
    month: '',
    currency: 'CNY',
    dimension: 'COMPANY',
    totalAmount: 0,
    totalTokens: 0,
    promptTokens: 0,
    completionTokens: 0,
    requestCount: 0,
    userCount: 0,
    successCount: 0,
    failedCount: 0,
    successRate: 0,
    groups: [],
    byModel: [],
    byVirtualModel: [],
    byServiceType: [],
    departments: []
  }
}

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

/** 是否已配置过价格（全部为 0 说明没配价，花费恒为 ¥0） */
const hasAnyPrice = computed(() => usage.value.byModel.some(m => Number(m.amount) > 0))

const loadUsage = async () => {
  loading.value = true
  try {
    const [y, m] = (monthValue.value || currentMonth()).split('-')
    const res = await getTeamUsage({
      dimension: dimension.value,
      year: Number(y),
      month: Number(m),
      department: department.value || undefined
    })
    if (res.data?.success) {
      usage.value = res.data.data || emptyUsage()
      await nextTick()
      renderCharts()
    } else {
      ElMessage.error(res.data?.message || t('v.tu.t029'))
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.tu.t029'))
  } finally {
    loading.value = false
  }
}

const loadDepartments = async () => {
  try {
    const res = await getBillingDepartments()
    if (res.data?.success) {
      departments.value = res.data.data || []
    }
  } catch {
    departments.value = []
  }
}

// ==================== 图表 ====================

const vmChartRef = ref<HTMLElement | null>(null)
const stChartRef = ref<HTMLElement | null>(null)
let vmChart: echarts.ECharts | null = null
let stChart: echarts.ECharts | null = null

const { chartTheme } = useChartTheme()

/**
 * 容器 0 尺寸守卫：v-show 刚展开或布局未完成时 clientWidth/Height 可能为 0，
 * 此时 echarts.init 会告警并出空白图。等下一帧重试（最多 5 帧后放弃）。
 */
const chartSized = (el: HTMLElement, render: () => void, tries = 0): void => {
  if (el.clientWidth > 0 && el.clientHeight > 0) {
    render()
    return
  }
  if (tries < 5) requestAnimationFrame(() => chartSized(el, render, tries + 1))
}

/** 横向条形图：虚拟模型用量排行 */
const renderVmChart = () => {
  if (!vmChartRef.value) return
  chartSized(vmChartRef.value, () => {
    if (!vmChart) vmChart = echarts.init(vmChartRef.value!)
    const rows = usage.value.byVirtualModel
    // ECharts 类目轴自下而上，倒序取数据让最高条显示在顶部
    const names = rows.map(r => r.virtualModel).reverse()
    const values = rows.map(r => Number(r.amount) || 0).reverse()
    vmChart.setOption({
    grid: { left: 8, right: 70, top: 10, bottom: 10, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: any[]) => {
        const p = params[0]
        const row = rows[rows.length - 1 - p.dataIndex]
        if (!row) return ''
        return `${p.name}${t('v.tu.x006', { amount: formatAmount(row.amount), tokens: formatNumber(row.totalTokens), calls: formatNumber(row.requestCount) })}`
      }
    },
    xAxis: {
      type: 'value',
      axisLabel: { color: chartTheme.value.textColor, formatter: (v: number) => `¥${v}` },
      splitLine: { lineStyle: { color: 'rgba(0,0,0,.06)' } }
    },
    yAxis: {
      type: 'category',
      data: names,
      axisLabel: { color: chartTheme.value.textColor, width: 90, overflow: 'truncate' },
      axisTick: { show: false },
      axisLine: { show: false }
    },
    series: [{
      type: 'bar',
      data: values,
      barMaxWidth: 18,
      itemStyle: { color: chartTheme.value.primary, borderRadius: [0, 4, 4, 0] },
      label: {
        show: true,
        position: 'right',
        color: chartTheme.value.textColor,
        formatter: (p: any) => `¥${formatAmount(p.value)}`
      }
    }]
    })
  })
}

/** 环形图：按用途类型分布 */
const renderStChart = () => {
  if (!stChartRef.value) return
  chartSized(stChartRef.value, () => {
    if (!stChart) stChart = echarts.init(stChartRef.value!)
    const rows = usage.value.byServiceType
    stChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => `${p.name}${t('v.tu.x007', { amount: formatAmount(p.value), percent: p.percent })}`
    },
    legend: {
      bottom: 0,
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: chartTheme.value.textColor, fontSize: 12 }
    },
    series: [{
      type: 'pie',
      radius: ['45%', '68%'],
      center: ['50%', '42%'],
      avoidLabelOverlap: true,
      // echarts canvas 不解析 CSS 变量；白描边在亮暗主题下均为常规图表做法（verify-disable）
      itemStyle: { borderColor: '#fff', borderWidth: 2 }, // verify-disable
      label: { show: false },
      data: rows.map(r => ({
        name: r.label,
        value: Number(r.amount) || 0
      }))
    }]
    })
  })
}

const renderCharts = () => {
  renderVmChart()
  renderStChart()
}

const resizeCharts = () => {
  vmChart?.resize()
  stChart?.resize()
}

watch(chartTheme, () => {
  renderCharts()
})

onMounted(() => {
  loadUsage()
  loadDepartments()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  vmChart?.dispose()
  stChart?.dispose()
  vmChart = null
  stChart = null
})

// ==================== 格式化 ====================

const formatAmount = (value?: number): string =>
  (Number(value) || 0).toFixed(2)

const formatNumber = (value?: number): string =>
  (Number(value) || 0).toLocaleString('zh-CN')

/** 大数缩写（2.4M / 600K），Token 量级用 */
const formatCompact = (value?: number): string => {
  const n = Number(value) || 0
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return n.toLocaleString('zh-CN')
}

const formatPercent = (value?: number): string =>
  `${((Number(value) || 0) * 100).toFixed(1)}%`

const percentOf = (part?: number, total?: number): string => {
  const t = Number(total) || 0
  if (t <= 0) return '0'
  return Math.round(((Number(part) || 0) / t) * 100).toString()
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.dept-row {
  margin-bottom: 12px;
}

.dept-card {
  margin-bottom: 8px;
}

.dept-card--company {
  border-color: var(--ja-primary);
}

.dept-name {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.dept-amount {
  font-size: 16px;
  font-weight: 700;
  margin-top: 4px;
  color: var(--ja-text-primary);
}

.dept-foot {
  font-size: 11px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}

.dept-bar {
  margin-top: 8px;
}

.kpi-row {
  margin-bottom: 12px;
}

.kpi-label {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.kpi-value {
  font-size: 24px;
  font-weight: 700;
  margin-top: 4px;
  color: var(--ja-text-primary);
}

.kpi-value--primary {
  color: var(--ja-primary);
}

.kpi-value--accent {
  color: var(--ja-accent-2, #c4633f);
}

.kpi-value--ok {
  color: var(--ja-success, #2e7d32);
}

.kpi-foot {
  font-size: 11px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}

.price-hint {
  margin-bottom: 12px;
}

.chart-row {
  margin-bottom: 12px;
}

.chart-box {
  width: 100%;
  height: 260px;
}

.detail-row {
  margin-top: 2px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-head__hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.cell-title {
  font-weight: 600;
  color: var(--ja-text-primary);
}

.model-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.model-tag {
  max-width: 100%;
}

.model-empty {
  color: var(--ja-text-secondary);
}
</style>
