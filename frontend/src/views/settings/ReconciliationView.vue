<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 上游对账 + 成本归因（B4）：账单能力的运营视图。
 *
 * <p>差异只报告不修账；人工处置仅允许 OPEN → RESOLVED，操作人由服务端取。
 * 归因维度走后端白名单，前端不拼 SQL、不猜字段名。功能位 billing 未解锁时锁定。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  downloadReconciliationCsv,
  getAttribution,
  getReconciliationStatus,
  listDifferences,
  resolveDifference,
  runReconciliation,
  type ReconciliationAggregation,
  type ReconciliationDifference
} from '@/api/reconciliation'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'

const { t } = useI18n()
const { billing: unlocked, ensure } = useEnterpriseFeatures()

const status = ref<{ differences?: Record<string, number>; attributionRows?: number; dimensions?: string[] }>({})
const differences = ref<ReconciliationDifference[]>([])
const aggregations = ref<ReconciliationAggregation[]>([])
const statusFilter = ref('OPEN')
const dimension = ref('department')
const from = ref('')
const to = ref('')
const loading = ref(false)
const running = ref(false)
const resolving = ref(false)

const dimensions = computed(() => status.value.dimensions ?? ['department', 'project', 'costCenter', 'env'])

const load = async (): Promise<void> => {
  if (!unlocked.value) return
  loading.value = true
  try {
    const [nextStatus, nextDifferences, nextAggregations] = await Promise.all([
      getReconciliationStatus(),
      listDifferences(statusFilter.value, 200, from.value || undefined, to.value || undefined),
      getAttribution(dimension.value, from.value || undefined, to.value || undefined)
    ])
    status.value = nextStatus
    differences.value = nextDifferences
    aggregations.value = nextAggregations
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.reconciliation.loadFailed'))
  } finally {
    loading.value = false
  }
}

const run = async (): Promise<void> => {
  running.value = true
  try {
    await runReconciliation(from.value || undefined, to.value || undefined)
    ElMessage.success(t('settings.reconciliation.runDone'))
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.reconciliation.actionFailed'))
  } finally {
    running.value = false
  }
}

const exportCsv = async (): Promise<void> => {
  try {
    await downloadReconciliationCsv('ALL', from.value || undefined, to.value || undefined)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.reconciliation.actionFailed'))
  }
}

const confirmResolve = async (row: ReconciliationDifference): Promise<void> => {
  let note: string
  try {
    const result = await ElMessageBox.prompt(
      t('settings.reconciliation.resolveConfirm', { key: row.diffKey ?? '' }),
      t('settings.reconciliation.resolve'),
      {
        confirmButtonText: t('settings.reconciliation.resolve'),
        cancelButtonText: t('common.cancel'),
        inputPlaceholder: t('settings.reconciliation.notePlaceholder'),
        inputValidator: value => (value && value.trim() ? true : t('settings.reconciliation.noteRequired'))
      }
    )
    note = String(result.value).trim()
  } catch {
    return
  }
  resolving.value = true
  try {
    const ok = await resolveDifference(row.diffKey ?? '', note)
    ElMessage[ok ? 'success' : 'warning'](t(ok ? 'settings.reconciliation.resolveDone' : 'settings.reconciliation.resolveConflict'))
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.reconciliation.actionFailed'))
  } finally {
    resolving.value = false
  }
}

onMounted(() => {
  void ensure().then(() => load())
})
</script>

<template>
  <div v-if="!unlocked" class="rc-locked">
    <el-empty :description="t('settings.reconciliation.locked')" />
  </div>

  <div v-else class="rc-page">
    <div class="rc-status">
      <div class="rc-item"><span>{{ t('settings.reconciliation.open') }}</span><strong>{{ status.differences?.OPEN ?? 0 }}</strong></div>
      <div class="rc-item"><span>{{ t('settings.reconciliation.resolved') }}</span><strong>{{ status.differences?.RESOLVED ?? 0 }}</strong></div>
      <div class="rc-item"><span>{{ t('settings.reconciliation.attributionRows') }}</span><strong>{{ status.attributionRows ?? 0 }}</strong></div>
      <el-button size="small" :loading="loading" @click="load">{{ t('settings.reconciliation.refresh') }}</el-button>
    </div>

    <el-card shadow="never">
      <template #header>{{ t('settings.reconciliation.queryTitle') }}</template>
      <div class="rc-form">
        <label><span class="rc-label">{{ t('settings.reconciliation.from') }}</span><el-input v-model="from" placeholder="2026-11-16T00:00:00" /></label>
        <label><span class="rc-label">{{ t('settings.reconciliation.to') }}</span><el-input v-model="to" placeholder="2026-11-17T00:00:00" /></label>
        <el-button type="primary" :loading="running" @click="run">{{ t('settings.reconciliation.run') }}</el-button>
        <el-button :loading="running" @click="exportCsv">{{ t('settings.reconciliation.export') }}</el-button>
      </div>
      <p class="rc-hint">{{ t('settings.reconciliation.disclaimer') }}</p>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>
        <div class="rc-header">
          <span>{{ t('settings.reconciliation.differences') }}</span>
          <el-select v-model="statusFilter" size="small" style="width: 140px" @change="load">
            <el-option label="OPEN" value="OPEN" />
            <el-option label="RESOLVED" value="RESOLVED" />
            <el-option label="ALL" value="ALL" />
          </el-select>
        </div>
      </template>
      <el-table :data="differences" v-loading="loading" size="small" max-height="300">
        <el-table-column prop="diffType" :label="t('settings.reconciliation.diffType')" width="120" />
        <el-table-column prop="traceId" :label="t('settings.reconciliation.traceId')" min-width="170" />
        <el-table-column :label="t('settings.reconciliation.cost')" min-width="150">
          <template #default="{ row }">{{ row.expectedCost }} → {{ row.actualCost }}</template>
        </el-table-column>
        <el-table-column :label="t('settings.reconciliation.tokens')" min-width="120">
          <template #default="{ row }">{{ row.expectedTokens }} → {{ row.actualTokens }}</template>
        </el-table-column>
        <el-table-column prop="occurredAt" :label="t('settings.reconciliation.occurredAt')" min-width="160" />
        <el-table-column :label="t('common.action')" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'OPEN'" link type="primary" size="small" :loading="resolving" @click="confirmResolve(row)">
              {{ t('settings.reconciliation.resolve') }}
            </el-button>
            <span v-else class="rc-muted">{{ t('settings.reconciliation.resolved') }}</span>
          </template>
        </el-table-column>
        <template #empty><div class="rc-hint">{{ t('settings.reconciliation.noDifferences') }}</div></template>
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>
        <div class="rc-header">
          <span>{{ t('settings.reconciliation.attribution') }}</span>
          <el-select v-model="dimension" size="small" style="width: 180px" @change="load">
            <el-option v-for="item in dimensions" :key="item" :label="item" :value="item" />
          </el-select>
        </div>
      </template>
      <el-table :data="aggregations" size="small" max-height="260">
        <el-table-column prop="dimensionValue" :label="t('settings.reconciliation.dimensionValue')" min-width="180" />
        <el-table-column prop="totalCost" :label="t('settings.reconciliation.totalCost')" min-width="140" />
        <el-table-column prop="totalTokens" :label="t('settings.reconciliation.totalTokens')" min-width="120" />
        <el-table-column prop="callCount" :label="t('settings.reconciliation.callCount')" min-width="100" />
        <template #empty><div class="rc-hint">{{ t('settings.reconciliation.noAttribution') }}</div></template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.rc-locked { padding: 24px 0; }
.rc-page { display: flex; flex-direction: column; }
.rc-status { display: flex; align-items: center; gap: 20px; margin-bottom: 12px; }
.rc-item { display: flex; flex-direction: column; }
.rc-item span, .rc-label, .rc-hint, .rc-muted { color: var(--el-text-color-secondary); font-size: 12px; }
.rc-item strong { font-size: 18px; }
.rc-form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px; }
.rc-form label { display: flex; flex-direction: column; gap: 4px; }
.rc-hint { line-height: 1.6; margin: 8px 0 0; }
.rc-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
</style>
