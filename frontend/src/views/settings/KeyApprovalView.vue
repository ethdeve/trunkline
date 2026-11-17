<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * API Key 申请-审批管理：查看申请单、审批 APPROVED/REJECTED。
 * 补齐 MyKeysView 里"提交申请仅复制文本"的后端闭环（申请单状态机）。
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  listAllKeyRequests,
  decideKeyRequest,
  type KeyRequest
} from '@/api/consoleExtensions'

const { t } = useI18n()
const requests = ref<KeyRequest[]>([])
const loading = ref(false)
const filter = ref<'ALL' | 'PENDING'>('ALL')

const visible = () => filter.value === 'ALL' ? requests.value : requests.value.filter(r => r.status === 'PENDING')

const load = async (): Promise<void> => {
  loading.value = true
  try { requests.value = await listAllKeyRequests() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.keyApproval.loadFailed')) }
  finally { loading.value = false }
}

const decide = async (row: KeyRequest, decision: 'APPROVED' | 'REJECTED'): Promise<void> => {
  if (!row.requestId) return
  try {
    await decideKeyRequest(row.requestId, decision, '')
    ElMessage.success(t('settings.keyApproval.decided'))
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.keyApproval.actionFailed')) }
}

const statusTag = (status?: string): string => {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'warning'
}
const statusText = (status?: string): string =>
  t(`settings.keyApproval.status_${(status ?? 'PENDING').toLowerCase()}`)

onMounted(load)
</script>

<template>
  <div class="ka-page">
    <el-alert type="info" :closable="false" :title="t('settings.keyApproval.tip')" style="margin-bottom: 12px" />
    <el-card shadow="never">
      <template #header>
        <div class="ka-header">
          <span>{{ t('settings.keyApproval.title') }}</span>
          <el-radio-group v-model="filter" size="small">
            <el-radio-button value="ALL">{{ t('settings.keyApproval.all') }}</el-radio-button>
            <el-radio-button value="PENDING">{{ t('settings.keyApproval.pending') }}</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="visible()" size="small" border v-loading="loading">
        <el-table-column prop="applicant" :label="t('settings.keyApproval.applicant')" width="140" />
        <el-table-column prop="purpose" :label="t('settings.keyApproval.purpose')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="desiredPermissions" :label="t('settings.keyApproval.permissions')" min-width="150" show-overflow-tooltip />
        <el-table-column :label="t('settings.keyApproval.status')" width="120">
          <template #default="{ row }"><el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="decidedBy" :label="t('settings.keyApproval.decidedBy')" width="130" />
        <el-table-column :label="t('settings.keyApproval.action')" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button link size="small" type="success" @click="decide(row, 'APPROVED')">{{ t('settings.keyApproval.approve') }}</el-button>
              <el-button link size="small" type="danger" @click="decide(row, 'REJECTED')">{{ t('settings.keyApproval.reject') }}</el-button>
            </template>
            <span v-else class="ka-done">—</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!visible().length && !loading" :description="t('settings.keyApproval.empty')" />
    </el-card>
  </div>
</template>

<style scoped>
.ka-page { display: flex; flex-direction: column; }
.ka-header { display: flex; align-items: center; justify-content: space-between; }
.ka-done { color: var(--el-text-color-placeholder); }
</style>
