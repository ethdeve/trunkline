<script setup lang="ts">
/**
 * 链路追踪（方案 A：dashboard / search 2 tab，「追踪管理」进高级折叠区）
 */
import { ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import TracingDashboard from '@/views/tracing/Dashboard.vue'
import TracingSearch from '@/views/tracing/Search.vue'
import TracingManagement from '@/views/tracing/Management.vue'

const TABS = ['dashboard', 'search'] as const
type TabKey = (typeof TABS)[number]

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()
const showManagement = ref(false)

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
</script>

<template>
  <div>
    <PageHeader title="链路追踪">
      <template #actions>
        <el-button text type="info" @click="showManagement = !showManagement">
          <el-icon><Setting /></el-icon>
          {{ showManagement ? '收起高级设置' : '高级：追踪管理' }}
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never" style="margin-bottom: 12px" v-show="showManagement">
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
