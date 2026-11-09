<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 策略中心（v3.6 Trunkline UI 改造：路由规则 / 响应缓存）
 *
 * <p>原为 5 tab（路由规则 / 负载均衡 / 熔断阈值 / 响应缓存 / 用量配额），按「全局配置」页的规划收敛：</p>
 * <ul>
 *   <li><b>熔断阈值</b>、<b>用量配额</b> → 迁至「系统 → 审计与版本 → 全局配置」，本页 tab 删除；</li>
 *   <li><b>负载均衡</b> → v3.6 起「全局默认配置」卡片与「各模型类型的策略列表」
 *       <b>两张卡片一并迁至「全局配置 → 负载均衡」tab</b>（合回 globalConfigShared.ts
 *       注释描述的原设计，使 provide/inject 两端同页生效），本页该 tab 删除；</li>
 *   <li><b>路由规则</b> → 留在本页（属于策略编排，不是全局默认值）；</li>
 *   <li>响应缓存 tab 仍由 feature flag 控制（本期关闭）。</li>
 * </ul>
 *
 * <p>tab 形态按原型改为按钮组（🎯 路由规则），只挂载当前激活面板，
 * 避免面板的表格/请求常驻；URL 的 ?tab= 同步保留（可深链到某个 tab）。</p>
 */
import { ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import RuleManagement from '@/views/config/rules/RuleManagement.vue'
import ResponseCacheManagement from '@/views/config/ResponseCacheManagement.vue'
import { RESPONSE_CACHE_ENABLED } from '@/featureFlags'
import { menuSubtitle } from '@/config/menu'

const { t } = useI18n()

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()

const TABS = ['rules', ...(RESPONSE_CACHE_ENABLED ? (['cache'] as const) : ([] as const))]
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
    <PageHeader :title="t('shell.strategy.title')" :subtitle="menuSubtitle('/strategy')" help-link="/docs?path=team/routing-rules" />

    <!-- 原型式 tab 按钮组 -->
    <div class="pol-tabs">
      <button
        class="pol-tab"
        :class="{ 'is-active': active === 'rules' }"
        type="button"
        @click="active = 'rules'"
      >
        {{ t('shell.strategy.tabs.routeRules') }}
      </button>
      <button
        v-if="RESPONSE_CACHE_ENABLED"
        class="pol-tab"
        :class="{ 'is-active': active === 'cache' }"
        type="button"
        @click="active = 'cache'"
      >
        {{ t('shell.strategy.tabs.responseCache') }}
      </button>
    </div>

    <!-- 只挂载激活面板：切走即销毁，避免面板的表格与请求常驻 -->
    <div class="pol-panel">
      <RuleManagement v-if="active === 'rules'" />
      <ResponseCacheManagement v-else-if="active === 'cache'" />
    </div>
  </div>
</template>

<style scoped>
/* 原型式 tab 按钮组：小圆角按钮，选中态为品牌色底 */
.pol-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.pol-tab {
  padding: 5px 12px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ja-text-primary);
  background: var(--ja-bg-card, #fff);
  border: 1px solid var(--ja-border-color, var(--el-border-color));
  border-radius: 8px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s, background 0.15s;
}

.pol-tab:hover {
  color: var(--ja-primary, #176C6B);
  border-color: var(--ja-primary, #176C6B);
}

.pol-tab.is-active {
  /* 暗色下主色提亮，白字对比不足；用卡片底色文字（亮=白 / 暗=深） */
  color: var(--ja-bg-card);
  background: var(--ja-primary, #176C6B);
  border-color: var(--ja-primary, #176C6B);
  font-weight: 600;
}

.pol-panel {
  background: var(--ja-bg-card, #fff);
  border: 1px solid var(--ja-border-color, var(--el-border-color-lighter));
  border-radius: 12px;
  padding: 16px 18px;
}
</style>
