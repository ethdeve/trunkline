<template>
  <PageSkeleton :title="t('providerTemplate.title')">
    <!-- 聚合页嵌入模式下 PageSkeleton 隐藏标题区（含 actions 插槽），按钮需自绘工具行 -->
    <div class="page-actions-row">
      <el-button v-if="canWrite" type="primary" size="default" @click="handleCreate">
        <el-icon><Plus /></el-icon>
        {{ t('providerTemplate.create') }}
      </el-button>
      <el-button size="default" @click="fetchTemplates">
        <el-icon><Refresh /></el-icon>
        {{ t('providerTemplate.refresh') }}
      </el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      :title="t('providerTemplate.tip')"
      style="margin-bottom: 12px"
    />

    <el-table :data="templates" v-loading="loading" stripe border style="width: 100%">
      <el-table-column prop="name" :label="t('providerTemplate.name')" min-width="150" />
      <el-table-column prop="id" :label="t('providerTemplate.templateId')" min-width="110" />
      <el-table-column :label="t('providerTemplate.categoryLabel')" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="categoryTagType(row.category)">
            {{ t('providerTemplate.category.' + row.category) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="type" :label="t('providerTemplate.type')" min-width="140">
        <template #default="{ row }">
          {{ row.type === 'ollama-compatible' ? t('providerTemplate.typeOllama') : t('providerTemplate.typeOpenai') }}
        </template>
      </el-table-column>
      <el-table-column prop="defaultBaseUrl" :label="t('providerTemplate.baseUrl')" min-width="220" show-overflow-tooltip />
      <el-table-column :label="t('providerTemplate.models')" min-width="180">
        <template #default="{ row }">
          <span v-if="row.supportedModels?.length" class="models-cell">{{ row.supportedModels.join('、') }}</span>
          <span v-else class="text-secondary">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" :label="t('providerTemplate.sortOrder')" width="70" />
      <el-table-column :label="t('providerTemplate.source')" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.source === 'custom' ? 'success' : 'info'">
            {{ row.source === 'custom' ? t('providerTemplate.sourceCustom') : t('providerTemplate.sourceBuiltin') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('providerTemplate.action')" width="140" fixed="right">
        <template #default="{ row }">
          <template v-if="row.source === 'custom'">
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(row)">
              {{ t('providerTemplate.edit') }}
            </el-button>
            <el-button v-if="canWrite" type="danger" link size="small" @click="handleDelete(row)">
              {{ t('providerTemplate.delete') }}
            </el-button>
          </template>
          <span v-else class="text-secondary">{{ t('providerTemplate.builtinReadonly') }}</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? t('providerTemplate.editTitle') : t('providerTemplate.createTitle')"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item :label="t('providerTemplate.templateId')" prop="id">
          <el-input v-model="form.id" :disabled="isEditing" :placeholder="t('providerTemplate.idPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('providerTemplate.name')" prop="name">
          <el-input v-model="form.name" :placeholder="t('providerTemplate.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('providerTemplate.description')">
          <el-input v-model="form.description" :placeholder="t('providerTemplate.descPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('providerTemplate.categoryLabel')" prop="category">
          <el-radio-group v-model="form.category">
            <el-radio-button value="domestic">{{ t('providerTemplate.category.domestic') }}</el-radio-button>
            <el-radio-button value="international">{{ t('providerTemplate.category.international') }}</el-radio-button>
            <el-radio-button value="local">{{ t('providerTemplate.category.local') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('providerTemplate.type')" prop="type">
          <el-select v-model="form.type" style="width: 100%">
            <el-option :label="t('providerTemplate.typeOpenai')" value="openai-compatible" />
            <el-option :label="t('providerTemplate.typeOllama')" value="ollama-compatible" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('providerTemplate.baseUrl')" prop="defaultBaseUrl">
          <el-input v-model="form.defaultBaseUrl" placeholder="https://api.example.com/v1" />
        </el-form-item>
        <el-form-item :label="t('providerTemplate.authHeader')">
          <el-input v-model="form.auth.headerName" placeholder="Authorization" />
        </el-form-item>
        <el-form-item :label="t('providerTemplate.headerPrefix')">
          <el-input v-model="form.auth.headerPrefix" placeholder="Bearer " />
        </el-form-item>
        <el-form-item :label="t('providerTemplate.capabilities')">
          <div class="capability-checkboxes">
            <el-checkbox v-model="form.capabilities.chat">{{ t('adapter.capability.chat') }}</el-checkbox>
            <el-checkbox v-model="form.capabilities.embedding">{{ t('adapter.capability.embedding') }}</el-checkbox>
            <el-checkbox v-model="form.capabilities.rerank">{{ t('adapter.capability.rerank') }}</el-checkbox>
            <el-checkbox v-model="form.capabilities.tts">{{ t('adapter.capability.tts') }}</el-checkbox>
            <el-checkbox v-model="form.capabilities.stt">{{ t('adapter.capability.stt') }}</el-checkbox>
            <el-checkbox v-model="form.capabilities.imgGen">{{ t('adapter.capability.imgGen') }}</el-checkbox>
            <el-checkbox v-model="form.capabilities.imgEdit">{{ t('adapter.capability.imgEdit') }}</el-checkbox>
            <el-checkbox v-model="form.capabilities.streaming">{{ t('adapter.capability.streaming') }}</el-checkbox>
          </div>
        </el-form-item>
        <el-form-item :label="t('providerTemplate.models')">
          <el-input
            v-model="modelsInput"
            type="textarea"
            :rows="2"
            :placeholder="t('providerTemplate.modelsPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('providerTemplate.sortOrder')">
          <el-input-number v-model="form.sortOrder" :min="1" :max="9999" />
          <div class="form-note">{{ t('providerTemplate.sortOrderTip') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('providerTemplate.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">
          {{ t('providerTemplate.save') }}
        </el-button>
      </template>
    </el-dialog>
  </PageSkeleton>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  getAdapterTemplates,
  createAdapterTemplate,
  updateAdapterTemplate,
  deleteAdapterTemplate,
  type AdapterTemplate
} from '@/api/adapter'
import PageSkeleton from '@/components/PageSkeleton.vue'

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('config:adapters:write')

const loading = ref(false)
const saving = ref(false)
const templates = ref<AdapterTemplate[]>([])
const dialogVisible = ref(false)
const isEditing = ref(false)
const editingId = ref('')
const formRef = ref<FormInstance>()
const modelsInput = ref('')

const defaultForm = () => ({
  id: '',
  name: '',
  description: '',
  category: 'domestic' as AdapterTemplate['category'],
  type: 'openai-compatible',
  defaultBaseUrl: '',
  capabilities: {
    chat: true,
    embedding: false,
    rerank: false,
    tts: false,
    stt: false,
    imgGen: false,
    imgEdit: false,
    streaming: true
  },
  auth: {
    headerName: 'Authorization',
    headerPrefix: 'Bearer '
  },
  sortOrder: 200
})

const form = reactive(defaultForm())

const rules = computed<FormRules>(() => ({
  id: [
    { required: true, message: t('providerTemplate.validation.idRequired'), trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_-]{1,64}$/, message: t('providerTemplate.validation.idPattern'), trigger: 'blur' }
  ],
  name: [{ required: true, message: t('providerTemplate.validation.nameRequired'), trigger: 'blur' }],
  category: [{ required: true, message: t('providerTemplate.validation.categoryRequired'), trigger: 'change' }],
  type: [{ required: true, message: t('providerTemplate.validation.typeRequired'), trigger: 'change' }],
  defaultBaseUrl: [
    { required: true, message: t('providerTemplate.validation.baseUrlRequired'), trigger: 'blur' },
    { pattern: /^https?:\/\/.+/, message: t('providerTemplate.validation.baseUrlInvalid'), trigger: 'blur' }
  ]
}))

const categoryTagType = (category: string) =>
  category === 'domestic' ? 'success' : category === 'international' ? 'primary' : 'warning'

const fetchTemplates = async () => {
  loading.value = true
  try {
    const res = await getAdapterTemplates()
    if (res.data?.success) {
      templates.value = res.data.data || []
    }
  } catch (e: any) {
    ElMessage.error(t('providerTemplate.fetchFailed', { error: e.message || '' }))
  } finally {
    loading.value = false
  }
}

const handleCreate = () => {
  isEditing.value = false
  editingId.value = ''
  Object.assign(form, defaultForm())
  modelsInput.value = ''
  dialogVisible.value = true
}

const handleEdit = (row: AdapterTemplate) => {
  isEditing.value = true
  editingId.value = row.id
  Object.assign(form, {
    id: row.id,
    name: row.name,
    description: row.description || '',
    category: row.category,
    type: row.type,
    defaultBaseUrl: row.defaultBaseUrl || '',
    capabilities: { ...row.capabilities },
    auth: { ...row.auth },
    sortOrder: row.sortOrder
  })
  modelsInput.value = (row.supportedModels || []).join('\n')
  dialogVisible.value = true
}

const handleDelete = async (row: AdapterTemplate) => {
  try {
    await ElMessageBox.confirm(
      t('providerTemplate.deleteConfirm', { name: row.name }),
      t('providerTemplate.deleteConfirmTitle'),
      { type: 'warning' }
    )
    const res = await deleteAdapterTemplate(row.id)
    if (res.data?.success) {
      ElMessage.success(t('providerTemplate.deleteSuccess'))
      fetchTemplates()
    } else {
      ElMessage.error(res.data?.message || t('providerTemplate.deleteFailed'))
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(t('providerTemplate.deleteFailedDetail', { error: e.message || '' }))
    }
  }
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate()

  saving.value = true
  try {
    const models = modelsInput.value
      .split(/[\n,，;；]/)
      .map(s => s.trim())
      .filter(Boolean)

    const data: AdapterTemplate = {
      id: form.id.trim(),
      name: form.name.trim(),
      description: form.description?.trim() || '',
      icon: '',
      category: form.category,
      type: form.type,
      defaultBaseUrl: form.defaultBaseUrl.trim(),
      capabilities: { ...form.capabilities },
      auth: { ...form.auth },
      supportedModels: models,
      sortOrder: form.sortOrder
    }

    const res = isEditing.value
      ? await updateAdapterTemplate(editingId.value, data)
      : await createAdapterTemplate(data)

    if (res.data?.success) {
      ElMessage.success(isEditing.value ? t('providerTemplate.updateSuccess') : t('providerTemplate.createSuccess'))
      dialogVisible.value = false
      fetchTemplates()
    } else {
      ElMessage.error(res.data?.message || t('providerTemplate.operationFailed'))
    }
  } catch (e: any) {
    ElMessage.error(t('providerTemplate.operationFailedDetail', { error: e.message || '' }))
  } finally {
    saving.value = false
  }
}

onMounted(fetchTemplates)
</script>

<style scoped>
.page-actions-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.capability-checkboxes {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.models-cell {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.text-secondary {
  color: var(--ja-text-secondary);
  font-size: 12px;
}

.form-note {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
  line-height: 1.4;
}
</style>
