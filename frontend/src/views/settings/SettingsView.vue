<script setup lang="ts">
/**
 * 系统设置
 *
 * 结构（2026-10-26 起：顶层 4 分组提升为左侧导航菜单项，页内只保留子页签）：
 * - 左侧菜单「系统管理」：账号与权限 / 模型配置 / 审计与版本 / 状态监控（menu.ts）
 * - 分组由路由路径决定（/settings/accounts 等，见 router meta.defaultGroup），
 *   页内不再渲染顶层 tab，只渲染当前分组的子页签（账号/权限、供应商模板/...）
 * - 子页签状态写 URL query ?sub=，刷新/分享不丢上下文
 * - 旧深链 /settings?tab=xxx 由路由 redirect 映射到新路径（见 router/index.ts）
 */
import { ref, computed, watch, provide, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import { menuGroups } from '@/config/menu'
import JwtAccountManagement from '@/views/security/JwtAccountManagement.vue'
import PermissionManagement from '@/views/system/PermissionManagement.vue'
import AuditLogManagement from '@/views/security/AuditLogManagement.vue'
import VersionManagement from '@/views/config/VersionManagement.vue'
import StatePersistenceManagement from '@/views/config/StatePersistenceManagement.vue'
import ProviderTemplateManagement from '@/views/settings/ProviderTemplateManagement.vue'
import ServiceManagement from '@/views/config/ServiceManagement.vue'
import AdapterManagement from '@/views/config/AdapterManagement.vue'

type GroupKey = 'access' | 'modelConfig' | 'auditVersions' | 'monitoring'
type SubKey =
  | 'accounts'
  | 'permissions'
  | 'providerTemplates'
  | 'modelCategories'
  | 'protocolAdapters'
  | 'audit'
  | 'versions'
  | 'persistence'

/** 分组 → 子页签（顺序即展示顺序） */
const GROUPS: { key: GroupKey; sub: SubKey[] }[] = [
  { key: 'access', sub: ['accounts', 'permissions'] },
  { key: 'modelConfig', sub: ['providerTemplates', 'modelCategories', 'protocolAdapters'] },
  { key: 'auditVersions', sub: ['audit', 'versions'] },
  { key: 'monitoring', sub: ['persistence'] }
]

/** 路由路径 → 分组（与 router 里 4 个 settings 路由一一对应） */
const GROUP_BY_PATH: Record<string, GroupKey> = {
  '/settings/accounts': 'access',
  '/settings/model-config': 'modelConfig',
  '/settings/audit-versions': 'auditVersions',
  '/settings/monitoring': 'monitoring'
}

/** 子页签 → 组件 */
const SUB_COMPONENTS: Record<SubKey, Component> = {
  accounts: JwtAccountManagement,
  permissions: PermissionManagement,
  providerTemplates: ProviderTemplateManagement,
  modelCategories: ServiceManagement,
  protocolAdapters: AdapterManagement,
  audit: AuditLogManagement,
  versions: VersionManagement,
  persistence: StatePersistenceManagement
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
const pageTitle = computed(() => currentMenuItem.value?.title ?? t('shell.settings.title'))
const pageSubtitle = computed(() => currentMenuItem.value?.subtitle ?? t('shell.settings.subtitle'))

/** 权限页需要整页填充布局（权限树是唯一滚动口） */
const isPermissions = computed(
  () => activeGroup.value === 'access' && activeSub.value === 'permissions'
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
  <div class="settings-page" :class="{ 'settings-fill': isPermissions }">
    <PageHeader :title="pageTitle" :subtitle="pageSubtitle" />

    <el-card shadow="never">
      <el-tabs v-model="activeSub" class="settings-sub-tabs">
        <el-tab-pane
          v-for="s in currentSubs"
          :key="s"
          :label="t('settings.subTabs.' + s)"
          :name="s"
          lazy
        >
          <el-alert
            v-if="s === 'persistence'"
            type="info"
            :closable="false"
            :title="t('settings.monitoring.persistenceAlert')"
            style="margin-bottom: 12px"
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
