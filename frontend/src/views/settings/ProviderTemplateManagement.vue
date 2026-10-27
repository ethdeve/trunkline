<template>
  <PageSkeleton :title="t('providerTemplate.title')">
    <!-- 聚合页嵌入模式下 PageSkeleton 隐藏标题区（含 actions 插槽），按钮需自绘工具行 -->
    <div class="page-actions-row">
      <el-button v-if="canWrite" type="primary" size="default" @click="handleCreate">
        <el-icon><Plus /></el-icon>
        {{ t('providerTemplate.create') }}
      </el-button>
      <el-button size="default" :loading="detecting" @click="detectAvailability">
        <el-icon><Connection /></el-icon>
        {{ detecting ? t('providerTemplate.detecting') : t('providerTemplate.detect') }}
      </el-button>
      <el-button size="default" :disabled="!hasUnavailableBuiltin" @click="hideAllUnavailable">
        <el-icon><Hide /></el-icon>
        {{ t('providerTemplate.hideUnavailable') }}
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

    <el-alert
      v-if="detectSummary"
      type="success"
      :closable="true"
      :title="detectSummary"
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
      <el-table-column :label="t('providerTemplate.availability')" width="110" fixed="right">
        <template #default="{ row }">
          <span v-if="availabilityOf(row.id).testing">
            <el-icon class="is-loading"><Loading /></el-icon>
            {{ t('providerTemplate.detecting') }}
          </span>
          <el-tooltip v-else-if="availabilityOf(row.id).message" :content="availabilityOf(row.id).message">
            <el-tag size="small" :type="availabilityTagType(availabilityOf(row.id).status)">
              {{ availabilityLabel(availabilityOf(row.id).status) }}
            </el-tag>
          </el-tooltip>
          <span v-else class="text-secondary">{{ t('providerTemplate.availabilityUntested') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('providerTemplate.action')" width="160" fixed="right">
        <template #default="{ row }">
          <template v-if="row.source === 'custom'">
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(row)">
              {{ t('providerTemplate.edit') }}
            </el-button>
            <el-button v-if="canWrite" type="danger" link size="small" @click="handleDelete(row)">
              {{ t('providerTemplate.delete') }}
            </el-button>
          </template>
          <template v-else>
            <el-button v-if="canWrite" type="warning" link size="small" @click="handleHide(row)">
              {{ t('providerTemplate.actionHide') }}
            </el-button>
            <span v-else class="text-secondary">{{ t('providerTemplate.builtinReadonly') }}</span>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 已隐藏的默认模板（可逆恢复） -->
    <el-collapse v-if="hiddenTemplates.length || canWrite" v-model="hiddenCollapse" style="margin-top: 16px">
      <el-collapse-item :name="'hidden'">
        <template #title>
          <span class="hidden-title">
            {{ t('providerTemplate.hiddenTitle') }}
            <el-tag size="small" type="info" round>{{ hiddenTemplates.length }}</el-tag>
          </span>
        </template>
        <el-table v-if="hiddenTemplates.length" :data="hiddenTemplates" stripe border style="width: 100%">
          <el-table-column prop="name" :label="t('providerTemplate.name')" min-width="150" />
          <el-table-column prop="defaultBaseUrl" :label="t('providerTemplate.baseUrl')" min-width="220" show-overflow-tooltip />
          <el-table-column :label="t('providerTemplate.action')" width="120">
            <template #default="{ row }">
              <el-button v-if="canWrite" type="primary" link size="small" @click="handleRestore(row)">
                {{ t('providerTemplate.actionRestore') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :description="t('providerTemplate.hiddenEmpty')" :image-size="60" />
      </el-collapse-item>
    </el-collapse>

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
import { useDependencyGuard } from '@/composables/useDependencyGuard'
import { confirmDanger } from '@/utils/dangerConfirm'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh, Connection, Loading, Hide } from '@element-plus/icons-vue'
import {
  getAdapterTemplates,
  createAdapterTemplate,
  updateAdapterTemplate,
  deleteAdapterTemplate,
  hideAdapterTemplate,
  restoreAdapterTemplate,
  getHiddenAdapterTemplates,
  testAdapterConfig,
  type AdapterTemplate,
  type AdapterTestResult
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

// 可用性检测相关
const detecting = ref(false)
const availabilityMap = reactive<Record<string, {
  testing: boolean
  status?: string
  message?: string
}>>({})
const detectSummary = ref('')
const hiddenTemplates = ref<AdapterTemplate[]>([])
const hiddenCollapse = ref<string[]>([])

/** 检测为不可用的内置模板 ID（用于一键隐藏） */
const hasUnavailableBuiltin = computed(() =>
  templates.value.some(tpl =>
    tpl.source === 'builtin' &&
    availabilityMap[tpl.id] &&
    !availabilityMap[tpl.id].testing &&
    isUnavailable(availabilityMap[tpl.id].status)
  )
)

const availabilityOf = (id: string) => availabilityMap[id] || { testing: false }

const isUnavailable = (status?: string) =>
  status === 'TIMEOUT' || status === 'ERROR'

const availabilityTagType = (status?: string) => {
  if (status === 'CONNECTED') return 'success'
  if (status === 'AUTH_FAILED') return 'warning'
  if (isUnavailable(status)) return 'danger'
  return 'info'
}

const availabilityLabel = (status?: string) => {
  switch (status) {
    case 'CONNECTED': return t('providerTemplate.availabilityReachable')
    case 'AUTH_FAILED': return t('providerTemplate.availabilityNeedsKey')
    case 'TIMEOUT': return t('providerTemplate.availabilityTimeout')
    case 'ERROR': return t('providerTemplate.availabilityUnavailable')
    default: return t('providerTemplate.availabilityUntested')
  }
}

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

/** 加载已隐藏的内置模板 */
const loadHidden = async () => {
  try {
    const res = await getHiddenAdapterTemplates()
    if (res.data?.success) {
      hiddenTemplates.value = res.data.data || []
    }
  } catch (e: any) {
    hiddenTemplates.value = []
  }
}

/** 逐个 PING 供应商默认地址，判定 API 是否可达 */
const detectAvailability = async () => {
  if (detecting.value) return
  const targets = templates.value.filter(tpl => tpl.defaultBaseUrl)
  if (!targets.length) {
    ElMessage.info(t('providerTemplate.availabilityHint'))
    return
  }
  detecting.value = true
  detectSummary.value = ''
  // 重置检测状态
  targets.forEach(tpl => {
    availabilityMap[tpl.id] = { testing: true }
  })
  try {
    const results = await Promise.allSettled(
      targets.map(tpl =>
        testAdapterConfig({
          type: tpl.type,
          baseUrl: tpl.defaultBaseUrl,
          testType: 'PING',
          authHeaderName: tpl.auth?.headerName || 'Authorization',
          authHeaderPrefix: tpl.auth?.headerPrefix || ''
        }).then(res => ({ id: tpl.id, result: res.data?.data as AdapterTestResult | undefined }))
          .catch((err: any) => ({ id: tpl.id, error: err?.message || 'ERROR' }))
      )
    )
    let ok = 0
    let bad = 0
    results.forEach(r => {
      if (r.status === 'fulfilled') {
        const { id, result, error } = r.value as any
        if (error) {
          availabilityMap[id] = { testing: false, status: 'ERROR', message: String(error) }
          bad++
        } else if (result) {
          availabilityMap[id] = { testing: false, status: result.status, message: result.message }
          if (isUnavailable(result.status)) bad++
          else ok++
        }
      }
    })
    detectSummary.value = t('providerTemplate.detectDone', {
      total: targets.length, ok, bad
    })
  } finally {
    detecting.value = false
  }
}

/** 一键隐藏检测为不可用的默认模板 */
const hideAllUnavailable = async () => {
  const ids = templates.value
    .filter(tpl =>
      tpl.source === 'builtin' &&
      availabilityMap[tpl.id] &&
      !availabilityMap[tpl.id].testing &&
      isUnavailable(availabilityMap[tpl.id].status)
    )
    .map(tpl => tpl.id)
  if (!ids.length) {
    ElMessage.info(t('providerTemplate.availabilityHint'))
    return
  }
  try {
    await ElMessageBox.confirm(
      t('providerTemplate.hideUnavailableConfirm', { count: ids.length }),
      t('providerTemplate.hideConfirmTitle'),
      { type: 'warning' }
    )
    let count = 0
    for (const id of ids) {
      try {
        await hideAdapterTemplate(id)
        count++
      } catch (e: any) {
        ElMessage.error(t('providerTemplate.operationFailedDetail', { error: e.message || '' }))
      }
    }
    ElMessage.success(t('providerTemplate.hideUnavailableDone', { count }))
    await fetchTemplates()
    await loadHidden()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(t('providerTemplate.operationFailedDetail', { error: e.message || '' }))
    }
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

/** 移除内置默认模板（隐藏，可逆） */
const handleHide = async (row: AdapterTemplate) => {
  try {
    await ElMessageBox.confirm(
      t('providerTemplate.hideConfirm', { name: row.name }),
      t('providerTemplate.hideConfirmTitle'),
      { type: 'warning' }
    )
    const res = await hideAdapterTemplate(row.id)
    if (res.data?.success) {
      ElMessage.success(t('providerTemplate.hideSuccess', { name: row.name }))
      await fetchTemplates()
      await loadHidden()
    } else {
      ElMessage.error(res.data?.message || t('providerTemplate.operationFailed'))
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(t('providerTemplate.operationFailedDetail', { error: e.message || '' }))
    }
  }
}

/** 恢复已隐藏的内置默认模板 */
const handleRestore = async (row: AdapterTemplate) => {
  try {
    const res = await restoreAdapterTemplate(row.id)
    if (res.data?.success) {
      ElMessage.success(t('providerTemplate.restoreSuccess', { name: row.name }))
      await fetchTemplates()
      await loadHidden()
    } else {
      ElMessage.error(res.data?.message || t('providerTemplate.operationFailed'))
    }
  } catch (e: any) {
    ElMessage.error(t('providerTemplate.operationFailedDetail', { error: e.message || '' }))
  }
}

/** 删除前依赖预检：其下仍有已接入模型时禁止删除（须先删除这些模型） */
const { precheckDeletion } = useDependencyGuard()

const handleDelete = async (row: AdapterTemplate) => {
  // 删除前依赖预检：由该模板启用的模型供应商存在时禁止删除
  const pre = await precheckDeletion('template', row.id)
  if (pre.blocked) return
  const confirmed = await confirmDanger({
    title: t('providerTemplate.deleteConfirmTitle'),
    target: row.name,
    impact: t('dependency.noDependency'),
    remedy: t('providerTemplate.deleteRemedy'),
    requireType: row.name
  })
  if (!confirmed) return
  try {
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

onMounted(() => {
  fetchTemplates()
  loadHidden()
})
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

.hidden-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}
</style>
