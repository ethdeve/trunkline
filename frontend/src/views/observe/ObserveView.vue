<script setup lang="ts">
/**
 * 观测中心（方案 A：调用历史 / Token 用量 / 慢查询 / 异常 / 配额用量 / 熔断 / 限流 7 tab 合一页）
 * - 异常 tab 内含「列表/统计」2 子 tab（旧台两页合并，定稿判定）
 * - 熔断 tab 内含「监控/历史」2 子 tab（旧台两页合并，定稿判定）
 * - 慢查询 tab 收编 SlowQueryAnalysis（旧台慢调用/慢查询/慢调用列表三合一的成品）
 * - tab 状态写 URL query，刷新/分享不丢上下文
 */
import { ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import CallHistoryList from '@/views/callHistory/CallHistoryList.vue'
import TokenUsageStatistics from '@/views/callHistory/TokenUsageStatistics.vue'
import SlowQueryAnalysis from '@/views/monitoring/SlowQueryAnalysis.vue'
import ExceptionManagement from '@/views/exception/ExceptionManagement.vue'
import ExceptionStatistics from '@/views/exception/ExceptionStatistics.vue'
import QuotaUsageMonitoring from '@/views/monitoring/QuotaUsageMonitoring.vue'
import CircuitBreakerMonitoring from '@/views/circuit-breaker/Monitoring.vue'
import CircuitBreakerHistory from '@/views/circuit-breaker/History.vue'
import RateLimiterMonitoring from '@/views/rate-limiter/Monitoring.vue'

const TABS = ['history', 'tokens', 'slow', 'exceptions', 'quota', 'circuit', 'rate'] as const
type TabKey = (typeof TABS)[number]

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()

const active = ref<TabKey>(TABS.includes(route.query.tab as TabKey) ? (route.query.tab as TabKey) : 'history')

watch(active, tab => {
  router.replace({ query: { ...route.query, tab } })
})
watch(
  () => route.query.tab,
  tab => {
    if (TABS.includes(tab as TabKey) && tab !== active.value) active.value = tab as TabKey
  }
)
</script>

<template>
  <div>
    <PageHeader title="观测中心" subtitle="调用历史 · Token · 慢查询 · 异常 · 配额 · 熔断 · 限流" />
    <el-card shadow="never">
      <el-tabs v-model="active">
        <el-tab-pane label="调用历史" name="history">
          <CallHistoryList />
        </el-tab-pane>
        <el-tab-pane label="Token 用量" name="tokens" lazy>
          <TokenUsageStatistics />
        </el-tab-pane>
        <el-tab-pane label="慢查询" name="slow" lazy>
          <SlowQueryAnalysis />
        </el-tab-pane>
        <el-tab-pane label="异常" name="exceptions" lazy>
          <el-tabs type="card">
            <el-tab-pane label="异常列表">
              <ExceptionManagement />
            </el-tab-pane>
            <el-tab-pane label="异常统计" lazy>
              <ExceptionStatistics />
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>
        <el-tab-pane label="配额用量" name="quota" lazy>
          <QuotaUsageMonitoring />
        </el-tab-pane>
        <el-tab-pane label="熔断" name="circuit" lazy>
          <el-tabs type="card">
            <el-tab-pane label="熔断监控">
              <CircuitBreakerMonitoring />
            </el-tab-pane>
            <el-tab-pane label="熔断历史" lazy>
              <CircuitBreakerHistory />
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>
        <el-tab-pane label="限流" name="rate" lazy>
          <RateLimiterMonitoring />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
