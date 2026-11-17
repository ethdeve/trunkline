<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 一键证据包 + 哈希链校验（B1）：审计与版本页子块。
 *
 * <p>证据包由后端按时间窗生成（含逐行自验结论与链段完整性），前端只做
 * 展示与 JSON 下载，不拼装、不篡改链数据。功能位 auditChain 未解锁时只显示锁定态。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  exportEvidence,
  getAuditChainStatus,
  verifyAuditChain,
  type AuditChainStatus,
  type ChainVerifyResult,
  type EvidencePackage
} from '@/api/auditChain'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'

const { t } = useI18n()
const { isEnabled, ensure } = useEnterpriseFeatures()
const unlocked = computed(() => isEnabled('auditChain'))

const status = ref<AuditChainStatus>({})
const verifyFrom = ref(1)
const verifyResult = ref<ChainVerifyResult | null>(null)
const verifying = ref(false)
const evidence = ref<EvidencePackage | null>(null)
const exporting = ref(false)

/** datetime-local → ISO_LOCAL_DATE_TIME（后端 LocalDateTime.parse 口径） */
const localDateTime = (offsetMinutes: number): string => {
  const date = new Date(Date.now() + offsetMinutes * 60_000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
const from = ref(localDateTime(-24 * 60))
const to = ref(localDateTime(0))
const userId = ref('')
const eventId = ref('')
const requestId = ref('')

const entries = computed(() => evidence.value?.entries ?? [])
const segment = computed(() => evidence.value?.segmentIntegrity)

const loadStatus = async (): Promise<void> => {
  if (!unlocked.value) return
  try {
    status.value = await getAuditChainStatus()
  } catch {
    // 状态卡失败不影响证据导出（fail-soft）
  }
}

const runVerify = async (): Promise<void> => {
  if (verifyFrom.value < 1) {
    ElMessage.warning(t('settings.evidence.invalidFromSeq'))
    return
  }
  verifying.value = true
  try {
    verifyResult.value = await verifyAuditChain(verifyFrom.value)
    if (verifyResult.value?.valid === false) {
      ElMessage.warning(t('settings.evidence.chainBroken'))
    } else {
      ElMessage.success(t('settings.evidence.verifyDone'))
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.evidence.actionFailed'))
  } finally {
    verifying.value = false
  }
}

const runEvidence = async (): Promise<void> => {
  if (!from.value || !to.value) {
    ElMessage.warning(t('settings.evidence.timeRequired'))
    return
  }
  exporting.value = true
  try {
    evidence.value = await exportEvidence({
      from: from.value,
      to: to.value,
      userId: userId.value.trim() || undefined,
      eventId: eventId.value.trim() || undefined,
      requestId: requestId.value.trim() || undefined
    })
    ElMessage.success(t('settings.evidence.exportDone'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.evidence.actionFailed'))
  } finally {
    exporting.value = false
  }
}

const downloadEvidence = (): void => {
  if (!evidence.value) return
  const blob = new Blob([JSON.stringify(evidence.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `trunkline-audit-evidence-${Date.now()}.json`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  void ensure().then(() => loadStatus())
})
</script>

<template>
  <div v-if="!unlocked" class="ev-locked">
    <el-empty :description="t('settings.evidence.locked')" />
  </div>

  <div v-else class="ev-page">
    <div class="ev-status">
      <div class="ev-item"><span>{{ t('settings.evidence.chainRows') }}</span><strong>{{ status.chainRows ?? '—' }}</strong></div>
      <div class="ev-item"><span>{{ t('settings.evidence.tailSeq') }}</span><strong>{{ status.tailSeq ?? '—' }}</strong></div>
      <div class="ev-item"><span>{{ t('settings.evidence.algorithm') }}</span><strong>{{ status.algorithmId || '—' }}</strong></div>
      <div class="ev-item"><span>{{ t('settings.evidence.dropped') }}</span><strong :class="{ 'ev-bad': (status.dropped ?? 0) > 0 }">{{ status.dropped ?? 0 }}</strong></div>
      <el-button size="small" @click="loadStatus">{{ t('settings.evidence.refresh') }}</el-button>
    </div>

    <el-card shadow="never">
      <template #header>{{ t('settings.evidence.verifyTitle') }}</template>
      <div class="ev-form">
        <span class="ev-label">{{ t('settings.evidence.fromSeq') }}</span>
        <el-input-number v-model="verifyFrom" :min="1" :step="1" />
        <el-button type="primary" :loading="verifying" @click="runVerify">{{ t('settings.evidence.verify') }}</el-button>
      </div>
      <div v-if="verifyResult" class="ev-result" :class="verifyResult.valid ? 'ev-ok' : 'ev-bad'">
        <span>{{ t(verifyResult.valid ? 'settings.evidence.valid' : 'settings.evidence.invalid') }}</span>
        <span>{{ t('settings.evidence.checkedRows') }}: {{ verifyResult.checkedRows ?? 0 }}</span>
        <span v-if="!verifyResult.valid">{{ t('settings.evidence.firstBadSeq') }}: {{ verifyResult.firstBadSeq }}</span>
        <span v-if="verifyResult.failureReason">{{ verifyResult.failureReason }}</span>
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>{{ t('settings.evidence.exportTitle') }}</template>
      <div class="ev-form ev-form-grid">
        <label>
          <span class="ev-label">{{ t('settings.evidence.from') }}</span>
          <el-input v-model="from" placeholder="2026-11-16T00:00:00" />
        </label>
        <label>
          <span class="ev-label">{{ t('settings.evidence.to') }}</span>
          <el-input v-model="to" placeholder="2026-11-17T00:00:00" />
        </label>
        <label>
          <span class="ev-label">{{ t('settings.evidence.userId') }}</span>
          <el-input v-model="userId" :placeholder="t('settings.evidence.optional')" />
        </label>
        <label>
          <span class="ev-label">{{ t('settings.evidence.eventId') }}</span>
          <el-input v-model="eventId" :placeholder="t('settings.evidence.optional')" />
        </label>
        <label>
          <span class="ev-label">{{ t('settings.evidence.requestId') }}</span>
          <el-input v-model="requestId" :placeholder="t('settings.evidence.optional')" />
        </label>
        <div class="ev-actions">
          <el-button type="primary" :loading="exporting" @click="runEvidence">{{ t('settings.evidence.export') }}</el-button>
          <el-button :disabled="!evidence" @click="downloadEvidence">{{ t('settings.evidence.download') }}</el-button>
        </div>
      </div>
      <p class="ev-hint">{{ t('settings.evidence.timeHint') }}</p>

      <div v-if="evidence" class="ev-evidence">
        <div class="ev-result" :class="segment?.valid ? 'ev-ok' : 'ev-bad'">
          <span>{{ t('settings.evidence.segmentIntegrity') }}: {{ t(segment?.valid ? 'settings.evidence.valid' : 'settings.evidence.invalid') }}</span>
          <span>{{ t('settings.evidence.checkedRows') }}: {{ segment?.checkedRows ?? 0 }}</span>
          <span v-if="!segment?.valid">{{ t('settings.evidence.firstBadSeq') }}: {{ segment?.firstBadSeq }}</span>
        </div>
        <el-table :data="entries" size="small" max-height="320" row-key="row.seq">
          <el-table-column prop="row.seq" :label="t('settings.evidence.seq')" width="80" />
          <el-table-column prop="row.eventTs" :label="t('settings.evidence.eventTs')" min-width="160" />
          <el-table-column prop="row.eventId" :label="t('settings.evidence.eventId')" min-width="180" />
          <el-table-column prop="row.userId" :label="t('settings.evidence.userId')" min-width="120" />
          <el-table-column :label="t('settings.evidence.hashValid')" width="100">
            <template #default="{ row }"><span :class="row.hashValid ? 'ev-ok' : 'ev-bad'">{{ t(row.hashValid ? 'settings.evidence.valid' : 'settings.evidence.invalid') }}</span></template>
          </el-table-column>
          <el-table-column :label="t('settings.evidence.linkValid')" width="100">
            <template #default="{ row }"><span :class="row.linkValid ? 'ev-ok' : 'ev-bad'">{{ t(row.linkValid ? 'settings.evidence.valid' : 'settings.evidence.invalid') }}</span></template>
          </el-table-column>
          <template #empty><div class="ev-hint">{{ t('settings.evidence.noEntries') }}</div></template>
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.ev-locked { padding: 24px 0; }
.ev-page { display: flex; flex-direction: column; }
.ev-status { display: flex; flex-wrap: wrap; align-items: center; gap: 18px; margin-bottom: 12px; }
.ev-item { display: flex; flex-direction: column; min-width: 100px; }
.ev-item span, .ev-label, .ev-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.ev-item strong { font-size: 18px; }
.ev-form { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.ev-form-grid label { display: flex; flex-direction: column; gap: 4px; min-width: 170px; }
.ev-actions { display: flex; align-items: flex-end; gap: 8px; }
.ev-hint { line-height: 1.6; margin: 8px 0 0; }
.ev-result { display: flex; flex-wrap: wrap; gap: 14px; margin: 10px 0; padding: 8px 10px; border-radius: 4px; font-size: 12px; background: var(--el-fill-color-lighter); }
.ev-ok { color: var(--el-color-success); }
.ev-bad { color: var(--el-color-danger); }
.ev-evidence { margin-top: 10px; }
</style>
