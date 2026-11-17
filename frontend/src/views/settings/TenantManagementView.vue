<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 租户管理（A1-pre）：开通 / 软停用 / 列表 + 隔离验证视图。
 *
 * <p>后端 TenantManagementController 三端点均在 /api/v1/enterprise/tenants 下，
 * 功能位 tenantManagement 未解锁时只显示锁定态、不发任何企业请求（前端 fail-closed）。
 * 操作人由服务端从认证上下文取，前端不传用户身份。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createTenant, disableTenant, listTenants, type TenantRecord } from '@/api/tenant'
import { useEnterpriseFeatures } from '@/composables/useEnterpriseFeatures'
import { useTenantStore } from '@/stores/tenant'

const { t } = useI18n()
const { tenantManagement: unlocked, ensure } = useEnterpriseFeatures()
const tenantStore = useTenantStore()

const tenants = ref<TenantRecord[]>([])
const loading = ref(false)
const submitting = ref(false)
const tenantId = ref('')
const displayName = ref('')
const currentTenantId = computed(() => tenantStore.currentTenantId)

/** 租户 ID 格式与后端注册表一致：[a-z0-9-]{1,63} */
const validId = computed(() => /^[a-z0-9-]{1,63}$/.test(tenantId.value.trim()))

const load = async (): Promise<void> => {
  if (!unlocked.value) return
  loading.value = true
  try {
    tenants.value = await listTenants()
  } finally {
    loading.value = false
  }
}

const submitCreate = async (): Promise<void> => {
  if (!validId.value) {
    ElMessage.warning(t('settings.tenantManagement.invalidId'))
    return
  }
  submitting.value = true
  try {
    const record = await createTenant(tenantId.value, displayName.value)
    ElMessage.success(t('settings.tenantManagement.created'))
    tenantId.value = ''
    displayName.value = ''
    await load()
    if (record?.tenantId) tenantStore.setTenant(record.tenantId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.tenantManagement.actionFailed'))
  } finally {
    submitting.value = false
  }
}

const confirmDisable = async (record: TenantRecord): Promise<void> => {
  try {
    await ElMessageBox.confirm(
      t('settings.tenantManagement.disableConfirm', { id: record.tenantId }),
      t('settings.tenantManagement.disable'),
      { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') }
    )
  } catch {
    return
  }
  try {
    await disableTenant(record.tenantId)
    ElMessage.success(t('settings.tenantManagement.disabled'))
    if (currentTenantId.value === record.tenantId && tenants.value.length > 1) {
      const next = tenants.value.find(item => item.tenantId !== record.tenantId)
      if (next) tenantStore.setTenant(next.tenantId)
    }
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('settings.tenantManagement.actionFailed'))
  }
}

onMounted(() => {
  void ensure().then(() => load())
})
</script>

<template>
  <div v-if="!unlocked" class="tm-locked">
    <el-empty :description="t('settings.tenantManagement.locked')" />
  </div>

  <div v-else class="tm-page">
    <el-alert type="info" :closable="false" :title="t('settings.tenantManagement.tip')" style="margin-bottom: 12px" />

    <el-row :gutter="12">
      <el-col :xs="24" :md="10">
        <el-card shadow="never">
          <template #header>{{ t('settings.tenantManagement.create') }}</template>
          <el-form label-position="top" @submit.prevent="submitCreate">
            <el-form-item :label="t('settings.tenantManagement.idLabel')">
              <el-input v-model="tenantId" :placeholder="t('settings.tenantManagement.idPlaceholder')" maxlength="63" />
              <div class="tm-hint">{{ t('settings.tenantManagement.idHint') }}</div>
            </el-form-item>
            <el-form-item :label="t('settings.tenantManagement.nameLabel')">
              <el-input v-model="displayName" :placeholder="t('settings.tenantManagement.namePlaceholder')" maxlength="128" />
            </el-form-item>
            <el-button type="primary" :loading="submitting" :disabled="!validId" @click="submitCreate">
              {{ t('settings.tenantManagement.create') }}
            </el-button>
          </el-form>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px">
          <template #header>{{ t('settings.tenantManagement.isolationTitle') }}</template>
          <div class="tm-isolation">
            <p>{{ t('settings.tenantManagement.isolationTip') }}</p>
            <div class="tm-current">
              <span>{{ t('settings.tenantManagement.currentTenant') }}</span>
              <strong>{{ currentTenantId }}</strong>
            </div>
            <div class="tm-switch-list">
              <el-button
                v-for="item in tenants"
                :key="item.tenantId"
                size="small"
                :type="item.tenantId === currentTenantId ? 'primary' : 'default'"
                @click="tenantStore.setTenant(item.tenantId)"
              >
                {{ item.displayName || item.tenantId }}
              </el-button>
              <span v-if="tenants.length === 0" class="tm-hint">{{ t('settings.tenantManagement.noTenants') }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="14">
        <el-card shadow="never">
          <template #header>
            <div class="tm-card-header">
              <span>{{ t('settings.tenantManagement.list') }}</span>
              <el-button size="small" :loading="loading" @click="load">{{ t('settings.tenantManagement.refresh') }}</el-button>
            </div>
          </template>
          <el-table :data="tenants" v-loading="loading" size="small" max-height="460">
            <el-table-column prop="tenantId" :label="t('settings.tenantManagement.idLabel')" min-width="140" />
            <el-table-column prop="displayName" :label="t('settings.tenantManagement.nameLabel')" min-width="130">
              <template #default="{ row }">{{ row.displayName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" :label="t('settings.tenantManagement.createdAt')" min-width="160" />
            <el-table-column :label="t('settings.tenantManagement.action')" width="110">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="confirmDisable(row)">
                  {{ t('settings.tenantManagement.disable') }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="tm-hint">{{ t('settings.tenantManagement.noTenants') }}</div>
            </template>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.tm-locked { padding: 24px 0; }
.tm-page { display: flex; flex-direction: column; }
.tm-hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.tm-card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.tm-isolation p { margin: 0 0 10px; font-size: 13px; color: var(--el-text-color-regular); }
.tm-current { display: flex; justify-content: space-between; padding: 8px 10px; background: var(--el-fill-color-lighter); border-radius: 4px; font-size: 13px; }
.tm-switch-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
</style>
