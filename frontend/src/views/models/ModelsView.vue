<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 接入的模型（v2.11.0 供应商层改造）
 * - 页签一「供应商接入」：同一上游只配一次（名称/地址/Key），再在「管理模型」里
 *   批量或单个添加模型（支持从上游发现模型），解决每次加模型都要重填 Key 的问题
 * - 页签二「已接入模型」：原有实例列表（高级视图：权重/适配器/请求头/标签/限流/熔断）
 * - 内部组件继续进入 PageSkeleton 嵌入模式，统一由 PageHeader 承担页头
 * - 支持 ?tab=providers|instances 深链（测试场空态「去配置模型」等入口直接落到对应页签）
 */
import { provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import ProviderManagement from '@/views/models/ProviderManagement.vue'
import InstanceManagement from '@/views/config/InstanceManagement.vue'

const { t } = useI18n()

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()

const TABS = ['providers', 'instances'] as const
const activeTab = ref<string>(
  TABS.includes(route.query.tab as (typeof TABS)[number]) ? (route.query.tab as string) : 'providers'
)

// 外部（如浏览器后退 / 其他页面跳转带 query）改变 tab 时同步
watch(
  () => route.query.tab,
  tab => {
    if (TABS.includes(tab as (typeof TABS)[number]) && tab !== activeTab.value) {
      activeTab.value = tab as string
    }
  }
)

// 切换页签时同步到 URL，便于分享/回退
watch(activeTab, tab => {
  if (route.query.tab !== tab) {
    router.replace({ query: { ...route.query, tab } })
  }
})
</script>

<template>
  <div>
    <PageHeader :title="t('shell.models.title')" :subtitle="t('shell.models.subtitle')" />

    <el-card shadow="never">
      <el-tabs v-model="activeTab" class="models-tabs">
        <el-tab-pane :label="'供应商接入'" name="providers">
          <ProviderManagement />
        </el-tab-pane>
        <el-tab-pane :label="'已接入模型'" name="instances">
          <!-- 跟随 tab 激活挂载：每次切入都重新 onMounted 拉取最新实例（供应商卡片添加模型后切回即见新数据） -->
          <InstanceManagement v-if="activeTab === 'instances'" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.models-tabs ::v-deep(.el-tabs__content) {
  padding: 12px 0 0;
}
</style>
