<template>
  <PageSkeleton :title="t('callHistory.list.pageTitle')">
    <template #toolbar>
      <!-- 查询筛选区 -->
      <el-form :inline="true" :model="queryForm" class="filter-form">
        <el-form-item :label="t('callHistory.common.timeRange')">
          <el-date-picker
            v-model="dateRange"
            type="datetimerange"
            :range-separator="t('callHistory.common.rangeSeparator')"
            :start-placeholder="t('callHistory.common.startTime')"
            :end-placeholder="t('callHistory.common.endTime')"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 360px"
          />
        </el-form-item>
        <el-form-item :label="t('callHistory.common.modelName')">
          <el-input
            v-model="queryForm.modelName"
            :placeholder="t('callHistory.list.modelNamePlaceholder')"
            clearable
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item :label="t('callHistory.common.serviceType')">
          <el-select v-model="queryForm.serviceType" :placeholder="t('callHistory.list.allPlaceholder')" clearable style="width: 120px">
            <el-option :label="t('serviceTypes.chat')" value="chat" />
            <el-option :label="t('serviceTypes.embedding')" value="embedding" />
            <el-option :label="t('serviceTypes.rerank')" value="rerank" />
            <el-option :label="t('serviceTypes.tts')" value="tts" />
            <el-option :label="t('serviceTypes.stt')" value="stt" />
            <el-option :label="t('serviceTypes.imgGen')" value="imgGen" />
            <el-option :label="t('serviceTypes.imgEdit')" value="imgEdit" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('callHistory.common.status')">
          <el-radio-group v-model="statusFilter">
            <el-radio-button value="all">{{ t('callHistory.list.allPlaceholder') }}</el-radio-button>
            <el-radio-button value="failed">{{ t('callHistory.common.failed') }}</el-radio-button>
            <el-radio-button value="success">{{ t('callHistory.common.success') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('callHistory.common.httpStatusCode')">
          <el-input
            v-model.number="queryForm.httpStatusCode"
            :placeholder="t('callHistory.list.httpStatusCodePlaceholder')"
            clearable
            style="width: 120px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleSearch">{{ t('callHistory.common.query') }}</el-button>
          <el-button icon="Refresh" @click="handleReset">{{ t('callHistory.common.reset') }}</el-button>
        </el-form-item>
      </el-form>
    </template>

    <!-- v2.9.2 记录治理设置 -->
    <el-card class="settings-card" shadow="hover" v-loading="configLoading">
      <template #header>
        <div class="table-header">
          <span class="chart-title">{{ t('callHistory.list.recordGovernanceTitle') }}</span>
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

    <!-- 数据表格 -->
    <el-card shadow="hover">
      <template #header>
        <div class="table-header">
          <span class="chart-title">{{ t('callHistory.list.listTitle') }}</span>
          <span class="total-count">{{ t('callHistory.list.totalRecords', { count: totalCount }) }}</span>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="tableData"
        border
        stripe
        :max-height="600"
        :empty-text="statusFilter === 'failed' ? t('callHistory.list.emptyFailedHint') : undefined"
        @sort-change="handleSortChange"
      >
        <el-table-column :label="t('callHistory.common.time')" prop="createdAt" width="168" sortable="custom">
          <template #default="scope">
            {{ formatTime(scope.row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="Trace ID" prop="traceId" width="128" show-overflow-tooltip>
          <template #default="scope">
            <el-link type="primary" @click="handleTraceIdClick(scope.row.traceId)">
              {{ scope.row.traceId?.substring(0, 8) }}...
            </el-link>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.modelName')" prop="modelName" min-width="148" show-overflow-tooltip />
        <el-table-column :label="t('callHistory.common.serviceType')" prop="serviceType" width="100">
          <template #default="scope">
            <el-link
              v-if="scope.row.serviceType"
              type="primary"
              underline="never"
              @click.stop="router.push({ name: 'models', query: { serviceType: scope.row.serviceType } })"
            >
              <el-tag size="small">{{ getServiceTypeLabel(scope.row.serviceType) }}</el-tag>
            </el-link>
            <el-tag v-else size="small">{{ getServiceTypeLabel(scope.row.serviceType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.provider')" prop="provider" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ getAdapterDisplay(row.provider) }}</template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.httpStatus')" prop="httpStatusCode" width="100" align="center" sortable="custom">
          <template #default="scope">
            <el-tag :type="getStatusType(scope.row.httpStatusCode)" size="small">
              {{ scope.row.httpStatusCode || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.token')" prop="totalTokens" width="100" align="right" sortable="custom">
          <template #default="scope">
            {{ formatNumber(scope.row.totalTokens) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.responseTimeMs')" prop="responseTimeMs" width="112" align="right" sortable="custom">
          <template #default="scope">
            <el-tag :type="getResponseTimeType(scope.row.responseTimeMs)" size="small">
              {{ scope.row.responseTimeMs?.toFixed(0) || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.status')" width="80" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.isSuccess ? 'success' : 'danger'" size="small">
              {{ scope.row.isSuccess ? t('callHistory.common.success') : t('callHistory.common.failed') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.failureExplain.title')" min-width="220">
          <template #default="scope">
            <FailureExplainer
              v-if="!scope.row.isSuccess"
              :status-code="scope.row.httpStatusCode"
              :error-code="scope.row.errorCode"
              :rate-limited="scope.row.rateLimited"
              :circuit-broken="scope.row.circuitBroken"
              :response-time-ms="scope.row.responseTimeMs"
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.action')" width="80" fixed="right" align="center">
          <template #default="scope">
            <el-button type="primary" link size="small" @click="handleDetail(scope.row)">
              {{ t('callHistory.common.details') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <template #footer>
      <el-pagination
        v-if="totalCount > 0"
        :current-page="(queryForm.page || 0) + 1"
        :page-sizes="[20, 50, 100]"
        :page-size="queryForm.size || 20"
        :total="totalCount"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </template>
  </PageSkeleton>

    <!-- 详情抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      :title="t('callHistory.list.detailsTitle')"
      size="600px"
    >
      <template v-if="selectedRecord">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="Trace ID" :span="2">
            <el-tag>{{ selectedRecord.traceId }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.requestId')" :span="2">
            {{ selectedRecord.requestId }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.common.time')" :span="2">
            {{ formatTime(selectedRecord.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.requestMethod')">
            {{ selectedRecord.requestMethod }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.requestPath')" :span="2">
            {{ selectedRecord.requestPath }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.common.modelName')">
            {{ selectedRecord.modelName }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.common.serviceType')">
            <el-link
              v-if="selectedRecord.serviceType"
              type="primary"
              underline="never"
              @click="router.push({ path: '/settings', query: { tab: 'modelConfig', sub: 'modelCategories', serviceType: selectedRecord.serviceType } })"
            >
              {{ getServiceTypeLabel(selectedRecord.serviceType) }}
            </el-link>
            <span v-else>{{ getServiceTypeLabel(selectedRecord.serviceType) }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.common.provider')">
            {{ getAdapterDisplay(selectedRecord.provider) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.instanceName')">
            <el-link
              v-if="selectedRecord.instanceName && selectedRecord.serviceType"
              type="primary"
              underline="never"
              @click="router.push({ name: 'models', query: { serviceType: selectedRecord.serviceType } })"
            >
              {{ selectedRecord.instanceName }}
            </el-link>
            <span v-else>{{ selectedRecord.instanceName || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.common.httpStatusCode')">
            <el-tag :type="getStatusType(selectedRecord.httpStatusCode)">
              {{ selectedRecord.httpStatusCode || '-' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.responseTime')">
            {{ selectedRecord.responseTimeMs?.toFixed(0) || '-' }} ms
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.common.callStatus')">
            <el-tag :type="selectedRecord.isSuccess ? 'success' : 'danger'">
              {{ selectedRecord.isSuccess ? t('callHistory.common.success') : t('callHistory.common.failed') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.errorCode')">
            {{ selectedRecord.errorCode || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.common.errorMessage')" :span="2">
            <span v-if="selectedRecord.errorMessage" class="error-message">
              {{ selectedRecord.errorMessage }}
            </span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.tokenStatsTitle')" :span="2">
            <el-tag type="info" size="small">{{ t('callHistory.list.tokenStats.input', { value: selectedRecord.promptTokens }) }}</el-tag>
            <el-tag type="success" size="small">{{ t('callHistory.list.tokenStats.output', { value: selectedRecord.completionTokens }) }}</el-tag>
            <el-tag type="warning" size="small">{{ t('callHistory.list.tokenStats.total', { value: selectedRecord.totalTokens }) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.rateLimitedStatus')">
            <el-tag :type="selectedRecord.rateLimited ? 'danger' : 'info'" size="small">
              {{ selectedRecord.rateLimited ? t('callHistory.list.rateLimited') : t('callHistory.common.normal') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.circuitBreakStatus')">
            <el-tag :type="selectedRecord.circuitBroken ? 'danger' : 'info'" size="small">
              {{ selectedRecord.circuitBroken ? t('callHistory.list.circuitBroken') : t('callHistory.common.normal') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.clientIp')">
            {{ selectedRecord.clientIp || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="User Agent">
            {{ selectedRecord.userAgent || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="API Key" :span="2">
            {{ selectedRecord.apiKeyId || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 请求/响应摘要 -->
        <el-card v-if="selectedRecord.requestBodySummary || selectedRecord.responseBodySummary"
                 shadow="never" style="margin-top: 16px;">
          <template #header>
            <span>{{ t('callHistory.list.bodySummaryTitle') }}</span>
          </template>
          <div v-if="selectedRecord.requestBodySummary" class="body-summary">
            <div class="summary-label">{{ t('callHistory.list.requestBodySummary') }}</div>
            <pre class="summary-content">{{ selectedRecord.requestBodySummary }}</pre>
          </div>
          <div v-if="selectedRecord.responseBodySummary" class="body-summary">
            <div class="summary-label">{{ t('callHistory.list.responseBodySummary') }}</div>
            <pre class="summary-content">{{ selectedRecord.responseBodySummary }}</pre>
          </div>
        </el-card>
      </template>
    </el-drawer>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { usePersistedState } from '@/composables/usePersistedState'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { queryCallHistory, getCallHistoryConfig, updateCallHistoryConfig } from '@/api/callHistory'
import { getAdapterList } from '@/api/adapter'
import type { CallHistoryConfig } from '@/api/callHistory'
import type { ApiCallHistoryRecord, CallHistoryQuery } from '@/types/callHistory'
import PageSkeleton from '@/components/PageSkeleton.vue'
import FailureExplainer from '@/components/common/FailureExplainer.vue'

const router = useRouter()
const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('config:callhistory:write')

// 查询表单
const queryForm = reactive<CallHistoryQuery>({
  startTime: undefined,
  endTime: undefined,
  modelName: undefined,
  serviceType: undefined,
  isSuccess: undefined,
  httpStatusCode: undefined,
  page: 0,
  size: 20,
  sortField: 'createdAt',
  sortDirection: 'desc'
})

// 日期范围
const dateRange = ref<[string, string] | null>(null)

/* U7-1 修订（2026-10-23）：默认改「全部」。原默认「仅失败」基于运营排障主诉求，
   但普通用户测完对话进来看「0 条」会误以为记录没落库（实测：全成功时仅失败=0、全部=49）。
   排障主诉求由空态引导语承接：仅失败筛选为空时提示切换「全部」。 */
const statusFilter = ref<'all' | 'failed' | 'success'>('all')

// U10-5：状态筛选持久化，下次进入本页自动恢复。
// version 升到 2：旧缓存里持久化的默认值 'failed' 必须失效，否则改默认不生效。
usePersistedState('callHistory.status', statusFilter, 2)

function applyStatusFilter() {
  queryForm.isSuccess =
    statusFilter.value === 'all' ? undefined : statusFilter.value === 'failed' ? false : true
}

// 表格数据
const tableData = ref<ApiCallHistoryRecord[]>([])
const loading = ref(false)
const totalCount = ref(0)

// 详情抽屉
const drawerVisible = ref(false)
const selectedRecord = ref<ApiCallHistoryRecord | null>(null)

// ---- v2.9.2 记录治理 settings ----
const configLoading = ref(false)
const configSaving = ref(false)
const recordLevel = ref<string>('METADATA_ONLY')
const configLoaded = ref(false)

const loadConfig = async () => {
  configLoading.value = true
  try {
    const cfg: CallHistoryConfig = await getCallHistoryConfig()
    recordLevel.value = cfg.recordLevel || 'METADATA_ONLY'
    configLoaded.value = true
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

// 格式化数字
const formatNumber = (num?: number): string => {
  if (!num && num !== 0) return '0'
  return num.toLocaleString()
}

// 格式化时间
const formatTime = (time?: string) => {
  if (!time) return '-'
  return time.replace('T', ' ').substring(0, 19)
}

// 服务类型 key -> i18n 键名映射（保持原有顺序）
const serviceTypeKeyMap: Record<string, string> = {
  chat: 'chat',
  embedding: 'embedding',
  rerank: 'rerank',
  tts: 'tts',
  stt: 'stt',
  imgGen: 'imgGen',
  imgEdit: 'imgEdit'
}

// 获取服务类型标签（v2.10.4: 文案收敛到顶层 serviceTypes.*，未知类型回退原值）
const getServiceTypeLabel = (type?: string) => {
  if (!type) return t('callHistory.common.unknown')
  return serviceTypeKeyMap[type] ? t(`serviceTypes.${serviceTypeKeyMap[type]}`) : type
}

// 获取 HTTP 状态码标签类型
const getStatusType = (code?: number) => {
  if (!code) return 'info'
  if (code >= 200 && code < 300) return 'success'
  if (code >= 400 && code < 500) return 'warning'
  if (code >= 500) return 'danger'
  return 'info'
}

// 获取响应时间标签类型
const getResponseTimeType = (ms?: number) => {
  if (!ms) return 'info'
  if (ms < 500) return 'success'
  if (ms < 1000) return 'warning'
  return 'danger'
}

// 加载数据
const loadData = async () => {
  loading.value = true
  try {
    // 处理日期范围
    if (dateRange.value && dateRange.value.length === 2) {
      queryForm.startTime = dateRange.value[0].replace(' ', 'T')
      queryForm.endTime = dateRange.value[1].replace(' ', 'T')
    } else {
      queryForm.startTime = undefined
      queryForm.endTime = undefined
    }
    applyStatusFilter()

    const result = await queryCallHistory(queryForm)
    tableData.value = result.content || []
    totalCount.value = result.totalElements || 0
  } catch (error: any) {
    console.error('加载调用历史失败:', error)
    ElMessage.error(t('callHistory.list.loadFailed', { message: error.message || t('callHistory.common.unknownError') }))
  } finally {
    loading.value = false
  }
}

// 搜索
const handleSearch = () => {
  queryForm.page = 0
  loadData()
}

// 重置
const handleReset = () => {
  dateRange.value = null
  queryForm.startTime = undefined
  queryForm.endTime = undefined
  queryForm.modelName = undefined
  queryForm.serviceType = undefined
  statusFilter.value = 'all'
  queryForm.isSuccess = undefined
  queryForm.httpStatusCode = undefined
  queryForm.page = 0
  queryForm.size = 20
  queryForm.sortField = 'createdAt'
  queryForm.sortDirection = 'desc'
  loadData()
}

// 排序变化
const handleSortChange = ({ prop, order }: { prop: string; order: string }) => {
  if (prop) {
    queryForm.sortField = prop
    queryForm.sortDirection = order === 'ascending' ? 'asc' : 'desc'
  } else {
    queryForm.sortField = 'createdAt'
    queryForm.sortDirection = 'desc'
  }
  loadData()
}

// 分页大小变化
const handleSizeChange = (size: number) => {
  queryForm.size = size
  queryForm.page = 0
  loadData()
}

// 页码变化
const handleCurrentChange = (page: number) => {
  queryForm.page = page - 1
  loadData()
}

// Trace ID 点击
const handleTraceIdClick = async (traceId: string) => {
  if (!traceId) return
  try {
    // 这里可以通过 API 查询该 traceId 的所有调用
    // 暂时简单提示
    ElMessage.info(`Trace ID: ${traceId}`)
  } catch (error: any) {
    console.error('查询 Trace ID 失败:', error)
  }
}

// 查看详情
const handleDetail = (record: ApiCallHistoryRecord) => {
  selectedRecord.value = record
  drawerVisible.value = true
}

// 初始化
onMounted(() => {
  loadData()
  loadConfig()
  loadAdapterDialects()
})

// ---- 适配器方言显示 ----
// 记录里的 provider 字段实际存的是 adapterType（适配器名，如 normal/claude），
// 展示层转成适配器方言（如「OpenAI 标准协议（OpenAI 兼容）」），用户才看得懂。
const adapterDialectMap = ref<Record<string, string>>({})
const loadAdapterDialects = async () => {
  try {
    const res = await getAdapterList()
    const map: Record<string, string> = {}
    ;(res.data?.data || []).forEach((a: { name?: string; dialect?: string }) => {
      if (a?.name) map[a.name] = a.dialect || a.name
    })
    adapterDialectMap.value = map
  } catch {
    // 加载失败时回退显示原始适配器名，不影响列表
  }
}
const getAdapterDisplay = (name?: string) => {
  if (!name) return '-'
  return adapterDialectMap.value[name] || name
}
</script>

<style scoped>
.settings-card {
  margin-bottom: 16px;
}

.settings-form {
  margin-bottom: 12px;
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-title {
  font-size: 16px;
  font-weight: bold;
}

.total-count {
  font-size: 14px;
  color: var(--ja-text-secondary);
}

.error-message {
  color: var(--el-color-danger);
  font-size: 12px;
  word-break: break-all;
}

.body-summary {
  margin-bottom: 12px;
}

.body-summary:last-child {
  margin-bottom: 0;
}

.summary-label {
  font-weight: bold;
  color: var(--ja-text-regular);
  margin-bottom: 4px;
}

.summary-content {
  background-color: var(--el-fill-color-light);
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
  max-height: 200px;
  overflow-y: auto;
  margin: 0;
}
</style>
