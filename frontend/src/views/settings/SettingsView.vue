<script setup lang="ts">
/**
 * 系统设置（方案 A：账号 / 权限 / 审计日志 / 配置版本 / 数据持久化 5 tab 合一页）
 * - 数据持久化为一次性部署决策（定稿：退出一级视线，收进此页末位 tab）
 * - tab 状态写 URL query
 */
import { ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import JwtAccountManagement from '@/views/security/JwtAccountManagement.vue'
import PermissionManagement from '@/views/system/PermissionManagement.vue'
import AuditLogManagement from '@/views/security/AuditLogManagement.vue'
import VersionManagement from '@/views/config/VersionManagement.vue'
import StatePersistenceManagement from '@/views/config/StatePersistenceManagement.vue'
import ProviderTemplateManagement from '@/views/settings/ProviderTemplateManagement.vue'
import ServiceManagement from '@/views/config/ServiceManagement.vue'
import AdapterManagement from '@/views/config/AdapterManagement.vue'

const TABS = [
  'accounts',
  'permissions',
  'audit',
  'versions',
  'persistence',
  'providerTemplates',
  'modelCategories',
  'protocolAdapters'
] as const
type TabKey = (typeof TABS)[number]

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()

const active = ref<TabKey>(TABS.includes(route.query.tab as TabKey) ? (route.query.tab as TabKey) : 'accounts')

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
    <PageHeader title="系统设置" subtitle="账号 / 权限 / 审计日志 / 配置版本 / 数据持久化 / 模型供应商模板 / 模型分类 / 模型适配器" />
    <el-card shadow="never">
      <el-tabs v-model="active">
        <el-tab-pane label="账号" name="accounts">
          <JwtAccountManagement />
        </el-tab-pane>
        <el-tab-pane label="权限" name="permissions" lazy>
          <PermissionManagement />
        </el-tab-pane>
        <el-tab-pane label="审计日志" name="audit" lazy>
          <AuditLogManagement />
        </el-tab-pane>
        <el-tab-pane label="配置版本" name="versions" lazy>
          <VersionManagement />
        </el-tab-pane>
        <el-tab-pane label="数据持久化" name="persistence" lazy>
          <el-alert
            type="info"
            :closable="false"
            title="一次性部署决策：持久化介质（redis / h2 / file）安装后无需再改"
            style="margin-bottom: 12px"
          />
          <StatePersistenceManagement />
        </el-tab-pane>
        <el-tab-pane label="模型供应商模板" name="providerTemplates" lazy>
          <ProviderTemplateManagement />
        </el-tab-pane>
        <el-tab-pane label="模型分类" name="modelCategories" lazy>
          <ServiceManagement />
        </el-tab-pane>
        <el-tab-pane label="模型适配器" name="protocolAdapters" lazy>
          <AdapterManagement />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
