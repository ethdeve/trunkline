/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import { enterpriseError, unwrapEnterprise } from '@/api/enterpriseShared'

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
