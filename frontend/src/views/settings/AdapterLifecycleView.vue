<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 适配器生命周期：上下线 / 灰度比例 / 灰度目标版本。
 * 只登记生命周期元数据，不改写路由规则（与 H3 策略编排职责分离）。
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  listAdapterLifecycle,
  setAdapterLifecycle,
  type AdapterLifecycle
} from '@/api/consoleExtensions'

const { t } = useI18n()
const rows = ref<AdapterLifecycle[]>([])
const loading = ref(false)
const savingName = ref<string | null>(null)

const load = async (): Promise<void> => {
  loading.value = true
  try { rows.value = await listAdapterLifecycle() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.adapterLifecycle.loadFailed')) }
  finally { loading.value = false }
}

const patch = async (row: AdapterLifecycle, body: Partial<AdapterLifecycle>): Promise<void> => {
  if (!row.adapterName) return
  savingName.value = row.adapterName
  try {
    const updated = await setAdapterLifecycle(row.adapterName, body)
    Object.assign(row, updated)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.adapterLifecycle.actionFailed')) }
  finally { savingName.value = null }
}

const onGrayPercent = (row: AdapterLifecycle, value: number | string): void => {
  const n = typeof value === 'number' ? value : Number(value) || 0
  void patch(row, { grayPercent: n })
}

onMounted(load)
</script>

<template>
  <div class="lc-page">
    <el-alert type="info" :closable="false" :title="t('settings.adapterLifecycle.tip')" style="margin-bottom: 12px" />
    <el-card shadow="never">
      <template #header>{{ t('settings.adapterLifecycle.title') }}</template>
      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="adapterName" :label="t('settings.adapterLifecycle.adapter')" min-width="160" />
        <el-table-column :label="t('settings.adapterLifecycle.online')" width="110">
          <template #default="{ row }">
            <el-switch :model-value="!!row.online" :loading="savingName === row.adapterName" @change="(v: boolean) => patch(row, { online: v })" />
          </template>
        </el-table-column>
        <el-table-column :label="t('settings.adapterLifecycle.gray')" width="110">
          <template #default="{ row }">
            <el-switch :model-value="!!row.gray" @change="(v: boolean) => patch(row, { gray: v })" />
          </template>
        </el-table-column>
        <el-table-column :label="t('settings.adapterLifecycle.grayPercent')" min-width="180">
          <template #default="{ row }">
            <el-slider :model-value="row.grayPercent ?? 0" :min="0" :max="100" show-input :disabled="!row.gray"
                       @change="(v: number | string) => onGrayPercent(row, v)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('settings.adapterLifecycle.rolloutVersion')" min-width="150">
          <template #default="{ row }">
            <el-input :model-value="row.rolloutVersion ?? ''" size="small" :placeholder="t('settings.adapterLifecycle.versionPlaceholder')"
                      @blur="(e: FocusEvent) => patch(row, { rolloutVersion: (e.target as HTMLInputElement).value })" />
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!rows.length && !loading" :description="t('settings.adapterLifecycle.empty')" />
    </el-card>
  </div>
</template>

<style scoped>
.lc-page { display: flex; flex-direction: column; }
</style>
