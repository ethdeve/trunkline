<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * Agent 工具注册中心「前端一屏」（M4）：登记 / 启停 / 租户授权 / 注销。
 *
 * <p>网关只治理不执行工具；本页不提供「调用工具」按钮，避免把治理界面误讲成调度器。
 * 功能位 agentGateway 未解锁时只显示锁定态，不发任何企业端点请求。</p>
 */
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteAgentTool,
  getAgentToolStatus,
  listAgentTools,
  registerAgentTool,
  toggleAgentTool,
  updateAgentTool,
  type AgentTool
} from '@/api/agentTools'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'

const { t } = useI18n()
const { agentGateway: unlocked, ensure } = useEnterpriseFeatures()

const tools = ref<AgentTool[]>([])
const loading = ref(false)
const submitting = ref(false)
const form = reactive({ toolName: '', description: '', inputSchema: '{}', version: 'v1', tenants: '' })

const load = async (): Promise<void> => {
  if (!unlocked.value) return
  loading.value = true
  try {
    const [, list] = await Promise.all([getAgentToolStatus(), listAgentTools()])
    tools.value = list
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.agentTools.loadFailed'))
  } finally {
    loading.value = false
  }
}

const splitTenants = (value: string): string[] =>
  value.split(/[,，\s]+/).map(item => item.trim()).filter(Boolean)

const register = async (): Promise<void> => {
  if (!form.toolName.trim()) {
    ElMessage.warning(t('settings.agentTools.nameRequired'))
    return
  }
  submitting.value = true
  try {
    await registerAgentTool({
      toolName: form.toolName,
      description: form.description,
      inputSchema: form.inputSchema || '{}',
      version: form.version,
      allowedTenants: splitTenants(form.tenants)
    })
    ElMessage.success(t('settings.agentTools.registerDone'))
    form.toolName = ''
    form.description = ''
    form.tenants = ''
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.agentTools.actionFailed'))
  } finally {
    submitting.value = false
  }
}

const toggle = async (row: AgentTool): Promise<void> => {
  try {
    await toggleAgentTool(row.toolName ?? '', !row.enabled)
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.agentTools.actionFailed'))
  }
}

const editTenants = async (row: AgentTool): Promise<void> => {
  let value: string
  try {
    const result = await ElMessageBox.prompt(
      t('settings.agentTools.tenantsTip'),
      t('settings.agentTools.tenants'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        inputValue: (row.allowedTenants ?? []).join(', ')
      }
    )
    value = String(result.value)
  } catch {
    return
  }
  try {
    await updateAgentTool(row.toolName ?? '', { allowedTenants: splitTenants(value) })
    ElMessage.success(t('settings.agentTools.updateDone'))
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.agentTools.actionFailed'))
  }
}

const remove = async (row: AgentTool): Promise<void> => {
  try {
    await ElMessageBox.confirm(t('settings.agentTools.deleteConfirm', { name: row.toolName ?? '' }), t('settings.agentTools.delete'), {
      type: 'warning',
      confirmButtonText: t('settings.agentTools.delete'),
      cancelButtonText: t('common.cancel')
    })
  } catch {
    return
  }
  try {
    await deleteAgentTool(row.toolName ?? '')
    ElMessage.success(t('settings.agentTools.deleteDone'))
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.agentTools.actionFailed'))
  }
}

onMounted(() => {
  void ensure().then(() => load())
})
</script>

<template>
  <div v-if="!unlocked" class="at-locked">
    <el-empty :description="t('settings.agentTools.locked')" />
  </div>

  <div v-else class="at-page">
    <el-alert type="info" :closable="false" :title="t('settings.agentTools.tip')" style="margin-bottom: 12px" />

    <el-card shadow="never">
      <template #header>{{ t('settings.agentTools.register') }}</template>
      <div class="at-form">
        <label><span class="at-label">{{ t('settings.agentTools.name') }}</span><el-input v-model="form.toolName" placeholder="web_search" maxlength="128" /></label>
        <label><span class="at-label">{{ t('settings.agentTools.version') }}</span><el-input v-model="form.version" maxlength="32" /></label>
        <label class="at-wide"><span class="at-label">{{ t('settings.agentTools.description') }}</span><el-input v-model="form.description" maxlength="256" /></label>
        <label class="at-wide"><span class="at-label">{{ t('settings.agentTools.schema') }}</span><el-input v-model="form.inputSchema" type="textarea" :rows="2" /></label>
        <label class="at-wide"><span class="at-label">{{ t('settings.agentTools.tenants') }}</span><el-input v-model="form.tenants" :placeholder="t('settings.agentTools.tenantsPlaceholder')" /></label>
        <el-button type="primary" :loading="submitting" @click="register">{{ t('settings.agentTools.register') }}</el-button>
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>
        <div class="at-header">
          <span>{{ t('settings.agentTools.list') }}</span>
          <el-button size="small" :loading="loading" @click="load">{{ t('settings.agentTools.refresh') }}</el-button>
        </div>
      </template>
      <el-table :data="tools" v-loading="loading" size="small" max-height="420">
        <el-table-column prop="toolName" :label="t('settings.agentTools.name')" min-width="150" />
        <el-table-column prop="version" :label="t('settings.agentTools.version')" width="90" />
        <el-table-column prop="description" :label="t('settings.agentTools.description')" min-width="180">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('settings.agentTools.enabled')" width="100">
          <template #default="{ row }">
            <el-switch :model-value="!!row.enabled" @change="toggle(row)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('settings.agentTools.tenants')" min-width="160">
          <template #default="{ row }">{{ (row.allowedTenants ?? []).join(', ') || t('settings.agentTools.allTenants') }}</template>
        </el-table-column>
        <el-table-column prop="updatedBy" :label="t('settings.agentTools.updatedBy')" min-width="120" />
        <el-table-column :label="t('common.action')" width="110">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="editTenants(row)">{{ t('settings.agentTools.edit') }}</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">{{ t('settings.agentTools.delete') }}</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="at-hint">{{ t('settings.agentTools.empty') }}</div></template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.at-locked { padding: 24px 0; }
.at-page { display: flex; flex-direction: column; }
.at-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; align-items: end; }
.at-form label { display: flex; flex-direction: column; gap: 4px; }
.at-wide { grid-column: span 2; }
.at-label, .at-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.at-hint { line-height: 1.6; }
.at-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
@media (max-width: 900px) { .at-wide { grid-column: span 1; } }
</style>
