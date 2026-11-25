// Common type definitions for the admin interface

export interface ApiResponse<T = any> {
  success: boolean
  data?: T
  message?: string
  error?: string
}

export interface RouterResponse<T = any> {
  success: boolean
  message: string
  data?: T
  errorCode?: string
  timestamp: string
}

export interface SystemStatus {
  uptime: number
  version: string
  environment: string
}

export interface DashboardOverview {
  systemStatus: SystemStatus
  serviceStats: {
    totalServices: number
    activeServices: number
    failedServices: number
  }
  requestStats: {
    totalRequests: number
    requestsPerSecond: number
    averageResponseTime: number
    errorRate: number
  }
  resourceUsage: {
    cpuUsage: number
    memoryUsage: number
    diskUsage: number
  }
  /**
   * 近 24 小时安全告警数。
   * 由 MonitoringController#getMonitoringOverview（/api/monitoring/overview）返回；alerts 关闭时不返回该键。
   */
  alertsLast24Hours?: number
}

// API Key Management Types

/**
 * 密钥可见分组（资源池）的扩展信息（P0-L 越权泄漏收敛）。
 *
 * <p>由「我的密钥」接口下发，替代此前「拉全公司资源池列表」的做法：
 * 只含分组名 / 友好名 / 服务类型 / 加权均价，**不含**成员 instanceId / weight
 * 等内部拓扑信息。</p>
 */
export interface ApiKeyModelScopeVO {
  /** 分组名（= 虚拟模型名，员工填进 AI 工具里的那个名字） */
  poolName: string
  /** 分组显示名（友好名，如「公司主力」）；未配置时回落展示 poolName */
  displayName?: string
  /** 虚拟模型中文描述（运维在「虚拟模型」编辑弹窗填写的用途说明）；测试场下拉在英文名旁展示 */
  description?: string
  /** 服务类型（chat / embedding / rerank / tts / stt / imgGen / imgEdit） */
  serviceType?: string
  /** 加权均价（¥ / 千 token）；组内成员均未配价时为 null */
  mixedPrice?: number | null
  /**
   * 分组扩展信息是否可用（应修9 / F5）。
   * 后端解析器依赖缺失时为 false，前端据此显示提示而非空白下拉。
   */
  modelScopeAvailable?: boolean
}

export interface ApiKeyVO {
  keyId: string
  description: string
  /** 脱敏后的密钥展示串（如 sk-9fK2••••••••4aQz）；不可用于认证，仅供辨认 */
  keyMasked?: string
  /** 数据密级（#9 数据分级出境）：PUBLIC / INTERNAL / CONFIDENTIAL；未打标展示 PUBLIC */
  classification?: string
  permissions: string[]
  enabled: boolean
  expired: boolean
  createdAt: string
  createdBy?: string          // 创建者用户名
  holder?: string             // 密钥持有人（分配给谁）；为空回落显示 createdBy
  tenantId?: string           // 归属租户 ID（多租户下列表展示；单租户为 default）
  creatorIpAddress?: string   // 创建者 IP 地址
  expiresAt: string
  lastUsedAt?: string
  totalRequests: number
  successfulRequests: number
  failedRequests: number
  remainingDays?: number      // -1 表示已过期，null 表示永不过期
  allowedIpAddresses?: string[]  // IP 白名单
  allowedGroups?: string[]        // 可访问的模型分组（pool）白名单（按分组名匹配；空=不限制）
  /**
   * 该密钥可见分组的扩展信息（P0-L）。
   *
   * <p>由「我的密钥」接口下发：分组名 / 友好名 / 服务类型 / 加权均价。
   * 白名单为空（= 不限制模型）的密钥会拿到全部启用中分组的展示信息。</p>
   */
  modelScope?: ApiKeyModelScopeVO[]
  /**
   * 密钥级合计均价（¥ / 千 token）—— 应修8 口径统一。
   *
   * <p>由服务端随「我的密钥」一起下发（与 {@code BillingService} 计价口径一致：
   * 白名单内已配价成员的单价按权重加权平均）。前端<b>只显示不计算</b>。</p>
   *
   * <p>白名单为空（= 不限制模型）时为 {@code null} —— 此时聚合的是全公司成本水平，
   * 不下发（应修7），前端显示「—」。组内成员均未配价时也为 {@code null}。</p>
   */
  mixedPrice?: number | null
  dailyRequestLimit?: number     // 每日请求限制
  dailyTokenLimit?: number       // 每日 Token 使用上限
  hourlyRequestLimit?: number   // 每小时请求上限（0=不限）
  hourlyTokenLimit?: number      // 每小时 Token 上限（0=不限）
  monthlyRequestLimit?: number   // 每月请求上限（0=不限）
  monthlyTokenLimit?: number     // 每月 Token 上限（0=不限）
  rateLimitPerMinute?: number    // 每分钟请求速率限制
  quotaAlertThreshold?: number   // 配额告警阈值 (0.0-1.0)
  todayRequestCount?: number     // 今日请求次数
  todayTokenUsage?: number       // 今日 Token 使用量
  quotaAlertTriggered?: boolean  // 是否触发配额告警
  rotationPeriodDays?: number    // 密钥轮换周期（天数）
  lastRotatedAt?: string         // 上次轮换时间
  needsRotation?: boolean        // 是否需要轮换
}

export interface ApiKeyCreationVO {
  keyId: string
  keyValue: string  // 仅在创建时返回，请妥善保存
  description: string
  classification?: string  // 数据密级：PUBLIC / INTERNAL / CONFIDENTIAL
  createdBy?: string  // 创建者用户名
  holder?: string     // 密钥持有人（分配给谁）
  permissions: string[]
  allowedGroups?: string[]        // 可访问的模型分组（pool）白名单（按分组名匹配；空=不限制）
  enabled: boolean
  createdAt: string
  expiresAt?: string
  lastRotatedAt?: string    // 上次轮换时间
  warning: string  // 警告信息
}

export interface ApiKeyListVO {
  items: ApiKeyVO[]
  total: number
  enabledCount: number
  disabledCount: number
  expiredCount: number
  summary: {
    todayTotalRequests: number
    todaySuccessfulRequests: number
    todayFailedRequests: number
  }
}

/**
 * 「我的密钥」响应（P0-L）：在 ApiKeyListVO 基础上附带分组扩展信息。
 *
 * <p>顶层 `modelScope` 是该用户各密钥分组的**并集**（按 poolName 去重），
 * 供测试场「虚拟模型」下拉一次取全；每把密钥自己的 `modelScope`
 * （见 {@link ApiKeyVO.modelScope}）用于「可用虚拟模型」列的友好名与均价。</p>
 */
export interface ApiKeyWithModelScopeVO extends ApiKeyListVO {
  modelScope: ApiKeyModelScopeVO[]
}

// API Key Management Request Types
export interface ApiKeyCreateRequest {
  keyId?: string
  description?: string
  holder?: string              // 密钥持有人（分配给谁）
  permissions?: string[]
  classification?: string      // 数据密级：PUBLIC / INTERNAL / CONFIDENTIAL（默认 PUBLIC）
  enabled?: boolean
  expiresAt?: string
  allowedIpAddresses?: string[]
  allowedGroups?: string[]        // 可访问的模型分组（pool）白名单（按分组名匹配；空=不限制）
  dailyRequestLimit?: number
  dailyTokenLimit?: number       // 每日 Token 使用上限
  rateLimitPerMinute?: number    // 每分钟请求速率限制
  hourlyRequestLimit?: number   // 每小时请求上限（0=不限）
  hourlyTokenLimit?: number      // 每小时 Token 上限（0=不限）
  monthlyRequestLimit?: number   // 每月请求上限（0=不限）
  monthlyTokenLimit?: number     // 每月 Token 上限（0=不限）
  quotaAlertThreshold?: number   // 配额告警阈值 (0.0-1.0)
  rotationPeriodDays?: number   // 密钥轮换周期（天数）
}

export interface ApiKeyUpdateRequest {
  description?: string
  holder?: string              // 密钥持有人（分配给谁）；不传 = 不修改
  permissions?: string[]
  classification?: string      // 数据密级：PUBLIC / INTERNAL / CONFIDENTIAL；不传 = 不修改
  enabled?: boolean
  expiresAt?: string
  allowedIpAddresses?: string[]
  allowedGroups?: string[]        // 可访问的模型分组（pool）白名单（按分组名匹配；空=不限制）
  dailyRequestLimit?: number
  dailyTokenLimit?: number       // 每日 Token 使用上限
  rateLimitPerMinute?: number    // 每分钟请求速率限制
  hourlyRequestLimit?: number   // 每小时请求上限（0=不限）
  hourlyTokenLimit?: number      // 每小时 Token 上限（0=不限）
  monthlyRequestLimit?: number   // 每月请求上限（0=不限）
  monthlyTokenLimit?: number     // 每月 Token 上限（0=不限）
  quotaAlertThreshold?: number   // 配额告警阈值 (0.0-1.0)
  rotationPeriodDays?: number   // 密钥轮换周期（天数）
}

// 批量导入/导出类型
export interface ApiKeyBatchExportVO {
  exportTime: string
  total: number
  keys: ApiKeyExportedKey[]
}

export interface ApiKeyExportedKey {
  keyId: string
  description: string
  permissions: string[]
  enabled: boolean
  createdAt: string
  createdBy?: string
  holder?: string        // 密钥持有人（分配给谁）
  expiresAt?: string
  allowedIpAddresses?: string[]
  allowedGroups?: string[]        // 可访问的模型分组（pool）白名单（按分组名匹配；空=不限制）
  dailyRequestLimit?: number
  rotationPeriodDays?: number
  lastRotatedAt?: string
}

export interface ApiKeyBatchImportRequest {
  keys: ApiKeyImportItem[]
  mode?: 'MERGE' | 'REPLACE'  // MERGE: 合并保留现有, REPLACE: 替换删除现有
}

export interface ApiKeyImportItem {
  keyId?: string
  description?: string
  holder?: string        // 密钥持有人（分配给谁）
  permissions?: string[]
  enabled?: boolean
  expiresAt?: string
  allowedIpAddresses?: string[]
  allowedGroups?: string[]        // 可访问的模型分组（pool）白名单（按分组名匹配；空=不限制）
  dailyRequestLimit?: number
  rotationPeriodDays?: number
}

export interface ApiKeyBatchImportResult {
  totalAttempted: number
  successCount: number
  failureCount: number
  importedKeys: ApiKeyCreationVO[]
  errors: ImportError[]
}

export interface ImportError {
  keyId?: string
  reason: string
}

// API Key 配额管理类型
export interface QuotaUsageDetail {
  keyId: string
  description: string
  dailyRequestLimit: number
  dailyTokenLimit: number
  hourlyRequestLimit: number
  hourlyTokenLimit: number
  monthlyRequestLimit: number
  monthlyTokenLimit: number
  rateLimitPerMinute: number
  quotaAlertThreshold: number
  todayRequestCount: number
  todayTokenUsage: number
  currentRatePerMinute: number
  totalRequests: number
  dailyRequestUsagePercent: number  // -1 表示无限制
  dailyTokenUsagePercent: number    // -1 表示无限制
  alertTriggered: boolean
  /** 剩余请求数；限额 0 时为 -1（v3.2.1） */
  remainingRequests?: number
  /** 剩余 Token 数；限额 0 时为 -1（v3.2.1） */
  remainingTokens?: number
}

export interface QuotaAlertInfo {
  keyId: string
  description: string
  alertType: string        // REQUEST_QUOTA | TOKEN_QUOTA | GENERAL
  dailyRequestUsagePercent: number
  dailyTokenUsagePercent: number
  message: string
}

// 兼容旧类型
export type ApiKeyInfo = ApiKeyVO
export type ApiKeyCreationResponse = ApiKeyCreationVO
export type CreateApiKeyRequest = ApiKeyCreateRequest
export type UpdateApiKeyRequest = ApiKeyUpdateRequest

// Tracing Management Types
export interface TracingOverview {
  totalTraces: number
  errorTraces: number
  avgDuration: number
  samplingRate: number
  successfulTraces?: number
  totalSpans?: number
  maxDuration?: number
  minDuration?: number
}

export interface TracingStats {
  traceVolumeTrend: TimeSeriesData[]
  errorTrend: TimeSeriesData[]
  processing?: {
    queue_size: number
    processed_count: number
    is_running: boolean
    success_rate: number
    dropped_count: number
  }
  configInfo?: {
    serviceName: string
    exporterType: string
    globalSamplingRatio: number
    enabled: boolean
  }
  memory?: {
    cache_size: number
    pressure_level: string
    cache_hit_ratio: number
    heap_usage_ratio: number
  }
  timestamp?: string
}

export interface ServiceStats {
  name: string
  traces: number
  errors: number
  avgDuration: number
  p95Duration: number
  p99Duration: number
  errorRate: number
}

export interface TimeSeriesData {
  timestamp: string | number
  value: number
}

// Performance Analysis Types
export interface PerformanceStats {
  latencyDistribution: ServiceLatency[]
  latencyTrend: LatencyTrendData
  slowTraces: SlowTrace[]
}

export interface LatencyAnalysis {
  distribution: ServiceLatency[]
  trend: LatencyTrendData
  percentiles: PercentileData
}

export interface ErrorAnalysis {
  errorRateDistribution: ServiceErrorRate[]
  errorTrend: TimeSeriesData[]
  commonErrors: CommonError[]
}

export interface ThroughputAnalysis {
  requestDistribution: ServiceThroughput[]
  qpsTrend: TimeSeriesData[]
}

export interface ServiceLatency {
  service: string
  avgLatency: number
  p95Latency: number
  p99Latency: number
}

export interface LatencyTrendData {
  timestamps: string[]
  avgLatency: number[]
  p95Latency: number[]
  p99Latency: number[]
}

export interface PercentileData {
  p50: number
  p95: number
  p99: number
  p999: number
}

export interface ServiceErrorRate {
  service: string
  errorRate: number
  totalRequests: number
  errorCount: number
}

export interface ServiceThroughput {
  service: string
  requestsPerSecond: number
  totalRequests: number
}

export interface SlowTrace {
  traceId: string
  service: string
  operation: string
  duration: number
  startTime: string
}

export interface CommonError {
  errorType: string
  service: string
  operation: string
  count: number
  lastOccurrence: string
}

export interface TraceDetails {
  traceId: string
  spans: SpanInfo[]
  totalDuration: number
  services: string[]
}

export interface SpanInfo {
  spanId: string
  service: string
  operation: string
  duration: number
  startTime: string
  status: string
}

// Sampling Configuration Types
export interface SamplingConfig {
  globalRate: number
  adaptiveSampling: boolean
  alwaysSamplePaths: string[]
  neverSamplePaths: string[]
  serviceConfigs: ServiceSamplingConfig[]
}

export interface ServiceSamplingConfig {
  service: string
  rate: number
}

export interface SamplingStats {
  totalSamples: number
  droppedSamples: number
  samplingEfficiency: number
}

// Common Types
export interface TimeRange {
  startTime?: string | number
  endTime?: string | number
}