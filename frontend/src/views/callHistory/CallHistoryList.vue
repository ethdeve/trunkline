<template>
  <PageSkeleton :title="t('callHistory.list.pageTitle')">
    <template #toolbar>
      <!--
        查询筛选区（v3.5 Trunkline UI 改造，按原型收敛为「一个搜索框 + 状态 + 时间范围」）
        - 搜索框：一次搜员工 / 虚拟模型 / Trace ID / 模型名（后端按 OR 匹配多个字段）
        - 状态：全部 / 成功 / 失败 / 限流
        - 时间范围：快捷选项（最近 1 小时 / 24 小时 / 7 天）+ 自定义
        原先 6 个独立筛选框（时间/虚拟模型/员工/模型/服务类型/状态/HTTP 码）对普通员工过重。
      -->
      <div class="observability-filter">
        <el-input
          v-model="keyword"
          :placeholder="t('callHistory.list.searchPlaceholder')"
          clearable
          class="observability-filter__search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select v-model="statusFilter" class="observability-filter__status" @change="handleSearch">
          <el-option :label="t('callHistory.list.allPlaceholder')" value="all" />
          <el-option :label="t('callHistory.common.success')" value="success" />
          <el-option :label="t('callHistory.common.failed')" value="failed" />
          <el-option :label="t('callHistory.list.rateLimitedFilter')" value="rateLimited" />
        </el-select>

        <el-select v-model="timeRange" class="observability-filter__time" @change="handleTimeRangeChange">
          <el-option :label="t('callHistory.list.last1Hour')" value="1h" />
          <el-option :label="t('callHistory.list.last24Hours')" value="24h" />
          <el-option :label="t('callHistory.list.last7Days')" value="7d" />
          <el-option :label="t('callHistory.list.customRange')" value="custom" />
        </el-select>

        <el-date-picker
          v-if="timeRange === 'custom'"
          v-model="dateRange"
          type="datetimerange"
          :range-separator="t('callHistory.common.rangeSeparator')"
          :start-placeholder="t('callHistory.common.startTime')"
          :end-placeholder="t('callHistory.common.endTime')"
          value-format="YYYY-MM-DD HH:mm:ss"
          class="observability-filter__picker"
          @change="handleSearch"
        />

        <el-button type="primary" icon="Search" @click="handleSearch">{{ t('callHistory.common.query') }}</el-button>
        <el-button icon="Refresh" @click="handleReset">{{ t('callHistory.common.reset') }}</el-button>
        <!-- 任务 58：按当前筛选条件导出（CSV / JSON，元数据口径） -->
        <el-dropdown trigger="click" @command="handleExport">
          <el-button :loading="exporting" icon="Download">
            {{ t('callHistory.list.export') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="csv">{{ t('v.ov.t010') }}</el-dropdown-item>
              <el-dropdown-item command="json">JSON</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </template>

    <!-- 数据表格 -->
    <el-card shadow="hover">
      <template #header>
        <div class="table-header">
          <span class="chart-title">{{ t('callHistory.list.listTitle') }}</span>
          <span class="total-count">{{ t('callHistory.list.totalRecords', { count: totalCount }) }}</span>
        </div>
      </template>

      <el-table
        ref="historyTableRef"
        v-loading="loading"
        :data="tableData"
        border
        stripe
        :max-height="historyMaxHeight"
        :empty-text="statusFilter === 'failed' ? t('callHistory.list.emptyFailedHint') : undefined"
        @sort-change="handleSortChange"
      >
        <el-table-column :label="t('callHistory.common.time')" prop="createdAt" width="110" sortable="custom">
          <template #default="scope">
            {{ formatTime(scope.row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="Trace ID" prop="traceId" width="118" show-overflow-tooltip>
          <template #default="scope">
            <el-link v-if="scope.row.traceId" type="primary" class="trace-id" @click="handleTraceIdClick(scope.row.traceId)">
              {{ scope.row.traceId.substring(0, 8) }}…
            </el-link>
            <span v-else class="cell-unknown">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.list.requestedVirtualModelLabel')" prop="virtualModel" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.virtualModel" class="model-chip">
              <i></i>{{ row.virtualModel }}
            </span>
            <span v-else class="cell-unknown">{{ t('callHistory.list.unassignedVirtualModel') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.list.routedInstanceLabel')" prop="instanceName" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.instanceName">{{ row.instanceName }}</span>
            <span v-else class="cell-unknown">-</span>
            <span v-if="routeHint(row)" class="route-hint">{{ routeHint(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.list.callerLabel')" prop="userId" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.userId">{{ row.userId }}</span>
            <span v-else class="cell-unknown">{{ t('callHistory.list.unknownUser') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.duration')" prop="responseTimeMs" width="88" align="right" sortable="custom">
          <template #default="scope">
            <span :class="['duration-cell', durationClass(scope.row.responseTimeMs)]">
              {{ formatDuration(scope.row.responseTimeMs) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('callHistory.common.status')" width="104" align="center" fixed="right">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row)" size="small">
              {{ statusText(scope.row) }}
            </el-tag>
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
        :page-sizes="[10, 20, 50, 100]"
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
      size="640px"
    >
      <template v-if="selectedRecord">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="Trace ID" :span="2">
            <el-tag class="trace-id-tag" @click="handleTraceIdClick(selectedRecord.traceId)">{{ selectedRecord.traceId }}</el-tag>
            <span class="trace-id-hint">{{ t('callHistory.list.traceDetailHint') }}</span>
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
            <!-- 旧 /models 路由已下线（无 name），改用 path 直达「厂商与模型库 · 已接入模型」tab -->
            <el-link
              v-if="selectedRecord.instanceName && selectedRecord.serviceType"
              type="primary"
              underline="never"
              @click="router.push({ path: '/providers', query: { tab: 'models', serviceType: selectedRecord.serviceType } })"
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
            {{ formatDuration(selectedRecord.responseTimeMs) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.common.callStatus')">
            <el-tag :type="selectedRecord.isSuccess ? 'success' : 'danger'">
              {{ selectedRecord.isSuccess ? t('callHistory.common.success') : t('callHistory.common.failed') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('callHistory.list.errorCode')">
            {{ selectedRecord.errorCode || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('failureExplain.title')" :span="2">
            <FailureExplainer
              v-if="!selectedRecord.isSuccess"
              :status-code="selectedRecord.httpStatusCode"
              :error-code="selectedRecord.errorCode"
              :rate-limited="selectedRecord.rateLimited"
              :circuit-broken="selectedRecord.circuitBroken"
              :response-time-ms="selectedRecord.responseTimeMs"
            />
            <span v-else>-</span>
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

    <!-- 追踪详情抽屉：从调用历史的 Trace ID 跳入链路追踪 -->
    <el-drawer
      v-model="traceDrawerVisible"
      :title="t('tracing.dashboard.drawerTitle')"
      direction="rtl"
      size="60%"
    >
      <TraceDetail v-if="selectedTraceId" :trace-id="selectedTraceId" />
    </el-drawer>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePersistedState } from '@/composables/usePersistedState'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { ElMessage } from 'element-plus'
import { Search, Refresh as _Refresh, ArrowDown } from '@element-plus/icons-vue'
import { queryCallHistory, exportCallHistory } from '@/api/callHistory'
import { getAdapterList } from '@/api/adapter'
import type { ApiCallHistoryRecord, CallHistoryQuery } from '@/types/callHistory'
import PageSkeleton from '@/components/PageSkeleton.vue'
import FailureExplainer from '@/components/common/FailureExplainer.vue'
import TraceDetail from '@/views/tracing/components/TraceDetail.vue'

const router = useRouter()
const { t } = useI18n()

/**
 * 初始状态筛选（v3.5 Trunkline：「调用观测」页的「只看失败」视图）。
 *
 * <p>由「调用观测」页传入：值为 {@code failed} 时列表直接进入「只看失败」视角，
 * 等价于把「状态」筛选切到失败——异常调用即失败调用，不必再开独立的异常 tab。</p>
 */
const props = withDefaults(
  defineProps<{
    initialStatus?: 'all' | 'failed' | 'success'
  }>(),
  { initialStatus: 'all' }
)

// 查询表单
const queryForm = reactive<CallHistoryQuery>({
  startTime: undefined,
  endTime: undefined,
  modelName: undefined,
  virtualModel: undefined,
  userId: undefined,
  serviceType: undefined,
  isSuccess: undefined,
  rateLimited: undefined,
  httpStatusCode: undefined,
  keyword: undefined,
  page: 0,
  size: 20,
  sortField: 'createdAt',
  sortDirection: 'desc'
})

// 日期范围（自定义时间范围时生效）
const dateRange = ref<[string, string] | null>(null)

/**
 * 时间范围快捷选项（v3.5 Trunkline UI 改造：按原型收敛为「最近 1 小时 / 24 小时 / 7 天 / 自定义」）。
 *
 * <p>选「自定义」时才展开日期区间选择器；其余按快捷窗口实时计算 start/end。</p>
 */
type TimeRangeKey = '1h' | '24h' | '7d' | 'custom'
const timeRange = ref<TimeRangeKey>('24h')

/**
 * 关键字搜索（v3.5 Trunkline UI 改造：一个框搜多个维度）。
 *
 * <p>后端按「员工 / 虚拟模型 / Trace ID / 模型名」任一命中即返回（OR 匹配），
 * 避免为每个维度各开一个输入框——原型的定位是「搜人名、虚拟模型、Trace ID」。</p>
 */
const keyword = ref('')

/* U7-1 修订（2026-10-23）：默认改「全部」。原默认「仅失败」基于运营排障主诉求，
   但普通用户测完对话进来看「0 条」会误以为记录没落库（实测：全成功时仅失败=0、全部=49）。
   排障主诉求由空态引导语承接：仅失败筛选为空时提示切换「全部」。
   v3.5：新增「限流」态（rateLimited 标记），对应原型状态筛选的第四项。 */
const statusFilter = ref<'all' | 'failed' | 'success' | 'rateLimited'>('all')

// U10-5：状态筛选持久化，下次进入本页自动恢复。
// version 升到 2：旧缓存里持久化的默认值 'failed' 必须失效，否则改默认不生效。
usePersistedState('callHistory.status', statusFilter, 2)

/**
 * 把状态筛选翻译成后端查询条件。
 *
 * <p>「限流」不是独立的 isSuccess 取值，而是 {@code rateLimited=true} 的子集——
 * 限流的调用 isSuccess 仍是 true（网关自己拒的，没到上游），故不能简单映射成
 * isSuccess=false，否则会漏掉限流行。</p>
 */
function applyStatusFilter() {
  switch (statusFilter.value) {
    case 'failed':
      queryForm.isSuccess = false
      queryForm.rateLimited = false
      break
    case 'success':
      queryForm.isSuccess = true
      queryForm.rateLimited = false
      break
    case 'rateLimited':
      queryForm.isSuccess = undefined
      queryForm.rateLimited = true
      break
    default:
      queryForm.isSuccess = undefined
      queryForm.rateLimited = false
  }
}

/** 快捷时间窗口 → 起止时间（ISO 字符串，后端按 ISO-8601 解析） */
function applyTimeRange() {
  if (timeRange.value === 'custom') {
    if (dateRange.value) {
      queryForm.startTime = dateRange.value[0]
      queryForm.endTime = dateRange.value[1]
    }
    return
  }
  const hours = timeRange.value === '1h' ? 1 : timeRange.value === '24h' ? 24 : 24 * 7
  const end = new Date()
  const start = new Date(end.getTime() - hours * 3600 * 1000)
  queryForm.startTime = formatIso(start)
  queryForm.endTime = formatIso(end)
}

/** Date → 'YYYY-MM-DD HH:mm:ss'（与后端解析格式一致） */
function formatIso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/**
 * 外部（「调用观测」页的「只看失败」）切换视图时，同步本页的状态筛选。
 *
 * <p>只在值确实变化时改写并重新加载，避免与用户在本页手动切换状态筛选互相打架
 * （本页手动切换不会回写 prop，故不会被此监听覆盖）。</p>
 */
watch(
  () => props.initialStatus,
  value => {
    if (value && value !== statusFilter.value) {
      statusFilter.value = value
      applyStatusFilter()
      queryForm.page = 0
      loadData()
    }
  }
)

// 表格数据
const tableData = ref<ApiCallHistoryRecord[]>([])
const loading = ref(false)
const totalCount = ref(0)

// 主列表表格内部滚动（自适应 max-height）
const historyTableRef = ref()
const { maxHeight: historyMaxHeight } = useTableMaxHeight(historyTableRef)

// 详情抽屉
const drawerVisible = ref(false)
const selectedRecord = ref<ApiCallHistoryRecord | null>(null)

// 记录治理设置（记录级别）已移至「系统 → 审计与版本 → 全局配置」，
// 本页只管看调用记录，不再承载配置项。

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

/**
 * 耗时格式化（v3.5 Trunkline UI 改造：按原型用人话单位）。
 *
 * <p>原型用的是 {@code 1.2s} / {@code 0.3s} / {@code 12ms} 这类写法，
 * 比裸毫秒数（1204）更容易一眼看出快慢。超过 1 分钟用分钟。</p>
 */
const formatDuration = (ms?: number): string => {
  if (ms === null || ms === undefined) return '-'
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60_000).toFixed(1)}min`
}

/** 耗时的语义色（配合原型「慢调用一眼可见」的意图） */
const durationClass = (ms?: number): string => {
  if (!ms) return ''
  if (ms < 1000) return 'duration-cell--fast'
  if (ms < 5000) return 'duration-cell--normal'
  return 'duration-cell--slow'
}

/**
 * 路由说明（v3.5 Trunkline UI 改造：按原型在「实际路由到」后加括号说明）。
 *
 * <p>说明由现有信号推导，<b>不需要后端新增字段</b>：</p>
 * <ul>
 *   <li>限流 → 「被限流」（网关自己拒了，请求没到上游）；</li>
 *   <li>熔断 → 「被熔断」（实例不健康，临时摘除）；</li>
 *   <li>失败 → 「调用失败」；</li>
 *   <li>成功 → 「直连成功」。</li>
 * </ul>
 *
 * <p>原型里的「GPT-4o 超时，自动切备用」需要「首选实例 + 重试次数」才能还原，
 * 后端目前未记录，故此处不臆造该文案。</p>
 */
const routeHint = (row: ApiCallHistoryRecord): string => {
  if (row.rateLimited) return t('callHistory.list.routeHintRateLimited')
  if (row.circuitBroken) return t('callHistory.list.routeHintCircuitBroken')
  if (!row.isSuccess) return t('callHistory.list.routeHintFailed')
  return t('callHistory.list.routeHintDirect')
}

/** 状态标签文案：限流单列（原型状态列有「429 限流」这一态） */
const statusText = (row: ApiCallHistoryRecord): string => {
  if (row.rateLimited) return t('callHistory.list.statusRateLimited')
  if (!row.isSuccess) return t('callHistory.common.failed')
  return t('callHistory.common.success')
}

/** 状态标签类型 */
const statusTagType = (row: ApiCallHistoryRecord): 'success' | 'danger' | 'warning' | 'info' => {
  if (row.rateLimited) return 'warning'
  if (!row.isSuccess) return 'danger'
  return 'success'
}

// 加载数据
const loadData = async () => {
  loading.value = true
  try {
    applyTimeRange()
    applyStatusFilter()
    queryForm.keyword = keyword.value?.trim() || undefined

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

// ---- 任务 58：调用记录导出 ----
const exporting = ref(false)

/** 按当前筛选条件导出（与列表所见一致；封顶 10000 行由服务端控制） */
const handleExport = async (format: 'csv' | 'json') => {
  exporting.value = true
  try {
    applyTimeRange()
    applyStatusFilter()
    queryForm.keyword = keyword.value?.trim() || undefined

    await exportCallHistory(queryForm, format, `call-history.${format}`)
    ElMessage.success(t('callHistory.list.exportSuccess'))
  } catch (error: any) {
    console.error('导出调用历史失败:', error)
    ElMessage.error(t('callHistory.list.exportFailed', { message: error.message || t('callHistory.common.unknownError') }))
  } finally {
    exporting.value = false
  }
}

/** 切换时间范围快捷项（自定义时由日期选择器再触发一次搜索） */
const handleTimeRangeChange = () => {
  if (timeRange.value !== 'custom') {
    dateRange.value = null
    handleSearch()
  }
}

// 重置
const handleReset = () => {
  dateRange.value = null
  keyword.value = ''
  timeRange.value = '24h'
  queryForm.startTime = undefined
  queryForm.endTime = undefined
  queryForm.modelName = undefined
  queryForm.virtualModel = undefined
  queryForm.userId = undefined
  queryForm.serviceType = undefined
  statusFilter.value = 'all'
  queryForm.isSuccess = undefined
  queryForm.rateLimited = undefined
  queryForm.httpStatusCode = undefined
  queryForm.keyword = undefined
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

// Trace ID 点击：打开链路追踪详情抽屉（复用链路追踪的 TraceDetail 组件）
const traceDrawerVisible = ref(false)
const selectedTraceId = ref('')
const handleTraceIdClick = (traceId: string) => {
  if (!traceId) return
  selectedTraceId.value = traceId
  traceDrawerVisible.value = true
}

// 查看详情
const handleDetail = (record: ApiCallHistoryRecord) => {
  selectedRecord.value = record
  drawerVisible.value = true
}

// 初始化
onMounted(() => {
  // 外部指定的初始状态优先于持久化值（「调用观测」页的「只看失败」视图）
  if (props.initialStatus && props.initialStatus !== statusFilter.value) {
    statusFilter.value = props.initialStatus
    applyStatusFilter()
  }
  loadData()
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
/* 未归属（员工/虚拟模型为空）时的占位样式——与真实值区分，一眼看出是「没数据」而非「查不到」 */
.cell-unknown {
  color: var(--ja-text-placeholder);
  font-style: italic;
}

/* ---- v3.5 Trunkline UI 改造：按原型收敛的筛选区与列表样式 ---- */

/* 筛选区：搜索框占主要宽度，状态与时间范围为定宽下拉 */
.observability-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.observability-filter__search {
  flex: 1;
  min-width: 240px;
}

.observability-filter__status {
  width: 120px;
}

.observability-filter__time {
  width: 130px;
}

.observability-filter__picker {
  width: 360px;
}

/* 虚拟模型 chip：原型用「圆点 + 药丸」呈现，一眼区分于普通文本 */
.model-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--ja-bg-secondary, var(--ja-main-bg));
  color: var(--ja-text-secondary);
}

.model-chip i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ja-primary);
}

/* Trace ID：等宽字体，便于肉眼比对 */
.trace-id {
  font-family: var(--ja-font-mono);
  font-size: 11.5px;
}

/* 详情抽屉里的 Trace ID：可点击跳转追踪详情 */
.trace-id-tag {
  cursor: pointer;
}

.trace-id-hint {
  margin-left: 8px;
  font-size: 11px;
  color: var(--ja-text-secondary);
}

/* 路由说明：紧跟实例名，弱化显示但可读 */
.route-hint {
  margin-left: 6px;
  font-size: 11px;
  color: var(--ja-text-secondary);
}

/* 耗时：人话单位 + 语义色（慢调用一眼可见） */
.duration-cell {
  font-variant-numeric: tabular-nums;
}

.duration-cell--fast {
  color: var(--ja-text-primary);
}

.duration-cell--normal {
  color: var(--ja-text-regular, var(--ja-text-primary));
}

.duration-cell--slow {
  color: var(--ja-danger, #f56c6c);
  font-weight: 600;
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
