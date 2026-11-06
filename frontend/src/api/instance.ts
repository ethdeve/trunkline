import request from '@/utils/request'
import type { RouterResponse } from '@/types'

// 定义实例配置接口
export interface InstanceConfig {
  /** 数据库主键；注意后端 DTO 的 instanceId 字段即此值的字符串形式（兼容字段），并非路由层 UUID */
  id?: number
  instanceId?: string
  name: string
  baseUrl: string
  path?: string
  weight?: number
  status?: 'active' | 'inactive'
  adapter?: string
  headers?: Record<string, string>
  tags?: Record<string, string>  // v2.9.7: 实例标签(键值对)
  /** v2.11.0: 关联的模型供应商 ID（0 = 解除关联；不传 = 不改变关联） */
  providerId?: number
  /** v2.11.0: 关联的模型供应商名称（后端随 providerId 一起带出，展示用） */
  providerName?: string
  /**
   * 模型分类（服务类型）：chat / embedding / rerank / tts / stt / imgGen / imgEdit。
   * - 创建时：决定新实例挂到哪个 service_config（服务配置）下
   * - 更新时：非空表示把实例迁移到该分类（后端做级联迁移与前置校验）
   */
  serviceType?: string
}

// 限流器配置接口
export interface RateLimitConfig {
  id?: number
  instanceId?: number
  enabled: boolean
  algorithm?: string
  capacity?: number
  rate?: number
  scope?: string
  key?: string
  clientIpEnable?: boolean
}

// 熔断器配置接口
export interface CircuitBreakerConfig {
  id?: number
  instanceId?: number
  enabled: boolean
  failureThreshold?: number
  timeout?: number
  successThreshold?: number
}

// 获取服务实例列表
export const getServiceInstances = (serviceType: string) => {
  return request.get<RouterResponse<any[]>>(`/config/instance/${serviceType}`)
}

// 获取单个实例详情
export const getServiceInstance = (serviceType: string, instanceId: string) => {
  return request.get<RouterResponse<any>>(`/config/instance/${serviceType}/${instanceId}`)
}

/**
 * 获取脱敏后的实例清单（员工自助端点，P0-L 凭据泄漏防护）。
 *
 * <p>P0-L 越权泄漏修复：普通员工不再有 `config:instances:read` 权限，无法访问
 * `/config/instance/{serviceType}`（该响应含上游厂商 API Key 明文）。
 * 员工侧改走本端点（权限码 `config:instances:self`），返回的 headers 中
 * 敏感头值已被服务端替换为 `"***"`。</p>
 *
 * <p>口径为全量实例清单（不按密钥白名单收窄）——员工密钥白名单为空即「不受限」，
 * 收窄没有安全收益，反而可能因脏数据（白名单里写了已删除的分组名）导致试验场选不到模型。</p>
 *
 * @param serviceType 可选的服务类型（chat / embedding / …）；不传则返回全部实例
 */
export const getMyServiceInstances = (serviceType?: string) => {
  return request.get<RouterResponse<any[]>>('/instance/mine', {
    params: serviceType ? { serviceType } : undefined
  })
}

// 添加服务实例
export const addServiceInstance = (serviceType: string, instanceConfig: InstanceConfig) => {
  return request.post<RouterResponse<any>>(`/config/instance/${serviceType}`, instanceConfig)
}

// 更新服务实例
export const updateServiceInstance = (serviceType: string, instanceId: string, instanceConfig: InstanceConfig) => {
  return request.put<RouterResponse<any>>(`/config/instance/${serviceType}/${instanceId}`, instanceConfig)
}

// 删除服务实例
export const deleteServiceInstance = (serviceType: string, instanceId: string) => {
  return request.delete<RouterResponse<void>>(`/config/instance/${serviceType}/${instanceId}`)
}

// ==================== 限流器配置 API ====================

/**
 * 获取实例的限流器配置
 */
export const getRateLimitConfig = (serviceType: string, instanceId: string) => {
  return request.get<RouterResponse<RateLimitConfig>>(`/config/instance/${serviceType}/${instanceId}/rate-limit`)
}

/**
 * 保存实例的限流器配置
 */
export const saveRateLimitConfig = (serviceType: string, instanceId: string, config: RateLimitConfig) => {
  return request.put<RouterResponse<RateLimitConfig>>(`/config/instance/${serviceType}/${instanceId}/rate-limit`, config)
}

// ==================== 熔断器配置 API ====================

/**
 * 获取实例的熔断器配置
 */
export const getCircuitBreakerConfig = (serviceType: string, instanceId: string) => {
  return request.get<RouterResponse<CircuitBreakerConfig>>(`/config/instance/${serviceType}/${instanceId}/circuit-breaker`)
}

/**
 * 保存实例的熔断器配置
 */
export const saveCircuitBreakerConfig = (serviceType: string, instanceId: string, config: CircuitBreakerConfig) => {
  return request.put<RouterResponse<CircuitBreakerConfig>>(`/config/instance/${serviceType}/${instanceId}/circuit-breaker`, config)
}

// ==================== 熔断器重置 API ====================

/**
 * 重置指定实例的熔断器状态（路径参数方式）
 * @param serviceType 服务类型
 * @param instanceId 实例数据库 ID
 */
export const resetCircuitBreakerByPath = (serviceType: string, instanceId: string) => {
  return request.post<RouterResponse<null>>(`/config/instance/${serviceType}/${instanceId}/circuit-breaker/reset`)
}

/**
 * 重置指定实例的熔断器状态（请求体方式）
 * @param instanceId 实例标识（Map key，可以是数字 ID 或复合键）
 */
export const resetCircuitBreakerById = (instanceId: string) => {
  return request.post<RouterResponse<null>>('/config/instance/circuit-breaker/reset', { instanceId })
}

/**
 * 清除所有实例的熔断器状态
 */
export const clearAllCircuitBreakers = () => {
  return request.post<RouterResponse<null>>('/config/instance/circuit-breaker/clear-all')
}