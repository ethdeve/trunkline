<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 调用观测（v3.5 Trunkline UI 改造；2026-11-05 收编「调用详情」/tracing）
 *
 * <p>tab 结构：</p>
 * <ul>
 *   <li><b>调用历史</b>（默认）——唯一事实来源：时间/员工/虚拟模型/实际路由到/成败/耗时，
 *       点 Trace ID 打开追踪详情抽屉（原「调用详情-调用列表」的信息由此承接）</li>
 *   <li><b>调用分析</b>——原 /tracing 的追踪总览（趋势图与服务维度统计），
 *       需 {@code tracing:dashboard:read} 权限</li>
 * </ul>
 *
 * <p>原「高级查询」tab 已于 2026-11-06 移除：调用历史已覆盖其全部核心能力
 * （含按调用编号搜索 + 链路详情抽屉），且其数据源为内存追踪存储
 * （重启即空、仅保留最近记录），对普通用户只造成困惑。</p>
 *
 * <p>「高级：追踪管理」（采样率/保留策略）仍按 {@code tracing:config:manage} 权限
 * 以折叠面板形式保留在页头。设计规格 §9：不在本页塞熔断/限流/Token 统计——
 * 那些归状态监控和团队用量。</p>
 */
import { ref, computed, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import { usePermission } from '@/composables/usePermission'
import CallHistoryList from '@/views/callHistory/CallHistoryList.vue'
import TracingDashboard from '@/views/tracing/Dashboard.vue'
import TracingManagement from '@/views/tracing/Management.vue'
import { menuSubtitle } from '@/config/menu'
import { useI18n } from 'vue-i18n'

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const { hasPermission } = usePermission()
const { t } = useI18n()

/** 调用历史查询权限码（与 PermissionRuleRegistry URL 矩阵对齐） */
const CAN_VIEW = 'callhistory:view'
/** 追踪分析/查询权限码（原「调用详情」菜单的权限） */
const CAN_TRACE = 'tracing:dashboard:read'
/** 追踪管理（高级配置）权限码 */
const CAN_MANAGE_TRACE = 'tracing:config:manage'

const canTrace = computed(() => hasPermission(CAN_TRACE))
const canManageTracing = computed(() => hasPermission(CAN_MANAGE_TRACE))
const showManagement = ref(false)
// 权限被回收时收起高级区，避免残留展开态
watch(canManageTracing, allowed => {
  if (!allowed) showManagement.value = false
})

const route = useRoute()
const router = useRouter()

const _TABS = ['history', 'analysis'] as const
type TabKey = (typeof _TABS)[number]

/** 当前 tab：analysis 无权限时强制回落到 history（历史深链 tab=search 也回落） */
const active = ref<TabKey>(normalizeTab(route.query.tab))

function normalizeTab(tab: unknown): TabKey {
  if (tab === 'analysis' && hasPermission(CAN_TRACE)) return 'analysis'
  return 'history'
}

watch(active, tab => {
  router.replace({ query: { ...route.query, tab: tab === 'history' ? undefined : tab } })
})
watch(
  () => route.query.tab,
  tab => {
    const next = normalizeTab(tab)
    if (next !== active.value) active.value = next
  }
)
watch(canTrace, allowed => {
  if (!allowed && active.value !== 'history') active.value = 'history'
})

/**
 * 视图模式：全部 / 只看失败（仅「调用历史」tab）。
 *
 * <p>「只看失败」= 调用历史的「状态」筛选切到失败（{@code isSuccess=false}），
 * 即异常调用的等价视图；异常列表的独立统计（异常趋势、异常类型分布）留在
 * 「状态监控」等页面，本页不重复承载。</p>
 */
type ViewMode = 'all' | 'failed'
const viewMode = ref<ViewMode>(route.query.view === 'failed' ? 'failed' : 'all')

watch(viewMode, mode => {
  router.replace({ query: { ...route.query, view: mode === 'failed' ? 'failed' : undefined } })
})
watch(
  () => route.query.view,
  view => {
    const mode: ViewMode = view === 'failed' ? 'failed' : 'all'
    if (mode !== viewMode.value) {
      viewMode.value = mode
    }
  }
)
</script>

<template>
  <div>
    <PageHeader :title="t('nav.observability.title')" :subtitle="t(menuSubtitle('/observability'))">
      <template #actions>
        <el-button v-if="canManageTracing" text type="info" @click="showManagement = !showManagement">
          <el-icon><Setting /></el-icon>
          {{ showManagement ? t('v.ov.t001') : t('v.ov.t002') }}
        </el-button>
      </template>
    </PageHeader>

    <el-card v-if="canManageTracing" shadow="never" style="margin-bottom: 12px" v-show="showManagement">
      <TracingManagement />
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="active">
        <el-tab-pane :label="t('v.ov.t003')" name="history">
          <template v-if="hasPermission(CAN_VIEW)">
            <div class="observability-toolbar">
              <el-radio-group v-model="viewMode" size="small">
                <el-radio-button value="all">{{ t('v.ov.t004') }}</el-radio-button>
                <el-radio-button value="failed">{{ t('v.ov.t005') }}</el-radio-button>
              </el-radio-group>
              <span class="observability-hint">
                {{
                  viewMode === 'failed'
                    ? t('v.ov.t006')
                    : t('v.ov.t007')
                }}
              </span>
            </div>
            <CallHistoryList :initial-status="viewMode === 'failed' ? 'failed' : 'all'" />
          </template>
          <el-empty v-else :description="t('v.ov.t008')" />
        </el-tab-pane>
        <el-tab-pane v-if="canTrace" :label="t('v.ov.t009')" name="analysis" lazy>
          <TracingDashboard />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.observability-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.observability-hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
}
</style>
