<template>
  <PageSkeleton :title="t('instance.title')">
    <!-- 嵌入模式下 #actions 插槽会被 PageSkeleton 整体吞掉（复发性 bug 模式），
         按钮改为内容顶部 .page-actions-row 自渲染，直挂/嵌入两种模式都可见 -->
    <div class="page-actions-row">
      <el-button
        type="primary"
        link
        size="small"
        class="header-cross-link"
        @click="router.push({ path: '/settings', query: { tab: 'modelConfig', sub: 'modelCategories', ...(route.query.serviceType ? { serviceType: route.query.serviceType as string } : {}) } })"
      >
        {{ t('instance.openServiceConfig') }}
      </el-button>
    </div>

    <template #toolbar>
      <el-input v-model="searchQuery" :placeholder="t('instance.searchPlaceholderUser')" clearable size="default"
        class="search-input" @clear="handleSearchClear" @keyup.enter.native="applySearch">
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <el-select v-model="statusFilter" :placeholder="t('instance.status')" clearable size="default" class="filter-select">
        <el-option :label="t('instance.all')" value=""></el-option>
        <el-option :label="t('instance.enabled')" value="active"></el-option>
        <el-option :label="t('instance.disabled')" value="inactive"></el-option>
      </el-select>

      <el-button type="text" class="refresh-button" @click="refreshCurrent">
        <el-icon><Refresh /></el-icon>
      </el-button>

      <el-button type="text" class="refresh-button" :disabled="filtered.length === 0" @click="handleExport">
        <el-icon><Download /></el-icon>
      </el-button>

      <!-- 批量操作条：选中后浮现 -->
      <div v-if="canWrite && selectedInstances.length > 0" class="batch-bar">
        <span class="batch-bar__count">{{ t('instance.batch.selected', { count: selectedInstances.length }) }}</span>
        <el-button size="small" type="success" plain @click="handleBatchEnable">
          {{ t('instance.batch.enable') }}
        </el-button>
        <el-button size="small" type="warning" plain @click="handleBatchDisable">
          {{ t('instance.batch.disable') }}
        </el-button>
        <el-button size="small" type="danger" plain @click="handleBatchDelete">
          {{ t('instance.batch.delete') }}
        </el-button>
        <el-button size="small" link @click="clearSelection">{{ t('instance.batch.clear') }}</el-button>
      </div>
    </template>

    <div class="tabs-wrap">
      <el-tabs v-model="activeServiceType" class="service-tabs" type="card">
        <el-tab-pane v-for="serviceType in serviceTypes" :key="serviceType"
          :label="serviceTypeLabel(serviceType)" :name="serviceType">
          <div class="table-area">
            <el-skeleton :loading="loading && !hasInstances" :rows="6" animated>
              <template #default>
                <el-table :data="paginated" style="width: 100%" class="instance-table" row-key="id" border fit
                  @selection-change="onSelectionChange">
                  <el-table-column v-if="canWrite" type="selection" width="46" :reserve-selection="false" />
                  <!-- 普通用户视角首列：模型名（有备注名时优先显示备注名，小字标真实模型名） -->
                  <el-table-column prop="name" :label="t('instance.columnModelName')" min-width="180">
                    <template #default="scope">
                      <div class="cell-title">{{ scope.row.tags?.alias || scope.row.name }}</div>
                      <div class="cell-sub">{{ t('instance.modelPrefix') }}{{ scope.row.name }}</div>
                    </template>
                  </el-table-column>

                  <!-- 普通用户视角第二列：上游厂商 + 接入地址 -->
                  <el-table-column prop="baseUrl" :label="t('instance.columnVendorAddress')" min-width="260">
                    <template #default="scope">
                      <div class="cell-title">
                        {{ providerLabelOf(scope.row) }}
                        <el-tag v-if="scope.row.providerName" size="small" type="warning" effect="plain" class="provider-tag">
                          {{ t('provider.fromProvider') }}{{ scope.row.providerName }}
                        </el-tag>
                      </div>
                      <div class="cell-sub">
                        <el-tooltip :content="`${scope.row.baseUrl}${scope.row.path || ''}`" placement="top">
                          <span>{{ extractDomain(scope.row.baseUrl) }}{{ scope.row.path || '' }}</span>
                        </el-tooltip>
                      </div>
                    </template>
                  </el-table-column>

                  <el-table-column prop="status" :label="t('instance.status')" width="150" align="center">
                    <template #default="scope">
                      <div class="status-cell">
                        <el-tag :type="scope.row.status === 'active' ? 'success' : 'info'" class="table-tag">
                          {{ scope.row.status === 'active' ? t('instance.enabled') : t('instance.disabled') }}
                        </el-tag>
                        <!-- 运行态标签：熔断中 / 健康异常 / 限流中 -->
                        <el-tooltip v-if="runtimeStateOf(scope.row).tip" :content="runtimeStateOf(scope.row).tip"
                          placement="top">
                          <el-tag :type="runtimeStateOf(scope.row).tagType" size="small" class="table-tag">
                            {{ runtimeStateOf(scope.row).label }}
                          </el-tag>
                        </el-tooltip>
                        <span v-else-if="scope.row.healthStatus !== undefined && scope.row.healthStatus !== null"
                          class="health-dot" :class="healthDotClass(scope.row)" :title="healthTitleOf(scope.row)" />
                      </div>
                    </template>
                  </el-table-column>

                  <el-table-column :label="t('instance.actions')" width="180" align="left">
                    <template #default="scope">
                      <div class="row-actions">
                        <el-button v-if="canWrite" link type="primary" size="small" @click="handleEdit(scope.row)">
                          {{ t('instance.editInstance') }}
                        </el-button>
                        <el-button v-if="canWrite" link type="warning" size="small" @click="openRateLimitConfig(scope.row)">
                          {{ t('instance.rateLimitConfig') }}
                        </el-button>
                        <el-button v-if="canWrite" link type="danger" size="small" @click="openCircuitBreakerConfig(scope.row)">
                          {{ t('instance.circuitBreakerConfig') }}
                        </el-button>
                        <el-button v-if="canWrite" link type="danger" size="small" @click="handleDelete(scope.row)">
                          {{ t('instance.delete') }}
                        </el-button>
                      </div>
                    </template>
                  </el-table-column>
                </el-table>

                <div v-if="filtered.length === 0" class="empty-wrap">
                  <el-empty :description="t('instance.emptyText')"></el-empty>
                </div>

                <div class="table-footer" v-if="filtered.length > 0">
                  <div class="footer-info">
                    {{ t('instance.tableSummary', { count: filtered.length, current: currentPage, total: totalPages }) }}
                  </div>
                  <div class="footer-actions">
                    <el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="filtered.length"
                      layout="prev, pager, next, sizes, jumper" :page-sizes="[5, 10, 20, 50]"
                      @size-change="handleSizeChange" @current-change="handlePageChange" />
                  </div>
                </div>
              </template>
            </el-skeleton>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </PageSkeleton>

  <!-- 限流器配置弹窗 -->
  <RateLimitConfig
    v-model="rateLimitDialogVisible"
    :title="rateLimitDialogTitle"
    :initial-data="rateLimitFormData"
    @save="handleRateLimitSave"
  />

  <!-- 熔断器配置弹窗 -->
  <CircuitBreakerConfig
    v-model="circuitBreakerDialogVisible"
    :title="circuitBreakerDialogTitle"
    :initial-data="circuitBreakerFormData"
    @save="handleCircuitBreakerSave"
  />

  <!-- 添加/编辑实例对话框 -->
  <el-dialog v-model="dialogVisible" :title="dialogTitle" width="820px" :before-close="handleDialogClose"
    class="instance-dialog" @opened="onDialogOpened">
    <UpstreamInstanceForm
      ref="instanceFormRef"
      :service-types="serviceTypes"
      :service-type-label="serviceTypeLabel"
      :adapters="adapters"
      :global-adapter="globalAdapter"
      :providers="providerOptions"
      :is-edit="isEdit"
      :advanced="true"
      :simplified="true"
    />

    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleDialogClose">{{ t('instance.cancel') }}</el-button>
        <el-button type="primary" @click="handleSave" :loading="saveLoading">{{ t('instance.save') }}</el-button>
      </span>
    </template>
  </el-dialog>

</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { SERVICE_TYPE_LABELS, COMMON_SERVICE_TYPES } from '@/constants/serviceTypes'
import { matchProviderPreset, extractDomain } from '@/constants/providerPresets'
import { useProviderTemplatePresets } from '@/composables/useProviderTemplatePresets'
import { ElMessage } from 'element-plus'
import {
  addServiceInstance,
  deleteServiceInstance,
  getServiceInstances,
  updateServiceInstance,
  getRateLimitConfig,
  saveRateLimitConfig,
  getCircuitBreakerConfig,
  saveCircuitBreakerConfig,
  type InstanceConfig,
  type RateLimitConfig as RateLimitConfigType,
  type CircuitBreakerConfig as CircuitBreakerConfigType
} from '@/api/instance'
import { getServiceTypes } from '@/api/dashboard'
import { getAdapters, getAllConfigurations } from '@/api/service'
import { getProviders, type ModelProvider } from '@/api/provider'
import { getPoolList, type PoolDefinition } from '@/api/pools'
import { clearCache } from '@/stores/playgroundCache'
import { exportCsv, datedFilename } from '@/utils/csvExport'
import {
  fetchCircuitStates as fetchSharedCircuitStates,
  circuitStateOf as sharedCircuitStateOf
} from '@/composables/useInstanceAvailability'
import request from '@/utils/request'
import { Search, Refresh, Download } from '@element-plus/icons-vue'
import RateLimitConfig from '@/components/RateLimitConfig.vue'
import CircuitBreakerConfig from '@/components/CircuitBreakerConfig.vue'
import UpstreamInstanceForm from '@/components/UpstreamInstanceForm.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import { uid } from '@/utils/uid'
import { usePermission } from '@/composables/usePermission'
import { usePersistedState } from '@/composables/usePersistedState'
import { confirmDanger } from '@/utils/dangerConfirm'
import { useDependencyGuard } from '@/composables/useDependencyGuard'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = computed(() => hasPermission('config:instances:write'))

// v2.10.3 双语版：SERVICE_TYPE_LABELS 现为「类型→i18n key」映射，渲染时翻译；未知类型回退原值
const serviceTypeMap: Record<string, string> = SERVICE_TYPE_LABELS as Record<string, string>
const serviceTypeLabel = (type: string): string => {
  const key = serviceTypeMap[type]
  return key ? t(key) : type
}

// 指定显示顺序（会按此顺序排列卡片/标签）
const serviceOrder: string[] = COMMON_SERVICE_TYPES as string[]

// 服务类型（按指定顺序显示）
const serviceTypes = ref<string[]>([])

// 当前激活的服务类型
const activeServiceType = ref('chat')

// 适配器相关数据
const adapters = ref<any[]>([])
const globalConfig = ref<any>({})
const globalAdapter = ref<string>('')

// v2.11.0: 供应商列表（实例表单「从供应商选择」用，选中即继承地址与 Key）
const providerOptions = ref<ModelProvider[]>([])
const fetchProviderOptions = async () => {
  try {
    const response = await getProviders()
    if (response.data?.success) {
      providerOptions.value = response.data.data || []
    }
  } catch (error) {
    console.error('获取供应商列表失败:', error)
  }
}

// 根据 baseUrl/path 识别上游厂商名称（用于列表普通用户视角展示）
// 厂商预设统一取自后端「供应商模板」，因此用户自定义模板也能被正确识别
const { presets: PROVIDER_TEMPLATES } = useProviderTemplatePresets()
function providerLabelOf(row: ServiceInstance): string {
  const matched = matchProviderPreset(row.baseUrl || '', row.path || '', PROVIDER_TEMPLATES.value)
  if (matched) return matched.label
  const domain = extractDomain(row.baseUrl || '')
  return domain || '自定义'
}

// 定义实例类型
interface ServiceInstance {
  id: string | number  // 修改为联合类型以支持字符串ID
  serviceType: string
  name: string
  baseUrl: string
  path: string
  weight: number
  status: 'active' | 'inactive'
  instanceId: string
  /** 运行时实例 UUID（后端 v2.x 生成），用于关联熔断运行态；老数据可能为空 */
  runtimeInstanceId?: string
  /** 健康状态：HEALTHY / UNHEALTHY / UNKNOWN */
  healthStatus?: string
  /** 健康检查失败原因 */
  errorMessage?: string
  adapter?: string  // 适配器配置
  headers?: Record<string, string>  // 请求头配置
  tags?: Record<string, string>  // 标签配置
  providerId?: number  // v2.11.0: 关联的供应商 ID
  providerName?: string  // v2.11.0: 关联的供应商名称（后端带出）
  rateLimit: {
    enabled: boolean
    algorithm: string
    capacity: number
    rate: number
    scope: string
    key: string
    clientIpEnable: boolean
  }
  circuitBreaker: {
    enabled: boolean
    failureThreshold: number
    timeout: number
    successThreshold: number
  }
}

// 实例数据缓存和当前数据
const instancesCache = ref<Record<string, ServiceInstance[]>>({})
const instances = ref<Record<string, ServiceInstance[]>>({})

/** 模型分组列表缓存（用于删除实例时提示影响面） */
const poolCache = ref<PoolDefinition[]>([])

// 加载与状态
const loading = ref(false)
const saveLoading = ref(false)

const dialogVisible = ref(false)
const dialogTitle = ref('')
const isEdit = ref(false)
const instanceFormRef = ref<InstanceType<typeof UpstreamInstanceForm>>()
const editingId = ref<string>('')
// 暂存待编辑行：避免在 el-dialog 首次渲染前调用组件方法（ref 尚未挂载）导致回填丢失
const editingRow = ref<any>(null)

// 搜索/过滤/分页
const searchQuery = ref('')
const statusFilter = ref('')
const pageSize = ref(10)
const currentPage = ref(1)

// U10-5：筛选条件持久化，下次进入本页自动恢复
usePersistedState('instances.search', searchQuery)
usePersistedState('instances.status', statusFilter)

// 限流器配置弹窗相关
const rateLimitDialogVisible = ref(false)
const rateLimitDialogTitle = ref('')
const rateLimitFormData = ref<any>({ enabled: false })

// 熔断器配置弹窗相关
const circuitBreakerDialogVisible = ref(false)
const circuitBreakerDialogTitle = ref('')
const circuitBreakerFormData = ref<any>({ enabled: false })

// 简单防抖
function debounce<T extends (...args: any[]) => void>(fn: T, wait = 300) {
  let timeout: ReturnType<typeof setTimeout> | null = null
  return (...args: Parameters<T>) => {
    if (timeout) clearTimeout(timeout)
    timeout = setTimeout(() => fn(...args), wait)
  }
}
const applySearch = debounce(() => { currentPage.value = 1 }, 350)
const handleSearchClear = () => { currentPage.value = 1 }

// 计算当前选中类型是否有数据
const hasInstances = computed(() => {
  const list = instances.value[activeServiceType.value]
  return Array.isArray(list) && list.length > 0
})

// 计算过滤后的数组（按搜索 & 状态）
const filtered = computed(() => {
  const list = instances.value[activeServiceType.value] || []
  const q = (searchQuery.value || '').trim().toLowerCase()
  return list.filter(item => {
    if (statusFilter.value && item.status !== statusFilter.value) return false
    if (!q) return true
    return (
      (item.name && item.name.toLowerCase().includes(q)) ||
      (item.tags?.alias && item.tags.alias.toLowerCase().includes(q)) ||
      (item.baseUrl && item.baseUrl.toLowerCase().includes(q)) ||
      (item.providerName && item.providerName.toLowerCase().includes(q)) ||
      (providerLabelOf(item).toLowerCase().includes(q))
    )
  })
})

// ============ 运行态（熔断 / 健康）============

/**
 * 熔断器状态：实例 UUID（或 baseUrl）→ 状态名（CLOSED / OPEN / HALF_OPEN）
 * 统一走 useInstanceAvailability 的全局共享状态，避免与测试场/总览各拉一份导致数据不一致。
 */
const fetchCircuitStates = async () => {
  await fetchSharedCircuitStates()
}

/** 取该实例的熔断状态；OPEN=已熔断摘除，HALF_OPEN=半开探测中 */
const circuitStateOf = (row: ServiceInstance): string | undefined => {
  return sharedCircuitStateOf(row)
}

/** 健康状态归一化：HEALTHY / UNHEALTHY / UNKNOWN */
const healthOf = (row: ServiceInstance): string => (row.healthStatus || 'UNKNOWN').toUpperCase()

const healthDotClass = (row: ServiceInstance) => {
  const h = healthOf(row)
  if (h === 'HEALTHY') return 'is-ok'
  if (h === 'UNHEALTHY') return 'is-off'
  return 'is-unknown'
}

const healthTitleOf = (row: ServiceInstance) => {
  const h = healthOf(row)
  if (h === 'HEALTHY') return t('instance.healthOk')
  if (h === 'UNHEALTHY') {
    return row.errorMessage
      ? `${t('instance.healthOff')}：${row.errorMessage}`
      : t('instance.healthOff')
  }
  return t('instance.healthUnknown')
}

/** 运行态综合标签：熔断中 > 半开探测 > 健康异常 */
const runtimeStateOf = (row: ServiceInstance) => {
  const cs = circuitStateOf(row)
  if (cs === 'OPEN') {
    return { label: t('instance.circuitOpen'), tagType: 'danger' as const, tip: t('instance.circuitOpenTip') }
  }
  if (cs === 'HALF_OPEN') {
    return { label: t('instance.circuitHalfOpen'), tagType: 'warning' as const, tip: t('instance.circuitHalfOpenTip') }
  }
  if (healthOf(row) === 'UNHEALTHY') {
    return {
      label: t('instance.healthBad'),
      tagType: 'danger' as const,
      tip: row.errorMessage ? `${t('instance.healthOff')}：${row.errorMessage}` : t('instance.healthOff')
    }
  }
  return { label: '', tagType: 'info' as const, tip: '' }
}

// 分页计算
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const paginated = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

// 监控页数、过滤变化，修正页码
watch([filtered, pageSize], () => {
  if (currentPage.value > totalPages.value) currentPage.value = totalPages.value
})

// 监听服务类型变化，使用缓存或请求
watch(activeServiceType, (newServiceType) => {
  currentPage.value = 1
  searchQuery.value = ''
  statusFilter.value = ''
  if (newServiceType && !instancesCache.value[newServiceType]) {
    fetchServiceInstances(newServiceType)
  } else if (newServiceType && instancesCache.value[newServiceType]) {
    instances.value[newServiceType] = [...instancesCache.value[newServiceType]]
  }
})

// Cross-link: 监听路由 query 变化，切换到对应服务类型 Tab
watch(() => route.query.serviceType, (val) => {
  if (val && typeof val === 'string' && serviceTypes.value.includes(val) && activeServiceType.value !== val) {
    activeServiceType.value = val
  }
})

// 获取适配器列表
const fetchAdapters = async () => {
  try {
    const response = await getAdapters()
    if (response.data?.success) {
      adapters.value = response.data.data || []
    }
  } catch (error) {
    console.error('获取适配器列表失败:', error)
  }
}

// 获取全局配置
const fetchGlobalConfig = async () => {
  try {
    const response = await getAllConfigurations()
    if (response.data?.success) {
      globalConfig.value = response.data.data || {}
      // 从全局配置中提取默认适配器
      globalAdapter.value = globalConfig.value.adapter || ''
    }
  } catch (error) {
    console.error('获取全局配置失败:', error)
  }
}

// 获取服务类型并按 serviceOrder 排序显示
const fetchServiceTypes = async () => {
  try {
    const response = await getServiceTypes()
    if (response.data?.success) {
      const types: string[] = response.data.data || []

      // 保持 serviceOrder 中的顺序，其他未在 serviceOrder 中的类型追加在后
      const ordered = serviceOrder.filter(t => types.includes(t)).concat(types.filter(t => !serviceOrder.includes(t)))
      serviceTypes.value = ordered

      if (serviceTypes.value.length > 0) {
        // Cross-link: 从路由 query 中读取 serviceType，预选对应 Tab
        const queryType = route.query.serviceType as string | undefined
        if (queryType && serviceTypes.value.includes(queryType)) {
          activeServiceType.value = queryType
        } else {
          activeServiceType.value = serviceTypes.value[0]
        }
        fetchServiceInstances(activeServiceType.value)
      }
    }
  } catch (error) {
    console.error('获取服务类型失败:', error)
    ElMessage.error(t('instance.messages.fetchTypesFailed'))
  }
}

// 获取实例（带缓存与简单防抖）
let fetchTimer: ReturnType<typeof setTimeout> | null = null
const fetchServiceInstances = (serviceType: string) => {
  if (fetchTimer) clearTimeout(fetchTimer)
  fetchTimer = setTimeout(async () => {
    loading.value = true
    try {
      if (instancesCache.value[serviceType]) {
        instances.value[serviceType] = [...instancesCache.value[serviceType]]
        loading.value = false
        return
      }
      const response = await getServiceInstances(serviceType)
      if (response.data?.success) {
        const data: any[] = response.data.data || []
        // 确保每个实例都包含正确的服务类型和ID
        // serviceType 优先取后端权威值（2026-10-22 后端 DTO 已带出），缺失时回退到当前 Tab 上下文
        const typedData: ServiceInstance[] = data.map((item) => ({
          ...item,
          id: item.instanceId || uid(), // 使用后端返回的instanceId作为唯一标识符，如果没有则使用客户端唯一ID
          serviceType: item.serviceType || serviceType,
          adapter: item.adapter || '', // 确保适配器字段存在
          headers: item.headers || {}, // 确保headers字段存在
          tags: item.tags || {} // 确保tags字段存在
        }))
        instancesCache.value[serviceType] = typedData
        instances.value[serviceType] = [...typedData]
      } else {
        instances.value[serviceType] = []
      }
    } catch (error) {
      console.error(`获取${serviceType}服务实例失败:`, error)
      instances.value[serviceType] = []
      ElMessage.error(t('instance.messages.fetchInstancesFailed', { type: serviceType }))
    } finally {
      loading.value = false
    }
  }, 200)
}

/** 拉取模型分组列表（用于删除实例时提示影响面；失败静默降级） */
const fetchPools = async () => {
  try {
    const response = await getPoolList()
    if (response.data?.success) {
      poolCache.value = response.data.data || []
    }
  } catch (error) {
    console.error('获取模型分组列表失败:', error)
  }
}

// 刷新当前 tab
const refreshCurrent = () => {
  const serviceType = activeServiceType.value
  delete instancesCache.value[serviceType]
  fetchServiceInstances(serviceType)
  // 同时刷新适配器列表、全局配置与熔断运行态
  fetchAdapters()
  fetchGlobalConfig()
  fetchCircuitStates()
  fetchPools()
  ElMessage.success(t('instance.messages.refreshing'))
}

// 编辑实例（表单状态交给公共组件管理；回填延迟到 dialog @opened 以保证组件已挂载）
const handleEdit = (row: ServiceInstance) => {
  dialogTitle.value = t('instance.editInstance')
  isEdit.value = true
  editingId.value = String(row.id)
  editingRow.value = row
  dialogVisible.value = true
}

// 对话框打开动画结束后回调：此时公共表单已稳定挂载，安全回填
const onDialogOpened = () => {
  if (isEdit.value && editingRow.value) {
    instanceFormRef.value?.loadInstance(editingRow.value)
  }
}

/* ==================== 导出 ==================== */
/** 导出当前筛选结果（全量，不受分页限制），内容与页面所见一致 */
const handleExport = () => {
  const rows = filtered.value
  if (rows.length === 0) {
    ElMessage.info(t('instance.exportEmpty'))
    return
  }
  exportCsv(
    [
      { header: t('instance.columnModelName'), value: r => r.tags?.alias || r.name },
      { header: t('instance.modelPrefix').replace('：', ''), value: r => r.name },
      { header: t('instance.columnVendorAddress'), value: r => `${providerLabelOf(r)} ${r.baseUrl}${r.path || ''}`.trim() },
      { header: t('instance.status'), value: r => (r.status === 'active' ? t('instance.enabled') : t('instance.disabled')) },
      { header: t('instance.exportColumnHealth'), value: r => healthOf(r) },
      { header: t('instance.exportColumnCircuit'), value: r => circuitStateOf(r) || 'CLOSED' },
      { header: t('instance.adapter'), value: r => r.adapter || '' },
      { header: t('instance.weight'), value: r => r.weight ?? '' }
    ],
    rows,
    datedFilename(`instances-${activeServiceType.value}`)
  )
  ElMessage.success(t('instance.exportSuccess', { count: rows.length }))
}

/* ==================== 批量操作 ==================== */
/** 当前选中的实例行 */
const selectedInstances = ref<ServiceInstance[]>([])

function onSelectionChange(rows: ServiceInstance[]) {
  selectedInstances.value = rows || []
}

function clearSelection() {
  selectedInstances.value = []
}

/**
 * 由列表行还原为可提交的实例配置（启停是全量 PUT，字段必须完整）。
 * 注意：后端语义为「字段缺省 = 不变」，因此 undefined 的字段一律不下发，
 * 避免用空对象覆盖掉实例已有的请求头 / 标签。
 */
const toInstanceConfig = (row: ServiceInstance): InstanceConfig => ({
  name: row.name,
  baseUrl: row.baseUrl,
  path: row.path || '',
  weight: row.weight ?? 1,
  status: 'active',
  adapter: row.adapter || undefined,
  headers: row.headers && Object.keys(row.headers).length > 0 ? row.headers : undefined,
  tags: row.tags && Object.keys(row.tags).length > 0 ? row.tags : undefined,
  providerId: row.providerId
})

/** 批量启停：逐条 PUT，汇总成功/失败 */
async function batchToggle(enable: boolean) {
  const rows = [...selectedInstances.value]
  if (rows.length === 0) return
  const serviceType = activeServiceType.value
  const succeeded: string[] = []
  const failed: string[] = []
  for (const row of rows) {
    try {
      const config = toInstanceConfig(row)
      config.status = enable ? 'active' : 'inactive'
      const response = await updateServiceInstance(serviceType, String(row.id), config)
      if (response.data?.success) {
        succeeded.push(row.name)
      } else {
        failed.push(row.name)
      }
    } catch (error) {
      console.error(`批量${enable ? '启用' : '停用'}实例失败 ${row.name}:`, error)
      failed.push(row.name)
    }
  }
  delete instancesCache.value[serviceType]
  clearCache(serviceType)
  await fetchServiceInstances(serviceType)
  clearSelection()
  const action = t(enable ? 'instance.batch.enable' : 'instance.batch.disable')
  if (failed.length === 0) {
    ElMessage.success(t('instance.batch.resultAllOk', { count: succeeded.length, action }))
  } else {
    ElMessage.warning(t('instance.batch.resultPartial', { ok: succeeded.length, fail: failed.length, action }))
  }
}

const handleBatchEnable = () => batchToggle(true)
const handleBatchDisable = () => batchToggle(false)

/** 批量删除：影响面合并所有选中实例引用的分组 */
async function handleBatchDelete() {
  const rows = [...selectedInstances.value]
  if (rows.length === 0) return
  const serviceType = activeServiceType.value

  // 逐个预检（静默）：任一实例仍被分组/密钥引用即整批中止
  const blockedNames: string[] = []
  for (const row of rows) {
    const pre = await precheckDeletion('instance', row.id, { silent: true })
    if (pre.blocked) {
      blockedNames.push(row.name)
    }
  }
  if (blockedNames.length > 0) {
    ElMessage.error(t('dependency.batchBlocked', { names: blockedNames.join('、') }))
    return
  }

  const confirmed = await confirmDanger({
    title: t('instance.batch.deleteTitle'),
    target: t('instance.batch.deleteTarget', { count: rows.length }),
    impact: t('instance.batch.deleteImpactNone'),
    remedy: t('instance.batch.deleteRemedy'),
    requireType: String(rows.length),
    confirmText: t('instance.batch.confirmDelete'),
    cancelText: t('instance.cancel'),
    type: 'warning'
  })
  if (!confirmed) return

  const succeeded: string[] = []
  const failed: string[] = []
  for (const row of rows) {
    try {
      const response = await deleteServiceInstance(serviceType, String(row.id))
      if (response.data?.success) {
        succeeded.push(row.name)
      } else {
        failed.push(row.name)
      }
    } catch (error) {
      console.error(`批量删除实例失败 ${row.name}:`, error)
      failed.push(row.name)
    }
  }
  delete instancesCache.value[serviceType]
  clearCache(serviceType)
  fetchCircuitStates()
  await fetchServiceInstances(serviceType)
  clearSelection()
  if (failed.length === 0) {
    ElMessage.success(t('instance.batch.deleteSuccess', { count: succeeded.length }))
  } else {
    ElMessage.warning(t('instance.batch.deletePartial', { ok: succeeded.length, fail: failed.length }))
  }
}

/** 删除前依赖预检：被模型分组引用、或被密钥白名单关联时禁止删除 */
const { precheckDeletion } = useDependencyGuard()

// 删除实例
const handleDelete = async (row: ServiceInstance) => {
  // 删除前依赖预检：模型分组引用 / 密钥白名单关联 → 不允许删除
  const pre = await precheckDeletion('instance', row.id)
  if (pre.blocked) return

  // 影响面：无引用时说明删除不会破坏现有分组与密钥
  const impact = t('instance.deleteImpactNone')

  // U10-3：统一危险操作确认模板（影响面 + 补救方式 + 输入名称确认）
  const confirmed = await confirmDanger({
    title: t('instance.confirmTitle'),
    target: row.name,
    impact,
    remedy: t('instance.deleteRemedy'),
    requireType: row.name,
    confirmText: t('instance.confirm'),
    cancelText: t('instance.cancel'),
    type: 'warning'
  })
  if (!confirmed) {
    ElMessage.info(t('instance.messages.deleteCancelled'))
    return
  }

  try {
    // 确保使用当前激活的页签对应的服务类型
    const serviceType = activeServiceType.value
    // 使用数据库ID
    const dbId = row.id
    const response = await deleteServiceInstance(serviceType, String(dbId))
    if (response.data?.success) {
      instances.value[serviceType] = (instances.value[serviceType] || []).filter(i => i.id !== dbId)
      instancesCache.value[serviceType] = [...(instances.value[serviceType] || [])]
      // 同时清除试验场的缓存，让试验场下拉列表刷新
      clearCache(serviceType)
      // 熔断运行态已变（实例被删），同步刷新
      fetchCircuitStates()
      ElMessage.success(t('instance.messages.deleteSuccess'))
    } else {
      ElMessage.error(response.data?.message || t('instance.messages.deleteFailed'))
    }
  } catch (error) {
    console.error('删除实例失败:', error)
    ElMessage.error(t('instance.messages.deleteInstanceFailed'))
  }
}

/** 找出引用了该实例的模型分组（池成员匹配 instanceId 或实例名，与后端 PoolSelector 口径一致） */
const findPoolsReferencing = (row: ServiceInstance): string[] => {
  const candidates = new Set<string>()
  if (row.runtimeInstanceId) candidates.add(row.runtimeInstanceId)
  if (row.instanceId) candidates.add(String(row.instanceId))
  if (row.name) candidates.add(row.name)
  if (!candidates.size) return []

  try {
    const pools = poolCache.value
    return pools
      .filter(pool => (pool.members || []).some(m => m.instanceId && candidates.has(m.instanceId)))
      .map(pool => pool.poolName)
  } catch (error) {
    console.error('检查模型分组引用失败:', error)
    return []
  }
}

// 关闭对话框
const handleDialogClose = () => {
  dialogVisible.value = false
}

// 保存实例（新增/编辑）- 表单数据由公共组件 buildPayload 组装
const handleSave = async () => {
  const payload = instanceFormRef.value?.buildPayload()
  if (!payload) return
  saveLoading.value = true
  try {
    // 当前选项卡的模型分类（实例所属分类，也是请求路径参数）
    const serviceType = activeServiceType.value
    // 表单里选择的模型分类：编辑态可改（跨分类迁移）；新增态与 Tab 一致
    const targetServiceType = payload.serviceType || serviceType

    const instanceData: InstanceConfig = {
      name: payload.name,
      baseUrl: payload.baseUrl,
      path: payload.path || '',
      weight: payload.weight,
      status: payload.status,
      adapter: payload.adapter || undefined,
      headers: payload.headers || {},
      tags: payload.tags || {},
      // v2.11.0: 供应商关联透传（0=解除；不传=不变）
      providerId: payload.providerId,
      // 模型分类：仅在确实发生变化时下发，避免无谓触发后端迁移校验
      ...(targetServiceType !== serviceType ? { serviceType: targetServiceType } : {})
    }

    if (isEdit.value) {
      const response = await updateServiceInstance(serviceType, editingId.value, instanceData)
      if (response.data?.success) {
        if (targetServiceType !== serviceType) {
          // 跨分类迁移：实例已移到目标分类，两个分类的列表缓存都要失效
          delete instancesCache.value[serviceType]
          clearCache(serviceType)
          delete instancesCache.value[targetServiceType]
          clearCache(targetServiceType)
          fetchServiceInstances(targetServiceType)
          // 切到目标分类，让用户直接看到迁移后的实例
          activeServiceType.value = targetServiceType
          ElMessage.success(t('instance.serviceTypeChanged', {
            from: serviceTypeLabel(serviceType),
            to: serviceTypeLabel(targetServiceType)
          }))
        } else {
          delete instancesCache.value[serviceType]
          clearCache(serviceType)
          fetchServiceInstances(serviceType)
          ElMessage.success(t('instance.messages.editSuccess'))
        }
      } else {
        ElMessage.error(response.data?.message || t('instance.messages.editFailed'))
        saveLoading.value = false
        return
      }
    } else {
      const response = await addServiceInstance(serviceType, instanceData)
      if (response.data?.success) {
        delete instancesCache.value[serviceType]
        clearCache(serviceType)
        fetchServiceInstances(serviceType)
        ElMessage.success(t('instance.messages.addSuccess'))
      } else {
        ElMessage.error(response.data?.message || t('instance.messages.addFailed'))
        saveLoading.value = false
        return
      }
    }

    dialogVisible.value = false
  } catch (error) {
    console.error('保存实例失败:', error)
    ElMessage.error(t('instance.messages.saveFailed'))
  } finally {
    saveLoading.value = false
  }
}

// 分页事件
const handleSizeChange = (size: number) => { pageSize.value = size; currentPage.value = 1 }
const handlePageChange = (page: number) => { currentPage.value = page }

// 当前正在编辑的实例（使用数据库ID）
const currentEditInstanceId = ref<string>('')
const currentEditServiceType = ref<string>('')

// 打开限流器配置弹窗
const openRateLimitConfig = (row: any) => {
  // 使用数据库ID
  currentEditInstanceId.value = String(row.id)
  currentEditServiceType.value = activeServiceType.value
  rateLimitDialogTitle.value = t('instance.rateLimitConfigTitle', { name: row.name })
  // 从 row.rateLimit 中提取配置，转换为组件期望的嵌套格式
  const rateLimitConfig = row.rateLimit || { enabled: false }
  rateLimitFormData.value = {
    rateLimit: {
      enabled: rateLimitConfig.enabled || false,
      algorithm: rateLimitConfig.algorithm || 'token-bucket',
      capacity: rateLimitConfig.capacity || 100,
      rate: rateLimitConfig.rate || 10,
      scope: rateLimitConfig.scope || 'instance',
      key: rateLimitConfig.key || '',
      clientIpEnable: rateLimitConfig.clientIpEnable || false
    }
  }
  rateLimitDialogVisible.value = true
}

// 打开熔断器配置弹窗
const openCircuitBreakerConfig = (row: any) => {
  // 使用数据库ID
  currentEditInstanceId.value = String(row.id)
  currentEditServiceType.value = activeServiceType.value
  circuitBreakerDialogTitle.value = t('instance.circuitBreakerConfigTitle', { name: row.name })
  // 从 row.circuitBreaker 中提取配置，转换为组件期望的嵌套格式
  const circuitBreakerConfig = row.circuitBreaker || { enabled: false }
  circuitBreakerFormData.value = {
    circuitBreaker: {
      enabled: circuitBreakerConfig.enabled || false,
      failureThreshold: circuitBreakerConfig.failureThreshold || 5,
      timeout: circuitBreakerConfig.timeout || 60000,
      successThreshold: circuitBreakerConfig.successThreshold || 2
    }
  }
  circuitBreakerDialogVisible.value = true
}

// 保存限流器配置
const handleRateLimitSave = async (configData: any) => {
  if (!currentEditInstanceId.value) return

  saveLoading.value = true
  try {
    const serviceType = currentEditServiceType.value
    const instanceId = currentEditInstanceId.value

    // 从嵌套格式中提取限流器配置
    const rateLimitConfig = configData.rateLimit || configData

    // 构造请求数据
    const config: RateLimitConfigType = {
      enabled: rateLimitConfig.enabled || false,
      algorithm: rateLimitConfig.algorithm || 'token-bucket',
      capacity: rateLimitConfig.capacity || 100,
      rate: rateLimitConfig.rate || 10,
      scope: rateLimitConfig.scope || 'instance',
      key: rateLimitConfig.key || '',
      clientIpEnable: rateLimitConfig.clientIpEnable || false
    }

    const response = await saveRateLimitConfig(serviceType, instanceId, config)

    if (response.data?.success) {
      ElMessage.success(t('instance.messages.rateLimitSaveSuccess'))
      // 刷新缓存
      delete instancesCache.value[serviceType]
      fetchServiceInstances(serviceType)
    } else {
      ElMessage.error(response.data?.message || t('instance.messages.saveFailedGeneric'))
    }
  } catch (error) {
    console.error('保存限流器配置失败:', error)
    ElMessage.error(t('instance.messages.rateLimitSaveFailed'))
  } finally {
    saveLoading.value = false
  }
}

// 保存熔断器配置
const handleCircuitBreakerSave = async (configData: any) => {
  if (!currentEditInstanceId.value) return

  saveLoading.value = true
  try {
    const serviceType = currentEditServiceType.value
    const instanceId = currentEditInstanceId.value

    // 从嵌套格式中提取熔断器配置
    const circuitBreakerConfig = configData.circuitBreaker || configData

    // 构造请求数据
    const config: CircuitBreakerConfigType = {
      enabled: circuitBreakerConfig.enabled || false,
      failureThreshold: circuitBreakerConfig.failureThreshold || 5,
      timeout: circuitBreakerConfig.timeout || 60000,
      successThreshold: circuitBreakerConfig.successThreshold || 2
    }

    const response = await saveCircuitBreakerConfig(serviceType, instanceId, config)

    if (response.data?.success) {
      ElMessage.success(t('instance.messages.cbSaveSuccess'))
      // 刷新缓存
      delete instancesCache.value[serviceType]
      fetchServiceInstances(serviceType)
    } else {
      ElMessage.error(response.data?.message || t('instance.messages.saveFailedGeneric'))
    }
  } catch (error) {
    console.error('保存熔断器配置失败:', error)
    ElMessage.error(t('instance.messages.cbSaveFailed'))
  } finally {
    saveLoading.value = false
  }
}

// 初始化
onMounted(() => {
  fetchServiceTypes()
  fetchAdapters()
  fetchGlobalConfig()
  fetchProviderOptions()
  fetchCircuitStates()
  fetchPools()
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

.header-cross-link {
  font-size: 13px;
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

/* 批量操作条：选中后浮现，靠右对齐 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  padding: 4px 10px;
  border-radius: 6px;
  background: var(--ja-bg-subtle, rgb(0 0 0 / 3%));
}
.batch-bar__count {
  font-size: 13px;
  color: var(--ja-text-secondary);
  white-space: nowrap;
}

/* tabs & table area */
.tabs-wrap {
  padding: 0;
}

.service-tabs ::v-deep(.el-tabs__content) {
  padding: 0;
}

.table-area {
  background: var(--ja-bg-card);
  border-radius: 8px;
  padding: 12px;
  box-shadow: var(--ja-shadow-sm);
}

/* table */
.instance-table {
  width: 100%;
  border-radius: 6px;
  /* 宽表兜底：EP 的 header/body wrapper 默认 overflow:hidden 会裁掉超宽列且无法滚动；
     放开裁剪并让表格根 auto —— 仅在列总宽超出容器时才出现横向滚动条 */
  overflow: auto;
}
.instance-table :deep(.el-table__header-wrapper),
.instance-table :deep(.el-table__body-wrapper) {
  overflow: visible;
}

.instance-table :deep(.el-table__cell) {
  font-size: 14px;
  padding: 12px;
}

.instance-table :deep(.el-table__header th) {
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary);
  background-color: var(--el-fill-color-light);
}

.status-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex-wrap: wrap;
}

.table-tag {
  font-size: 13px;
  padding: 6px 10px;
}

/* 删除确认：多行文案（影响面提示）需保留换行 */
:global(.instance-delete-confirm .el-message-box__message) {
  white-space: pre-wrap;
  line-height: 1.7;
}

.ellipsis {
  max-width: 420px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* empty and footer */
.empty-wrap {
  padding: 30px 0;
  display: flex;
  justify-content: center;
  align-items: center;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 4px;
}

.table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--ja-border-lighter);
}

.footer-info {
  color: var(--ja-text-secondary);
  font-size: 13px;
}

/* dialog */
.health-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.health-dot.is-ok {
  background: var(--ja-dot-ok, #67c23a);
}
.health-dot.is-off {
  background: var(--ja-dot-off, #909399);
}
.health-dot.is-unknown {
  background: transparent;
  border: 1px solid var(--ja-text-placeholder, #c0c4cc);
}
.instance-dialog :deep(.el-dialog__header) {
  background-color: var(--el-fill-color-light);
  border-bottom: 1px solid var(--ja-border-lighter);
  padding: 14px 20px;
}

.instance-dialog :deep(.el-dialog__title) {
  font-weight: 700;
  color: var(--ja-text-primary);
  font-size: 18px;
}

.instance-dialog :deep(.el-dialog__body) {
  padding: 20px;
}

.instance-dialog :deep(.el-form-item__label) {
  font-weight: 600;
  font-size: 14px;
  color: var(--ja-text-primary);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 10px 20px;
}

/* 适配器相关样式 */
.adapter-note {
  font-size: 11px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}

.cell-title {
  font-weight: 600;
  color: var(--ja-text-primary);
}

.provider-tag {
  margin-left: 6px;
  font-weight: 400;
}

.cell-sub {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}

.form-tip {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
  line-height: 1.4;
}
</style>
