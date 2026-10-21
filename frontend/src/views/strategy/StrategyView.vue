<script setup lang="ts">
/**
 * 策略中心（方案 A：路由规则 / 负载均衡 / 熔断阈值 / 响应缓存 / 用量配额 5 tab 合一页）
 * - 响应缓存 tab 由 feature flag 控制（本期拍板关闭，flag 打开自动恢复）
 * - 用量配额原独立菜单收编为 tab（/quota 路由保留作 deep-link 兼容）
 * - LB 监控不在此页（定稿：并入上游模型健康灯与总览拓扑）
 * - tab 状态写 URL query
 */
import { ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import RuleManagement from '@/views/config/rules/RuleManagement.vue'
import LbStrategyConfig from '@/views/load-balancer/StrategyConfig.vue'
import CircuitBreakerGlobalConfig from '@/views/circuit-breaker/GlobalConfig.vue'
import ResponseCacheManagement from '@/views/config/ResponseCacheManagement.vue'
import QuotaConfigManagement from '@/views/config/QuotaConfigManagement.vue'
import { RESPONSE_CACHE_ENABLED } from '@/featureFlags'

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()

const TABS = ['rules', 'lb', 'circuit', ...(RESPONSE_CACHE_ENABLED ? (['cache'] as const) : ([] as const)), 'quota']
type TabKey = (typeof TABS)[number]

const active = ref<TabKey>(TABS.includes(route.query.tab as TabKey) ? (route.query.tab as TabKey) : 'rules')

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
    <PageHeader title="策略中心" subtitle="路由规则 / 负载均衡 / 熔断阈值 / 响应缓存 / 用量配额" />
    <el-card shadow="never">
      <el-tabs v-model="active">
        <el-tab-pane label="路由规则" name="rules">
          <RuleManagement />
        </el-tab-pane>
        <el-tab-pane label="负载均衡" name="lb" lazy>
          <LbStrategyConfig />
        </el-tab-pane>
        <el-tab-pane label="熔断阈值" name="circuit" lazy>
          <CircuitBreakerGlobalConfig />
        </el-tab-pane>
        <el-tab-pane v-if="RESPONSE_CACHE_ENABLED" label="响应缓存" name="cache" lazy>
          <ResponseCacheManagement />
        </el-tab-pane>
        <el-tab-pane label="用量配额" name="quota" lazy>
          <QuotaConfigManagement />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
