<template>
  <PageSkeleton :title="t('rule.pageTitle')">
    <!-- 原型式面板头：标题 + 提示 + 操作按钮 -->
    <div class="rule-panel-head">
      <div class="rule-panel-head__left">
        <span class="rule-panel-head__title">{{ t('rule.panelTitle') }}</span>
        <span class="rule-panel-head__hint">{{ t('rule.panelHint') }}</span>
      </div>
      <div class="rule-panel-head__actions">
        <el-button :icon="Refresh" circle @click="refresh" :title="t('rule.refreshTooltip')" />
        <el-button v-if="canWrite" type="success" plain @click="templateDialogVisible = true">
          <el-icon><MagicStick /></el-icon>&nbsp;{{ t('rule.createFromTemplate') }}
        </el-button>
        <el-button v-if="canWrite" @click="simulateVisible = true">
          <el-icon><MagicStick /></el-icon>&nbsp;{{ t('rule.simulate') }}
        </el-button>
        <el-button v-if="canWrite" type="primary" @click="handleCreate">
          <el-icon><Plus /></el-icon>&nbsp;{{ t('rule.addRule') }}
        </el-button>
      </div>
    </div>

    <el-table ref="tableRef" :data="rules" :max-height="tableMaxHeight" v-loading="loading" style="width: 100%" row-key="id">
      <!-- 优先级：数字标签（越大越先匹配），YAML 规则不可拖拽 -->
      <el-table-column :label="t('rule.priority')" width="86" align="center">
        <template #default="{ row }">
          <span class="rule-priority">
            <el-icon v-if="row.source !== 'YAML'" class="drag-handle"><Rank /></el-icon>
            <el-tag size="small" effect="plain" :type="row.enabled ? 'primary' : 'info'">{{ row.priority }}</el-tag>
          </span>
        </template>
      </el-table-column>

      <!-- 规则名 -->
      <el-table-column :label="t('rule.name')" min-width="150">
        <template #default="{ row }">
          <span class="rule-name">{{ row.name }}</span>
          <el-tag v-if="row.source === 'YAML'" size="small" type="info" style="margin-left: 6px">YAML</el-tag>
        </template>
      </el-table-column>

      <!-- 满足什么条件（白话） -->
      <el-table-column :label="t('rule.condition')" min-width="220">
        <template #default="{ row }">
          <span class="rule-plain">{{ plainConditions(t, row.conditions) }}</span>
        </template>
      </el-table-column>

      <!-- 做什么（白话） -->
      <el-table-column :label="t('rule.action')" min-width="200">
        <template #default="{ row }">
          <el-link
            v-if="row.action && (row.action.type === 'TARGET_MODEL' || row.action.type === 'TARGET_INSTANCE' || row.action.type === 'TARGET_ADAPTER')"
            type="primary"
            underline="never"
            @click.stop="navigateActionTarget(row.action)"
          >
            {{ plainAction(t, row.action) }}
          </el-link>
          <span v-else class="rule-plain">{{ plainAction(t, row.action) }}</span>
        </template>
      </el-table-column>

      <!-- 本月命中：多副本下命中数只含本副本，必须标注口径，否则运维会误判 -->
      <el-table-column :label="monthHitsLabel" width="130" align="right">
        <template #default="{ row }">
          <span v-if="monthHitsMap[row.id]" class="rule-hits">{{ formatNumber(monthHitsMap[row.id]) }}</span>
          <span v-else class="rule-hits-empty">—</span>
        </template>
      </el-table-column>

      <!-- 状态 -->
      <el-table-column :label="t('rule.status')" width="90" align="center" fixed="right">
        <template #default="{ row }">
          <el-tag v-if="row.enabled" type="success" size="small" effect="plain">{{ t('rule.statusEnabled') }}</el-tag>
          <el-tag v-else type="info" size="small" effect="plain">{{ t('rule.statusDisabled') }}</el-tag>
        </template>
      </el-table-column>

      <!-- 操作 -->
      <el-table-column :label="t('rule.operations')" width="190" align="center" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canWrite" size="small" @click="handleToggle(row, !row.enabled)">
            {{ row.enabled ? t('rule.disable') : t('rule.enable') }}
          </el-button>
          <el-button v-if="canWrite" size="small" @click="handleEdit(row)">{{ t('rule.edit') }}</el-button>
          <el-button v-if="canWrite" size="small" type="danger" @click="handleDelete(row)">{{ t('rule.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && rules.length === 0" class="list-empty">
      <el-empty :description="t('rule.emptyText')">
        <el-button v-if="canWrite" type="primary" @click="handleCreate">{{ t('rule.addRule') }}</el-button>
      </el-empty>
    </div>
  </PageSkeleton>

  <RuleFormDialog
    v-model="dialogVisible"
    :rule="editingRule"
    @saved="fetchRules"
  />

  <RuleSimulateDialog v-model="simulateVisible" />

  <RuleTemplateDialog v-model="templateDialogVisible" @created="handleTemplateCreated" />
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { ElMessage } from 'element-plus'
import { confirmDanger } from '@/utils/dangerConfirm'
import { Plus, MagicStick, Rank, Refresh } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import {
  getRuleList,
  deleteRule,
  enableRule,
  disableRule,
  getRuleStats,
  updateRulePriorities,
  type RuleDefinition,
  type RuleAction
} from '@/api/rules'
import RuleFormDialog from './RuleFormDialog.vue'
import RuleSimulateDialog from './RuleSimulateDialog.vue'
import RuleTemplateDialog from './RuleTemplateDialog.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import { plainAction, plainConditions } from './rulePlainText'
import { useDeploymentStore } from '@/stores/deployment'

const rules = ref<RuleDefinition[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editingRule = ref<RuleDefinition | null>(null)
/** 本月命中数（ruleId → 次数），跨月归零 */
const monthHitsMap = ref<Record<string, number>>({})
const tableRef = ref()
const { maxHeight: tableMaxHeight } = useTableMaxHeight(tableRef)
const templateDialogVisible = ref(false)
const simulateVisible = ref(false)
const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('config:rules:write')
const router = useRouter()
const deployment = useDeploymentStore()

/**
 * 「本月命中」列头（S4 副本感知）。
 *
 * 命中计数是**进程内**统计，多副本部署时每台只算自己经手的请求，
 * 三副本下看到的数字大约是总量的 1/3。列头不标注的话，运维会拿它当月度总量做判断。
 */
const monthHitsLabel = computed(() =>
  deployment.isCluster && deployment.replicas > 0
    ? `${t('rule.monthHits')}（本副本 / ${deployment.replicas}）`
    : deployment.isCluster
      ? `${t('rule.monthHits')}（本副本）`
      : t('rule.monthHits')
)

const formatNumber = (n: number) => n.toLocaleString()

const fetchRules = async () => {
  loading.value = true
  try {
    const res = await getRuleList()
    rules.value = res.data?.data || []
    await nextTick()
    initDrag()
  } catch (_e) {
    ElMessage.error(t('rule.fetchListFailed'))
  } finally {
    loading.value = false
  }
}

const fetchStats = async () => {
  try {
    const res = await getRuleStats()
    const stats = res.data?.data || []
    const map: Record<string, number> = {}
    stats.forEach(s => {
      map[s.ruleId] = (map[s.ruleId] || 0) + (s.monthHits ?? 0)
    })
    monthHitsMap.value = map
  } catch (_e) {
    // 统计加载失败不阻塞列表
  }
}

const refresh = () => {
  fetchRules()
  fetchStats()
}

// ==================== 优先级拖拽 ====================
let sortable: Sortable | null = null

const initDrag = () => {
  if (!tableRef.value?.$el) return
  const tbody = tableRef.value.$el.querySelector('.el-table__body-wrapper tbody')
  if (!tbody || sortable) return
  sortable = Sortable.create(tbody, {
    handle: '.drag-handle',
    filter: '.yaml-row',
    animation: 150,
    onEnd: (evt) => {
      const { oldIndex, newIndex } = evt
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) return

      const arr = [...rules.value]
      const [moved] = arr.splice(oldIndex, 1)
      arr.splice(newIndex, 0, moved)
      rules.value = arr
      commitPriorities()
    }
  })
}

const commitPriorities = async () => {
  const items = rules.value.map((rule, index) => ({
    id: rule.id!,
    priority: rules.value.length - index
  }))
  try {
    const res = await updateRulePriorities(items)
    const data = res.data?.data
    if (data?.skipped) {
      ElMessage.warning(
        t('rule.priorityUpdatedPartial', { updated: data.updated, skipped: data.skipped })
      )
    } else {
      ElMessage.success(t('rule.priorityUpdated'))
    }
    fetchRules()
  } catch (_e) {
    ElMessage.error(t('rule.priorityUpdateFailed'))
    fetchRules()
  }
}

const handleCreate = () => {
  editingRule.value = null
  dialogVisible.value = true
}

const handleTemplateCreated = (draft: RuleDefinition) => {
  editingRule.value = draft
  dialogVisible.value = true
}

const handleEdit = (row: RuleDefinition) => {
  editingRule.value = row
  dialogVisible.value = true
}

const handleDelete = async (row: RuleDefinition) => {
  const confirmed = await confirmDanger({
    title: t('rule.deleteConfirmTitle'),
    target: row.name,
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await deleteRule(row.id!)
    ElMessage.success(t('rule.ruleDeleted'))
    fetchRules()
  } catch (_e) {
    // 删除失败保持安静（与原行为一致）
  }
}

const handleToggle = async (row: RuleDefinition, enabled: boolean) => {
  try {
    if (enabled) {
      await enableRule(row.id!)
    } else {
      await disableRule(row.id!)
    }
    ElMessage.success(enabled ? t('rule.ruleEnabled') : t('rule.ruleDisabled'))
    fetchRules()
  } catch (_e) {
    ElMessage.error(t('rule.operationFailed'))
  }
}

// Cross-link: 根据动作类型导航到对应配置页
// U1-3：站内入口统一收归「系统设置 → 模型配置」子页签，游离路由仅保留深链兼容
const navigateActionTarget = (action: RuleAction) => {
  if (!action) return
  if (action.type === 'TARGET_ADAPTER' && action.adapterName) {
    router.push({ path: '/settings', query: { tab: 'modelConfig', sub: 'protocolAdapters' } })
  } else if (action.type === 'TARGET_MODEL' && action.modelName) {
    router.push({ path: '/settings', query: { tab: 'modelConfig', sub: 'modelCategories' } })
  } else if (action.type === 'TARGET_INSTANCE' && action.instanceId) {
    router.push({ path: '/settings', query: { tab: 'modelConfig', sub: 'modelCategories' } })
  }
}

onMounted(() => {
  fetchRules()
  fetchStats()
})
</script>

<style scoped>
/* 原型式面板头：标题 + 灰字提示 + 右侧按钮 */
.rule-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--ja-border-color, var(--el-border-color-lighter));
}

.rule-panel-head__left {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.rule-panel-head__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.rule-panel-head__hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.rule-panel-head__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rule-priority {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.rule-name {
  font-weight: 600;
  color: var(--ja-text-primary);
}

.rule-plain {
  color: var(--ja-text-primary);
  line-height: 1.6;
}

.rule-hits {
  font-variant-numeric: tabular-nums;
  color: var(--ja-text-primary);
}

.rule-hits-empty {
  color: var(--ja-text-tertiary, var(--ja-text-placeholder));
}

.drag-handle {
  cursor: grab;
}
</style>
