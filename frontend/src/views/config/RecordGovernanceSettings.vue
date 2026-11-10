<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<template>
  <el-card class="record-governance-card" shadow="never" v-loading="configLoading">
    <template #header>
      <div class="card-header">
        <span class="card-title">{{ t('callHistory.list.recordGovernanceTitle') }}</span>
      </div>
    </template>
    <el-form label-width="100px" class="settings-form">
      <el-form-item :label="t('callHistory.list.recordLevelLabel')">
        <el-radio-group v-model="recordLevel" :disabled="configSaving">
          <el-radio value="METADATA_ONLY">{{ t('callHistory.list.recordLevels.metadataOnly') }}</el-radio>
          <el-radio value="SUMMARY">{{ t('callHistory.list.recordLevels.summary') }}</el-radio>
          <el-radio value="FULL">{{ t('callHistory.list.recordLevels.full') }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item>
        <el-button
          v-if="canWrite"
          type="primary"
          :loading="configSaving"
          :disabled="configSaving || configLoading"
          @click="handleSaveConfig"
        >
          {{ t('callHistory.common.save') }}
        </el-button>
      </el-form-item>
    </el-form>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="t('callHistory.list.levelGuideTitle')"
      :description="t('callHistory.list.levelGuideDescription')"
    />
  </el-card>
</template>

<script setup lang="ts">
/**
 * 记录治理设置（v2.9.2）
 *
 * <p>控制调用历史记录的<b>记录级别</b>：仅元数据 / 摘要 / 完整。影响全局，
 * 因此同时出现在两处：</p>
 * <ul>
 *   <li>「全局配置」页（系统 → 审计与版本 → 全局配置）——面向管理员的统一配置入口；</li>
 *   <li>「调用观测」页——运维在排查问题时可就地调整记录粒度。</li>
 * </ul>
 *
 * <p>两处操作的是<b>同一份后端配置</b>（{@code /call-history/config}），
 * 改动实时互相可见。</p>
 */
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { usePermission } from '@/composables/usePermission'
import { getCallHistoryConfig, updateCallHistoryConfig } from '@/api/callHistory'
import type { CallHistoryConfig } from '@/api/callHistory'

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('config:callhistory:write')

const configLoading = ref(false)
const configSaving = ref(false)
const recordLevel = ref<string>('METADATA_ONLY')

const loadConfig = async () => {
  configLoading.value = true
  try {
    const cfg: CallHistoryConfig = await getCallHistoryConfig()
    recordLevel.value = cfg.recordLevel || 'METADATA_ONLY'
  } catch (error: any) {
    console.error('加载记录配置失败:', error)
    ElMessage.error(t('callHistory.list.loadConfigFailed', { message: error.message || t('callHistory.common.unknownError') }))
  } finally {
    configLoading.value = false
  }
}

const handleSaveConfig = async () => {
  configSaving.value = true
  try {
    await updateCallHistoryConfig({ recordLevel: recordLevel.value })
    ElMessage.success(t('callHistory.list.saveSuccess'))
  } catch (error: any) {
    console.error('保存记录配置失败:', error)
    ElMessage.error(t('callHistory.list.saveConfigFailed', { message: error.message || t('callHistory.common.unknownError') }))
  } finally {
    configSaving.value = false
  }
}

onMounted(loadConfig)
</script>

<style scoped>
.record-governance-card {
  margin-bottom: 16px;
}

.settings-form {
  margin-bottom: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 16px;
  font-weight: bold;
}
</style>
