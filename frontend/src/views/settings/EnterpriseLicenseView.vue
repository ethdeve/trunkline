<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * License 状态可见性（A1 在线吊销）：只读状态卡 + 吊销 / 解除吊销。
 *
 * <p>状态端点在社区版不存在；未探测到企业版时只显示锁定态。
 * 吊销是高危操作：证号 + 原因必填、二次确认；写共享状态失败时后端返回 503，
 * 前端原样提示，绝不显示「已吊销」的假成功。</p>
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearLicenseRevocation, getLicenseStatus, revokeLicense } from '@/api/licenseOps'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'

const { t } = useI18n()
const { enterpriseEdition, ensure } = useEnterpriseFeatures()
const unlocked = enterpriseEdition

const status = ref<{
  revoked?: boolean
  blocking?: boolean
  currentSerial?: number
  licenseEnforce?: boolean
  clusterWide?: boolean
  instance?: string
  refreshCount?: number
  revocation?: Record<string, unknown>
}>({})
const loading = ref(false)
const serial = ref<number>()
const reason = ref('')
const submitting = ref(false)

const load = async (): Promise<void> => {
  if (!unlocked.value) return
  loading.value = true
  try {
    status.value = await getLicenseStatus()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.licenseOps.loadFailed'))
  } finally {
    loading.value = false
  }
}

const confirmRevoke = async (): Promise<void> => {
  if (!serial.value || serial.value <= 0 || !reason.value.trim()) {
    ElMessage.warning(t('settings.licenseOps.revokeRequired'))
    return
  }
  try {
    await ElMessageBox.confirm(
      t('settings.licenseOps.revokeConfirm', { serial: serial.value }),
      t('settings.licenseOps.revoke'),
      { type: 'warning', confirmButtonText: t('settings.licenseOps.revoke'), cancelButtonText: t('common.cancel') }
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    await revokeLicense(serial.value, reason.value.trim())
    ElMessage.success(t('settings.licenseOps.revokeDone'))
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.licenseOps.actionFailed'))
  } finally {
    submitting.value = false
  }
}

const confirmClear = async (): Promise<void> => {
  try {
    await ElMessageBox.confirm(t('settings.licenseOps.clearConfirm'), t('settings.licenseOps.clear'), {
      type: 'warning',
      confirmButtonText: t('settings.licenseOps.clear'),
      cancelButtonText: t('common.cancel')
    })
  } catch {
    return
  }
  try {
    await clearLicenseRevocation()
    ElMessage.success(t('settings.licenseOps.clearDone'))
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.licenseOps.actionFailed'))
  }
}

/** 状态布尔值的人话文案 */
const yesNo = (value: boolean | undefined): string => t(value ? 'common.yes' : 'common.no')

onMounted(() => {
  void ensure().then(() => load())
})
</script>

<template>
  <div v-if="!unlocked" class="lc-locked">
    <el-empty :description="t('settings.licenseOps.locked')" />
  </div>

  <div v-else v-loading="loading" class="lc-page">
    <el-card shadow="never">
      <template #header>
        <div class="lc-header">
          <span>{{ t('settings.licenseOps.statusTitle') }}</span>
          <el-button size="small" @click="load">{{ t('settings.licenseOps.refresh') }}</el-button>
        </div>
      </template>
      <div class="lc-grid">
        <div class="lc-item"><span>{{ t('settings.licenseOps.revoked') }}</span><strong :class="status.revoked ? 'lc-bad' : 'lc-ok'">{{ yesNo(status.revoked) }}</strong></div>
        <div class="lc-item"><span>{{ t('settings.licenseOps.blocking') }}</span><strong>{{ yesNo(status.blocking) }}</strong></div>
        <div class="lc-item"><span>{{ t('settings.licenseOps.serial') }}</span><strong>{{ status.currentSerial ?? '—' }}</strong></div>
        <div class="lc-item"><span>{{ t('settings.licenseOps.enforce') }}</span><strong>{{ yesNo(status.licenseEnforce) }}</strong></div>
        <div class="lc-item"><span>{{ t('settings.licenseOps.clusterWide') }}</span><strong>{{ yesNo(status.clusterWide) }}</strong></div>
        <div class="lc-item"><span>{{ t('settings.licenseOps.instance') }}</span><strong>{{ status.instance || '—' }}</strong></div>
        <div class="lc-item"><span>{{ t('settings.licenseOps.refreshCount') }}</span><strong>{{ status.refreshCount ?? 0 }}</strong></div>
        <div class="lc-item"><span>{{ t('settings.licenseOps.lastRevocation') }}</span><strong>{{ status.revocation?.revokedAt || '—' }}</strong></div>
      </div>
      <div v-if="status.revocation?.reason" class="lc-reason">{{ t('settings.licenseOps.reason') }}: {{ status.revocation.reason }}</div>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>{{ t('settings.licenseOps.actionsTitle') }}</template>
      <div class="lc-form">
        <label>
          <span class="lc-label">{{ t('settings.licenseOps.serial') }}</span>
          <el-input-number v-model="serial" :min="1" />
        </label>
        <label class="lc-reason-input">
          <span class="lc-label">{{ t('settings.licenseOps.reason') }}</span>
          <el-input v-model="reason" :placeholder="t('settings.licenseOps.reasonPlaceholder')" maxlength="256" />
        </label>
        <el-button type="danger" :loading="submitting" @click="confirmRevoke">{{ t('settings.licenseOps.revoke') }}</el-button>
        <el-button :disabled="!status.revoked" @click="confirmClear">{{ t('settings.licenseOps.clear') }}</el-button>
      </div>
      <p class="lc-hint">{{ t('settings.licenseOps.hint') }}</p>
    </el-card>
  </div>
</template>

<style scoped>
.lc-locked { padding: 24px 0; }
.lc-page { display: flex; flex-direction: column; }
.lc-header { display: flex; justify-content: space-between; align-items: center; }
.lc-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.lc-item { display: flex; flex-direction: column; gap: 4px; }
.lc-item span, .lc-label, .lc-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.lc-item strong { font-size: 16px; }
.lc-ok { color: var(--el-color-success); }
.lc-bad { color: var(--el-color-danger); }
.lc-reason { margin-top: 12px; font-size: 13px; }
.lc-form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; }
.lc-form label { display: flex; flex-direction: column; gap: 4px; }
.lc-reason-input { min-width: 280px; flex: 1; }
.lc-hint { line-height: 1.6; margin: 10px 0 0; }
</style>
