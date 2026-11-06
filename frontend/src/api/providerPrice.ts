import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/**
 * 模型价格与额度 API（v3.5 Trunkline UI 改造，接入管理「价格与额度」tab）
 *
 * 价格与额度复用同一张表：一行 = 一个厂商模型（已接入的模型实例名）。
 * - 价格单位：¥ / 千 token，来源一期手工配置。
 * - 额度与限速是厂商模型维度的配置；虚拟模型（模型分组）的额度限速独立配置在分组定义上。
 * - 账单按厂商模型名计价（与 model_price.model_name 键一致）。
 */

/** 价格与额度目录条目（一行 = 一个厂商模型） */
export interface ModelPriceCatalogItem {
  /** 厂商模型名（价格表主键，同时是账单计价口径） */
  modelName: string
  /** 行类型：固定 PROVIDER_MODEL（厂商模型） */
  kind: string
  /** 所属供应商名 */
  providerName?: string
  /** 服务分类 */
  serviceType?: string
  /** 代表实例主键（service_instance.id，Long）。熔断/限流配置按实例粒度存储，前端据此跳转实例级配置，并在表格渲染「熔断」「限流」两列开关 */
  instanceId?: number
  /** 代表实例的熔断器是否已启用（来自 instance_circuit_breaker.enabled） */
  circuitBreakerEnabled?: boolean
  /** 代表实例的限流器是否已启用（来自 instance_rate_limit.enabled） */
  rateLimitEnabled?: boolean
  /** 是否已配置价格 */
  hasPrice: boolean
  /** 输入单价（¥ / 千 token） */
  priceInput: number
  /** 输出单价（¥ / 千 token） */
  priceOutput: number
  /** 币种 */
  currency: string
  /** 是否启用 */
  enabled: boolean
  description?: string
  /** 每分钟请求数上限（0 = 不限制） */
  rateLimitPerMinute?: number
  /** 每小时请求数上限（0 = 不限制） */
  hourlyRequestLimit?: number
  /** 每小时 token 上限（0 = 不限制） */
  hourlyTokenLimit?: number
  /** 每日请求数上限（0 = 不限制） */
  dailyRequestLimit?: number
  /** 每日 token 上限（0 = 不限制） */
  dailyTokenLimit?: number
  /** 每月请求数上限（0 = 不限制） */
  monthlyRequestLimit?: number
  /** 每月 token 上限（0 = 不限制） */
  monthlyTokenLimit?: number
  /** 额度告警阈值（0-1） */
  quotaAlertThreshold?: number
}

/** 价格与额度写入请求（字段为 undefined 时不修改，0 表示不限制） */
export interface ModelPriceUpsertRequest {
  serviceType?: string
  priceInput?: number
  priceOutput?: number
  currency?: string
  enabled?: boolean
  description?: string
  rateLimitPerMinute?: number
  hourlyRequestLimit?: number
  hourlyTokenLimit?: number
  dailyRequestLimit?: number
  dailyTokenLimit?: number
  monthlyRequestLimit?: number
  monthlyTokenLimit?: number
  quotaAlertThreshold?: number
}

/** 价格与额度目录（厂商模型 × 已配置单价与额度） */
export const getPriceCatalog = () => {
  return request.get<RouterResponse<ModelPriceCatalogItem[]>>('/config/prices/catalog')
}

/** 按厂商模型名配置价格与额度（存在更新，不存在新建） */
export const upsertModelPrice = (modelName: string, data: ModelPriceUpsertRequest) => {
  return request.put<RouterResponse<any>>(`/config/prices/${encodeURIComponent(modelName)}`, data)
}

/** 删除价格与额度（该模型回到 ¥0 计、无限额） */
export const deleteModelPrice = (modelName: string) => {
  return request.delete<RouterResponse<void>>(`/config/prices/${encodeURIComponent(modelName)}`)
}
