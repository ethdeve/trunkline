<template>
  <PageSkeleton :title="t('instance.title')">
    <template #actions>
      <el-button
        type="primary"
        link
        size="small"
        class="header-cross-link"
        @click="router.push({ name: 'model-categories', query: route.query.serviceType ? { serviceType: route.query.serviceType as string } : {} })"
      >
        {{ t('instance.openServiceConfig') }}
      </el-button>
      <el-button type="primary" @click="handleAddInstance" size="default">
        <el-icon><Plus /></el-icon>
        {{ t('instance.addInstance') }}
      </el-button>
    </template>

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
    </template>

    <div class="tabs-wrap">
      <el-tabs v-model="activeServiceType" class="service-tabs" type="card">
        <el-tab-pane v-for="serviceType in serviceTypes" :key="serviceType"
          :label="serviceTypeLabel(serviceType)" :name="serviceType">
          <div class="table-area">
            <el-skeleton :loading="loading && !hasInstances" :rows="6" animated>
              <template #default>
                <el-table :data="paginated" style="width: 100%" class="instance-table" row-key="id" border fit>
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
                      <div class="cell-title">{{ providerLabelOf(scope.row) }}</div>
                      <div class="cell-sub">
                        <el-tooltip :content="`${scope.row.baseUrl}${scope.row.path || ''}`" placement="top">
                          <span>{{ extractDomain(scope.row.baseUrl) }}{{ scope.row.path || '' }}</span>
                        </el-tooltip>
                      </div>
                    </template>
                  </el-table-column>

                  <el-table-column prop="status" :label="t('instance.status')" width="110" align="center">
                    <template #default="scope">
                      <div class="status-cell">
                        <el-tag :type="scope.row.status === 'active' ? 'success' : 'info'" class="table-tag">
                          {{ scope.row.status === 'active' ? t('instance.enabled') : t('instance.disabled') }}
                        </el-tag>
                        <span v-if="scope.row.health !== undefined" class="health-dot" :class="scope.row.health ? 'is-ok' : 'is-off'"
                              :title="scope.row.health ? t('instance.healthOk') : t('instance.healthOff')" />
                      </div>
                    </template>
                  </el-table-column>

                  <el-table-column :label="t('instance.actions')" width="200" align="center">
                    <template #default="scope">
                      <el-button size="small" type="success" plain circle :title="t('instance.testConnection')" @click="openTestDialog(scope.row)">
                        <el-icon><VideoPlay /></el-icon>
                      </el-button>

                      <el-button size="small" @click="handleEdit(scope.row)" type="primary" plain circle :title="t('instance.editInstance')">
                        <el-icon><Edit /></el-icon>
                      </el-button>

                      <el-button size="small" @click="openRateLimitConfig(scope.row)" type="warning" plain circle :title="t('instance.rateLimitConfig')">
                        <el-icon><Timer /></el-icon>
                      </el-button>

                      <el-button size="small" @click="openCircuitBreakerConfig(scope.row)" type="danger" plain circle :title="t('instance.circuitBreakerConfig')">
                        <el-icon><WarningFilled /></el-icon>
                      </el-button>

                      <el-button size="small" type="info" @click="handleDelete(scope.row)" plain circle :title="t('instance.delete')">
                        <el-icon><Delete /></el-icon>
                      </el-button>
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
      :is-edit="isEdit"
      :advanced="true"
    />

    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleDialogClose">{{ t('instance.cancel') }}</el-button>
        <el-button type="primary" @click="handleSave" :loading="saveLoading">{{ t('instance.save') }}</el-button>
      </span>
    </template>
  </el-dialog>

  <!-- 测试模型连通弹窗 -->
  <el-dialog v-model="testDialogVisible" :title="t('instance.testConnectionTitle')" width="560px" :before-close="handleTestDialogClose">
    <el-form label-width="100px">
      <el-form-item :label="t('instance.testConnectionModel')">
        <el-input :model-value="testTarget?.name" disabled />
      </el-form-item>

      <el-form-item :label="t('instance.testConnectionMessage')">
        <el-input v-model="testForm.message" type="textarea" :rows="2" />
      </el-form-item>

      <el-form-item :label="t('instance.testConnectionApiKey')">
        <el-input v-model="testForm.apiKey" type="password" show-password clearable
          :placeholder="t('instance.testConnectionApiKeyPlaceholder')" />
        <div class="form-tip">{{ t('instance.testConnectionApiKeyTip') }}</div>
      </el-form-item>
    </el-form>

    <el-result
      v-if="testResult"
      :icon="testResult.ok ? 'success' : 'error'"
      :title="testResult.ok ? t('instance.testConnectionSuccess') : t('instance.testConnectionFailed')"
      :sub-title="testResult.text"
      style="padding: 12px 0"
    />

    <template #footer>
      <el-button @click="handleTestDialogClose">{{ t('instance.cancel') }}</el-button>
      <el-button type="primary" :loading="testing" :disabled="!testValid" @click="runTest">{{ t('instance.testConnectionSend') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { SERVICE_TYPE_LABELS, COMMON_SERVICE_TYPES } from '@/constants/serviceTypes'
import { PROVIDER_PRESETS, matchProviderPreset, extractDomain } from '@/constants/providerPresets'
import { ElMessage, ElMessageBox } from 'element-plus'
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
import { deleteApiKey } from '@/api/apiKey'
import { clearCache } from '@/stores/playgroundCache'
import { Plus, Delete, Close, Timer, WarningFilled, Edit, Search, Refresh, VideoPlay } from '@element-plus/icons-vue'
import RateLimitConfig from '@/components/RateLimitConfig.vue'
import CircuitBreakerConfig from '@/components/CircuitBreakerConfig.vue'
import UpstreamInstanceForm from '@/components/UpstreamInstanceForm.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import { uid } from '@/utils/uid'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

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

// 根据 baseUrl/path 识别上游厂商名称（用于列表普通用户视角展示）
function providerLabelOf(row: ServiceInstance): string {
  const matched = matchProviderPreset(row.baseUrl || '', row.path || '')
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
  adapter?: string  // 适配器配置
  headers?: Record<string, string>  // 请求头配置
  tags?: Record<string, string>  // 标签配置
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
      (providerLabelOf(item).toLowerCase().includes(q))
    )
  })
})

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
        const typedData: ServiceInstance[] = data.map((item) => ({
          ...item,
          id: item.instanceId || uid(), // 使用后端返回的instanceId作为唯一标识符，如果没有则使用客户端唯一ID
          serviceType,
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

// 刷新当前 tab
const refreshCurrent = () => {
  const serviceType = activeServiceType.value
  delete instancesCache.value[serviceType]
  fetchServiceInstances(serviceType)
  // 同时刷新适配器列表和全局配置
  fetchAdapters()
  fetchGlobalConfig()
  ElMessage.success(t('instance.messages.refreshing'))
}

// 添加实例
const handleAddInstance = () => {
  dialogTitle.value = t('instance.addInstance')
  isEdit.value = false
  editingId.value = ''
  editingRow.value = null
  dialogVisible.value = true
}

// 编辑实例（表单状态交给公共组件管理；回填延迟到 dialog @opened 以保证组件已挂载）
const handleEdit = (row: ServiceInstance) => {
  dialogTitle.value = t('instance.editInstance')
  isEdit.value = true
  editingId.value = String(row.id)
  editingRow.value = row
  dialogVisible.value = true
}

// 对话框打开动画结束后回调：此时公共表单已稳定挂载，安全回填/重置
const onDialogOpened = () => {
  if (isEdit.value && editingRow.value) {
    instanceFormRef.value?.loadInstance(editingRow.value)
  } else {
    instanceFormRef.value?.resetForm()
  }
}

// 删除实例
const handleDelete = (row: ServiceInstance) => {
  const apiKeyId = row.tags?.apiKeyId
  ElMessageBox.confirm(
    apiKeyId ? t('instance.deleteConfirmMessageWithKey') : t('instance.deleteConfirmMessage'),
    t('instance.confirmTitle'),
    {
      confirmButtonText: t('instance.confirm'),
      cancelButtonText: t('instance.cancel'),
      type: 'warning'
    }
  ).then(async () => {
    try {
      // 确保使用当前激活的页签对应的服务类型
      const serviceType = activeServiceType.value;
      // 使用数据库ID
      const dbId = row.id
      const response = await deleteServiceInstance(serviceType, String(dbId))
      if (response.data?.success) {
        // 级联清理关联的网关密钥（若有），避免残留孤儿密钥；失败不影响实例已删除的结果
        if (apiKeyId) {
          try {
            await deleteApiKey(apiKeyId)
          } catch (ke: any) {
            console.warn('删除关联网关密钥失败（实例已删除）:', ke)
            ElMessage.warning(t('instance.messages.deleteKeyFailed'))
          }
        }
        instances.value[serviceType] = (instances.value[serviceType] || []).filter(i => i.id !== dbId)
        instancesCache.value[serviceType] = [...(instances.value[serviceType] || [])]
        // 同时清除试验场的缓存，让试验场下拉列表刷新
        clearCache(serviceType)
        ElMessage.success(t('instance.messages.deleteSuccess'))
      } else {
        ElMessage.error(response.data?.message || t('instance.messages.deleteFailed'))
      }
    } catch (error) {
      console.error('删除实例失败:', error)
      ElMessage.error(t('instance.messages.deleteInstanceFailed'))
    }
  }).catch(() => {
    ElMessage.info(t('instance.messages.deleteCancelled'))
  })
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
    // 确保使用当前选项卡的服务类型
    const serviceType = activeServiceType.value

    const instanceData: InstanceConfig = {
      name: payload.name,
      baseUrl: payload.baseUrl,
      path: payload.path || '',
      weight: payload.weight,
      status: payload.status,
      adapter: payload.adapter || undefined,
      headers: payload.headers || {},
      tags: payload.tags || {}
    }

    if (isEdit.value) {
      const response = await updateServiceInstance(serviceType, editingId.value, instanceData)
      if (response.data?.success) {
        delete instancesCache.value[serviceType]
        clearCache(serviceType)
        fetchServiceInstances(serviceType)
        ElMessage.success(t('instance.messages.editSuccess'))
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

/* ---------- 测试模型连通 ---------- */
const testDialogVisible = ref(false)
const testTarget = ref<ServiceInstance | null>(null)
const testForm = reactive({
  apiKey: '',
  message: '你好，请回复"接入成功"四个字。'
})
const testing = ref(false)
const testResult = ref<{ ok: boolean; text: string } | null>(null)
const testValid = computed(() => Boolean(testForm.apiKey.trim() && testForm.message.trim()))

const openTestDialog = (row: ServiceInstance) => {
  testTarget.value = row
  testForm.apiKey = ''
  testForm.message = '你好，请回复"接入成功"四个字。'
  testResult.value = null
  testDialogVisible.value = true
}

const handleTestDialogClose = () => {
  testDialogVisible.value = false
}

const runTest = async () => {
  if (!testTarget.value) return
  const model = testTarget.value.name
  if (!model) {
    ElMessage.warning(t('instance.testConnectionEmptyName'))
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const res = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': testForm.apiKey.trim()
      },
      body: JSON.stringify({
        model: model.trim(),
        messages: [{ role: 'user', content: testForm.message }],
        max_tokens: 100
      })
    })
    const body = await res.json().catch(() => ({}))
    if (res.ok && body?.choices?.[0]?.message?.content) {
      testResult.value = { ok: true, text: body.choices[0].message.content }
      ElMessage.success(t('instance.testConnectionSuccess'))
    } else {
      testResult.value = { ok: false, text: body?.error?.message || body?.message || `HTTP ${res.status}` }
    }
  } catch (e: any) {
    testResult.value = { ok: false, text: e?.message || t('instance.testConnectionRequestFailed') }
  } finally {
    testing.value = false
  }
}

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
})
</script>

<style scoped>
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

.table-tag {
  font-size: 13px;
  padding: 6px 10px;
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
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
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
