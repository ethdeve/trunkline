/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import { enterpriseError, unwrapEnterprise } from '@/api/enterpriseShared'

/** B4 对账差异（只含记账字段，不含原始 prompt / 请求正文）。 */
export interface ReconciliationDifference {
  diffKey?: string
  traceId?: string
  tenantId?: string
  diffType?: string
  expectedCost?: string | number
  actualCost?: string | number
  expectedTokens?: number
  actualTokens?: number
  model?: string
  occurredAt?: string
  detectedAt?: string
  status?: string
  resolutionNote?: string
  resolvedBy?: string
  resolvedAt?: string
}

/** 按维度聚合的成本归因行。 */
export interface ReconciliationAggregation {
  dimensionValue?: string
  totalCost?: string | number
  totalTokens?: number
  callCount?: number
}

export interface ReconciliationStatus {
  differences?: Record<string, number>
  attributionRows?: number
  dimensions?: string[]
}

export interface ReconciliationRunResult {
  scanned?: number
  differences?: number
  from?: string
  to?: string
  [key: string]: unknown
}

const paramsOf = (from?: string, to?: string, extra?: Record<string, unknown>): Record<string, unknown> => ({
  ...(from ? { from } : {}),
  ...(to ? { to } : {}),
  ...extra
})

export const getReconciliationStatus = async (): Promise<ReconciliationStatus> => {
  try {
    const res = await request.get('/v1/enterprise/reconciliation/status')
    return unwrapEnterprise<ReconciliationStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 手动发起对账（时间窗可空，后端默认最近 24 小时）。 */
export const runReconciliation = async (from?: string, to?: string): Promise<ReconciliationRunResult> => {
  try {
    const res = await request.post('/v1/enterprise/reconciliation/run', { from, to })
    return unwrapEnterprise<ReconciliationRunResult>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const listDifferences = async (
  status = 'OPEN',
  limit = 200,
  from?: string,
  to?: string
): Promise<ReconciliationDifference[]> => {
  try {
    const res = await request.get('/v1/enterprise/reconciliation/differences', {
      params: paramsOf(from, to, { status, limit })
    })
    const payload = unwrapEnterprise<unknown>(res.data)
    return Array.isArray(payload) ? (payload as ReconciliationDifference[]) : []
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** CSV 下载（通过 Blob 保存，不跳转页面）。 */
export const downloadReconciliationCsv = async (status = 'ALL', from?: string, to?: string): Promise<void> => {
  try {
    const res = await request.get('/v1/enterprise/reconciliation/export', {
      params: paramsOf(from, to, { status, limit: 1000 }),
      responseType: 'blob'
    })
    const blob = new Blob([res.data as BlobPart], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `trunkline-reconciliation-${Date.now()}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 人工处置：仅 OPEN → RESOLVED，操作人由服务端取认证上下文。 */
export const resolveDifference = async (diffKey: string, note: string): Promise<boolean> => {
  try {
    const res = await request.post('/v1/enterprise/reconciliation/resolve', { diffKey, note })
    const payload = unwrapEnterprise<{ resolved?: boolean }>(res.data)
    return payload.resolved === true
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 成本归因聚合（维度走后端白名单：tenant/user/model/department/project/costCenter/env）。 */
export const getAttribution = async (
  dimension = 'department',
  from?: string,
  to?: string
): Promise<ReconciliationAggregation[]> => {
  try {
    const res = await request.get('/v1/enterprise/reconciliation/attribution', {
      params: paramsOf(from, to, { dimension })
    })
    const payload = unwrapEnterprise<unknown>(res.data)
    return Array.isArray(payload) ? (payload as ReconciliationAggregation[]) : []
  } catch (error) {
    throw enterpriseError(error)
  }
}
