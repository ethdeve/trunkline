/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/**
 * 厂商模型单价 API（商业版，接入管理「价格」tab；trunkline.license.feature.billing 功能位解锁）
 *
 * 单价属商业能力，由企业版 ModelPriceController 提供（/api/config/prices），开源版无此端点。
 * 额度与限速属开源基础能力，移至 @/api/modelQuota（/api/config/quota），与单价分表分端点。
 *
 * - 价格单位：¥ / 千 token，来源一期手工配置。
 * - 账单按厂商模型名计价（与 model_price.model_name 键一致）。
 */

/** 单价目录条目（一行 = 一个厂商模型） */
export interface ModelPriceCatalogItem {
  /** 厂商模型名（价格表主键，同时是账单计价口径） */
  modelName: string
  /** 行类型：固定 PROVIDER_MODEL（厂商模型） */
  kind: string
  /** 所属供应商名 */
  providerName?: string
  /** 服务分类 */
  serviceType?: string
  /** 代表实例主键（service_instance.id，Long） */
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
  /** 缓存单价（¥ / 千 token），命中缓存的 token 单独计价时使用；未配置为 0 */
  priceCache: number
  /** 定价状态：UNPRICED（未定价，不参与计价）/ PRICED / FREE（显式免费的零价） */
  pricingState?: string
  /** 币种 */
  currency: string
  /** 是否启用（停用后不参与账单计价） */
  enabled: boolean
  /** 说明 */
  description?: string
}

/** 单价写入请求（字段为 undefined 时不修改） */
export interface ModelPriceUpsertRequest {
  serviceType?: string
  /** 输入单价（¥ / 千 token） */
  priceInput?: number
  /** 输出单价（¥ / 千 token） */
  priceOutput?: number
  /** 缓存单价（¥ / 千 token） */
  priceCache?: number
  /** 币种（缺省 CNY） */
  currency?: string
  /** 是否启用（缺省 true） */
  enabled?: boolean
  /** 说明（可选） */
  description?: string
  /**
   * 定价状态：留空时服务端按单价推导。
   * 双 0 不会自动变 FREE —— 免费模型必须显式传 'FREE' 才会返回零价报价，
   * 否则新建空行会被当作「未定价」，绝不静默计入 ¥0。
   */
  pricingState?: 'UNPRICED' | 'PRICED' | 'FREE'
}

/** 单价目录（厂商模型 × 已配置单价） */
export const getPriceCatalog = () => {
  return request.get<RouterResponse<ModelPriceCatalogItem[]>>('/config/prices/catalog')
}

/** 按厂商模型名配置单价（存在更新，不存在新建） */
export const upsertModelPrice = (modelName: string, data: ModelPriceUpsertRequest) => {
  return request.put<RouterResponse<any>>(`/config/prices/${encodeURIComponent(modelName)}`, data)
}

/** 删除单价（该模型回到未定价） */
export const deleteModelPrice = (modelName: string) => {
  return request.delete<RouterResponse<void>>(`/config/prices/${encodeURIComponent(modelName)}`)
}
