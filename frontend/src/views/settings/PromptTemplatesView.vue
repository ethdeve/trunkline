<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/** 提示词模板管理：列表 / 版本历史 / 按 id 顺序编排预览。 */
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listPromptTemplates,
  savePromptTemplate,
  getPromptTemplateVersions,
  composePromptTemplates,
  deletePromptTemplate,
  type PromptTemplate
} from '@/api/consoleExtensions'

const { t } = useI18n()
const templates = ref<PromptTemplate[]>([])
const loading = ref(false)
const dialogOpen = ref(false)
const saving = ref(false)
const versions = ref<PromptTemplate[]>([])
const versionsOpen = ref(false)
const selectedIds = ref<string[]>([])
const composed = ref('')
const form = reactive<PromptTemplate>({ name: '', content: '' })

const load = async (): Promise<void> => {
  loading.value = true
  try { templates.value = await listPromptTemplates() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.promptTemplates.loadFailed')) }
  finally { loading.value = false }
}

const openCreate = (): void => { Object.assign(form, { templateId: '', name: '', content: '' }); dialogOpen.value = true }
const openEdit = (row: PromptTemplate): void => { Object.assign(form, row); dialogOpen.value = true }

const submit = async (): Promise<void> => {
  if (!form.name?.trim()) { ElMessage.warning(t('settings.promptTemplates.nameRequired')); return }
  saving.value = true
  try {
    await savePromptTemplate({ ...form })
    ElMessage.success(t('settings.promptTemplates.saved'))
    dialogOpen.value = false
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.promptTemplates.saveFailed')) }
  finally { saving.value = false }
}

const showVersions = async (row: PromptTemplate): Promise<void> => {
  if (!row.templateId) return
  try { versions.value = await getPromptTemplateVersions(row.templateId); versionsOpen.value = true }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.promptTemplates.actionFailed')) }
}

const compose = async (): Promise<void> => {
  try {
    const result = await composePromptTemplates(selectedIds.value)
    composed.value = result.content
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.promptTemplates.actionFailed')) }
}

const remove = async (row: PromptTemplate): Promise<void> => {
  if (!row.templateId) return
  try {
    await ElMessageBox.confirm(t('settings.promptTemplates.deleteConfirm', { name: row.name ?? '' }), t('common.confirm'), { type: 'warning' })
    await deletePromptTemplate(row.templateId)
    await load()
  } catch (error) { if (error === 'cancel' || error === 'close') return; ElMessage.error(t('settings.promptTemplates.actionFailed')) }
}

onMounted(load)
</script>

<template>
  <div class="pt-page">
    <el-alert type="info" :closable="false" :title="t('settings.promptTemplates.tip')" style="margin-bottom: 12px" />
    <el-card shadow="never">
      <template #header>
        <div class="pt-header"><span>{{ t('settings.promptTemplates.title') }}</span>
          <el-button size="small" type="primary" @click="openCreate">{{ t('settings.promptTemplates.create') }}</el-button>
        </div>
      </template>
      <el-table :data="templates" size="small" border v-loading="loading">
        <el-table-column prop="name" :label="t('settings.promptTemplates.name')" min-width="150" />
        <el-table-column prop="templateId" :label="t('settings.promptTemplates.id')" min-width="160" show-overflow-tooltip />
        <el-table-column prop="content" :label="t('settings.promptTemplates.content')" min-width="220" show-overflow-tooltip />
        <el-table-column :label="t('settings.promptTemplates.versions')" width="100">
          <template #default="{ row }">{{ (row.versions ?? []).length }}</template>
        </el-table-column>
        <el-table-column :label="t('settings.promptTemplates.action')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="openEdit(row)">{{ t('settings.promptTemplates.edit') }}</el-button>
            <el-button link size="small" @click="showVersions(row)">{{ t('settings.promptTemplates.history') }}</el-button>
            <el-button link size="small" type="danger" @click="remove(row)">{{ t('settings.promptTemplates.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!templates.length && !loading" :description="t('settings.promptTemplates.empty')" />
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>{{ t('settings.promptTemplates.composeTitle') }}</template>
      <div class="pt-compose">
        <el-select v-model="selectedIds" multiple :placeholder="t('settings.promptTemplates.composePlaceholder')" style="width: 100%">
          <el-option v-for="tp in templates" :key="tp.templateId" :label="tp.name ?? ''" :value="tp.templateId ?? ''" />
        </el-select>
        <el-button size="small" :disabled="!selectedIds.length" @click="compose">{{ t('settings.promptTemplates.compose') }}</el-button>
        <pre v-if="composed" class="pt-preview">{{ composed }}</pre>
      </div>
    </el-card>

    <el-dialog v-model="dialogOpen" :title="t('settings.promptTemplates.create')" width="560px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item :label="t('settings.promptTemplates.name')" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('settings.promptTemplates.content')"><el-input v-model="form.content" type="textarea" :rows="8" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionsOpen" :title="t('settings.promptTemplates.history')" width="560px">
      <el-timeline v-if="versions.length">
        <el-timeline-item v-for="(v, i) in versions" :key="i" :timestamp="v.updatedAt ?? ''">
          <pre class="pt-preview">{{ v.content }}</pre>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else :description="t('settings.promptTemplates.noVersions')" />
    </el-dialog>
  </div>
</template>

<style scoped>
.pt-page { display: flex; flex-direction: column; }
.pt-header { display: flex; align-items: center; justify-content: space-between; }
.pt-compose { display: flex; flex-direction: column; gap: 10px; }
.pt-preview { white-space: pre-wrap; word-break: break-word; font-size: 12px; padding: 8px; background: var(--el-fill-color-lighter); border-radius: 4px; max-height: 200px; overflow: auto; }
</style>
