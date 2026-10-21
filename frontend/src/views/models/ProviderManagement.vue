<template>
  <PageSkeleton title="">
    <template #toolbar>
      <el-input v-model="searchQuery" :placeholder="t('provider.searchPlaceholder')" clearable size="default"
        class="search-input" @clear="handleSearchClear" @keyup.enter.native="applySearch">
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <el-select v-model="statusFilter" :placeholder="t('provider.all')" clearable size="default" class="filter-select">
        <el-option :label="t('provider.all')" value=""></el-option>
        <el-option :label="t('provider.statusActive')" value="ACTIVE"></el-option>
        <el-option :label="t('provider.statusInactive')" value="INACTIVE"></el-option>
      </el-select>

      <el-button type="text" class="refresh-button" @click="fetchProviders">
        <el-icon><Refresh /></el-icon>
      </el-button>
    </template>

    <div class="page-actions-row">
      <span class="page-tip">{{ t('provider.subtitle') }}</span>
      <el-button v-if="canWrite" type="primary" @click="openPresetDrawer">
        <el-icon><Plus /></el-icon>
        {{ t('provider.addProvider') }}
      </el-button>
    </div>

    <el-skeleton :loading="loading && providers.length === 0" :rows="4" animated>
      <template #default>
        <el-row :gutter="16" v-if="filtered.length > 0" class="provider-grid">
          <el-col v-for="p in filtered" :key="p.id" :xs="24" :sm="12" :md="8" :lg="6">
            <el-card shadow="hover" class="provider-card" :class="{ 'is-disabled': p.status !== 'ACTIVE' }">
              <template #header>
                <div class="card-header">
                  <span class="card-title" :title="p.name">{{ p.name }}</span>
                  <el-tag :type="p.status === 'ACTIVE' ? 'success' : 'info'" size="small" effect="light">
                    {{ p.status === 'ACTIVE' ? t('provider.statusActive') : t('provider.statusInactive') }}
                  </el-tag>
                </div>
              </template>

              <div class="card-body">
                <div class="card-line">
                  <span class="line-label">{{ t('provider.formServiceType') }}</span>
                  <span class="line-value">{{ serviceTypeLabel(p.serviceType) }}</span>
                </div>
                <div class="card-line">
                  <span class="line-label">{{ t('provider.formBaseUrl') }}</span>
                  <span class="line-value" :title="p.baseUrl">{{ extractDomain(p.baseUrl) }}</span>
                </div>
                <div class="card-line">
                  <span class="line-label">Key</span>
                  <span class="line-value" :class="p.hasApiKey ? 'key-ok' : 'key-missing'">
                    <el-icon><CircleCheck v-if="p.hasApiKey" /><WarningFilled v-else /></el-icon>
                    {{ p.hasApiKey ? t('provider.keySet') : t('provider.keyNotSet') }}
                  </span>
                </div>
                <div class="card-line">
                  <span class="line-label">{{ t('provider.columnModel') }}</span>
                  <span class="line-value model-count">
                    {{ p.modelCount > 0 ? t('provider.modelCount', { count: p.modelCount }) : t('provider.noModels') }}
                  </span>
                </div>
              </div>

              <div class="card-actions">
                <el-button size="small" type="primary" plain @click="openManage(p)">
                  <el-icon><Management /></el-icon>
                  {{ t('provider.actions.manageModels') }}
                </el-button>
                <template v-if="canWrite">
                  <el-button size="small" @click="openRotateKey(p)">{{ t('provider.actions.rotateKey') }}</el-button>
                  <el-button size="small" @click="openConfig(p)">{{ t('provider.actions.config') }}</el-button>
                  <el-button size="small" :type="p.status === 'ACTIVE' ? 'warning' : 'success'" plain @click="toggleStatus(p)">
                    {{ p.status === 'ACTIVE' ? t('provider.actions.disable') : t('provider.actions.enable') }}
                  </el-button>
                  <el-button size="small" type="danger" plain @click="handleDelete(p)">{{ t('provider.actions.delete') }}</el-button>
                </template>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <div v-else class="empty-wrap">
          <el-empty :description="t('provider.emptyText')">
            <el-button v-if="canWrite" type="primary" @click="openPresetDrawer">{{ t('provider.addProvider') }}</el-button>
          </el-empty>
        </div>
      </template>
    </el-skeleton>

    <!-- ============ 预设目录抽屉 ============ -->
    <el-drawer v-model="presetDrawerVisible" :title="t('provider.presetTitle')" size="480px">
      <div class="preset-toolbar">
        <el-input v-model="presetQuery" :placeholder="t('provider.presetSearchPlaceholder')" clearable size="default"
          class="preset-search">
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-radio-group v-model="presetCategory" size="small">
          <el-radio-button value="">{{ t('provider.presetCategory.all') }}</el-radio-button>
          <el-radio-button value="domestic">{{ t('provider.presetCategory.domestic') }}</el-radio-button>
          <el-radio-button value="international">{{ t('provider.presetCategory.international') }}</el-radio-button>
          <el-radio-button value="local">{{ t('provider.presetCategory.local') }}</el-radio-button>
        </el-radio-group>
      </div>

      <div class="preset-list">
        <div v-for="preset in filteredPresets" :key="preset.label" class="preset-item">
          <div class="preset-info">
            <div class="preset-name">
              {{ preset.label }}
              <el-tag size="small" :type="preset.category === 'domestic' ? 'success' : preset.category === 'international' ? 'primary' : 'warning'" class="preset-category-tag">
                {{ t('provider.presetCategory.' + preset.category) }}
              </el-tag>
            </div>
            <div class="preset-url">{{ preset.baseUrl || t('provider.formBaseUrl') }}</div>
          </div>
          <el-button size="small" :type="presetAdded(preset) ? 'info' : 'primary'" plain
            :disabled="presetAdded(preset)" @click="enablePreset(preset)">
            {{ presetAdded(preset) ? t('provider.presetEnabled') : t('provider.presetEnable') }}
          </el-button>
        </div>

        <div v-if="filteredPresets.length === 0" class="preset-empty">
          <el-empty :description="t('provider.presetEmpty')" :image-size="60" />
        </div>

        <el-divider content-position="left">{{ t('provider.presetCustom') }}</el-divider>
        <div class="preset-custom">
          <div class="preset-desc">{{ t('provider.presetCustomDesc') }}</div>
          <el-button type="primary" plain @click="openCreateCustom">{{ t('provider.addProvider') }}</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- ============ 供应商表单（新增/配置） ============ -->
    <el-dialog v-model="formDialogVisible" :title="formDialogTitle" width="560px" class="provider-form-dialog">
      <el-form :model="providerForm" label-width="120px" ref="providerFormRef">
        <el-form-item :label="t('provider.formName')" required>
          <el-input v-model="providerForm.name" :placeholder="t('provider.formNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('provider.formServiceType')">
          <el-select v-model="providerForm.serviceType" style="width: 100%">
            <el-option v-for="st in serviceTypeOptions" :key="st" :label="serviceTypeLabel(st)" :value="st" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('provider.formBaseUrl')" required>
          <el-input v-model="providerForm.baseUrl" placeholder="https://api.example.com" />
        </el-form-item>
        <el-form-item :label="t('provider.formPath')">
          <el-input v-model="providerForm.path" placeholder="/v1/chat/completions" />
        </el-form-item>
        <el-form-item :label="t('provider.formAdapter')">
          <el-select v-model="providerForm.adapter" :placeholder="t('provider.formAdapterAuto')" clearable
            style="width: 100%">
            <el-option v-for="a in adapterOptions" :key="a.value" :label="a.label" :value="a.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('provider.formApiKey')" :required="!isEdit">
          <el-input v-model="providerForm.apiKey" type="password" show-password clearable
            :placeholder="isEdit ? t('provider.formApiKeyKeep') : t('provider.formApiKeyPlaceholder')" />
          <div class="form-note">{{ t('provider.formApiKeyTip') }}</div>
        </el-form-item>
        <el-form-item :label="t('provider.formModelsPath')">
          <el-input v-model="providerForm.modelsPath" placeholder="/v1/models" />
          <div class="form-note">{{ t('provider.formModelsPathTip', { baseUrl: providerForm.baseUrl || '...' }) }}</div>
        </el-form-item>
        <el-form-item :label="t('provider.formDescription')">
          <el-input v-model="providerForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="formDialogVisible = false">{{ t('provider.cancel') }}</el-button>
          <el-button type="primary" :loading="formSaving" @click="submitForm">{{ t('provider.save') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- ============ 更换 Key ============ -->
    <el-dialog v-model="rotateDialogVisible" :title="rotateDialogTitle" width="480px">
      <el-form label-width="100px">
        <el-form-item :label="t('provider.rotateKeyNew')" required>
          <el-input v-model="rotateKeyValue" type="password" show-password placeholder="sk-..." />
          <div class="form-note">{{ t('provider.rotateKeyTip') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="rotateDialogVisible = false">{{ t('provider.cancel') }}</el-button>
          <el-button type="primary" :loading="rotateSaving" @click="submitRotateKey">{{ t('provider.save') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- ============ 管理模型抽屉 ============ -->
    <el-drawer v-model="manageDrawerVisible" :title="manageDrawerTitle" size="620px" @open="onManageOpened">
      <template v-if="managingProvider">
        <!-- 发现模型 -->
        <el-card shadow="never" class="manage-section">
          <div class="section-head">
            <span class="section-title">{{ t('provider.discover') }}</span>
            <el-button v-if="canWrite" size="small" type="primary" :loading="discovering" @click="runDiscover">
              <el-icon><Search /></el-icon>
              {{ t('provider.discover') }}
            </el-button>
          </div>
          <div class="section-tip">{{ t('provider.discoverTip') }}</div>

          <div v-if="discovered.length > 0" class="discover-result">
            <div class="discover-head">
              <span>{{ t('provider.discoveredList') }}</span>
              <el-checkbox :model-value="allDiscoveredSelected" :indeterminate="someDiscoveredSelected"
                @change="toggleSelectAll">
                {{ t('provider.selectAll') }}
              </el-checkbox>
            </div>
            <el-checkbox-group v-model="selectedModels" class="discover-list">
              <el-checkbox v-for="m in selectableDiscovered" :key="m" :value="m" :label="m" class="discover-item">
                <span class="model-id">{{ m }}</span>
              </el-checkbox>
            </el-checkbox-group>
            <div class="discover-actions">
              <el-form-item :label="t('provider.serviceTypeDefault')" class="service-type-item">
                <el-select v-model="manageServiceType" style="width: 220px">
                  <el-option v-for="st in serviceTypeOptions" :key="st" :label="serviceTypeLabel(st)" :value="st" />
                </el-select>
                <div class="form-note">{{ t('provider.serviceTypeDefaultTip') }}</div>
              </el-form-item>
              <el-button v-if="canWrite" type="primary" :disabled="selectedModels.length === 0" :loading="batchSaving"
                @click="addSelectedModels">
                {{ t('provider.addSelected', { count: selectedModels.length }) }}
              </el-button>
            </div>
          </div>
        </el-card>

        <!-- 单个添加 -->
        <el-card shadow="never" class="manage-section">
          <div class="section-head">
            <span class="section-title">{{ t('provider.singleAdd') }}</span>
          </div>
          <div class="single-add-row">
            <el-input v-model="singleModelId" :placeholder="t('provider.singleModelIdPlaceholder')" class="single-input" />
            <el-input v-model="singleDisplayName" :placeholder="t('provider.singleDisplayNamePlaceholder')"
              class="single-input" />
            <el-button v-if="canWrite" type="primary" :loading="singleSaving" :disabled="!singleModelId.trim()"
              @click="addSingleModel">
              {{ t('provider.singleAddButton') }}
            </el-button>
          </div>
        </el-card>

        <!-- 已接入模型 -->
        <el-card shadow="never" class="manage-section">
          <div class="section-head">
            <span class="section-title">{{ t('provider.addedModels') }}</span>
            <el-button size="small" text @click="loadManageModels">{{ t('provider.refresh') }}</el-button>
          </div>
          <el-table :data="manageModels" style="width: 100%" size="small" border>
            <el-table-column :label="t('provider.columnModel')" min-width="180">
              <template #default="scope">
                <div class="cell-title">{{ scope.row.tags?.alias || scope.row.name }}</div>
                <div class="cell-sub">{{ scope.row.name }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="status" :label="t('provider.columnStatus')" width="100" align="center">
              <template #default="scope">
                <el-tag :type="scope.row.status === 'active' ? 'success' : 'info'" size="small">
                  {{ scope.row.status === 'active' ? t('provider.statusActive') : t('provider.statusInactive') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('provider.columnActions')" width="80" align="center">
              <template #default="scope">
                <el-button v-if="canWrite" size="small" type="danger" text @click="deleteManageModel(scope.row)">
                  {{ t('provider.actions.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="manageModels.length === 0" class="added-empty">
            <el-empty :description="t('provider.addedModelsEmpty')" :image-size="60" />
          </div>
        </el-card>
      </template>
    </el-drawer>
  </PageSkeleton>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, CircleCheck, WarningFilled, Management } from '@element-plus/icons-vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import { COMMON_SERVICE_TYPES, getServiceTypeLabel } from '@/constants/serviceTypes'
import { PROVIDER_PRESETS, customTemplateToPreset, extractDomain, type ProviderPreset } from '@/constants/providerPresets'
import {
  getProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  rotateProviderKey,
  updateProviderStatus,
  discoverProviderModels,
  getProviderModels,
  batchAddProviderModels,
  type ModelProvider,
  type ModelProviderRequest
} from '@/api/provider'
import { deleteServiceInstance } from '@/api/instance'
import { getAdapterList, getAdapterTemplates } from '@/api/adapter'
import { usePermission } from '@/composables/usePermission'

const { t } = useI18n()
const serviceTypeLabel = (type: string): string => getServiceTypeLabel(type)

// v2.11.1: 写操作权限控制（VIEWER 等只读角色隐藏 添加/配置/启停/删除 按钮；后端同码二次拦截）
const { hasPermission } = usePermission()
const canWrite = computed(() => hasPermission('config:instances:write'))

const fetchAdapters = async () => {
  try {
    const response = await getAdapterList()
    if (response.data?.success) {
      adapters.value = response.data.data || []
    }
  } catch (error) {
    console.error('获取适配器列表失败:', error)
  }
}
const serviceTypeOptions: string[] = [...COMMON_SERVICE_TYPES] as string[]

// ---------- 列表 ----------
const providers = ref<ModelProvider[]>([])
const loading = ref(false)
const searchQuery = ref('')
const statusFilter = ref('')

const filtered = computed(() => {
  const list = providers.value
  const q = (searchQuery.value || '').trim().toLowerCase()
  return list.filter(p => {
    if (statusFilter.value && p.status !== statusFilter.value) return false
    if (!q) return true
    return (
      p.name.toLowerCase().includes(q) ||
      (p.baseUrl || '').toLowerCase().includes(q)
    )
  })
})

function debounce<T extends (...args: any[]) => void>(fn: T, wait = 300) {
  let timer: ReturnType<typeof setTimeout> | null = null
  return (...args: Parameters<T>) => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => fn(...args), wait)
  }
}
const applySearch = debounce(() => {}, 350)
const handleSearchClear = () => {}

const fetchProviders = async () => {
  loading.value = true
  try {
    const response = await getProviders()
    if (response.data?.success) {
      providers.value = response.data.data || []
    } else {
      ElMessage.error(response.data?.message || '获取供应商列表失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '获取供应商列表失败')
  } finally {
    loading.value = false
  }
}

// ---------- 预设目录 ----------
const presetDrawerVisible = ref(false)
const presetQuery = ref('')
/** 分类筛选：'' 全部 / domestic 国内 / international 国际 / local 本地 */
const presetCategory = ref('')

/** 系统设置「模型供应商模板」的自定义模板（source=custom），实时合并进预设目录 */
const customPresets = ref<ProviderPreset[]>([])
const loadCustomPresets = async () => {
  try {
    const res = await getAdapterTemplates()
    if (res.data?.success) {
      customPresets.value = (res.data.data || [])
        .filter(t => t.source === 'custom')
        .map(customTemplateToPreset)
    }
  } catch {
    /* 模板接口不可用时静默降级为仅内置预设 */
  }
}

/** 预设目录列表（内置 13 家 + 自定义模板；「自定义」手工入口固定在抽屉底部区块，不进列表） */
const filteredPresets = computed<ProviderPreset[]>(() => {
  const q = (presetQuery.value || '').trim().toLowerCase()
  return [...PROVIDER_PRESETS, ...customPresets.value].filter(p => {
    if (p.category === 'custom') return false
    const matchCategory = !presetCategory.value || p.category === presetCategory.value
    const matchSearch = !q
      || p.label.toLowerCase().includes(q)
      || (p.baseUrl || '').toLowerCase().includes(q)
    return matchCategory && matchSearch
  })
})

/** 该预设是否已添加（按 baseUrl 判重，名称允许用户改） */
const presetAdded = (preset: ProviderPreset): boolean => {
  if (!preset.baseUrl) return false
  return providers.value.some(p => (p.baseUrl || '').replace(/\/+$/, '') === preset.baseUrl.replace(/\/+$/, ''))
}

const openPresetDrawer = () => {
  presetQuery.value = ''
  presetCategory.value = ''
  // 每次打开都刷新，系统设置里新建的自定义模板即时可见
  loadCustomPresets()
  presetDrawerVisible.value = true
}

// ---------- 供应商表单 ----------
const formDialogVisible = ref(false)
const formSaving = ref(false)
const isEdit = ref(false)
const editingId = ref<number>(0)
const providerFormRef = ref()
const providerForm = reactive<ModelProviderRequest>({
  providerUid: '',
  name: '',
  serviceType: 'chat',
  baseUrl: '',
  path: '/v1/chat/completions',
  adapter: '',
  apiKey: '',
  modelsPath: '',
  description: ''
})

const formDialogTitle = computed(() => (isEdit.value ? t('provider.configTitle') : t('provider.addProvider'))
  .replace('{name}', isEdit.value ? providerForm.name || '' : ''))

// ---------- 适配器下拉（以方言显示，按所选模型分类过滤能力，值仍为适配器名） ----------
const adapters = ref<any[]>([])
const adapterOptions = computed(() =>
  adapters.value
    .filter((a: any) => {
      // 模型分类即能力键（chat/embedding/rerank/tts/stt/imgGen/imgEdit）
      const caps = a.capabilities || {}
      return caps[providerForm.serviceType || 'chat'] === true
    })
    .map((a: any) => ({
      value: a.name,
      label: a.dialect || a.name
    }))
)

// 模型分类切换后，若已选适配器不具备该能力则清空
watch(() => providerForm.serviceType || 'chat', (type, prev) => {
  if (prev && providerForm.adapter) {
    const hit = adapters.value.find((a: any) => a.name === providerForm.adapter)
    const caps = hit?.capabilities || {}
    if (caps[type] !== true) {
      providerForm.adapter = ''
    }
  }
})

function resetForm() {
  Object.assign(providerForm, {
    providerUid: '',
    name: '',
    serviceType: 'chat',
    baseUrl: '',
    path: '/v1/chat/completions',
    adapter: '',
    apiKey: '',
    modelsPath: '',
    description: ''
  })
}

function openCreateCustom() {
  resetForm()
  isEdit.value = false
  editingId.value = 0
  presetDrawerVisible.value = false
  formDialogVisible.value = true
}

/** 预设一键启用：预填地址/路径/适配器与 uid（幂等去重），只需补名称与 Key */
function enablePreset(preset: ProviderPreset) {
  resetForm()
  providerForm.providerUid = preset.uid
  providerForm.name = preset.label
  providerForm.baseUrl = preset.baseUrl
  providerForm.path = preset.path
  providerForm.adapter = preset.adapter || ''
  isEdit.value = false
  editingId.value = 0
  presetDrawerVisible.value = false
  formDialogVisible.value = true
}

function openConfig(provider: ModelProvider) {
  resetForm()
  Object.assign(providerForm, {
    name: provider.name,
    serviceType: provider.serviceType,
    baseUrl: provider.baseUrl,
    path: provider.path || '/v1/chat/completions',
    adapter: provider.adapter || '',
    apiKey: '',
    modelsPath: provider.modelsPath || '',
    description: provider.description || ''
  })
  isEdit.value = true
  editingId.value = provider.id
  formDialogVisible.value = true
}

const submitForm = async () => {
  if (!providerForm.name?.trim() || !providerForm.baseUrl?.trim() || (!isEdit.value && !providerForm.apiKey?.trim())) {
    ElMessage.warning(t('provider.formRequired'))
    return
  }
  formSaving.value = true
  try {
    const payload: ModelProviderRequest = { ...providerForm }
    // 编辑态未填 Key：不发送，保持原值
    if (isEdit.value && !payload.apiKey?.trim()) {
      delete payload.apiKey
    }
    const response = isEdit.value
      ? await updateProvider(editingId.value, payload)
      : await createProvider(payload)
    if (response.data?.success) {
      ElMessage.success(isEdit.value ? t('provider.updateSuccess') : t('provider.createSuccess'))
      formDialogVisible.value = false
      fetchProviders()
    } else {
      ElMessage.error(response.data?.message || t('provider.updateSuccess'))
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '保存失败')
  } finally {
    formSaving.value = false
  }
}

// ---------- 启停 / 删除 / 换 Key ----------
const toggleStatus = async (provider: ModelProvider) => {
  try {
    const target = provider.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    const response = await updateProviderStatus(provider.id, target)
    if (response.data?.success) {
      ElMessage.success(t('provider.statusUpdateSuccess'))
      fetchProviders()
    } else {
      ElMessage.error(response.data?.message || '操作失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '操作失败')
  }
}

const handleDelete = (provider: ModelProvider) => {
  ElMessageBox.confirm(
    t('provider.deleteConfirm', { name: provider.name }),
    t('provider.actions.delete'),
    { confirmButtonText: t('provider.confirmAdd'), cancelButtonText: t('provider.cancel'), type: 'warning' }
  ).then(async () => {
    try {
      const response = await deleteProvider(provider.id)
      if (response.data?.success) {
        ElMessage.success(t('provider.deleteSuccess'))
        fetchProviders()
      } else {
        ElMessage.error(response.data?.message || '删除失败')
      }
    } catch (error: any) {
      ElMessage.error(error?.message || '删除失败')
    }
  }).catch(() => {})
}

const rotateDialogVisible = ref(false)
const rotateSaving = ref(false)
const rotateKeyValue = ref('')
const rotatingId = ref<number>(0)
const rotatingName = ref('')
const rotateDialogTitle = computed(() => t('provider.rotateKeyTitle', { name: rotatingName.value }))

function openRotateKey(provider: ModelProvider) {
  rotatingId.value = provider.id
  rotatingName.value = provider.name
  rotateKeyValue.value = ''
  rotateDialogVisible.value = true
}

const submitRotateKey = async () => {
  if (!rotateKeyValue.value.trim()) {
    ElMessage.warning(t('provider.formRequired'))
    return
  }
  rotateSaving.value = true
  try {
    const response = await rotateProviderKey(rotatingId.value, rotateKeyValue.value.trim())
    if (response.data?.success) {
      ElMessage.success(t('provider.rotateKeySuccess'))
      rotateDialogVisible.value = false
      fetchProviders()
    } else {
      ElMessage.error(response.data?.message || '更换失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '更换失败')
  } finally {
    rotateSaving.value = false
  }
}

// ---------- 管理模型抽屉 ----------
const manageDrawerVisible = ref(false)
const managingProvider = ref<ModelProvider | null>(null)
const manageModels = ref<any[]>([])
const discovered = ref<string[]>([])
const selectedModels = ref<string[]>([])
const discovering = ref(false)
const batchSaving = ref(false)
const singleSaving = ref(false)
const singleModelId = ref('')
const singleDisplayName = ref('')
const manageServiceType = ref('chat')

const manageDrawerTitle = computed(() => t('provider.manageTitle', { name: managingProvider.value?.name || '' }))

/** 已接入的模型名（发现列表里标记/禁用） */
const addedModelNames = computed(() => new Set(manageModels.value.map(m => m.name)))
const selectableDiscovered = computed(() => discovered.value.filter(m => !addedModelNames.value.has(m)))
const allDiscoveredSelected = computed(() =>
  selectableDiscovered.value.length > 0 && selectedModels.value.length === selectableDiscovered.value.length
)
const someDiscoveredSelected = computed(() =>
  selectedModels.value.length > 0 && selectedModels.value.length < selectableDiscovered.value.length
)

function toggleSelectAll(val: boolean) {
  selectedModels.value = val ? [...selectableDiscovered.value] : []
}

async function openManage(provider: ModelProvider) {
  managingProvider.value = provider
  manageServiceType.value = provider.serviceType || 'chat'
  discovered.value = []
  selectedModels.value = []
  singleModelId.value = ''
  singleDisplayName.value = ''
  manageDrawerVisible.value = true
}

async function loadManageModels() {
  if (!managingProvider.value) return
  try {
    const response = await getProviderModels(managingProvider.value.id)
    if (response.data?.success) {
      manageModels.value = response.data.data || []
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '获取模型列表失败')
  }
}

function onManageOpened() {
  loadManageModels()
}

const runDiscover = async () => {
  if (!managingProvider.value) return
  discovering.value = true
  try {
    const response = await discoverProviderModels(managingProvider.value.id)
    if (response.data?.success) {
      const list = response.data.data || []
      discovered.value = list
      selectedModels.value = list.filter(m => !addedModelNames.value.has(m))
      if (list.length === 0) {
        ElMessage.info(t('provider.discoverEmpty'))
      } else {
        ElMessage.success(t('provider.discoverSuccess', { count: list.length }))
      }
    } else {
      ElMessage.error(response.data?.message || '获取失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('provider.discoverEmpty'))
  } finally {
    discovering.value = false
  }
}

const addSelectedModels = async () => {
  if (!managingProvider.value || selectedModels.value.length === 0) return
  batchSaving.value = true
  try {
    const response = await batchAddProviderModels(managingProvider.value.id, {
      serviceType: manageServiceType.value,
      items: selectedModels.value.map(m => ({ modelId: m }))
    })
    if (response.data?.success) {
      const created = response.data.data?.created ?? 0
      ElMessage.success(t('provider.batchAddSuccess', { count: created }))
      selectedModels.value = []
      await Promise.all([loadManageModels(), fetchProviders()])
    } else {
      ElMessage.error(response.data?.message || '添加失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '添加失败')
  } finally {
    batchSaving.value = false
  }
}

const addSingleModel = async () => {
  if (!managingProvider.value || !singleModelId.value.trim()) return
  singleSaving.value = true
  try {
    const response = await batchAddProviderModels(managingProvider.value.id, {
      serviceType: manageServiceType.value,
      items: [{
        modelId: singleModelId.value.trim(),
        displayName: singleDisplayName.value.trim() || undefined
      }]
    })
    if (response.data?.success) {
      const created = response.data.data?.created ?? 0
      if (created > 0) {
        ElMessage.success(t('provider.batchAddSuccess', { count: created }))
        singleModelId.value = ''
        singleDisplayName.value = ''
        await Promise.all([loadManageModels(), fetchProviders()])
      } else {
        ElMessage.info(t('provider.batchAddPartial', { created: 0, skipped: 1 }))
      }
    } else {
      ElMessage.error(response.data?.message || '添加失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '添加失败')
  } finally {
    singleSaving.value = false
  }
}

const deleteManageModel = (row: any) => {
  if (!managingProvider.value) return
  ElMessageBox.confirm(
    t('provider.deleteModelConfirm', { name: row.name }),
    t('provider.actions.delete'),
    { confirmButtonText: t('provider.confirmAdd'), cancelButtonText: t('provider.cancel'), type: 'warning' }
  ).then(async () => {
    try {
      const response = await deleteServiceInstance(manageServiceType.value, String(row.id))
      if (response.data?.success) {
        ElMessage.success(t('provider.modelDeleteSuccess'))
        await Promise.all([loadManageModels(), fetchProviders()])
      } else {
        ElMessage.error(response.data?.message || '删除失败')
      }
    } catch (error: any) {
      ElMessage.error(error?.message || '删除失败')
    }
  }).catch(() => {})
}

onMounted(() => {
  fetchProviders()
  fetchAdapters()
  loadCustomPresets()
})
</script>

<style scoped>
.page-actions-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.page-tip {
  font-size: 13px;
  color: var(--ja-text-secondary);
}

.search-input {
  width: 360px;
  min-width: 200px;
}

.filter-select {
  width: 140px;
  min-width: 120px;
}

.refresh-button {
  color: var(--ja-text-secondary);
}

.provider-grid {
  margin-top: 4px;
}

.provider-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.provider-card.is-disabled {
  opacity: 0.72;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.card-title {
  font-weight: 600;
  color: var(--ja-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 4px 0 8px;
}

.card-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.line-label {
  color: var(--ja-text-secondary);
  flex-shrink: 0;
}

.line-value {
  color: var(--ja-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.line-value.key-ok {
  color: var(--el-color-success);
}

.line-value.key-missing {
  color: var(--el-color-warning);
}

.line-value.model-count {
  font-weight: 600;
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  border-top: 1px solid var(--ja-border-lighter);
  padding-top: 10px;
}

.empty-wrap {
  padding: 30px 0;
}

/* 预设目录抽屉 */
.preset-toolbar {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 4px;
}

.preset-search {
  width: 100%;
}

.preset-category-tag {
  margin-left: 6px;
  font-weight: 400;
}

.preset-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preset-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--ja-border-light);
  border-radius: 6px;
  transition: border-color 0.2s ease;
}

.preset-item:hover {
  border-color: var(--ja-primary);
}

.preset-name {
  font-weight: 600;
  color: var(--ja-text-primary);
  font-size: 14px;
}

.preset-url {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}

.preset-empty {
  padding: 20px 0;
}

.preset-custom {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}

.preset-desc {
  font-size: 13px;
  color: var(--ja-text-secondary);
}

/* 表单 */
.form-note {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
  line-height: 1.4;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

/* 管理模型抽屉 */
.manage-section {
  margin-bottom: 14px;
  border-radius: 8px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.section-title {
  font-weight: 600;
  color: var(--ja-text-primary);
  font-size: 14px;
}

.section-tip {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-bottom: 8px;
}

.discover-result {
  border-top: 1px dashed var(--ja-border-light);
  padding-top: 10px;
}

.discover-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--ja-text-secondary);
  margin-bottom: 8px;
}

.discover-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 220px;
  overflow-y: auto;
  padding: 8px 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.discover-item {
  margin: 0;
}

.model-id {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 13px;
}

.discover-actions {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-top: 10px;
}

.service-type-item {
  margin-bottom: 0;
}

.single-add-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.single-input {
  flex: 1;
  min-width: 180px;
}

.cell-title {
  font-weight: 600;
  color: var(--ja-text-primary);
}

.cell-sub {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}

.added-empty {
  padding: 16px 0 4px;
}
</style>
