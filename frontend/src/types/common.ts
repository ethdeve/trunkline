/**
 * 通用类型定义
 * 用于减少 any 的使用，提高类型安全
 */

// 通用 API 错误响应
export interface ApiError {
  message: string
  code?: string
  details?: Record<string, unknown>
}

// 分页参数
export interface PaginationParams {
  page: number
  size: number
  sort?: string
  order?: 'asc' | 'desc'
}

// 分页响应
export interface PaginationResponse<T> {
  content: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

// 服务实例类型
export interface ServiceInstance {
  instanceId: string
  name: string
  /**
   * 模型分类（服务类型）：chat / embedding / rerank / tts / stt / imgGen / imgEdit。
   * 2026-10-22 起由后端 DTO 权威下发（ServiceInstanceDTO.serviceType），
   * 不再依赖「前端在哪个 Tab」推断。
   */
  serviceType?: string
  adapter: string
  baseUrl: string
  weight: number
  headers?: Record<string, string>
  /** 启停状态：active / inactive */
  status?: string
  /** 健康状态：HEALTHY / UNHEALTHY / UNKNOWN（后端权威字段） */
  healthStatus?: string
  /** 健康检查失败原因 */
  errorMessage?: string
  /** 运行时实例 UUID，用于关联熔断运行态 */
  runtimeInstanceId?: string
  /** 实例级限流配置 */
  rateLimit?: { enabled?: boolean; algorithm?: string; capacity?: number; rate?: number }
  metadata?: Record<string, unknown>
}

// 服务配置类型
export interface ServiceConfig {
  type: string
  adapter: string
  loadBalanceType: string
  rateLimitType: string
  instances: ServiceInstance[]
  metadata?: Record<string, unknown>
}

// 监控数据类型
export interface MonitoringData {
  serviceType: string
  totalInstances: number
  healthyInstances: number
  healthRate: number
  requests?: {
    total: number
    success: number
    failed: number
    avgResponseTime: number
  }
}

// 追踪数据类型
export interface TraceData {
  traceId: string
  spanId: string
  operation: string
  duration: number
  status: 'success' | 'error'
  timestamp: string
  tags?: Record<string, string>
}

// 审计日志类型
export interface AuditLog {
  eventId: string
  eventType: string
  username: string
  action: string
  resource: string
  result: 'success' | 'failure'
  timestamp: string
  ipAddress?: string
  details?: Record<string, unknown>
}

// JWT Token 类型
export interface JwtPayload {
  sub: string
  roles: string[]
  permissions?: string[]
  exp: number
  iat: number
  iss: string
}

// 配置版本类型
export interface ConfigVersion {
  version: number
  configKey: string
  configValue: Record<string, unknown>
  isLatest: boolean
  createdAt: string
  updatedAt: string
}

// 通用表单字段类型
export interface FormField {
  key: string
  value: string
  label?: string
  required?: boolean
  disabled?: boolean
}

// 通用选项类型
export interface SelectOption {
  label: string
  value: string | number
  disabled?: boolean
}

// 表格列定义
export interface TableColumn {
  prop: string
  label: string
  width?: string | number
  sortable?: boolean
  formatter?: (row: Record<string, unknown>) => string
}
