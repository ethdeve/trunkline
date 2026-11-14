/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'

/**
 * 租户记录（与企业端 TenantRecord 对齐；前端只取渲染所需的字段）。
 */
export interface TenantRecord {
  /** 租户标识（X-Tenant-Id 的合法取值，[a-z0-9-]{1,63}） */
  tenantId: string
  /** 展示名（缺省时前端回退到 tenantId） */
  displayName?: string
  /** 状态：active / disabled */
  status?: string
  /** 创建时间（ISO 字符串） */
  createdAt?: string
}

/**
 * 列出活跃租户（A1-pre：GET /api/v1/enterprise/tenants 返回原始 List，非 RouterResponse 包裹）。
 *
 * 接口失败（非多租户部署 / 无权限）时返回空数组，调用方按「无可选租户」处理。
 */
export const listTenants = async (): Promise<TenantRecord[]> => {
  try {
    const res = await request.get('/v1/enterprise/tenants')
    const body = res.data as unknown
    const payload =
      body && typeof body === 'object' && 'data' in (body as Record<string, unknown>)
        ? (body as Record<string, unknown>).data
        : body
    return (payload ?? []) as TenantRecord[]
  } catch {
    return []
  }
}
