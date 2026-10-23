<script setup lang="ts">
/**
 * 系统设置
 *
 * 结构（U9-1 Tab 分层：常用 / 高级）：
 * - 常用三个分组（顶层 Tab）：
 *   1) 账号与权限  → 子页签：账号 / 权限
 *   2) 模型配置    → 子页签：供应商模板 / 模型分类 / 模型适配器
 *   3) 审计与版本  → 子页签：审计日志 / 配置版本
 *   4) 状态监控    → 子页签：状态持久化（原「高级设置」折叠区内容，2026-10-22 由用户指定移入）
 * - 低频项（数据持久化）独立成「状态监控」分组，不再压在页面底部的折叠区里
 *   （折叠区藏在所有 Tab 下方，运营需要滚到底才能发现，且与所在 Tab 无视觉关联）。
 *
 * URL 约定：?tab=<分组>&sub=<子页签>
 * 兼容旧深链 ?tab=accounts|permissions|audit|versions|persistence|providerTemplates|modelCategories|protocolAdapters
 * （旧值会自动映射到对应分组+子页签，避免历史链接 404 到总览）。
 */
import { ref, computed, watch, provide } from 'vue'
import { useI18n } from 'vue-i18n'
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

/** 顶层分组定义（顺序即展示顺序） */
const GROUPS: { key: GroupKey; sub: SubKey[] }[] = [
  { key: 'access', sub: ['accounts', 'permissions'] },
  { key: 'modelConfig', sub: ['providerTemplates', 'modelCategories', 'protocolAdapters'] },
  { key: 'auditVersions', sub: ['audit', 'versions'] },
  { key: 'monitoring', sub: ['persistence'] }
]

/** 旧 tab 名 → 新分组/子页签 映射（历史深链兼容） */
const LEGACY_TAB_MAP: Record<string, { group: GroupKey; sub: SubKey }> = {
  accounts: { group: 'access', sub: 'accounts' },
  permissions: { group: 'access', sub: 'permissions' },
  providerTemplates: { group: 'modelConfig', sub: 'providerTemplates' },
  modelCategories: { group: 'modelConfig', sub: 'modelCategories' },
  protocolAdapters: { group: 'modelConfig', sub: 'protocolAdapters' },
  audit: { group: 'auditVersions', sub: 'audit' },
  versions: { group: 'auditVersions', sub: 'versions' },
  // 「数据持久化」原挂在页面底部「高级设置」折叠区，现独立为「状态监控」分组下的子页签
  persistence: { group: 'monitoring', sub: 'persistence' }
}

/** 旧 tab 名 → 新分组/子页签 映射（历史深链兼容，见 LEGACY_TAB_MAP） */

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

/** 解析 URL 上的 tab/sub → { group, sub } */
const resolveFromQuery = (): { group: GroupKey; sub: SubKey } => {
  const rawTab = (route.query.tab as string) || ''
  const rawSub = (route.query.sub as string) || ''

  // 新格式：tab 命中分组名
  const groupHit = GROUPS.find(g => g.key === rawTab)
  if (groupHit) {
    const sub = groupHit.sub.includes(rawSub as SubKey) ? (rawSub as SubKey) : groupHit.sub[0]
    return { group: groupHit.key, sub }
  }

  // 旧格式：tab 命中旧页签名
  const legacy = LEGACY_TAB_MAP[rawTab]
  if (legacy) {
    const groupHit2 = GROUPS.find(g => g.key === legacy.group)
    if (groupHit2) return { group: legacy.group, sub: legacy.sub }
  }

  return { group: 'access', sub: 'accounts' }
}

const resolved = resolveFromQuery()
const activeGroup = ref<GroupKey>(resolved.group)
const activeSub = ref<SubKey>(resolved.sub)

/** 当前分组下的子页签列表 */
const currentSubs = computed(() => GROUPS.find(g => g.key === activeGroup.value)?.sub ?? [])

/** 权限页需要整页填充布局（权限树是唯一滚动口） */
const isPermissions = computed(() => activeGroup.value === 'access' && activeSub.value === 'permissions')

// 分组切换：子页签重置为该组第一个
watch(activeGroup, group => {
  const subs = GROUPS.find(g => g.key === group)?.sub ?? []
  if (!subs.includes(activeSub.value)) {
    activeSub.value = subs[0] ?? 'accounts'
  }
})

// 外部（浏览器前进后退 / 其他页跳转）改变 query 时同步
watch(
  () => [route.query.tab, route.query.sub],
  () => {
    const r = resolveFromQuery()
    if (r.group !== activeGroup.value) activeGroup.value = r.group
    if (r.sub !== activeSub.value) activeSub.value = r.sub
  }
)

// 状态变化写回 URL（immediate：旧深链 ?tab=persistence 等在首次进入时也要规范化为新分组名）
watch(
  [activeGroup, activeSub],
  ([g, s]) => {
    const q = { ...route.query, tab: g, sub: s }
    if (route.query.tab !== g || route.query.sub !== s) {
      router.replace({ query: q })
    }
  },
  { immediate: true }
)
</script>

<template>
  <div class="settings-page" :class="{ 'settings-fill': isPermissions }">
    <PageHeader :title="t('shell.settings.title')" :subtitle="t('shell.settings.subtitle')" />

    <el-card shadow="never">
      <el-tabs v-model="activeGroup" class="settings-tabs">
        <!-- 1. 账号与权限 -->
        <el-tab-pane name="access">
          <template #label>
            <span class="tab-label">{{ t('settings.tabs.access') }}</span>
          </template>
          <el-tabs v-model="activeSub" class="settings-sub-tabs">
            <el-tab-pane :label="t('settings.subTabs.accounts')" name="accounts" lazy>
              <JwtAccountManagement />
            </el-tab-pane>
            <el-tab-pane :label="t('settings.subTabs.permissions')" name="permissions" lazy>
              <PermissionManagement />
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>

        <!-- 2. 模型配置 -->
        <el-tab-pane name="modelConfig">
          <template #label>
            <span class="tab-label">{{ t('settings.tabs.modelConfig') }}</span>
          </template>
          <el-tabs v-model="activeSub" class="settings-sub-tabs">
            <el-tab-pane :label="t('settings.subTabs.providerTemplates')" name="providerTemplates" lazy>
              <ProviderTemplateManagement />
            </el-tab-pane>
            <el-tab-pane :label="t('settings.subTabs.modelCategories')" name="modelCategories" lazy>
              <ServiceManagement />
            </el-tab-pane>
            <el-tab-pane :label="t('settings.subTabs.protocolAdapters')" name="protocolAdapters" lazy>
              <AdapterManagement />
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>

        <!-- 3. 审计与版本 -->
        <el-tab-pane name="auditVersions">
          <template #label>
            <span class="tab-label">{{ t('settings.tabs.auditVersions') }}</span>
          </template>
          <el-tabs v-model="activeSub" class="settings-sub-tabs">
            <el-tab-pane :label="t('settings.subTabs.audit')" name="audit" lazy>
              <AuditLogManagement />
            </el-tab-pane>
            <el-tab-pane :label="t('settings.subTabs.versions')" name="versions" lazy>
              <VersionManagement />
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>

        <!-- 4. 状态监控（原「高级设置」折叠区内容，2026-10-22 由用户指定移入） -->
        <el-tab-pane name="monitoring">
          <template #label>
            <span class="tab-label">{{ t('settings.tabs.monitoring') }}</span>
          </template>
          <el-tabs v-model="activeSub" class="settings-sub-tabs">
            <el-tab-pane :label="t('settings.subTabs.persistence')" name="persistence" lazy>
              <el-alert
                type="info"
                :closable="false"
                :title="t('settings.monitoring.persistenceAlert')"
                style="margin-bottom: 12px"
              />
              <StatePersistenceManagement />
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
/* 权限 tab 启用视口内填充布局：整页高度锁死在内容区，外层不再出现滚动条，
   页内只有权限树一个滚动口（其余 tab 保持原有页面滚动，不受影响） */
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
}

.settings-sub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.tab-label {
  font-weight: 500;
}
</style>
