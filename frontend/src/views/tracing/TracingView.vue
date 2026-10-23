<script setup lang="ts">
/**
 * 链路追踪（方案 A：dashboard / search 2 tab，「追踪管理」进高级折叠区）
 *
 * U7-4：「追踪管理」属高级配置（采样率/保留策略），对无 tracing:config:manage 权限的
 * 角色（VIEWER / USER）直接隐藏入口，避免误入后面对一堆改不了的设置。
 */
import { ref, computed, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/common/PageHeader.vue'
import TracingDashboard from '@/views/tracing/Dashboard.vue'
import TracingSearch from '@/views/tracing/Search.vue'
import TracingManagement from '@/views/tracing/Management.vue'
import { usePermission } from '@/composables/usePermission'

const TABS = ['dashboard', 'search'] as const
type TabKey = (typeof TABS)[number]

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const showManagement = ref(false)

const { hasPermission } = usePermission()
/** 只有能管理追踪配置的角色才看到「高级：追踪管理」入口 */
const canManageTracing = computed(() => hasPermission('tracing:config:manage'))

const active = ref<TabKey>(TABS.includes(route.query.tab as TabKey) ? (route.query.tab as TabKey) : 'dashboard')

watch(active, tab => {
  router.replace({ query: { ...route.query, tab } })
})
watch(
  () => route.query.tab,
  tab => {
    if (TABS.includes(tab as TabKey) && tab !== active.value) active.value = tab as TabKey
  }
)
// 权限被回收时收起高级区，避免残留展开态
watch(canManageTracing, allowed => {
  if (!allowed) showManagement.value = false
})
</script>

<template>
  <div>
    <PageHeader :title="t('shell.tracing.title')" :subtitle="t('shell.tracing.subtitle')">
      <template #actions>
        <el-button v-if="canManageTracing" text type="info" @click="showManagement = !showManagement">
          <el-icon><Setting /></el-icon>
          {{ showManagement ? '收起高级设置' : '高级：追踪管理' }}
        </el-button>
      </template>
    </PageHeader>

    <el-card v-if="canManageTracing" shadow="never" style="margin-bottom: 12px" v-show="showManagement">
      <TracingManagement />
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="active">
        <el-tab-pane label="追踪总览" name="dashboard">
          <TracingDashboard />
        </el-tab-pane>
        <el-tab-pane label="追踪查询" name="search" lazy>
          <TracingSearch />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
