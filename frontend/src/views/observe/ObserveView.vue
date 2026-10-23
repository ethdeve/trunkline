<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 观测中心（方案 A：调用历史 / Token 用量 / 慢查询 / 异常 / 配额用量 / 熔断 / 限流 7 tab 合一页）
 * - 异常 tab 内含「列表/统计」2 子 tab（旧台两页合并，定稿判定）
 * - 熔断 tab 内含「监控/历史」2 子 tab（旧台两页合并，定稿判定）
 * - 慢查询 tab 收编 SlowQueryAnalysis（旧台慢调用/慢查询/慢调用列表三合一的成品）
 * - tab 状态写 URL query，刷新/分享不丢上下文
 * - 性能：只挂载当前激活 tab 的内容（v-if = active），切走即销毁组件释放资源。
 *   此前仅靠 el-tab-pane 的 lazy（延迟首次渲染），但访问过后 pane 永不卸载——
 *   调用历史/Token 用量/异常统计等组件的 ECharts 实例、resize 监听、限流页 30s
 *   轮询定时器全部常驻，遍历一遍 7 个 tab 后页面累积 10+ 图表实例与隐藏 DOM，
 *   F12 与页面交互明显卡顿。各 tab 组件卸载清理已核实完善（dispose/removeEventListener/
 *   clearInterval），销毁重建安全；代价是切回 tab 重新拉一次数据（本地后端，开销可接受），
 *   调用历史的筛选条件经 usePersistedState 持久化，不受重建影响。
 */
import { ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import { usePermission } from '@/composables/usePermission'
import CallHistoryList from '@/views/callHistory/CallHistoryList.vue'
import TokenUsageStatistics from '@/views/callHistory/TokenUsageStatistics.vue'
import SlowQueryAnalysis from '@/views/monitoring/SlowQueryAnalysis.vue'
import ExceptionManagement from '@/views/exception/ExceptionManagement.vue'
import ExceptionStatistics from '@/views/exception/ExceptionStatistics.vue'
import QuotaUsageMonitoring from '@/views/monitoring/QuotaUsageMonitoring.vue'
import CircuitBreakerMonitoring from '@/views/circuit-breaker/Monitoring.vue'
import CircuitBreakerHistory from '@/views/circuit-breaker/History.vue'
import RateLimiterMonitoring from '@/views/rate-limiter/Monitoring.vue'

const { t } = useI18n()

// U7-1：Tab 按运营任务排序——「调不通来看原因」最高频，故 调用历史 置首（默认页）；
// 限流/配额是失败最常见成因，紧随其后；异常/熔断/慢查询为深排障，靠后。
const TABS = ['history', 'rate', 'quota', 'tokens', 'exceptions', 'circuit', 'slow'] as const
type TabKey = (typeof TABS)[number]
// tab → 加载该 tab 所需的后端权限码（与 PermissionRuleRegistry URL 矩阵对齐）。
// 无对应权限的 tab 直接隐藏，避免「切过去即 403」的体验问题
// （如 VIEWER 无 callhistory:view，调用历史 tab 隐藏；管理员可在权限 UI 给角色补码后重新登录恢复）。
const TAB_PERMISSIONS: Record<TabKey, string> = {
  history: 'callhistory:view',
  tokens: 'monitoring:tokenusage:read',
  slow: 'monitoring:slowquery:read',
  exceptions: 'monitoring:exceptions:read',
  quota: 'monitoring:quota:read',
  circuit: 'cb:monitoring:read',
  rate: 'rl:monitoring:read'
}

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const { hasPermission } = usePermission()
const visibleTabs = TABS.filter(tab => hasPermission(TAB_PERMISSIONS[tab]))
const fallbackTab = visibleTabs[0] ?? 'tokens'

const route = useRoute()
const router = useRouter()

const active = ref<TabKey>(
  visibleTabs.includes(route.query.tab as TabKey) ? (route.query.tab as TabKey) : fallbackTab
)

watch(active, tab => {
  router.replace({ query: { ...route.query, tab } })
})
watch(
  () => route.query.tab,
  tab => {
    if (visibleTabs.includes(tab as TabKey) && tab !== active.value) active.value = tab as TabKey
  }
)
</script>

<template>
  <div>
    <PageHeader :title="t('shell.observe.title')" :subtitle="t('shell.observe.subtitle')" help-link="/guide" />
    <el-card shadow="never">
      <el-tabs v-model="active">
        <el-tab-pane v-if="visibleTabs.includes('history')" label="调用历史" name="history">
          <template v-if="active === 'history'"><CallHistoryList /></template>
        </el-tab-pane>
        <el-tab-pane v-if="visibleTabs.includes('rate')" label="限流" name="rate">
          <template v-if="active === 'rate'"><RateLimiterMonitoring /></template>
        </el-tab-pane>
        <el-tab-pane v-if="visibleTabs.includes('quota')" label="配额用量" name="quota">
          <template v-if="active === 'quota'"><QuotaUsageMonitoring /></template>
        </el-tab-pane>
        <el-tab-pane v-if="visibleTabs.includes('tokens')" label="Token 用量" name="tokens">
          <template v-if="active === 'tokens'"><TokenUsageStatistics /></template>
        </el-tab-pane>
        <el-tab-pane v-if="visibleTabs.includes('exceptions')" label="异常" name="exceptions">
          <el-tabs v-if="active === 'exceptions'" type="card">
            <el-tab-pane label="异常列表">
              <ExceptionManagement />
            </el-tab-pane>
            <el-tab-pane label="异常统计" lazy>
              <ExceptionStatistics />
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>
        <el-tab-pane v-if="visibleTabs.includes('circuit')" label="熔断" name="circuit">
          <el-tabs v-if="active === 'circuit'" type="card">
            <el-tab-pane label="熔断监控">
              <CircuitBreakerMonitoring />
            </el-tab-pane>
            <el-tab-pane label="熔断历史" lazy>
              <CircuitBreakerHistory />
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>
        <el-tab-pane v-if="visibleTabs.includes('slow')" label="慢查询" name="slow">
          <template v-if="active === 'slow'"><SlowQueryAnalysis /></template>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
