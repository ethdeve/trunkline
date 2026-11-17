<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 告警外发渠道配置（企业侧）。
 *
 * <p>绑定 securityDashboard 功能位：态势中心发现的高风险告警需要外发出口，
 * 渠道配置是该闭环的一部分。这里只登记元数据与状态，真正外发由部署方注册的
 * Sink 承担；「测试」只返回结论，不回显目标凭据。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAlertChannelStatus,
  listAlertChannels,
  saveAlertChannel,
  toggleAlertChannel,
  deleteAlertChannel,
  testAlertChannel,
  type AlertChannel,
  type AlertChannelStatus,
  type ChannelTestResult
} from '@/api/alertChannels'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'

const { t } = useI18n()
const { securityDashboard: unlocked, ensure } = useEnterpriseFeatures()

const channels = ref<AlertChannel[]>([])
const status = ref<AlertChannelStatus>({})
const loading = ref(false)
const dialogOpen = ref(false)
const editing = ref(false)
const saving = ref(false)
const testingId = ref<string | null>(null)
const testResults = reactive<Record<string, ChannelTestResult>>({})

const form = reactive<AlertChannel>({
  channelId: '', name: '', type: 'CUSTOM', target: '', enabled: true, severities: [], description: ''
})

const severitiesOptions = ['INFO', 'WARN', 'ERROR', 'CRITICAL']
const typeOptions = ['WEBHOOK', 'SMTP', 'CUSTOM']

const summary = computed(() => [
  { label: t('settings.alertChannels.configured'), value: status.value.configured ?? channels.value.length },
  { label: t('settings.alertChannels.enabled'), value: status.value.enabled ?? channels.value.filter(c => c.enabled).length },
  { label: t('settings.alertChannels.sinks'), value: status.value.sinks ?? status.value.sinkCount ?? '—' }
])

const load = async (): Promise<void> => {
  loading.value = true
  try {
    const [s, list] = await Promise.all([getAlertChannelStatus(), listAlertChannels()])
    status.value = s
    channels.value = list
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.alertChannels.loadFailed'))
  } finally {
    loading.value = false
  }
}

const openCreate = (): void => {
  editing.value = false
  Object.assign(form, { channelId: '', name: '', type: 'CUSTOM', target: '', enabled: true, severities: [], description: '' })
  dialogOpen.value = true
}

const openEdit = (c: AlertChannel): void => {
  editing.value = true
  Object.assign(form, c, { severities: [...(c.severities ?? [])] })
  dialogOpen.value = true
}

const submit = async (): Promise<void> => {
  if (!form.name?.trim() || !form.channelId?.trim()) {
    ElMessage.warning(t('settings.alertChannels.required'))
    return
  }
  saving.value = true
  try {
    await saveAlertChannel(form)
    ElMessage.success(t('settings.alertChannels.saved'))
    dialogOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.alertChannels.saveFailed'))
  } finally {
    saving.value = false
  }
}

const toggle = async (c: AlertChannel, enable: boolean): Promise<void> => {
  if (!c.channelId) return
  try {
    await toggleAlertChannel(c.channelId, enable)
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.alertChannels.actionFailed'))
  }
}

const remove = async (c: AlertChannel): Promise<void> => {
  if (!c.channelId) return
  try {
    await ElMessageBox.confirm(t('settings.alertChannels.deleteConfirm', { name: c.name ?? c.channelId }), t('common.confirm'), { type: 'warning' })
    await deleteAlertChannel(c.channelId)
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : t('settings.alertChannels.actionFailed'))
  }
}

const runTest = async (c: AlertChannel): Promise<void> => {
  if (!c.channelId) return
  testingId.value = c.channelId
  try {
    testResults[c.channelId] = await testAlertChannel(c.channelId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.alertChannels.actionFailed'))
  } finally {
    testingId.value = null
  }
}

onMounted(() => { void ensure().then(() => { if (unlocked.value) void load() }) })
</script>

<template>
  <el-empty v-if="!unlocked" :description="t('settings.alertChannels.locked')" />
  <div v-else class="ac-page">
    <el-alert type="info" :closable="false" :title="t('settings.alertChannels.tip')" style="margin-bottom: 12px" />

    <div class="ac-summary">
      <div v-for="item in summary" :key="item.label" class="ac-summary-item">
        <span>{{ item.label }}</span><strong>{{ item.value }}</strong>
      </div>
    </div>

    <el-card shadow="never">
      <template #header>
        <div class="ac-header">
          <span>{{ t('settings.alertChannels.title') }}</span>
          <el-button size="small" type="primary" @click="openCreate">{{ t('settings.alertChannels.create') }}</el-button>
        </div>
      </template>

      <el-table :data="channels" size="small" border v-loading="loading">
        <el-table-column prop="name" :label="t('settings.alertChannels.name')" min-width="130" />
        <el-table-column prop="channelId" :label="t('settings.alertChannels.id')" min-width="140" />
        <el-table-column prop="type" :label="t('settings.alertChannels.type')" width="110" />
        <el-table-column prop="target" :label="t('settings.alertChannels.target')" min-width="160" show-overflow-tooltip />
        <el-table-column :label="t('settings.alertChannels.severities')" min-width="150">
          <template #default="{ row }">{{ (row.severities ?? []).join(', ') || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('settings.alertChannels.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('settings.alertChannels.action')" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="openEdit(row)">{{ t('settings.alertChannels.edit') }}</el-button>
            <el-button link size="small" :loading="testingId === row.channelId" @click="runTest(row)">{{ t('settings.alertChannels.test') }}</el-button>
            <el-button link size="small" @click="toggle(row, !row.enabled)">{{ row.enabled ? t('settings.alertChannels.disable') : t('settings.alertChannels.enable') }}</el-button>
            <el-button link size="small" type="danger" @click="remove(row)">{{ t('settings.alertChannels.delete') }}</el-button>
            <div v-if="testResults[row.channelId]" class="ac-test">
              {{ testResults[row.channelId].message || (testResults[row.channelId].reachable ? t('settings.alertChannels.reachable') : t('settings.alertChannels.sinkOnly')) }}
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!channels.length && !loading" :description="t('settings.alertChannels.empty')" />
    </el-card>

    <el-dialog v-model="dialogOpen" :title="editing ? t('settings.alertChannels.edit') : t('settings.alertChannels.create')" width="520px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item :label="t('settings.alertChannels.id')" required>
          <el-input v-model="form.channelId" :disabled="editing" :placeholder="t('settings.alertChannels.idPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('settings.alertChannels.name')" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('settings.alertChannels.type')">
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="tp in typeOptions" :key="tp" :label="tp" :value="tp" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('settings.alertChannels.target')"><el-input v-model="form.target" :placeholder="t('settings.alertChannels.targetPlaceholder')" /></el-form-item>
        <el-form-item :label="t('settings.alertChannels.severities')">
          <el-select v-model="form.severities" multiple style="width: 100%">
            <el-option v-for="s in severitiesOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('settings.alertChannels.status')"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item :label="t('settings.alertChannels.description')"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ac-page { display: flex; flex-direction: column; }
.ac-header { display: flex; align-items: center; justify-content: space-between; }
.ac-summary { display: flex; gap: 12px; margin-bottom: 12px; }
.ac-summary-item { display: flex; flex-direction: column; gap: 4px; padding: 10px 16px; background: var(--el-fill-color-lighter); border-radius: 4px; }
.ac-summary-item span { color: var(--el-text-color-secondary); font-size: 12px; }
.ac-summary-item strong { font-size: 18px; }
.ac-test { color: var(--el-text-color-secondary); font-size: 11px; margin-top: 2px; }
</style>
