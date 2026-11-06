import request from '@/utils/request'
import type { RouterResponse } from '@/types'

// v2.8.9: 资源池
export interface PoolMember {
  instanceId: string
  weight: number
  modelName?: string
}

export interface PoolDefinition {
  poolName: string
  name?: string
  serviceType: string
  enabled: boolean
  strategy: string
  description?: string
  members: PoolMember[]
  /** 每分钟请求速率上限（0 = 不限） */
  rateLimitPerMinute?: number
  /** 每小时请求上限（0 = 不限） */
  hourlyRequestLimit?: number
  /** 每小时 Token 上限（0 = 不限） */
  hourlyTokenLimit?: number
  /** 每日请求上限（0 = 不限） */
  dailyRequestLimit?: number
  /** 每日 Token 上限（0 = 不限） */
  dailyTokenLimit?: number
  /** 每月请求上限（0 = 不限） */
  monthlyRequestLimit?: number
  /** 每月 Token 上限（0 = 不限） */
  monthlyTokenLimit?: number
  /** 配额告警阈值（0.0-1.0） */
  quotaAlertThreshold?: number
}

export const getPoolList = () => {
  return request.get<RouterResponse<PoolDefinition[]>>('/config/pools/list')
}

export const getPool = (poolName: string) => {
  return request.get<RouterResponse<PoolDefinition>>(`/config/pools/${poolName}`)
}

export const createPool = (data: PoolDefinition) => {
  return request.post<RouterResponse<PoolDefinition>>('/config/pools', data)
}

export const updatePool = (poolName: string, data: PoolDefinition) => {
  return request.put<RouterResponse<PoolDefinition>>(`/config/pools/${poolName}`, data)
}

export const deletePool = (poolName: string) => {
  return request.delete<RouterResponse<void>>(`/config/pools/${poolName}`)
}

/**
 * 查询分组（池）维度的配额用量与限额。
 * 返回各窗口（DAILY / MONTHLY …）的请求数 / Token 数，用于「本月调用」列展示。
 * 注意：本接口每次查询一个池，列表页按池逐个数取即可（池数量通常很少）。
 */
export const getPoolQuotaUsage = (poolName: string) => {
  return request.get<RouterResponse<{
    usage?: Array<{ window: string; requestCount: number; tokenCount: number }>
  }>>(`/config/pools/${poolName}/quota/usage`)
}
