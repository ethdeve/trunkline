/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import { enterpriseError, unwrapEnterprise } from '@/api/enterpriseShared'

/** 链观测状态（B1）。 */
export interface AuditChainStatus {
  tableReady?: boolean
  chainRows?: number
  tailSeq?: number
  tailHash?: string
  appended?: number
  dropped?: number
  algorithmId?: string
}

/** 哈希链校验结论。 */
export interface ChainVerifyResult {
  fromSeq?: number
  checkedRows?: number
  valid?: boolean
  firstBadSeq?: number
  failureReason?: string
  tailSeq?: number
  tailHash?: string
}

/** 证据包内单行（链行 + 逐行自验结论）。 */
export interface EvidenceEntry {
  row?: {
    seq?: number
    eventId?: string
    userId?: string
    eventTs?: string
    prevHash?: string
    rowHash?: string
  }
  hashValid?: boolean
  linkValid?: boolean
}

/** 证据包（按时间窗导出，可独立复核）。 */
export interface EvidencePackage {
  algorithmId?: string
  generatedAt?: string
  criteria?: Record<string, unknown>
  entries?: EvidenceEntry[]
  segmentIntegrity?: ChainVerifyResult
}

/** 证据包查询条件（时间必填，ISO_LOCAL_DATE_TIME）。 */
export interface EvidenceQuery {
  from: string
  to: string
  userId?: string
  eventId?: string
  requestId?: string
  limit?: number
}

export const getAuditChainStatus = async (): Promise<AuditChainStatus> => {
  try {
    const res = await request.get('/v1/enterprise/audit-chain/status')
    return unwrapEnterprise<AuditChainStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 从指定链序校验（默认 1 = 全链）。 */
export const verifyAuditChain = async (fromSeq = 1): Promise<ChainVerifyResult> => {
  try {
    const res = await request.get('/v1/enterprise/audit-chain/verify', { params: { fromSeq } })
    return unwrapEnterprise<ChainVerifyResult>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 导出证据包 JSON（时间窗必填）。 */
export const exportEvidence = async (query: EvidenceQuery): Promise<EvidencePackage> => {
  try {
    const res = await request.get('/v1/enterprise/audit-chain/evidence', {
      params: { ...query, limit: query.limit ?? 1000 }
    })
    return unwrapEnterprise<EvidencePackage>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}
