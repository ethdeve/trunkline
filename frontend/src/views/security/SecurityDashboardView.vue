<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * B6 安全态势中心（落「状态监控」菜单的安全态势子页）。
 *
 * 一屏展示：安全统计（OSS 已有报告）、高风险清单与处置、合规能力健康度、告警规则。
 * 功能位未解锁时只显示锁定态，不发任何企业端点请求（fail-closed 前端语义）。
 * 任何一块数据故障只影响该块展示，不把整页变成错误页。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { getSecurityDashboard, resolveSecurityRisk, type SecurityDashboardData } from '@/api/securityDashboard'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'

const { t } = useI18n()
const { securityDashboard: unlocked, ensure } = useEnterpriseFeatures()

const data = ref<SecurityDashboardData>({})
const loading = ref(false)
const lastUpdated = ref('')
const dialogVisible = ref(false)
const pendingRisk = ref<{ riskKey?: string; alertType?: string }>({})
const note = ref('')
const submitting = ref(false)

const number = (value: unknown): number => {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

const stats = computed(() => data.value.statistics ?? {})
const risks = computed(() => data.value.risks ?? [])
const capabilities = computed(() => Object.entries(data.value.capabilities ?? {}))
const eventTypes = computed(() =>
  Object.entries(stats.value.eventTypeStatistics ?? {})
    .map(([name, count]) => ({ name, count: number(count) }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 8)
)
const maxEventCount = computed(() => Math.max(1, ...eventTypes.value.map(item => item.count)))
const openRisks = computed(() => {
  const counts = data.value.riskCounts ?? {}
  return number(counts.OPEN)
})
const enabledCapabilities = computed(() =>
  capabilities.value.filter(([, value]) => value?.available === true).length
)

const load = async (): Promise<void> => {
  if (!unlocked.value) return
  loading.value = true
  try {
    data.value = await getSecurityDashboard()
    lastUpdated.value = new Date().toLocaleTimeString()
  } catch (_error) {
    // 保留上一次成功快照，避免刷新抖动把整屏清空
    ElMessage.error(t('settings.securityDashboard.loadFailed'))
  } finally {
    loading.value = false
  }
}

const openResolve = (row: { riskKey?: string; alertType?: string }): void => {
  pendingRisk.value = row
  note.value = ''
  dialogVisible.value = true
}

const submitResolve = async (): Promise<void> => {
  if (!pendingRisk.value.riskKey || note.value.trim().length === 0) {
    ElMessage.warning(t('settings.securityDashboard.resolveNoteTip'))
    return
  }
  submitting.value = true
  try {
    const ok = await resolveSecurityRisk(pendingRisk.value.riskKey, note.value.trim())
    if (ok) {
      ElMessage.success(t('settings.securityDashboard.resolveDone'))
      dialogVisible.value = false
      await load()
    } else {
      ElMessage.warning(t('settings.securityDashboard.resolveConflict'))
    }
  } catch {
    ElMessage.error(t('settings.securityDashboard.loadFailed'))
  } finally {
    submitting.value = false
  }
}

let timer: ReturnType<typeof setInterval> | null = null

watch(unlocked, value => {
  if (value) void load()
}, { immediate: true })

onMounted(() => {
  // 深链直达子页时不能依赖布局层已探测能力；ensure 幂等，重复调用复用同一请求
  void ensure()
  timer = setInterval(() => void load(), 60_000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div v-if="!unlocked" class="sd-locked">
    <el-empty :description="t('settings.securityDashboard.locked')" />
  </div>

  <div v-else v-loading="loading" class="sd-page">
    <div class="sd-head">
      <span class="sd-hint">{{ t('settings.securityDashboard.autoRefresh') }}</span>
      <span v-if="lastUpdated" class="sd-hint">{{ t('settings.securityDashboard.lastUpdated') }}: {{ lastUpdated }}</span>
      <el-button size="small" :loading="loading" @click="load">{{ t('settings.securityDashboard.refresh') }}</el-button>
    </div>

    <div class="sd-cards">
      <el-card shadow="never" class="sd-card">
        <div class="sd-card-label">{{ t('settings.securityDashboard.statTotal') }}</div>
        <div class="sd-card-value">{{ number(stats.totalEvents) }}</div>
      </el-card>
      <el-card shadow="never" class="sd-card">
        <div class="sd-card-label">{{ t('settings.securityDashboard.statFailed') }}</div>
        <div class="sd-card-value sd-danger">{{ number(stats.failedAuthentications) }}</div>
      </el-card>
      <el-card shadow="never" class="sd-card">
        <div class="sd-card-label">{{ t('settings.securityDashboard.statSuspicious') }}</div>
        <div class="sd-card-value sd-danger">{{ number(stats.suspiciousActivities) }}</div>
      </el-card>
      <el-card shadow="never" class="sd-card">
        <div class="sd-card-label">{{ t('settings.securityDashboard.statOpenRisk') }}</div>
        <div class="sd-card-value sd-danger">{{ openRisks }}</div>
      </el-card>
      <el-card shadow="never" class="sd-card">
        <div class="sd-card-label">{{ t('settings.securityDashboard.statCapability') }}</div>
        <div class="sd-card-value">{{ enabledCapabilities }}/{{ capabilities.length }}</div>
      </el-card>
    </div>

    <div class="sd-grid">
      <el-card shadow="never" class="sd-panel">
        <template #header>{{ t('settings.securityDashboard.events') }}</template>
        <div v-if="eventTypes.length === 0" class="sd-empty">{{ t('settings.securityDashboard.noEvents') }}</div>
        <div v-for="item in eventTypes" :key="item.name" class="sd-bar-row">
          <span class="sd-bar-name">{{ item.name }}</span>
          <div class="sd-bar-track">
            <div class="sd-bar-fill" :style="{ width: `${(item.count / maxEventCount) * 100}%` }" />
          </div>
          <span class="sd-bar-value">{{ item.count }}</span>
        </div>
      </el-card>

      <el-card shadow="never" class="sd-panel">
        <template #header>{{ t('settings.securityDashboard.alertRule') }}</template>
        <div class="sd-kv">
          <span>{{ t('settings.securityDashboard.ruleType') }}</span>
          <span>{{ String(data.alertRule?.eventType ?? '') }}</span>
        </div>
        <div class="sd-kv">
          <span>{{ t('settings.securityDashboard.ruleWindow') }}</span>
          <span>{{ number(data.alertRule?.windowMinutes) }} min / {{ number(data.alertRule?.threshold) }}</span>
        </div>
        <div class="sd-kv">
          <span>{{ t('settings.securityDashboard.ruleState') }}</span>
          <span :class="data.alertRule?.triggered ? 'sd-danger' : 'sd-ok'">
            {{ t(data.alertRule?.triggered ? 'settings.securityDashboard.triggered' : 'settings.securityDashboard.notTriggered') }}
          </span>
        </div>
      </el-card>
    </div>

    <el-card shadow="never" class="sd-panel sd-full">
      <template #header>{{ t('settings.securityDashboard.risks') }}</template>
      <el-table :data="risks" size="small" max-height="320" row-key="riskKey">
        <el-table-column prop="alertType" :label="t('settings.securityDashboard.riskType')" min-width="150" />
        <el-table-column prop="userId" :label="t('settings.securityDashboard.riskUser')" min-width="120" />
        <el-table-column prop="ipAddress" :label="t('settings.securityDashboard.riskIp')" min-width="130" />
        <el-table-column prop="timestamp" :label="t('settings.securityDashboard.riskTime')" min-width="170" />
        <el-table-column :label="t('settings.securityDashboard.riskStatus')" width="110">
          <template #default="{ row }">
            <span :class="row.resolved ? 'sd-ok' : 'sd-danger'">
              {{ t(row.resolved ? 'settings.securityDashboard.resolved' : 'settings.securityDashboard.open') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('settings.securityDashboard.riskAction')" width="90">
          <template #default="{ row }">
            <el-button v-if="!row.resolved" link type="primary" size="small" @click="openResolve(row)">
              {{ t('settings.securityDashboard.resolve') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="sd-empty">{{ t('settings.securityDashboard.noRisks') }}</div>
        </template>
      </el-table>
    </el-card>

    <el-card shadow="never" class="sd-panel sd-full">
      <template #header>{{ t('settings.securityDashboard.capabilities') }}</template>
      <div class="sd-cap-grid">
        <div v-for="[key, value] in capabilities" :key="key" class="sd-cap">
          <span class="sd-cap-name">{{ key }}</span>
          <span :class="value?.available ? 'sd-ok' : 'sd-muted'">
            {{ t(value?.available ? 'settings.securityDashboard.capAvailable' : 'settings.securityDashboard.capUnavailable') }}
          </span>
          <span v-if="value?.reason" class="sd-cap-reason">{{ value.reason }}</span>
        </div>
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="t('settings.securityDashboard.resolveTitle')" width="440px">
      <p class="sd-dialog-risk">{{ pendingRisk.alertType }} / {{ pendingRisk.riskKey }}</p>
      <el-input
        v-model="note"
        type="textarea"
        :rows="3"
        :placeholder="t('settings.securityDashboard.resolveNoteTip')"
      />
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submitResolve">
          {{ t('settings.securityDashboard.resolve') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sd-locked { padding: 24px 0; }
.sd-page { display: flex; flex-direction: column; gap: 12px; }
.sd-head { display: flex; align-items: center; justify-content: flex-end; gap: 16px; }
.sd-hint, .sd-empty, .sd-muted { color: var(--el-text-color-secondary); font-size: 12px; }
.sd-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; }
.sd-card-label { color: var(--el-text-color-secondary); font-size: 12px; }
.sd-card-value { margin-top: 6px; font-size: 24px; font-weight: 600; }
.sd-danger { color: var(--el-color-danger); }
.sd-ok { color: var(--el-color-success); }
.sd-grid { display: grid; grid-template-columns: 2fr 1fr; gap: 12px; }
.sd-full { width: 100%; }
.sd-bar-row { display: flex; align-items: center; gap: 8px; margin: 6px 0; }
.sd-bar-name { width: 190px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.sd-bar-track { flex: 1; height: 8px; background: var(--el-fill-color-light); border-radius: 4px; overflow: hidden; }
.sd-bar-fill { height: 100%; background: var(--el-color-primary); }
.sd-bar-value { width: 48px; text-align: right; font-size: 12px; }
.sd-kv { display: flex; justify-content: space-between; gap: 12px; padding: 6px 0; font-size: 13px; }
.sd-cap-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; }
.sd-cap { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; padding: 8px 10px; background: var(--el-fill-color-lighter); border-radius: 4px; font-size: 12px; }
.sd-cap-name { font-weight: 600; }
.sd-cap-reason { flex-basis: 100%; color: var(--el-text-color-secondary); }
.sd-dialog-risk { word-break: break-all; font-size: 12px; color: var(--el-text-color-secondary); }
@media (max-width: 900px) { .sd-grid { grid-template-columns: 1fr; } }
</style>
