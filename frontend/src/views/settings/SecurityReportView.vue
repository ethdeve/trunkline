<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 安全报告（独立导出页）。
 *
 * <p>数据源为开源审计的 /security/audit/extended/reports/security，原本只被
 * 「审计日志」页当统计卡片用。此页把同一份 JSON 报告做成可检索、
 * 可按时间窗生成、可下载归档的独立视图，供合规举证与内测 PoC 演示。</p>
 *
 * <p>本页是开源能力，不受企业功能位门控；时间戳统一按 ISO-8601 传给后端，
 * 后端负责时区归一，前端只做展示格式化。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  generateSecurityReport,
  type SecurityReport,
  type SecurityAlert
} from '@/api/auditLog'

const { t } = useI18n()

/** 最近 N 天快捷区间；custom 时用下方 date range。 */
const presets = [1, 7, 30] as const
const presetDays = ref<number>(7)
const customRange = ref<[string, string] | null>(null)
const useCustom = ref(false)
const report = ref<SecurityReport | null>(null)
const loading = ref(false)

const isoOrUndefined = (v: string | undefined): string | undefined =>
  v ? new Date(v).toISOString() : undefined

const range = (): { start?: string; end?: string } => {
  if (useCustom.value && customRange.value) {
    return { start: isoOrUndefined(customRange.value[0]), end: isoOrUndefined(customRange.value[1]) }
  }
  const end = new Date()
  const start = new Date(end.getTime() - presetDays.value * 86400_000)
  return { start: start.toISOString(), end: end.toISOString() }
}

const load = async (): Promise<void> => {
  loading.value = true
  try {
    const { start, end } = range()
    report.value = await generateSecurityReport(start, end)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.securityReport.loadFailed'))
  } finally {
    loading.value = false
  }
}

const alerts = computed<SecurityAlert[]>(() => report.value?.alerts ?? [])
const highRisk = computed(() => report.value?.highRiskEventCount ?? 0)
const successRate = computed(() => {
  const v = report.value?.successRate
  return typeof v === 'number' ? `${(v * 100).toFixed(1)}%` : '—'
})

const fmtTime = (v?: string): string => {
  if (!v) return '—'
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? v : d.toLocaleString()
}

const download = (): void => {
  if (!report.value) return
  const blob = new Blob([JSON.stringify(report.value, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `security-report-${new Date().toISOString().slice(0, 10)}.json`
  a.click()
  URL.revokeObjectURL(url)
}

const severityTag = (severity?: string): string => {
  const s = (severity ?? '').toUpperCase()
  if (s.includes('HIGH') || s.includes('CRITICAL')) return 'danger'
  if (s.includes('MED') || s.includes('WARN')) return 'warning'
  return 'info'
}

onMounted(() => { void load() })
</script>

<template>
  <div class="sr-page">
    <el-alert type="info" :closable="false" :title="t('settings.securityReport.tip')" style="margin-bottom: 12px" />

    <el-card shadow="never">
      <template #header>
        <div class="sr-card-header">
          <span>{{ t('settings.securityReport.title') }}</span>
          <el-button size="small" type="primary" :loading="loading" @click="load">{{ t('settings.securityReport.generate') }}</el-button>
        </div>
      </template>

      <div class="sr-filter">
        <el-radio-group v-model="useCustom" size="small">
          <el-radio-button :value="false">{{ t('settings.securityReport.recent') }}</el-radio-button>
          <el-radio-button :value="true">{{ t('settings.securityReport.custom') }}</el-radio-button>
        </el-radio-group>
        <el-radio-group v-if="!useCustom" v-model="presetDays" size="small">
          <el-radio-button v-for="d in presets" :key="d" :value="d">{{ t('settings.securityReport.days', { n: d }) }}</el-radio-button>
        </el-radio-group>
        <el-date-picker
          v-else
          v-model="customRange"
          type="datetimerange"
          :range-separator="t('settings.securityReport.to')"
          :start-placeholder="t('settings.securityReport.start')"
          :end-placeholder="t('settings.securityReport.end')"
          size="small"
        />
        <el-button size="small" :disabled="!report" @click="download">{{ t('settings.securityReport.download') }}</el-button>
      </div>

      <template v-if="report">
        <div class="sr-period">
          {{ t('settings.securityReport.period') }}: {{ fmtTime(report.reportPeriodStart) }} → {{ fmtTime(report.reportPeriodEnd) }}
        </div>
        <div class="sr-grid">
          <div class="sr-item"><span>{{ t('settings.securityReport.jwtOps') }}</span><strong>{{ report.totalJwtOperations ?? 0 }}</strong></div>
          <div class="sr-item"><span>{{ t('settings.securityReport.apiKeyOps') }}</span><strong>{{ report.totalApiKeyOperations ?? 0 }}</strong></div>
          <div class="sr-item"><span>{{ t('settings.securityReport.failedAuth') }}</span><strong>{{ report.failedAuthentications ?? 0 }}</strong></div>
          <div class="sr-item"><span>{{ t('settings.securityReport.suspicious') }}</span><strong>{{ report.suspiciousActivities ?? 0 }}</strong></div>
          <div class="sr-item"><span>{{ t('settings.securityReport.highRisk') }}</span><strong class="sr-risk">{{ highRisk }}</strong></div>
          <div class="sr-item"><span>{{ t('settings.securityReport.successRate') }}</span><strong>{{ successRate }}</strong></div>
        </div>

        <div class="sr-section">
          <h4>{{ t('settings.securityReport.byType') }}</h4>
          <div v-if="Object.keys(report.operationsByType ?? {}).length" class="sr-chips">
            <el-tag v-for="(count, type) in report.operationsByType" :key="type" size="small" type="info">{{ type }}: {{ count }}</el-tag>
          </div>
          <span v-else class="sr-empty">—</span>
        </div>

        <div class="sr-section">
          <h4>{{ t('settings.securityReport.topIps') }}</h4>
          <div v-if="(report.topIpAddresses ?? []).length" class="sr-chips">
            <el-tag v-for="ip in report.topIpAddresses" :key="ip" size="small" type="info">{{ ip }}</el-tag>
          </div>
          <span v-else class="sr-empty">—</span>
        </div>

        <div class="sr-section">
          <h4>{{ t('settings.securityReport.alerts') }} ({{ alerts.length }})</h4>
          <el-table :data="alerts" size="small" border v-if="alerts.length">
            <el-table-column :label="t('settings.securityReport.severity')" width="110">
              <template #default="{ row }"><el-tag :type="severityTag(row.severity)" size="small">{{ row.severity }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="eventType" :label="t('settings.securityReport.eventType')" min-width="150" />
            <el-table-column prop="message" :label="t('settings.securityReport.message')" min-width="200" />
            <el-table-column prop="userId" :label="t('settings.securityReport.user')" width="130" />
            <el-table-column prop="ipAddress" :label="t('settings.securityReport.ip')" width="150" />
            <el-table-column :label="t('settings.securityReport.time')" width="180">
              <template #default="{ row }">{{ fmtTime(row.timestamp) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else :description="t('settings.securityReport.noAlerts')" />
        </div>
      </template>
      <el-skeleton v-else-if="loading" :rows="6" animated />
      <el-empty v-else :description="t('settings.securityReport.empty')" />
    </el-card>
  </div>
</template>

<style scoped>
.sr-page { display: flex; flex-direction: column; }
.sr-card-header { display: flex; align-items: center; justify-content: space-between; }
.sr-filter { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 14px; }
.sr-period { color: var(--el-text-color-secondary); font-size: 12px; margin-bottom: 12px; }
.sr-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.sr-item { display: flex; flex-direction: column; gap: 4px; padding: 10px; background: var(--el-fill-color-lighter); border-radius: 4px; }
.sr-item span { color: var(--el-text-color-secondary); font-size: 12px; }
.sr-item strong { font-size: 20px; }
.sr-risk { color: var(--el-color-danger); }
.sr-section { margin-top: 18px; }
.sr-section h4 { margin: 0 0 8px; font-size: 14px; }
.sr-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.sr-empty { color: var(--el-text-color-placeholder); }
</style>
