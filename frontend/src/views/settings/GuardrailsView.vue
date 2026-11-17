<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 安全能力状态与自测（B2 DLP / B3 KMS / B5 内容审核）。
 *
 * <p>三块能力各自按功能位门控，未解锁只显示锁定态、不发端点请求；
 * 状态接口不暴露正则、词表与密钥材料，试检响应也不回显原文或命中片段。</p>
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  getDlpStatus,
  getKmsStatus,
  getModerationStatus,
  testDlp,
  testModeration,
  type DlpStatus,
  type DlpTestResult,
  type KmsStatus,
  type ModerationStatus,
  type ModerationTestResult
} from '@/api/enterpriseSafety'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'

const { t } = useI18n()
const { dlp: dlpUnlocked, kms: kmsUnlocked, moderation: moderationUnlocked, ensure } = useEnterpriseFeatures()

const dlp = ref<DlpStatus>({})
const kms = ref<KmsStatus>({})
const moderation = ref<ModerationStatus>({})
const sample = ref('')
const dlpResult = ref<DlpTestResult | null>(null)
const moderationResult = ref<ModerationTestResult | null>(null)
const testing = ref<'dlp' | 'moderation' | null>(null)

const load = async (): Promise<void> => {
  const jobs: Promise<unknown>[] = []
  if (dlpUnlocked.value) jobs.push(getDlpStatus().then(value => { dlp.value = value }))
  if (kmsUnlocked.value) jobs.push(getKmsStatus().then(value => { kms.value = value }))
  if (moderationUnlocked.value) jobs.push(getModerationStatus().then(value => { moderation.value = value }))
  await Promise.allSettled(jobs)
}

const runTest = async (target: 'dlp' | 'moderation'): Promise<void> => {
  if (!sample.value.trim()) {
    ElMessage.warning(t('settings.guardrails.sampleRequired'))
    return
  }
  testing.value = target
  try {
    if (target === 'dlp') dlpResult.value = await testDlp(sample.value)
    else moderationResult.value = await testModeration(sample.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.guardrails.actionFailed'))
  } finally {
    testing.value = null
  }
}

const verdict = (blocked: boolean | undefined): string => t(blocked ? 'settings.guardrails.blocked' : 'settings.guardrails.passed')

onMounted(() => {
  void ensure().then(() => load())
})
</script>

<template>
  <div class="gr-page">
    <el-alert type="info" :closable="false" :title="t('settings.guardrails.tip')" style="margin-bottom: 12px" />

    <el-card v-if="dlpUnlocked" shadow="never">
      <template #header>{{ t('settings.guardrails.dlpTitle') }}</template>
      <div class="gr-grid">
        <div class="gr-item"><span>{{ t('settings.guardrails.enabled') }}</span><strong>{{ dlp.enabled ? t('common.yes') : t('common.no') }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.ruleCount') }}</span><strong>{{ dlp.ruleCount ?? 0 }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.skipModels') }}</span><strong>{{ (dlp.skipModels ?? []).join(', ') || '—' }}</strong></div>
      </div>
      <div v-if="dlpResult" class="gr-result" :class="dlpResult.wouldBlock ? 'gr-bad' : 'gr-ok'">
        {{ t('settings.guardrails.wouldBlock') }}: {{ verdict(dlpResult.wouldBlock) }}
        <span v-if="dlpResult.engineFailure">{{ t('settings.guardrails.engineFailure') }}</span>
        <span v-for="hit in dlpResult.hits ?? []" :key="hit.ruleId">{{ hit.ruleId }} / {{ hit.action }}</span>
      </div>
    </el-card>
    <el-card v-else shadow="never"><el-empty :description="t('settings.guardrails.dlpLocked')" /></el-card>

    <el-card v-if="kmsUnlocked" shadow="never" style="margin-top: 12px">
      <template #header>{{ t('settings.guardrails.kmsTitle') }}</template>
      <div class="gr-grid">
        <div class="gr-item"><span>{{ t('settings.guardrails.provider') }}</span><strong>{{ kms.provider || '—' }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.available') }}</span><strong>{{ kms.available ? t('common.yes') : t('common.no') }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.activeVersion') }}</span><strong>{{ kms.activeVersion || '—' }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.versions') }}</span><strong>{{ (kms.availableVersions ?? []).join(', ') || '—' }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.algorithm') }}</span><strong>{{ kms.algorithm || '—' }}</strong></div>
      </div>
      <p class="gr-hint">{{ t('settings.guardrails.kmsHint') }}</p>
    </el-card>
    <el-card v-else shadow="never" style="margin-top: 12px"><el-empty :description="t('settings.guardrails.kmsLocked')" /></el-card>

    <el-card v-if="moderationUnlocked" shadow="never" style="margin-top: 12px">
      <template #header>{{ t('settings.guardrails.moderationTitle') }}</template>
      <div class="gr-grid">
        <div class="gr-item"><span>{{ t('settings.guardrails.enabled') }}</span><strong>{{ moderation.engineEnabled ? t('common.yes') : t('common.no') }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.available') }}</span><strong>{{ moderation.engineAvailable ? t('common.yes') : t('common.no') }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.threshold') }}</span><strong>{{ moderation.threshold ?? '—' }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.terms') }}</span><strong>{{ moderation.termCount ?? 0 }} / {{ moderation.regexCount ?? 0 }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.enforce') }}</span><strong>{{ moderation.enforce ? t('common.yes') : t('common.no') }}</strong></div>
        <div class="gr-item"><span>{{ t('settings.guardrails.outputSide') }}</span><strong>{{ moderation.outputSideEnabled ? t('common.yes') : t('common.no') }}</strong></div>
      </div>
      <div v-if="moderationResult" class="gr-result" :class="moderationResult.wouldBlock ? 'gr-bad' : 'gr-ok'">
        {{ t('settings.guardrails.wouldBlock') }}: {{ verdict(moderationResult.wouldBlock) }}
        <span>{{ t('settings.guardrails.score') }}: {{ moderationResult.score ?? '—' }} / {{ moderationResult.threshold ?? '—' }}</span>
        <span v-for="hit in moderationResult.hits ?? []" :key="hit">{{ hit }}</span>
      </div>
    </el-card>
    <el-card v-else shadow="never" style="margin-top: 12px"><el-empty :description="t('settings.guardrails.moderationLocked')" /></el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>{{ t('settings.guardrails.testTitle') }}</template>
      <div class="gr-test">
        <el-input v-model="sample" type="textarea" :rows="3" :placeholder="t('settings.guardrails.samplePlaceholder')" />
        <div class="gr-actions">
          <el-button :disabled="!dlpUnlocked" :loading="testing === 'dlp'" @click="runTest('dlp')">{{ t('settings.guardrails.testDlp') }}</el-button>
          <el-button :disabled="!moderationUnlocked" :loading="testing === 'moderation'" @click="runTest('moderation')">{{ t('settings.guardrails.testModeration') }}</el-button>
        </div>
        <p class="gr-hint">{{ t('settings.guardrails.testHint') }}</p>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.gr-page { display: flex; flex-direction: column; }
.gr-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.gr-item { display: flex; flex-direction: column; gap: 4px; }
.gr-item span, .gr-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.gr-item strong { font-size: 15px; word-break: break-all; }
.gr-hint { line-height: 1.6; margin: 10px 0 0; }
.gr-result { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; padding: 8px 10px; border-radius: 4px; font-size: 12px; background: var(--el-fill-color-lighter); }
.gr-ok { color: var(--el-color-success); }
.gr-bad { color: var(--el-color-danger); }
.gr-actions { display: flex; gap: 8px; margin-top: 10px; }
</style>
