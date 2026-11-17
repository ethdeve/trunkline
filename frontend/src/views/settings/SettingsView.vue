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
import { ref, computed, watch, provide, defineComponent, h, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import ClusterScopeNotice from '@/components/common/ClusterScopeNotice.vue'
import { menuGroups, ENTERPRISE_ONLY_SUBS } from '@/config/menu'
import { useEnterpriseStore } from '@/stores/enterprise'
import JwtAccountManagement from '@/views/security/JwtAccountManagement.vue'
import AuditLogManagement from '@/views/security/AuditLogManagement.vue'
import VersionManagement from '@/views/config/VersionManagement.vue'
import StatePersistenceManagement from '@/views/config/StatePersistenceManagement.vue'
import ProviderTemplateManagement from '@/views/settings/ProviderTemplateManagement.vue'
import ServiceManagement from '@/views/config/ServiceManagement.vue'
import AdapterManagement from '@/views/config/AdapterManagement.vue'
import EnterpriseModuleHost from '@/components/EnterpriseModuleHost.vue'

/**
 * 企业视图包装器：企业「视图内容」从企业 jar 的 module.mjs 加载（自包含子应用），
 * OSS 侧不再持有这些视图组件源码。每个包装闭包记住自己的 page key，
 * 由 EnterpriseModuleHost 按 page 切子应用内部页。
 */
const entPage = (page: string) =>
  defineComponent({
    name: `EnterprisePage_${page}`,
    setup: () => () => h(EnterpriseModuleHost, { page })
  })

type GroupKey = 'access' | 'modelConfig' | 'auditVersions' | 'monitoring' | 'tenants' | 'enterprise'
type SubKey =
  | 'accounts'
  | 'groupRoles'
  | 'providerTemplates'
  | 'modelCategories'
  | 'protocolAdapters'
  | 'promptTemplates'
  | 'adapterLifecycle'
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
  | 'keyApproval'

/** 分组 → 子页签（顺序即展示顺序；企业块各自按功能位在组件内门控） */
const GROUPS: { key: GroupKey; sub: SubKey[] }[] = [
  { key: 'access', sub: ['accounts', 'groupRoles'] },
  { key: 'modelConfig', sub: ['providerTemplates', 'modelCategories', 'protocolAdapters', 'promptTemplates', 'adapterLifecycle'] },
  { key: 'auditVersions', sub: ['audit', 'versions', 'evidence', 'archive', 'securityReport'] },
  { key: 'monitoring', sub: ['persistence', 'securityDashboard'] },
  { key: 'tenants', sub: ['tenants'] },
  { key: 'enterprise', sub: ['license', 'reconciliation', 'agentTools', 'guardrails', 'alertChannels', 'keyApproval'] }
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

/** 子页签 → 组件（企业视图一律走 EnterpriseModuleHost，从企业 jar 加载） */
const SUB_COMPONENTS: Record<SubKey, Component> = {
  accounts: JwtAccountManagement,
  groupRoles: entPage('groupRoles'),
  providerTemplates: ProviderTemplateManagement,
  modelCategories: ServiceManagement,
  protocolAdapters: AdapterManagement,
  promptTemplates: entPage('promptTemplates'),
  adapterLifecycle: entPage('adapterLifecycle'),
  audit: AuditLogManagement,
  versions: VersionManagement,
  evidence: entPage('evidence'),
  archive: entPage('archive'),
  securityReport: entPage('securityReport'),
  persistence: StatePersistenceManagement,
  securityDashboard: entPage('securityDashboard'),
  tenants: entPage('tenants'),
  license: entPage('license'),
  reconciliation: entPage('reconciliation'),
  agentTools: entPage('agentTools'),
  guardrails: entPage('guardrails'),
  alertChannels: entPage('alertChannels'),
  keyApproval: entPage('keyApproval')
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
  alertChannels: 'settings.enterpriseAlert.alertChannels',
  keyApproval: 'settings.enterpriseAlert.keyApproval',
  groupRoles: 'settings.enterpriseAlert.groupRoles',
  promptTemplates: 'settings.enterpriseAlert.promptTemplates',
  adapterLifecycle: 'settings.enterpriseAlert.adapterLifecycle'
}

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const enterprise = useEnterpriseStore()

/**
 * 当前分组下「可见」的子页签。
 *
 * 社区版（edition !== 'enterprise'）移除企业版专属子页签：
 * 合规证据（B1 哈希链）/ 合规归档（B7 WORM）/ 安全报告 / 组与角色（A5 企业身份链）/
 * 提示词模板 / 适配器生命周期 / 安全态势（B6 企业合规包）——这些只随企业版交付，
 * 社区版显示出来只会误导（ enterprise store 加载前默认按社区版
 * fail-closed 隐藏，加载完成后企业版自动补回，响应式重算）。
 */
const visibleSubs = (group: GroupKey): SubKey[] => {
  const subs = GROUPS.find(g => g.key === group)?.sub ?? []
  if (enterprise.enterpriseEdition) return subs
  return subs.filter(s => !ENTERPRISE_ONLY_SUBS.includes(s))
}

/** 由当前路由解析 { group, sub }：分组看路径（meta 兜底），子页签看 query.sub；
 *  sub 必须是该分组当前可见子页签，否则回退到第一个（防手输 ?sub=evidence 深链绕过）*/
const resolveCurrent = (): { group: GroupKey; sub: SubKey } => {
  const group =
    GROUP_BY_PATH[route.path] || (route.meta.defaultGroup as GroupKey | undefined) || 'access'
  const subs = visibleSubs(group)
  const sub = subs.includes(route.query.sub as SubKey) ? (route.query.sub as SubKey) : subs[0]
  return { group, sub }
}

const resolved = resolveCurrent()
const activeGroup = ref<GroupKey>(resolved.group)
const activeSub = ref<SubKey>(resolved.sub)

/** 当前分组下的可见子页签列表（随企业版探测结果响应式重算） */
const currentSubs = computed(() => visibleSubs(activeGroup.value))

// 功能位探测完成后，若当前 sub 在新可见列表中已不存在（如社区版曾深链到企业子页签），
// 自动回退到第一个可见页，避免显示一个「隐藏页签的内容但页签栏看不到」的错位状态。
watch(currentSubs, subs => {
  if (subs.length > 0 && !subs.includes(activeSub.value)) {
    activeSub.value = subs[0]
  }
})

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
