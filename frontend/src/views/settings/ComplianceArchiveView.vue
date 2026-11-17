<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * WORM 合规归档管理（B7）：审计与版本页子块。
 *
 * <p>归档由定时任务自动增量执行；本页只做观测 / 校验 / 手动补归档 / 期满销毁。
 * 销毁是危险操作：只处理已过保留期的对象，原因必填并写进 WORM 清单，
 * 操作人由服务端从认证上下文取（前端不传用户身份）。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  destroyArchive,
  getArchiveStatus,
  listArchives,
  listPendingArchives,
  runArchive,
  verifyArchive,
  type ArchiveMeta,
  type ArchiveStatus,
  type ArchiveVerification
} from '@/api/archive'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'

const { t } = useI18n()
const { isEnabled, ensure } = useEnterpriseFeatures()
const unlocked = computed(() => isEnabled('auditChain'))

const status = ref<ArchiveStatus>({})
const archives = ref<ArchiveMeta[]>([])
const pending = ref<ArchiveMeta[]>([])
const loading = ref(false)
const fromSeq = ref(1)
const toSeq = ref(1)
const archiving = ref(false)
const verification = ref<ArchiveVerification | null>(null)

const load = async (): Promise<void> => {
  if (!unlocked.value) return
  loading.value = true
  try {
    const [nextStatus, nextArchives, nextPending] = await Promise.all([
      getArchiveStatus(),
      listArchives(),
      listPendingArchives()
    ])
    status.value = nextStatus
    archives.value = nextArchives
    pending.value = nextPending
    toSeq.value = Math.max(1, Number(nextStatus.chainTailSeq ?? 1))
  } catch {
    // 逐块空态，不把整页变成错误页
  } finally {
    loading.value = false
  }
}

const runArchiveRange = async (): Promise<void> => {
  if (fromSeq.value < 1 || toSeq.value < fromSeq.value) {
    ElMessage.warning(t('settings.archive.invalidRange'))
    return
  }
  archiving.value = true
  try {
    const result = await runArchive(fromSeq.value, toSeq.value)
    ElMessage.success(
      result.skipped
        ? t('settings.archive.archiveSkipped')
        : t('settings.archive.archiveDone', { rows: result.rows ?? 0 })
    )
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.archive.actionFailed'))
  } finally {
    archiving.value = false
  }
}

const verify = async (name: string): Promise<void> => {
  try {
    verification.value = await verifyArchive(name)
    if (verification.value.valid) ElMessage.success(t('settings.archive.verifyOk'))
    else ElMessage.warning(t('settings.archive.verifyBad'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.archive.actionFailed'))
  }
}

const confirmDestroy = async (record: ArchiveMeta): Promise<void> => {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      t('settings.archive.destroyConfirm', { name: record.name ?? '' }),
      t('settings.archive.destroy'),
      {
        confirmButtonText: t('settings.archive.destroy'),
        cancelButtonText: t('common.cancel'),
        inputPlaceholder: t('settings.archive.reasonPlaceholder'),
        inputValidator: value => (value && value.trim() ? true : t('settings.archive.reasonRequired'))
      }
    )
    reason = String(result.value).trim()
  } catch {
    return
  }
  try {
    await destroyArchive(record.name ?? '', reason)
    ElMessage.success(t('settings.archive.destroyDone'))
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.archive.actionFailed'))
  }
}

onMounted(() => {
  void ensure().then(() => load())
})
</script>

<template>
  <div v-if="!unlocked" class="ar-locked">
    <el-empty :description="t('settings.archive.locked')" />
  </div>

  <div v-else class="ar-page">
    <div class="ar-status">
      <div class="ar-item"><span>{{ t('settings.archive.crypto') }}</span><strong :class="status.cryptoAvailable ? 'ar-ok' : 'ar-bad'">{{ t(status.cryptoAvailable ? 'settings.archive.available' : 'settings.archive.unavailable') }}</strong></div>
      <div class="ar-item"><span>{{ t('settings.archive.sink') }}</span><strong>{{ status.sinkAlgorithm || '—' }}</strong></div>
      <div class="ar-item"><span>{{ t('settings.archive.objects') }}</span><strong>{{ status.objects ?? 0 }}</strong></div>
      <div class="ar-item"><span>{{ t('settings.archive.chainTail') }}</span><strong>{{ status.chainTailSeq ?? '—' }}</strong></div>
      <div class="ar-item"><span>{{ t('settings.archive.failedBatches') }}</span><strong :class="(status.failedBatches ?? 0) > 0 ? 'ar-bad' : ''">{{ status.failedBatches ?? 0 }}</strong></div>
      <el-button size="small" :loading="loading" @click="load">{{ t('settings.archive.refresh') }}</el-button>
    </div>

    <el-alert type="warning" :closable="false" :title="t('settings.archive.disclaimer')" style="margin-bottom: 12px" />

    <el-card shadow="never">
      <template #header>{{ t('settings.archive.manualTitle') }}</template>
      <div class="ar-form">
        <span class="ar-label">{{ t('settings.archive.fromSeq') }}</span>
        <el-input-number v-model="fromSeq" :min="1" />
        <span class="ar-label">{{ t('settings.archive.toSeq') }}</span>
        <el-input-number v-model="toSeq" :min="1" />
        <el-button type="primary" :loading="archiving" @click="runArchiveRange">{{ t('settings.archive.archive') }}</el-button>
      </div>
      <p class="ar-hint">{{ t('settings.archive.manualHint') }}</p>
      <div v-if="verification" class="ar-result" :class="verification.valid ? 'ar-ok' : 'ar-bad'">
        {{ verification.name }} — {{ t(verification.valid ? 'settings.archive.verifyOk' : 'settings.archive.verifyBad') }}
        <span v-if="verification.reason">{{ verification.reason }}</span>
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>{{ t('settings.archive.list') }}</template>
      <el-table :data="archives" v-loading="loading" size="small" max-height="320">
        <el-table-column prop="name" :label="t('settings.archive.name')" min-width="200" />
        <el-table-column prop="seqFrom" :label="t('settings.archive.seqRange')" min-width="120">
          <template #default="{ row }">{{ row.seqFrom }} – {{ row.seqTo }}</template>
        </el-table-column>
        <el-table-column prop="rowCount" :label="t('settings.archive.rows')" width="90" />
        <el-table-column prop="retentionUntil" :label="t('settings.archive.retentionUntil')" min-width="170" />
        <el-table-column :label="t('settings.archive.state')" width="100">
          <template #default="{ row }"><span :class="row.destroyed ? 'ar-bad' : 'ar-ok'">{{ t(row.destroyed ? 'settings.archive.destroyed' : 'settings.archive.retained') }}</span></template>
        </el-table-column>
        <el-table-column :label="t('settings.archive.action')" width="90">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="row.destroyed" @click="verify(row.name)">{{ t('settings.archive.verify') }}</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="ar-hint">{{ t('settings.archive.noArchives') }}</div></template>
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>{{ t('settings.archive.pendingTitle') }}</template>
      <el-table :data="pending" size="small" max-height="220">
        <el-table-column prop="name" :label="t('settings.archive.name')" min-width="200" />
        <el-table-column prop="retentionUntil" :label="t('settings.archive.retentionUntil')" min-width="170" />
        <el-table-column :label="t('settings.archive.action')" width="110">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="confirmDestroy(row)">{{ t('settings.archive.destroy') }}</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="ar-hint">{{ t('settings.archive.noPending') }}</div></template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.ar-locked { padding: 24px 0; }
.ar-page { display: flex; flex-direction: column; }
.ar-status { display: flex; flex-wrap: wrap; align-items: center; gap: 18px; margin-bottom: 12px; }
.ar-item { display: flex; flex-direction: column; min-width: 96px; }
.ar-item span, .ar-label, .ar-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.ar-item strong { font-size: 18px; }
.ar-form { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.ar-hint { line-height: 1.6; margin: 8px 0 0; }
.ar-result { margin-top: 10px; padding: 8px 10px; border-radius: 4px; font-size: 12px; background: var(--el-fill-color-lighter); }
.ar-ok { color: var(--el-color-success); }
.ar-bad { color: var(--el-color-danger); }
</style>
