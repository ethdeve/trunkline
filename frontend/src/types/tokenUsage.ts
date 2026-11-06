/**
 * Token 使用量统计类型定义
 */

/**
 * Token 使用量「最近记录」（GET /api/token-usage/recent 的响应元素）
 *
 * P0-L 越权泄漏收敛：服务端已把该端点的响应由 JPA 实体全字段改为白名单投影
 * （后端 `TokenUsageRecentVO`），刻意剔除 `clientIp`（调用方 IP）、
 * `instanceUrl`（实例基础 URL）、`errorMessage`（上游报错原文）与 `metadata`
 * （JSON 元数据）—— 这些属于内部排障 / 安全审计信息，随列表整包外泄会让
 * 任一登录用户看到全公司调用明细。此类型与后端 VO 字段一一对应。
 */
export interface TokenUsageRecord {
  id: number
  traceId?: string
  serviceType: string
  modelName: string
  /** 虚拟模型名（模型分组 / 池名），历史数据可能为空 */
  virtualModel?: string
  /** 实际路由到的厂商模型实例名 */
  instanceName?: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  apiKeyId?: string
  userId?: string
  isSuccess?: boolean
  errorCode?: string
  responseTimeMs?: number
  occurredAt: string
}

// Token 使用量统计
export interface TokenUsageStatistics {
  startTime: string
  endTime: string
  totalRequests: number
  successfulRequests: number
  failedRequests: number
  totalTokens: number
  totalPromptTokens: number
  totalCompletionTokens: number
  avgResponseTimeMs: number
  successRate: number
  byModel: ModelTokenStats[]
  byServiceType: ServiceTypeStats[]
  byProvider: ProviderStats[]
  byDay: DailyStats[]
  byWeek: WeeklyStats[]
  byMonth: MonthlyStats[]
  byHour: HourlyStats[]
  byApiKey?: ApiKeyStats[]
  byUser?: UserStats[]
}

// 模型 Token 统计
export interface ModelTokenStats {
  modelName: string
  totalTokens: number
  promptTokens: number
  completionTokens: number
  requestCount: number
  avgTokensPerRequest: number
}

// 服务类型统计
export interface ServiceTypeStats {
  serviceType: string
  totalTokens: number
  promptTokens: number
  completionTokens: number
  requestCount: number
}

// 提供商统计
export interface ProviderStats {
  provider: string
  totalTokens: number
  requestCount: number
}

// 日统计
export interface DailyStats {
  date: string
  totalTokens: number
  promptTokens: number
  completionTokens: number
  requestCount: number
}

// 周统计
export interface WeeklyStats {
  year: number
  week: number
  totalTokens: number
  promptTokens: number
  completionTokens: number
  requestCount: number
  weekLabel: string
}

// 月统计
export interface MonthlyStats {
  year: number
  month: number
  totalTokens: number
  promptTokens: number
  completionTokens: number
  requestCount: number
  monthLabel: string
}

// 小时统计
export interface HourlyStats {
  hour: number
  totalTokens: number
  requestCount: number
  hourLabel: string
}

// API Key 统计
export interface ApiKeyStats {
  apiKeyId: string
  totalTokens: number
  requestCount: number
}

// 用户统计
export interface UserStats {
  userId: string
  totalTokens: number
  requestCount: number
}
