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
import { ElMessage } from 'element-plus'
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
  | 'usage'

/** 分组 → 子页签（顺序即展示顺序；企业块各自按功能位在组件内门控） */
const GROUPS: { key: GroupKey; sub: SubKey[] }[] = [
  { key: 'access', sub: ['accounts', 'groupRoles'] },
  { key: 'modelConfig', sub: ['providerTemplates', 'modelCategories', 'protocolAdapters', 'promptTemplates', 'adapterLifecycle'] },
  { key: 'auditVersions', sub: ['audit', 'versions', 'evidence', 'archive', 'securityReport'] },
  { key: 'monitoring', sub: ['persistence', 'securityDashboard'] },
  { key: 'tenants', sub: ['tenants'] },
  { key: 'enterprise', sub: ['license', 'reconciliation', 'agentTools', 'guardrails', 'alertChannels', 'keyApproval', 'usage'] }
]

/** 路由路径 → 分组（与 router 里各 settings 路由一一对应） */
const GROUP_BY_PATH: Record<string, GroupKey> = {
  '/settings/accounts': 'access',
  '/settings/model-config': 'modelConfig',
  '/settings/audit-versions': 'auditVersions',
  '/settings/monitoring': 'monitoring',
  '/settings/tenants': 'tenants',
  '/settings/enterprise': 'enterprise',
  '/settings/enterprise/usage': 'enterprise'
}

/** 专属路由 → 默认子页签：菜单里的「企业用量总览」path 是 /settings/enterprise/usage，
 *  但本页签状态走 ?sub=，手点菜单时 URL 不带 sub，需按 path 兜底到 usage，
 *  否则 resolveCurrent 会回退到第一个子页（license），点菜单却看到许可证页。 */
const PATH_DEFAULT_SUB: Partial<Record<string, SubKey>> = {
  '/settings/enterprise/usage': 'usage'
}

/**
 * 独立承载路由：整页只渲染指定子页，不显示分组子页签栏。
 * 「企业用量总览」是系统组下的独立菜单项，与「企业管理」（/settings/enterprise，
 * 许可证 / 对账与归因 / Agent 工具等 7 个子页签）是两个不同入口——不应共用聚合页外壳，
 * 否则用量页会带出一整排与用量无关的企业管理 tab（2026-11-21 修正）。
 */
const STANDALONE_SUB_BY_PATH: Partial<Record<string, SubKey>> = {
  '/settings/enterprise/usage': 'usage'
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
  keyApproval: entPage('keyApproval'),
  usage: entPage('usage')
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
 * 当前分组的全部子页签（「恢复灰态锁」后不再过滤隐藏）。
 *
 * 社区版：企业专属子页签（ENTERPRISE_ONLY_SUBS）仍显示在页签栏，但渲染为灰态锁
 * （锁图标 + 商业版徽章），点击弹升级提示、不加载企业模块。让用户「知道自己缺什么」。
 * 企业版：全部子页签正常可用。enterprise store 加载前默认按社区版 fail-closed 灰锁，
 * 加载完成后企业版自动解锁，响应式重算。
 */
const allSubs = (group: GroupKey): SubKey[] => GROUPS.find(g => g.key === group)?.sub ?? []

/** 某个子页签当前是否处于灰态锁（社区版的企业专属项） */
const isSubLocked = (s: SubKey): boolean =>
  !enterprise.enterpriseEdition && ENTERPRISE_ONLY_SUBS.includes(s)

/** 点击灰态锁子页签：不进入、提示升级，并立即把选中态回退到可进入页签 */
function onLockedSubClick() {
  ElMessage({
    message: t('nav.feature.lockedHint'),
    type: 'info',
    showClose: true
  })
}

/** el-tabs 点击拦截：命中灰态锁页签时回退选中态（pane.name 即 SubKey） */
function onSubTabClick(pane: { name?: string | number }) {
  const s = pane.name as SubKey | undefined
  if (s && isSubLocked(s)) {
    onLockedSubClick()
    const fallback = selectableSubs(activeGroup.value)[0]
    if (fallback) activeSub.value = fallback
  }
}

/** 当前分组下「可进入」的子页签（排除灰态锁项）——用于解析 activeSub，防深链落到锁定项 */
const selectableSubs = (group: GroupKey): SubKey[] => allSubs(group).filter(s => !isSubLocked(s))

/** 由当前路由解析 { group, sub }：分组看路径（meta 兜底），子页签看 query.sub；
 *  sub 必须是该分组当前可进入的子页签，否则回退到第一个（防手输 ?sub=evidence 深链绕过）。
 *  独立承载路由（企业用量总览）直接锁定到指定子页，忽略 query.sub 的其它取值。 */
const resolveCurrent = (): { group: GroupKey; sub: SubKey } => {
  const group =
    GROUP_BY_PATH[route.path] || (route.meta.defaultGroup as GroupKey | undefined) || 'access'
  const standalone = STANDALONE_SUB_BY_PATH[route.path]
  if (standalone) return { group, sub: standalone }
  const subs = selectableSubs(group)
  const wanted = (route.query.sub as SubKey) || PATH_DEFAULT_SUB[route.path]
  const sub = subs.includes(wanted) ? wanted : subs[0]
  return { group, sub }
}

const resolved = resolveCurrent()
const activeGroup = ref<GroupKey>(resolved.group)
const activeSub = ref<SubKey>(resolved.sub)

/** 当前分组下的全部子页签列表（含灰态锁项，随企业版探测结果响应式重算锁定态）。
 *  独立承载路由（企业用量总览）退化为单项，模板走「单子页签」分支、不渲染 tab 栏。 */
const currentSubs = computed(() => {
  const standalone = STANDALONE_SUB_BY_PATH[route.path]
  return standalone ? [standalone] : allSubs(activeGroup.value)
})

// 功能位探测完成后，若当前 sub 落在灰态锁项上（如社区版曾深链到企业子页签），
// 自动回退到第一个可进入页，避免「选中一个锁定页签但内容加载不出来」的错位状态。
// 另外：冷启动深链（?sub=evidence）在 enterprise store 加载前会按社区版 fail-closed
// 解析成第一个可进入子页签；探测完成后 URL 仍显式携带 ?sub=，此时以 URL 为准再解析一次。
watch(currentSubs, (subs, prev) => {
  const selectable = selectableSubs(activeGroup.value)
  if (
    subs.length > (prev?.length ?? 0) &&
    typeof route.query.sub === 'string' &&
    route.query.sub !== activeSub.value
  ) {
    const r = resolveCurrent()
    if (selectable.includes(r.sub)) {
      activeSub.value = r.sub
      return
    }
  }
  if (selectable.length > 0 && !selectable.includes(activeSub.value)) {
    activeSub.value = selectable[0]
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

// 【冷启动深链修复】企业功能位探测完成前，企业子页签按社区版 fail-closed 被过滤，
// ?sub=evidence / ?sub=securityDashboard 等会先回退到第一个可进入页。探测完成后
// enterpriseEdition 翻转，必须按 URL 再解析一次，否则「刷新/分享不丢上下文」失效。
watch(
  () => enterprise.enterpriseEdition,
  () => {
    const selectable = selectableSubs(activeGroup.value)
    const wanted = (route.query.sub as SubKey | undefined) || PATH_DEFAULT_SUB[route.path]
    if (wanted && selectable.includes(wanted)) {
      activeSub.value = wanted
    } else if (selectable.length > 0 && !selectable.includes(activeSub.value)) {
      activeSub.value = selectable[0]
    }
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
        <!-- 灰态锁：社区版的企业专属单页签（如租户管理）显示升级占位，不加载企业模块 -->
        <div v-if="isSubLocked(currentSubs[0])" class="sub-locked-placeholder">
          <el-icon :size="34"><Lock /></el-icon>
          <p>{{ t('nav.feature.lockedHint') }}</p>
        </div>
        <template v-else>
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
      </template>

      <el-tabs v-else v-model="activeSub" class="settings-sub-tabs" @tab-click="onSubTabClick">
        <el-tab-pane
          v-for="s in currentSubs"
          :key="s"
          :name="s"
          lazy
        >
          <template #label>
            <span
              class="sub-tab-label"
              :class="{ 'is-locked': isSubLocked(s) }"
              :title="isSubLocked(s) ? t('nav.feature.lockedTooltip') : undefined"
            >
              <el-icon v-if="isSubLocked(s)"><Lock /></el-icon>
              {{ t('settings.subTabs.' + s) }}
              <el-tag v-if="isSubLocked(s)" size="small" type="warning" class="sub-tab-badge">
                <el-icon><Lock /></el-icon>{{ t('nav.feature.enterpriseBadge') }}
              </el-tag>
            </span>
          </template>
          <template v-if="isSubLocked(s)">
            <div class="sub-locked-placeholder">
              <el-icon :size="34"><Lock /></el-icon>
              <p>{{ t('nav.feature.lockedHint') }}</p>
            </div>
          </template>
          <template v-else>
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
          </template>
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

/* 企业专属子页签的灰态锁 label：明显「存在但不可用」，与可进入页签区分 */
.sub-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.sub-tab-label.is-locked {
  color: var(--ja-text-placeholder);
  cursor: not-allowed;
}
.sub-tab-badge {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  transform: scale(0.82);
  transform-origin: center;
}

/* 灰态锁内容占位：居中提示「需商业版授权」，替代企业模块空态 */
.sub-locked-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 240px;
  color: var(--ja-text-placeholder);
}
.sub-locked-placeholder p {
  margin: 0;
  font-size: 13.5px;
}
</style>
