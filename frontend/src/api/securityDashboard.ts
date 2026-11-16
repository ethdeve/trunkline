/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/** B6 态势中心一屏数据（后端只返回观测字段，不含任何原始请求正文）。 */
export interface SecurityDashboardData {
  window?: { from?: string; to?: string; hours?: number }
  statistics?: Record<string, unknown>
  risks?: Array<{
    riskKey?: string
    alertType?: string
    userId?: string
    ipAddress?: string
    timestamp?: string
    message?: string
    resolved?: boolean
  }>
  riskCounts?: Record<string, number>
  capabilities?: Record<string, { available?: boolean; reason?: string; status?: unknown }>
  alertRule?: Record<string, unknown>
}

const unwrap = (body: unknown): SecurityDashboardData => {
  if (body && typeof body === 'object' && 'data' in (body as Record<string, unknown>)) {
    const inner = (body as Record<string, unknown>).data
    if (inner && typeof inner === 'object') return inner as SecurityDashboardData
  }
  return (body ?? {}) as SecurityDashboardData
}

/**
 * 拉取一屏态势。端点未解锁 / 无权限时抛错，由调用页降级为空态，
 * 不在这里吞异常，避免把「权限不足」误显示成「系统正常但无数据」。
 */
export const getSecurityDashboard = async (from?: string, to?: string): Promise<SecurityDashboardData> => {
  const response = await request.get<RouterResponse<SecurityDashboardData>>(
    '/v1/enterprise/security-dashboard/dashboard',
    { params: { from, to } }
  )
  return unwrap(response.data)
}

/** 人工处置一条风险（只改企业处置表，操作人取认证上下文）。 */
export const resolveSecurityRisk = async (riskKey: string, note: string): Promise<boolean> => {
  const response = await request.post<RouterResponse<{ resolved?: boolean }>>(
    '/v1/enterprise/security-dashboard/resolve',
    { riskKey, note }
  )
  const body = response.data as unknown
  if (body && typeof body === 'object' && 'data' in (body as Record<string, unknown>)) {
    const inner = (body as Record<string, unknown>).data
    if (inner && typeof inner === 'object') {
      return (inner as { resolved?: boolean }).resolved === true
    }
  }
  return (body as { resolved?: boolean } | null)?.resolved === true
}
