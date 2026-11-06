import type {
  CallHistoryQuery,
  CallHistoryStatistics,
  ApiCallHistoryRecord,
  PageResponse,
  CallHistoryDashboard
} from '@/types/callHistory'
import request from '@/utils/request'

/**
 * 分页查询调用历史
 */
export const queryCallHistory = async (
  query: CallHistoryQuery = {}
): Promise<PageResponse<ApiCallHistoryRecord>> => {
  const params: Record<string, any> = {
    page: query.page || 0,
    size: query.size || 20
  }
  if (query.modelName) params.modelName = query.modelName
  if (query.virtualModel) params.virtualModel = query.virtualModel
  if (query.userId) params.userId = query.userId
  if (query.keyword) params.keyword = query.keyword
  if (query.rateLimited !== undefined && query.rateLimited !== null) {
    params.rateLimited = query.rateLimited
  }
  if (query.serviceType) params.serviceType = query.serviceType
  if (query.apiKeyId) params.apiKeyId = query.apiKeyId
  if (query.isSuccess !== undefined && query.isSuccess !== null) params.isSuccess = query.isSuccess
  if (query.httpStatusCode) params.httpStatusCode = query.httpStatusCode
  if (query.startTime) params.startTime = query.startTime
  if (query.endTime) params.endTime = query.endTime
  if (query.sortField) params.sortField = query.sortField
  if (query.sortDirection) params.sortDirection = query.sortDirection

  const response = await request.get('/call-history', { params })
  return response.data.data
}

/**
 * 「我的调用记录」（v3.5 Trunkline 员工侧自助端点）
 *
 * 归属由服务端从登录态强制解析（principal），前端<b>不可</b>传 userId / keyword
 * 等归属参数，因此这里只透出分页参数。
 */
export const queryCallHistoryMine = async (
  query: { page?: number; size?: number } = {}
): Promise<PageResponse<ApiCallHistoryRecord>> => {
  const params: Record<string, any> = {
    page: query.page || 0,
    size: query.size || 20
  }

  const response = await request.get('/call-history/mine', { params })
  return response.data.data
}

/**
 * 导出调用记录（任务 58：CSV / JSON，元数据口径，不含请求/响应正文）。
 *
 * 服务端按筛选条件全量导出（封顶 10000 行），返回文件流；
 * 文件名取自响应的 Content-Disposition，取不到时用回退名。
 */
export const exportCallHistory = async (
  query: CallHistoryQuery,
  format: 'csv' | 'json' = 'csv',
  fallbackName = 'call-history.csv'
): Promise<void> => {
  const params: Record<string, any> = { format }
  if (query.modelName) params.modelName = query.modelName
  if (query.virtualModel) params.virtualModel = query.virtualModel
  if (query.userId) params.userId = query.userId
  if (query.keyword) params.keyword = query.keyword
  if (query.rateLimited !== undefined && query.rateLimited !== null) params.rateLimited = query.rateLimited
  if (query.serviceType) params.serviceType = query.serviceType
  if (query.apiKeyId) params.apiKeyId = query.apiKeyId
  if (query.isSuccess !== undefined && query.isSuccess !== null) params.isSuccess = query.isSuccess
  if (query.httpStatusCode) params.httpStatusCode = query.httpStatusCode
  if (query.startTime) params.startTime = query.startTime
  if (query.endTime) params.endTime = query.endTime
  if (query.sortField) params.sortField = query.sortField
  if (query.sortDirection) params.sortDirection = query.sortDirection

  const response = await request.get('/call-history/export', {
    params,
    responseType: 'blob'
  })
  downloadBlob(response, fallbackName)
}

/**
 * 导出「我的调用记录」（员工自助，CSV / JSON）。
 *
 * 归属由服务端强制解析，前端只能传时间范围与格式（与 /mine 同口径）。
 */
export const exportMyCallHistory = async (
  format: 'csv' | 'json' = 'csv',
  startTime?: string,
  endTime?: string,
  fallbackName = 'my-call-history.csv'
): Promise<void> => {
  const params: Record<string, any> = { format }
  if (startTime) params.startTime = startTime
  if (endTime) params.endTime = endTime

  const response = await request.get('/call-history/mine/export', {
    params,
    responseType: 'blob'
  })
  downloadBlob(response, fallbackName)
}

/** Blob 响应 → 触发浏览器下载（文件名优先取 Content-Disposition） */
const downloadBlob = (response: { headers: any; data: any }, fallbackName: string) => {
  const disposition: string = response.headers?.['content-disposition'] || ''
  const match = disposition.match(/filename="?([^";]+)"?/)
  const filename = match?.[1] || fallbackName

  const url = URL.createObjectURL(response.data)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = decodeURIComponent(filename)
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(url)
}

/**
 * 根据 traceId 查询调用链路
 */
export const findByTraceId = async (traceId: string): Promise<ApiCallHistoryRecord[]> => {
  const response = await request.get(`/call-history/trace/${traceId}`)
  return response.data.data || []
}

/**
 * 获取最近的调用记录
 */
export const getRecentCalls = async (limit: number = 20): Promise<ApiCallHistoryRecord[]> => {
  const response = await request.get('/call-history/recent', { params: { limit } })
  return response.data.data || []
}

/**
 * 查询慢调用
 */
export const getSlowCalls = async (
  threshold?: number,
  startTime?: string,
  endTime?: string,
  limit: number = 50
): Promise<ApiCallHistoryRecord[]> => {
  const params: Record<string, any> = { limit }
  if (threshold) params.threshold = threshold
  if (startTime) params.startTime = startTime
  if (endTime) params.endTime = endTime

  const response = await request.get('/call-history/slow', { params })
  return response.data.data || []
}

/**
 * 获取统计信息
 */
export const getCallHistoryStatistics = async (
  startTime?: string,
  endTime?: string
): Promise<CallHistoryStatistics> => {
  const params: Record<string, any> = {}
  if (startTime) params.startTime = startTime
  if (endTime) params.endTime = endTime

  const response = await request.get('/call-history/statistics', { params })
  return response.data.data
}

/**
 * 获取仪表盘数据
 */
export const getCallHistoryDashboard = async (
  startTime?: string,
  endTime?: string
): Promise<CallHistoryDashboard> => {
  const params: Record<string, any> = {}
  if (startTime) params.startTime = startTime
  if (endTime) params.endTime = endTime

  const response = await request.get('/call-history/dashboard', { params })
  return response.data.data
}

/**
 * 清理过期数据
 */
export const cleanupCallHistory = async (cutoffTime?: string): Promise<number> => {
  const params: Record<string, any> = {}
  if (cutoffTime) params.cutoffTime = cutoffTime

  const response = await request.delete('/call-history/cleanup', { params })
  return response.data.data?.deletedCount || 0
}

/**
 * 获取总记录数
 */
export const getCallHistoryCount = async (): Promise<number> => {
  const response = await request.get('/call-history/count')
  return response.data.data?.count || 0
}

// ---- v2.9.2 记录治理 (record governance) ----

export interface CallHistoryConfig {
  recordLevel: string   // "METADATA_ONLY" | "SUMMARY" | "FULL"
  maxContentLength?: number
  retentionDays?: number
  enabled?: boolean
}

/**
 * 获取调用历史记录配置
 */
export const getCallHistoryConfig = async (): Promise<CallHistoryConfig> => {
  const response = await request.get('/config/call-history')
  return response.data.data
}

/**
 * 更新调用历史记录配置
 */
export const updateCallHistoryConfig = async (
  payload: { recordLevel: string }
): Promise<CallHistoryConfig> => {
  const response = await request.put('/config/call-history', payload)
  return response.data.data
}
