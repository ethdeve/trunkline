/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/**
 * 厂商模型额度与限速 API（开源基础能力，接入管理「额度与限速」tab）
 *
 * 对应后端 ModelQuotaController（开源版）：
 * - GET  /api/config/quota/catalog   额度与限速目录（一行 = 一个已接入厂商模型）
 * - GET  /api/config/quota/list      全部额度行（含停用；无路径 GET 属运行时配额配置）
 * - PUT  /api/config/quota/{modelName}  按厂商模型名 upsert（null=不改，0=不限制）
 * - DELETE /api/config/quota/{modelName} 删除（回到不限额）
 *
 * 单价属商业能力，由 @/api/providerPrice（企业版 /api/config/prices）承载，本文件不含任何单价字段。
 */

/** 额度与限速目录条目（一行 = 一个厂商模型） */
export interface ModelQuotaCatalogItem {
  /** 厂商模型名（额度表主键，= 实例名 instance_name） */
  modelName: string
  /** 行类型：固定 PROVIDER_MODEL */
  kind: string
  /** 所属供应商名 */
  providerName?: string
  /** 服务分类 */
  serviceType?: string
  /** 代表实例主键 */
  instanceId?: number
  /** 代表实例的熔断器是否已启用 */
  circuitBreakerEnabled?: boolean
  /** 代表实例的限流器是否已启用 */
  rateLimitEnabled?: boolean
  /** 是否启用额度配置 */
  enabled?: boolean
  /** 说明 */
  description?: string
  /** 每分钟请求数上限（0 = 不限制） */
  rateLimitPerMinute?: number
  /** 每小时请求上限（0 = 不限制） */
  hourlyRequestLimit?: number
  /** 每小时 token 上限（0 = 不限制） */
  hourlyTokenLimit?: number
  /** 每日请求上限（0 = 不限制） */
  dailyRequestLimit?: number
  /** 每日 token 上限（0 = 不限制） */
  dailyTokenLimit?: number
  /** 每月请求上限（0 = 不限制） */
  monthlyRequestLimit?: number
  /** 每月 token 上限（0 = 不限制） */
  monthlyTokenLimit?: number
  /** 配额告警阈值（0-1） */
  quotaAlertThreshold?: number
}

/** 额度与限速写入请求（字段为 undefined 时不修改，0 表示不限制） */
export interface ModelQuotaUpsertRequest {
  serviceType?: string
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

/** 额度与限速目录（厂商模型 × 已配置额度与限速） */
export const getModelQuotaCatalog = () => {
  return request.get<RouterResponse<ModelQuotaCatalogItem[]>>('/config/quota/catalog')
}

/** 按厂商模型名配置额度与限速（存在更新，不存在新建） */
export const upsertModelQuota = (modelName: string, data: ModelQuotaUpsertRequest) => {
  return request.put<RouterResponse<any>>(`/config/quota/${encodeURIComponent(modelName)}`, data)
}

/** 删除额度与限速（该模型回到不限额） */
export const deleteModelQuota = (modelName: string) => {
  return request.delete<RouterResponse<void>>(`/config/quota/${encodeURIComponent(modelName)}`)
}
