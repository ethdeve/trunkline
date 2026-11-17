<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 系统设置
 *
 * 结构（2026-10-26 起：顶层 4 分组提升为左侧导航菜单项，页内只保留子页签）：
 * - 左侧菜单「系统管理」：账号管理 / 模型配置 / 审计与版本 / 状态监控（menu.ts）
 * - 分组由路由路径决定（/settings/accounts 等，见 router meta.defaultGroup），
 *   页内不再渲染顶层 tab，只渲染当前分组的子页签（供应商模板/...）；
 *   只有一个子页签的分组（账号管理）直接呈现内容，不出页签栏；状态监控含
 *   B6 安全态势子页（企业功能位门控，页面内自己显示锁定态）
 * - 子页签状态写 URL query ?sub=，刷新/分享不丢上下文
 * - 旧深链 /settings?tab=xxx 由路由 redirect 映射到新路径（见 router/index.ts）
 */
import { ref, computed, watch, provide, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import ClusterScopeNotice from '@/components/common/ClusterScopeNotice.vue'
import { menuGroups } from '@/config/menu'
import JwtAccountManagement from '@/views/security/JwtAccountManagement.vue'
import AuditLogManagement from '@/views/security/AuditLogManagement.vue'
import VersionManagement from '@/views/config/VersionManagement.vue'
import StatePersistenceManagement from '@/views/config/StatePersistenceManagement.vue'
import SecurityDashboardView from '@/views/security/SecurityDashboardView.vue'
import TenantManagementView from '@/views/settings/TenantManagementView.vue'
import ComplianceEvidenceView from '@/views/settings/ComplianceEvidenceView.vue'
import ComplianceArchiveView from '@/views/settings/ComplianceArchiveView.vue'
import SecurityReportView from '@/views/settings/SecurityReportView.vue'
import EnterpriseLicenseView from '@/views/settings/EnterpriseLicenseView.vue'
import ReconciliationView from '@/views/settings/ReconciliationView.vue'
import AgentToolsView from '@/views/settings/AgentToolsView.vue'
import GuardrailsView from '@/views/settings/GuardrailsView.vue'
import AlertChannelsView from '@/views/settings/AlertChannelsView.vue'
import ProviderTemplateManagement from '@/views/settings/ProviderTemplateManagement.vue'
import ServiceManagement from '@/views/config/ServiceManagement.vue'
import AdapterManagement from '@/views/config/AdapterManagement.vue'

type GroupKey = 'access' | 'modelConfig' | 'auditVersions' | 'monitoring' | 'tenants' | 'enterprise'
type SubKey =
  | 'accounts'
  | 'providerTemplates'
  | 'modelCategories'
  | 'protocolAdapters'
  | 'audit'
  | 'versions'
  | 'evidence'
  | 'archive'
  | 'securityReport'
  | 'persistence'
  | 'securityDashboard'
  | 'tenants'
  | 'license'
  | 'reconciliation'
  | 'agentTools'
  | 'guardrails'
  | 'alertChannels'

/** 分组 → 子页签（顺序即展示顺序；企业块各自按功能位在组件内门控） */
const GROUPS: { key: GroupKey; sub: SubKey[] }[] = [
  { key: 'access', sub: ['accounts'] },
  { key: 'modelConfig', sub: ['providerTemplates', 'modelCategories', 'protocolAdapters'] },
  { key: 'auditVersions', sub: ['audit', 'versions', 'evidence', 'archive', 'securityReport'] },
  { key: 'monitoring', sub: ['persistence', 'securityDashboard'] },
  { key: 'tenants', sub: ['tenants'] },
  { key: 'enterprise', sub: ['license', 'reconciliation', 'agentTools', 'guardrails', 'alertChannels'] }
]

/** 路由路径 → 分组（与 router 里各 settings 路由一一对应） */
const GROUP_BY_PATH: Record<string, GroupKey> = {
  '/settings/accounts': 'access',
  '/settings/model-config': 'modelConfig',
  '/settings/audit-versions': 'auditVersions',
  '/settings/monitoring': 'monitoring',
  '/settings/tenants': 'tenants',
  '/settings/enterprise': 'enterprise'
}

/** 子页签 → 组件 */
const SUB_COMPONENTS: Record<SubKey, Component> = {
  accounts: JwtAccountManagement,
  providerTemplates: ProviderTemplateManagement,
  modelCategories: ServiceManagement,
  protocolAdapters: AdapterManagement,
  audit: AuditLogManagement,
  versions: VersionManagement,
  evidence: ComplianceEvidenceView,
  archive: ComplianceArchiveView,
  securityReport: SecurityReportView,
  persistence: StatePersistenceManagement,
  securityDashboard: SecurityDashboardView,
  tenants: TenantManagementView,
  license: EnterpriseLicenseView,
  reconciliation: ReconciliationView,
  agentTools: AgentToolsView,
  guardrails: GuardrailsView,
  alertChannels: AlertChannelsView
}

/** 子页签顶部说明（人话一句话，告诉用户这页管什么、什么时候需要管） */
const SUB_ALERTS: Partial<Record<SubKey, string>> = {
  providerTemplates: 'settings.monitoring.templatesAlert',
  modelCategories: 'settings.monitoring.categoriesAlert',
  protocolAdapters: 'settings.monitoring.adaptersAlert',
  persistence: 'settings.monitoring.persistenceAlert',
  securityDashboard: 'settings.monitoring.securityDashboardAlert',
  evidence: 'settings.enterpriseAlert.evidence',
  archive: 'settings.enterpriseAlert.archive',
  license: 'settings.enterpriseAlert.license',
  reconciliation: 'settings.enterpriseAlert.reconciliation',
  agentTools: 'settings.enterpriseAlert.agentTools',
  guardrails: 'settings.enterpriseAlert.guardrails',
  alertChannels: 'settings.enterpriseAlert.alertChannels'
}

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

/** 由当前路由解析 { group, sub }：分组看路径（meta 兜底），子页签看 query.sub */
const resolveCurrent = (): { group: GroupKey; sub: SubKey } => {
  const group =
    GROUP_BY_PATH[route.path] || (route.meta.defaultGroup as GroupKey | undefined) || 'access'
  const subs = GROUPS.find(g => g.key === group)?.sub ?? ['accounts']
  const sub = subs.includes(route.query.sub as SubKey) ? (route.query.sub as SubKey) : subs[0]
  return { group, sub }
}

const resolved = resolveCurrent()
const activeGroup = ref<GroupKey>(resolved.group)
const activeSub = ref<SubKey>(resolved.sub)

/** 当前分组下的子页签列表 */
const currentSubs = computed(() => GROUPS.find(g => g.key === activeGroup.value)?.sub ?? [])

// 页头标题/副标题跟随当前菜单项（menu.ts 是唯一事实源，避免两处文案漂移）
const currentMenuItem = computed(() => {
  for (const g of menuGroups) {
    const hit = g.children.find(c => c.path === route.path)
    if (hit) return hit
  }
  return undefined
})
const pageTitle = computed(() =>
  currentMenuItem.value ? t(currentMenuItem.value.title) : t('shell.settings.title')
)
const pageSubtitle = computed(() =>
  currentMenuItem.value?.subtitle ? t(currentMenuItem.value.subtitle) : t('shell.settings.subtitle')
)

// 外部导航（菜单点击 / 浏览器前进后退 / 命令面板）改变路径或 sub 时同步页内状态
watch(
  () => [route.path, route.query.sub],
  () => {
    const r = resolveCurrent()
    if (r.group !== activeGroup.value) activeGroup.value = r.group
    if (r.sub !== activeSub.value) activeSub.value = r.sub
  }
)

// 子页签切换写回 query（分组由菜单/路径承担，不再写 tab）
watch(activeSub, s => {
  if (route.query.sub !== s) {
    router.replace({ query: { ...route.query, sub: s } })
  }
})
</script>

<template>
  <div class="settings-page">
    <PageHeader :title="pageTitle" :subtitle="pageSubtitle" />

    <el-card shadow="never">
      <!--
        单子页签分组（账号管理）不再渲染外层页签栏——
        页面本身就是这个内容，套一层「账号」页签纯属冗余（2026-11-06）
      -->
      <template v-if="currentSubs.length === 1">
        <el-alert
          v-if="SUB_ALERTS[currentSubs[0]]"
          type="info"
          :closable="false"
          :title="t(SUB_ALERTS[currentSubs[0]]!)"
          style="margin-bottom: 12px"
        />
        <ClusterScopeNotice
          v-if="currentSubs[0] === 'persistence'"
          :text="t('v.ov.t011')"
        />
        <component :is="SUB_COMPONENTS[currentSubs[0]]" />
      </template>

      <el-tabs v-else v-model="activeSub" class="settings-sub-tabs">
        <el-tab-pane
          v-for="s in currentSubs"
          :key="s"
          :label="t('settings.subTabs.' + s)"
          :name="s"
          lazy
        >
          <el-alert
            v-if="SUB_ALERTS[s]"
            type="info"
            :closable="false"
            :title="t(SUB_ALERTS[s]!)"
            style="margin-bottom: 12px"
          />
          <!--
            共享态自检结果（S4）：这是唯一需要「把自己的副本名报出来」的页面。
            单机部署下组件不渲染，多副本下说明共享态是否齐备。
          -->
          <ClusterScopeNotice
            v-if="s === 'persistence'"
            text="这里看到的是当前这台网关副本自己的状态；多台网关一起跑时，各台的状态靠共享存储保持一致"
          />
          <component :is="SUB_COMPONENTS[s]" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
/* 权限页启用视口内填充布局：整页高度锁死在内容区，外层不再出现滚动条，
   页内只有权限树一个滚动口（其余页保持原有页面滚动，不受影响） */
.settings-fill {
  height: calc(99vh - var(--ja-layout-topbar-h) - 2 * var(--ja-card-gap));
  display: flex;
  flex-direction: column;
}

.settings-fill .el-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.settings-fill :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.settings-fill :deep(.el-tabs) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.settings-fill :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.settings-fill :deep(.el-tab-pane) {
  height: 100%;
  /* pane 必须是 flex 列容器：仅设 height:100%（block）时高度约束在 pane 处断裂，
     权限树会被上层 overflow:hidden 裁掉且无滚动条（2026-10-26 修复） */
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.settings-sub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
