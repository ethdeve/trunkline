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
                  <div class="card-header__right">
                    <!-- Key 状态改用角标（悬停看说明），不再占一整行 -->
                    <el-tooltip :content="p.hasApiKey ? t('provider.keySet') : t('provider.keyNotSet')" placement="top">
                      <el-icon class="key-badge" :class="p.hasApiKey ? 'key-ok' : 'key-missing'">
                        <CircleCheck v-if="p.hasApiKey" />
                        <WarningFilled v-else />
                      </el-icon>
                    </el-tooltip>
                    <el-tag :type="p.status === 'ACTIVE' ? 'success' : 'info'" size="small" effect="light">
                      {{ p.status === 'ACTIVE' ? t('provider.statusActive') : t('provider.statusInactive') }}
                    </el-tag>
                  </div>
                </div>
              </template>

              <div class="card-body">
                <div class="card-line">
                  <el-tag size="small" type="info" effect="plain" class="cat-tag">
                    {{ serviceTypeLabel(p.serviceType) }}
                  </el-tag>
                  <span class="line-value model-count" :class="{ 'is-empty': !p.modelCount }">
                    {{ p.modelCount > 0 ? t('provider.modelCount', { count: p.modelCount }) : t('provider.noModels') }}
                  </span>
                </div>
                <div class="card-line">
                  <span class="line-value addr" :title="p.baseUrl">{{ extractDomain(p.baseUrl) }}</span>
                </div>
              </div>

              <div class="card-actions">
                <el-button size="small" type="primary" plain @click="openManage(p)">
                  <el-icon><Management /></el-icon>
                  {{ t('provider.actions.manageModels') }}
                </el-button>
                <div class="card-actions__right">
                  <el-switch
                    v-if="canWrite"
                    :model-value="p.status === 'ACTIVE'"
                    size="small"
                    inline-prompt
                    :active-text="t('provider.statusActive')"
                    :inactive-text="t('provider.statusInactive')"
                    @change="toggleStatus(p)"
                  />
                  <el-dropdown v-if="canWrite" trigger="click" @command="(cmd: string) => handleCardCommand(cmd, p)">
                    <el-button size="small" text>
                      {{ t('provider.more') }}
                      <el-icon><ArrowDown /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="rotateKey">
                          <el-icon><Key /></el-icon>{{ t('provider.actions.rotateKey') }}
                        </el-dropdown-item>
                        <el-dropdown-item command="config">
                          <el-icon><Setting /></el-icon>{{ t('provider.actions.config') }}
                        </el-dropdown-item>
                        <el-dropdown-item command="delete" divided>
                          <el-icon><Delete /></el-icon>{{ t('provider.actions.delete') }}
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </div>

              <!-- 下一步引导：按完成度给出唯一的下一步动作 -->
              <div v-if="nextStepHint(p)" class="card-next-step">
                <el-icon><Right /></el-icon>
                <span class="next-step-text">{{ nextStepHint(p) }}</span>
                <el-button v-if="canWrite" size="small" type="text" @click="goNextStep(p)">
                  {{ nextStepActionLabel(p) }}
                </el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <div v-else class="empty-wrap">
          <!-- 新手首访：零供应商时给出「三步接入」图形引导，而非干瘪的空白表格 -->
          <div v-if="providers.length === 0 && !searchQuery && !statusFilter" class="onboarding">
            <div class="onboarding-head">
              <span class="onboarding-title">{{ t('provider.onboarding.title') }}</span>
              <span class="onboarding-sub">{{ t('provider.onboarding.subtitle') }}</span>
            </div>
            <div class="onboarding-steps">
              <div v-for="(step, idx) in onboardingSteps" :key="step.key" class="onboarding-step">
                <div class="step-index">{{ idx + 1 }}</div>
                <div class="step-body">
                  <div class="step-title">{{ step.title }}</div>
                  <div class="step-desc">{{ step.desc }}</div>
                </div>
                <el-button
                  v-if="canWrite"
                  size="small"
                  :type="idx === 0 ? 'primary' : 'default'"
                  plain
                  @click="step.action"
                >
                  {{ step.actionLabel }}
                </el-button>
              </div>
            </div>
            <div class="onboarding-foot">
              <el-button v-if="canWrite" type="primary" @click="openPresetDrawer">
                <el-icon><Plus /></el-icon>
                {{ t('provider.onboarding.cta') }}
              </el-button>
              <span class="onboarding-foot-tip">{{ t('provider.onboarding.footTip') }}</span>
            </div>
          </div>

          <el-empty v-else :description="t('provider.emptyText')">
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
              <div v-for="m in selectableDiscovered" :key="m" class="discover-row">
                <el-checkbox :value="m" :label="m" class="discover-item" :disabled="isUnsupportedType(m)">
                  <span class="model-id">{{ m }}</span>
                </el-checkbox>
                <el-select v-if="isUnsupportedType(m)" :model-value="modelTypes[m]" size="small"
                  class="discover-type-select" disabled :title="t('provider.videoGenUnsupported')">
                  <el-option :label="serviceTypeLabel(modelTypes[m])" :value="modelTypes[m]" />
                </el-select>
                <el-select v-else v-model="modelTypes[m]" size="small" class="discover-type-select"
                  :title="t('provider.inferTypeTip')">
                  <el-option v-for="st in serviceTypeOptions" :key="st" :label="serviceTypeLabel(st)" :value="st" />
                </el-select>
              </div>
            </el-checkbox-group>
            <div class="discover-actions">
              <el-form-item :label="t('provider.serviceTypeDefault')" class="service-type-item">
                <el-select v-model="manageServiceType" style="width: 220px">
                  <el-option v-for="st in serviceTypeOptions" :key="st" :label="serviceTypeLabel(st)" :value="st" />
                </el-select>
                <div class="form-note">{{ t('provider.serviceTypeDefaultTip') }}</div>
              </el-form-item>              <el-button v-if="canWrite" type="primary" :disabled="selectedModels.length === 0" :loading="batchSaving"
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
            <el-button v-if="canWrite" type="primary" :loading="singleSaving"
              :disabled="!singleModelId.trim() || isVideoGenSingle" @click="addSingleModel">
              {{ t('provider.singleAddButton') }}
            </el-button>
          </div>
          <div v-if="singleModelId.trim()" class="form-note">
            {{ isVideoGenSingle
              ? t('provider.videoGenUnsupported')
              : t('provider.singleInferNote', { type: serviceTypeLabel(singleInferredType) }) }}
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
            <el-table-column prop="status" :label="t('provider.columnStatus')" width="90" align="center">
              <template #default="scope">
                <el-tag :type="scope.row.status === 'active' ? 'success' : 'info'" size="small">
                  {{ scope.row.status === 'active' ? t('provider.statusActive') : t('provider.statusInactive') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('provider.serviceTypeDefault')" width="96" align="center">
              <template #default="scope">
                <el-tag size="small" type="info">{{ serviceTypeLabel(scope.row.serviceType || '') }}</el-tag>
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
import { useRouter } from 'vue-router'
import { Search, Refresh, Plus, CircleCheck, WarningFilled, Management, ArrowDown, Key, Setting, Delete, Right } from '@element-plus/icons-vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import { COMMON_SERVICE_TYPES, ServiceType, getServiceTypeLabel, inferServiceTypeFromModelId } from '@/constants/serviceTypes'
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
import { useVisibleProviderPresets } from '@/composables/useVisibleProviderPresets'
import { usePermission } from '@/composables/usePermission'
import { usePersistedState } from '@/composables/usePersistedState'

const { t } = useI18n()
const serviceTypeLabel = (type: string): string => getServiceTypeLabel(type)
const router = useRouter()

/** 下一步引导文案：按供应商当前完成度给出唯一的下一步 */
const nextStepHint = (p: ModelProvider): string => {
  if (!p.hasApiKey) return t('provider.nextStepSetKey')
  if (!p.modelCount) return t('provider.nextStepAddModel')
  return t('provider.nextStepCreateKey')
}

/** 下一步动作按钮文案 */
const nextStepActionLabel = (p: ModelProvider): string => {
  if (!p.hasApiKey) return t('provider.nextStepActionSetKey')
  if (!p.modelCount) return t('provider.nextStepActionAddModel')
  return t('provider.nextStepAction')
}

/** 执行下一步：未配 Key → 打开配置弹窗；未加模型 → 打开管理模型抽屉；已就绪 → 去创建密钥 */
const goNextStep = (p: ModelProvider) => {
  if (!p.hasApiKey) {
    openConfig(p)
    return
  }
  if (!p.modelCount) {
    openManage(p)
    return
  }
  goCreateKey()
}

/** 跳转到密钥管理并直接打开创建弹窗 */
const goCreateKey = () => {
  router.push({ path: '/keys', query: { action: 'create' } })
}

/** 新手首访引导：零供应商时展示的「三步接入」步骤定义 */
const onboardingSteps = computed(() => [
  {
    key: 'pick',
    title: t('provider.onboarding.step1Title'),
    desc: t('provider.onboarding.step1Desc'),
    actionLabel: t('provider.onboarding.step1Action'),
    action: openPresetDrawer
  },
  {
    key: 'key',
    title: t('provider.onboarding.step2Title'),
    desc: t('provider.onboarding.step2Desc'),
    actionLabel: t('provider.onboarding.step2Action'),
    action: openPresetDrawer
  },
  {
    key: 'apikey',
    title: t('provider.onboarding.step3Title'),
    desc: t('provider.onboarding.step3Desc'),
    actionLabel: t('provider.onboarding.step3Action'),
    action: goCreateKey
  }
])

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

// U10-5：筛选条件持久化，下次进入本页自动恢复
usePersistedState('providers.search', searchQuery)
usePersistedState('providers.status', statusFilter)

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
/** 模板页「移除」的内置供应商与预设目录联动（useVisibleProviderPresets 统一管理隐藏状态） */
const { visiblePresets, refreshHidden } = useVisibleProviderPresets()

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

/** 预设目录列表（内置预设剔除已隐藏 + 自定义模板；「自定义」手工入口固定在抽屉底部区块，不进列表） */
const filteredPresets = computed<ProviderPreset[]>(() => {
  const q = (presetQuery.value || '').trim().toLowerCase()
  return [...visiblePresets.value, ...customPresets.value].filter(p => {
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
  // 每次打开都刷新，系统设置里新建的自定义模板与「移除」的内置厂商即时同步
  loadCustomPresets()
  refreshHidden()
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

// ---------- 卡片「更多」下拉分发 ----------
function handleCardCommand(cmd: string, provider: ModelProvider) {
  if (cmd === 'rotateKey') openRotateKey(provider)
  else if (cmd === 'config') openConfig(provider)
  else if (cmd === 'delete') handleDelete(provider)
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

/** 发现模型 → 绑定的模型分类（初始值=按模型 ID 自动识别，可手动调整） */
const modelTypes = ref<Record<string, string>>({})

/** 网关暂不支持接入的分类（识别出来仅做标记：默认不勾选、禁选、禁添加） */
const UNSUPPORTED_TYPES = new Set<string>([ServiceType.VIDEO_GEN])
const isUnsupportedType = (m: string) => UNSUPPORTED_TYPES.has(modelTypes.value[m] || '')

/** 单个添加的自动识别分类（识别不出回退默认分类） */
const singleInferredType = computed(() =>
  inferServiceTypeFromModelId(singleModelId.value.trim(), manageServiceType.value)
)
const isVideoGenSingle = computed(() => singleInferredType.value === ServiceType.VIDEO_GEN)

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
  selectedModels.value = val
    ? selectableDiscovered.value.filter(m => !isUnsupportedType(m))
    : []
}

async function openManage(provider: ModelProvider) {
  managingProvider.value = provider
  manageServiceType.value = provider.serviceType || 'chat'
  discovered.value = []
  selectedModels.value = []
  modelTypes.value = {}
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
      // 勾选即自动绑定分类：按模型 ID 关键词识别，识别不出的回退默认分类
      const types: Record<string, string> = {}
      for (const m of list) {
        types[m] = inferServiceTypeFromModelId(m, manageServiceType.value)
      }
      modelTypes.value = types
      // 视频生成等暂不支持接入的模型：识别出来但默认不勾选，防止误接入后调用 404
      selectedModels.value = list.filter(m => !addedModelNames.value.has(m) && !UNSUPPORTED_TYPES.has(types[m]))
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
    // 按每个模型各自绑定的分类分组（自动识别 + 手动调整的结果），
    // 逐分类调用批量接入——后端按请求级 serviceType 把模型落到对应模型分类下。
    // 兜底再滤一遍暂不支持接入的分类（视频生成等）。
    const groups = new Map<string, string[]>()
    for (const m of selectedModels.value) {
      if (isUnsupportedType(m)) continue
      const st = modelTypes.value[m] || manageServiceType.value
      const arr = groups.get(st)
      if (arr) arr.push(m)
      else groups.set(st, [m])
    }
    let created = 0
    for (const [st, items] of groups) {
      try {
        const response = await batchAddProviderModels(managingProvider.value.id, {
          serviceType: st,
          items: items.map(m => ({ modelId: m }))
        })
        if (response.data?.success) {
          created += response.data.data?.created ?? 0
        } else {
          ElMessage.error(`${serviceTypeLabel(st)}: ${response.data?.message || '添加失败'}`)
        }
      } catch (error: any) {
        ElMessage.error(`${serviceTypeLabel(st)}: ${error?.message || '添加失败'}`)
      }
    }
    if (created > 0) {
      ElMessage.success(t('provider.batchAddSuccess', { count: created }))
    }
    selectedModels.value = []
    await Promise.all([loadManageModels(), fetchProviders()])
  } finally {
    batchSaving.value = false
  }
}

const addSingleModel = async () => {
  if (!managingProvider.value || !singleModelId.value.trim()) return
  singleSaving.value = true
  try {
    const response = await batchAddProviderModels(managingProvider.value.id, {
      serviceType: singleInferredType.value,
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
      // 实例可能被自动识别到任意分类，删除须按该行真实分类（DTO 权威派生值），否则 404
      const response = await deleteServiceInstance(row.serviceType || manageServiceType.value, String(row.id))
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

.card-header__right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* Key 状态角标 */
.key-badge {
  font-size: 15px;
  cursor: default;
}

.key-badge.key-ok {
  color: var(--el-color-success);
}

.key-badge.key-missing {
  color: var(--el-color-warning);
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

.cat-tag {
  flex-shrink: 0;
}

.line-value.addr {
  color: var(--ja-text-secondary);
  font-size: 12px;
  max-width: 100%;
}

.line-value.model-count {
  font-weight: 600;
}

.line-value.model-count.is-empty {
  color: var(--ja-text-secondary);
  font-weight: 400;
}

.card-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  border-top: 1px solid var(--ja-border-lighter);
  padding-top: 10px;
}

.card-actions__right {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 下一步引导条 */
.card-next-step {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding: 7px 10px;
  border-radius: var(--ja-radius-md, 6px);
  background: var(--el-fill-color-lighter);
  color: var(--ja-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.card-next-step .el-icon {
  flex-shrink: 0;
  color: var(--ja-primary);
}

.next-step-text {
  flex: 1;
  min-width: 0;
}

.empty-wrap {
  padding: 30px 0;
}

/* 新手首访：三步接入引导 */
.onboarding {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 24px 20px;
  border: 1px solid var(--ja-border-light);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}

.onboarding-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 18px;
}

.onboarding-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.onboarding-sub {
  font-size: 13px;
  color: var(--ja-text-secondary);
}

.onboarding-steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.onboarding-step {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--ja-border-lighter);
  border-radius: 8px;
  background: var(--ja-bg-card, var(--el-bg-color));
}

.step-index {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--ja-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.step-body {
  flex: 1;
  min-width: 0;
}

.step-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.step-desc {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
  line-height: 1.5;
}

.onboarding-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
  flex-wrap: wrap;
}

.onboarding-foot-tip {
  font-size: 12px;
  color: var(--ja-text-secondary);
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
  flex: 1;
  min-width: 0;
}

/* 每行：模型名 checkbox + 自动识别的分类下拉 */
.discover-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.discover-type-select {
  width: 108px;
  flex-shrink: 0;
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
