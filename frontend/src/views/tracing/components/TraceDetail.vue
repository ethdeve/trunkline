<template>
  <div class="trace-detail" v-loading="loading">
    <template v-if="traceChain">
      <!-- 追踪概要（用户视角精简版） -->
      <el-card class="summary-card" shadow="never">
        <div class="summary-row">
          <div class="summary-item">
            <div class="summary-item__label">{{ t('tracing.traceDetail.traceId') }}</div>
            <div class="summary-item__value">
              <div class="trace-id-cell">
                <code>{{ shortTraceId }}</code>
                <el-button text type="primary" size="small" @click="copyTraceId">
                  <el-icon><CopyDocument /></el-icon>
                </el-button>
              </div>
            </div>
          </div>
          <div class="summary-item">
            <div class="summary-item__label">{{ t('tracing.traceDetail.status') }}</div>
            <div class="summary-item__value">
              <el-tag :type="hasError ? 'danger' : 'success'">
                {{ hasError ? t('tracing.traceDetail.error') : t('tracing.traceDetail.success') }}
              </el-tag>
            </div>
          </div>
          <div class="summary-item">
            <div class="summary-item__label">{{ t('tracing.traceDetail.totalDuration') }}</div>
            <div class="summary-item__value" :class="{ 'text-danger': totalDurationMs > 1000 }">
              {{ formatDuration(totalDurationMs) }}
            </div>
          </div>
          <div class="summary-item">
            <div class="summary-item__label">{{ t('tracing.traceDetail.startTime') }}</div>
            <div class="summary-item__value">{{ formatTime(traceChain.startTime) }}</div>
          </div>
          <div class="summary-item">
            <div class="summary-item__label">{{ t('tracing.traceDetail.spanCount') }}</div>
            <div class="summary-item__value">{{ sortedSpans.length }}</div>
          </div>
        </div>
      </el-card>

      <!-- 分步流程：这次请求经历了什么 -->
      <el-card class="steps-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>{{ t('tracing.traceDetail.flowTitle') }}</span>
            <el-button size="small" @click="exportTrace">
              <el-icon><Download /></el-icon>
              {{ t('tracing.traceDetail.export') }}
            </el-button>
          </div>
        </template>
        <div class="steps-flow">
          <div
            v-for="(step, index) in steps"
            :key="step.spanId"
            class="step-item"
          >
            <!-- 左侧步骤轨道 -->
            <div class="step-rail">
              <div class="step-node" :class="{ 'step-node--error': step.error }">
                <el-icon v-if="step.error"><CircleCloseFilled /></el-icon>
                <el-icon v-else><CircleCheckFilled /></el-icon>
              </div>
              <div v-if="index < steps.length - 1" class="step-line" />
            </div>
            <!-- 步骤内容 -->
            <div class="step-body">
              <div class="step-main">
                <div class="step-text">
                  <span class="step-name">{{ step.name }}</span>
                  <span v-if="step.subtitle" class="step-subtitle">{{ step.subtitle }}</span>
                </div>
                <div class="step-meta">
                  <span class="step-duration" :class="{ 'text-danger': step.duration > 1000 }">
                    {{ formatDuration(step.duration) }}
                  </span>
                </div>
              </div>
              <div class="step-bar-track" :title="`${t('tracing.traceDetail.timeShare')} ${step.percent}%`">
                <div class="step-bar" :class="{ 'step-bar--error': step.error }" :style="{ width: step.percent + '%' }" />
              </div>
            </div>
          </div>
        </div>
      </el-card>

      <!-- 技术详情（调试用，默认折叠） -->
      <el-collapse class="tech-collapse">
        <el-collapse-item :title="t('tracing.traceDetail.techTitle')" name="tech">
          <div class="tech-inner">
            <div class="tech-search">
              <el-input
                v-model="spanSearch"
                :placeholder="t('tracing.traceDetail.searchPlaceholder')"
                clearable
                style="width: 200px"
              >
                <template #prefix>
                  <el-icon><Search /></el-icon>
                </template>
              </el-input>
            </div>
            <el-table :data="filteredSpans" style="width: 100%" size="small" row-key="spanId">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="span-attributes" v-if="row.attributes">
                    <h5>{{ t('tracing.traceDetail.attributes') }}</h5>
                    <el-descriptions :column="2" border size="small">
                      <el-descriptions-item
                        v-for="(value, key) in row.attributes"
                        :key="key"
                        :label="attrLabel(String(key))"
                      >
                        {{ attrValue(String(key), value) }}
                      </el-descriptions-item>
                    </el-descriptions>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="operationName" :label="t('tracing.traceDetail.operation')" min-width="200">
                <template #default="{ row }">
                  <div class="operation-cell">
                    <el-icon :size="14" :color="row.error ? 'var(--ja-danger)' : 'var(--ja-success)'">
                      <component :is="row.error ? CircleCloseFilled : CircleCheckFilled" />
                    </el-icon>
                    <div class="operation-text">
                      <span>{{ friendlyStep(row).name }}</span>
                      <span
                        v-if="friendlyStep(row).name !== row.operationName"
                        class="operation-raw"
                      >{{ row.operationName }}</span>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="duration" :label="t('tracing.traceDetail.duration')" width="100" sortable>
                <template #default="{ row }">
                  <span :class="getDurationClass(row.duration)">
                    {{ formatDuration(row.duration) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column prop="startTime" :label="t('tracing.traceDetail.startTime')" width="140">
                <template #default="{ row }">
                  {{ formatTime(row.startTime) }}
                </template>
              </el-table-column>
              <el-table-column prop="statusCode" :label="t('tracing.traceDetail.statusCode')" width="80">
                <template #default="{ row }">
                  <el-tag :type="getStatusCodeType(row.statusCode)" size="small">
                    {{ row.statusCode || '-' }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-collapse-item>
      </el-collapse>
    </template>

    <el-empty v-else-if="!loading" :description="t('tracing.traceDetail.noData')" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  CopyDocument, Download, Search, CircleCheckFilled, CircleCloseFilled
} from '@element-plus/icons-vue'
import { getTraceChain } from '@/api/tracing'
import zhTracing from '@/locales/zh-CN/tracing.json'
import enTracing from '@/locales/en-US/tracing.json'

const props = defineProps<{
  traceId: string
}>()

const { t, locale } = useI18n()

const loading = ref(false)
const traceChain = ref<any>(null)
const spanSearch = ref('')

const hasError = computed(() => {
  return traceChain.value?.spans?.some((s: any) => s.error) || false
})

const sortedSpans = computed(() => {
  if (!traceChain.value?.spans) return []
  return [...traceChain.value.spans].sort((a: any, b: any) =>
    new Date(a.startTime).getTime() - new Date(b.startTime).getTime()
  )
})

const filteredSpans = computed(() => {
  if (!spanSearch.value) return sortedSpans.value
  const search = spanSearch.value.toLowerCase()
  return sortedSpans.value.filter((s: any) => {
    const friendly = friendlyStep(s).name
    return (
      s.operationName?.toLowerCase().includes(search) ||
      friendly?.toLowerCase().includes(search)
    )
  })
})

const totalDurationMs = computed(() => {
  const stats = traceChain.value?.stats
  if (stats?.totalDuration) return Math.round(stats.totalDuration)
  const spans = sortedSpans.value
  if (spans.length === 0) return 0
  const start = new Date(spans[0].startTime).getTime()
  const last = spans[spans.length - 1]
  return Math.max(1, new Date(last.startTime).getTime() + (last.duration || 0) - start)
})

const shortTraceId = computed(() => {
  const id = traceChain.value?.traceId || props.traceId || ''
  return id.length > 18 ? `${id.substring(0, 18)}…` : id
})

/** 把技术性 operationName 翻译成用户能看懂的步骤名 */
const friendlyStep = (span: any): { name: string; kind: string } => {
  const op = span.operationName || ''
  // 入口请求：HTTP.xxx 或 "GET /path" 形式
  if (op.startsWith('HTTP') || /^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b/i.test(op)) {
    return { name: t('tracing.traceDetail.stepNames.received'), kind: 'http' }
  }
  if (op.includes('InstanceSelection')) return { name: t('tracing.traceDetail.stepNames.instanceSelection'), kind: 'selection' }
  if (op.includes('load-balancer') || op.includes('LoadBalancer')) return { name: t('tracing.traceDetail.stepNames.loadBalancer'), kind: 'lb' }
  if (op.startsWith('Adapter')) return { name: t('tracing.traceDetail.stepNames.adapterCall'), kind: 'adapter' }
  return { name: op || '-', kind: 'other' }
}

/** 步骤副标题：从 span 属性里提取对用户有意义的上下文 */
const stepSubtitle = (span: any, kind: string): string => {
  const attrs = span.attributes || {}
  const pick = (...keys: string[]) =>
    keys.map((k) => attrs[k]).filter((v) => v !== undefined && v !== null && v !== '').join(' · ')
  if (kind === 'http') return pick('http.method', 'http.path') || span.operationName || ''
  if (kind === 'selection') return pick('model.name', 'instance.name')
  if (kind === 'adapter') return pick('adapter.name', 'instance.id', 'adapter.instance_url')
  return ''
}

/** 分步流程数据：按时间排序 + 人话名 + 耗时占比 */
const steps = computed(() => {
  const total = totalDurationMs.value || 1
  return sortedSpans.value.map((span: any) => {
    const { name, kind } = friendlyStep(span)
    const duration = Math.round(span.duration || 0)
    const percent = Math.max(1, Math.min(100, Math.round((duration / total) * 100)))
    return {
      spanId: span.spanId,
      name,
      subtitle: stepSubtitle(span, kind),
      duration,
      percent,
      error: !!span.error
    }
  })
})

const loadTraceChain = async () => {
  if (!props.traceId) return

  loading.value = true
  try {
    const response = await getTraceChain(props.traceId)
    const data = response.data?.data || response.data || response
    if (data) {
      traceChain.value = data
    }
  } catch (error) {
    console.error('加载追踪链路失败:', error)
    ElMessage.error(t('tracing.traceDetail.messages.loadChainFailed'))
  } finally {
    loading.value = false
  }
}

const copyTraceId = async () => {
  try {
    await navigator.clipboard.writeText(props.traceId)
    ElMessage.success(t('tracing.traceDetail.messages.traceIdCopied'))
  } catch {
    ElMessage.error(t('tracing.traceDetail.messages.copyFailed'))
  }
}

const exportTrace = () => {
  if (!traceChain.value) return

  const dataStr = JSON.stringify(traceChain.value, null, 2)
  const blob = new Blob([dataStr], { type: 'application/json' })
  const url = URL.createObjectURL(blob)

  const link = document.createElement('a')
  link.href = url
  link.download = `trace-${props.traceId}.json`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)

  ElMessage.success(t('tracing.traceDetail.messages.traceExported'))
}

const formatTime = (timeStr: string) => {
  try {
    const date = new Date(timeStr)
    return date.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    })
  } catch {
    return timeStr
  }
}

/** 耗时人性化：1 秒以上显示 x.xs，否则 xxxms */
const formatDuration = (duration: number) => {
  const d = Math.round(duration || 0)
  if (d >= 1000) return `${(d / 1000).toFixed(1)}s`
  return `${d}ms`
}

const formatAttributeValue = (value: any) => {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/**
 * 属性键/值翻译：语言包里的叶子键本身含点号（如 http.status_code），
 * vue-i18n 的 te/t 会把点号解析成嵌套路径导致永远查不到，
 * 因此直接引用语言包 JSON 做字典查找。
 */
const attrDicts = computed(() => {
  const pack = (locale.value === 'zh-CN' ? zhTracing : enTracing) as any
  return pack?.tracing?.traceDetail || {}
})

/** 属性键翻译成中文（无映射时回退原文） */
const attrLabel = (key: string): string => attrDicts.value.attrs?.[key] ?? key

/** 属性值翻译（chat/normal 等常见枚举值，无映射时回退原文） */
const attrValue = (key: string, value: any): string => {
  const raw = formatAttributeValue(value)
  return attrDicts.value.attrValues?.[raw] ?? raw
}

const getDurationClass = (duration: number) => {
  if (duration > 1000) return 'text-danger'
  if (duration > 500) return 'text-warning'
  return ''
}

const getStatusCodeType = (statusCode: string) => {
  if (!statusCode) return 'info'
  const code = parseInt(statusCode)
  if (code >= 200 && code < 300) return 'success'
  if (code >= 400 && code < 500) return 'warning'
  if (code >= 500) return 'danger'
  return 'info'
}

watch(() => props.traceId, () => {
  loadTraceChain()
}, { immediate: true })
</script>

<style scoped>
.trace-detail {
  padding: 0;
}

.summary-card {
  margin-bottom: 16px;
}

.summary-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 32px;
}

.summary-item {
  min-width: 120px;
}

.summary-item__label {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-bottom: 4px;
}

.summary-item__value {
  font-size: 14px;
  color: var(--ja-text-primary);
  font-weight: 500;
}

.trace-id-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.trace-id-cell code {
  font-family: var(--ja-font-mono);
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}

.steps-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.steps-flow {
  display: flex;
  flex-direction: column;
}

.step-item {
  display: flex;
  gap: 12px;
}

.step-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 24px;
  flex-shrink: 0;
}

.step-node {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: var(--ja-success);
  flex-shrink: 0;
}

.step-node--error {
  color: var(--ja-danger);
}

.step-line {
  flex: 1;
  width: 2px;
  min-height: 18px;
  background: var(--el-border-color-lighter);
}

.step-body {
  flex: 1;
  padding-bottom: 16px;
  min-width: 0;
}

.step-item:last-child .step-body {
  padding-bottom: 4px;
}

.step-main {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 6px;
}

.step-text {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}

.step-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--ja-text-primary);
}

.step-subtitle {
  font-size: 12px;
  color: var(--ja-text-secondary);
  font-family: var(--ja-font-mono);
  word-break: break-all;
}

.step-meta {
  flex-shrink: 0;
}

.step-duration {
  font-size: 13px;
  color: var(--ja-text-regular);
  font-variant-numeric: tabular-nums;
}

.step-bar-track {
  height: 6px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  overflow: hidden;
}

.step-bar {
  height: 100%;
  border-radius: 3px;
  background: var(--ja-primary, var(--el-color-primary));
  opacity: 0.75;
  transition: width 0.3s ease;
}

.step-bar--error {
  background: var(--ja-danger, var(--el-color-danger));
}

.tech-collapse {
  margin-bottom: 16px;
  border: none;
}

.tech-inner {
  padding: 0 4px;
}

.tech-search {
  margin-bottom: 12px;
}

.operation-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.operation-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.operation-raw {
  font-size: 11px;
  color: var(--ja-text-secondary);
  font-family: var(--ja-font-mono);
  word-break: break-all;
}

.span-attributes {
  padding: 12px;
  background: var(--el-fill-color-light);
}

.span-attributes h5 {
  margin: 0 0 12px 0;
  color: var(--ja-text-regular);
}

.text-danger {
  color: var(--ja-danger);
  font-weight: 500;
}

.text-warning {
  color: var(--ja-warning);
}
</style>
