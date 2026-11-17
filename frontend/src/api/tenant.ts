/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'

/** 解包裸响应（企业端点可能返回裸 List/Map 或 {data} 包裹）；内联避免依赖已迁出的 enterpriseShared。 */
function unwrapEnterprise<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'data' in (body as Record<string, unknown>)) {
    const inner = (body as Record<string, unknown>).data
    if (inner !== null && typeof inner === 'object') return inner as T
  }
  return (body ?? {}) as T
}

/** 从 axios 错误提取后端文案。 */
function enterpriseError(error: unknown, fallback = '请求失败'): Error {
  if (error && typeof error === 'object') {
    const err = error as { message?: unknown; response?: { data?: unknown } }
    const body = err.response?.data
    if (body && typeof body === 'object') {
      for (const key of ['message', 'error']) {
        const value = (body as Record<string, unknown>)[key]
        if (typeof value === 'string' && value.length > 0) return new Error(value)
      }
    }
    if (typeof err.message === 'string' && err.message.length > 0) return new Error(err.message)
  }
  return new Error(fallback)
}

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
    const payload = unwrapEnterprise<unknown>(res.data)
    return Array.isArray(payload) ? (payload as TenantRecord[]) : []
  } catch {
    return []
  }
}

/**
 * 开通（创建）租户；受 license maxTenants 刻度约束。
 *
 * <p>成功返回后端落库的 TenantRecord；失败抛可读 Error（超限 / 格式非法 / 无权限）。</p>
 */
export const createTenant = async (tenantId: string, displayName?: string): Promise<TenantRecord> => {
  try {
    const res = await request.post('/v1/enterprise/tenants', {
      tenantId: tenantId.trim(),
      displayName: displayName?.trim() || undefined
    })
    return unwrapEnterprise<TenantRecord>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/**
 * 软停用租户（保留审计痕迹）；404 视为「已不存在」并抛可读错误。
 */
export const disableTenant = async (tenantId: string): Promise<void> => {
  try {
    await request.delete(`/v1/enterprise/tenants/${encodeURIComponent(tenantId)}`)
  } catch (error) {
    throw enterpriseError(error)
  }
}
