/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import { enterpriseError, unwrapEnterprise } from '@/api/enterpriseShared'

/** 归档可观测状态（B7，不含密钥内容）。 */
export interface ArchiveStatus {
  cryptoAvailable?: boolean
  cryptoAlgorithm?: string
  sinkAlgorithm?: string
  manifestHealthy?: boolean
  objects?: number
  pendingDestruction?: number
  chainAvailable?: boolean
  chainTailSeq?: number
  archivedBatches?: number
  archivedRows?: number
  failedBatches?: number
}

/** 归档清单条目（含销毁留痕）。 */
export interface ArchiveMeta {
  name?: string
  sha256?: string
  size?: number
  createdAt?: string
  retentionUntil?: string
  seqFrom?: number
  seqTo?: number
  rowCount?: number
  destroyed?: boolean
  destroyedAt?: string
  destroyedBy?: string
  destroyReason?: string
}

export interface ArchiveResult {
  name?: string
  rows?: number
  sha256?: string
  size?: number
  seqFrom?: number
  seqTo?: number
  skipped?: boolean
}

export interface ArchiveVerification {
  name?: string
  valid?: boolean
  reason?: string
  rowCount?: number
  seqFrom?: number
  seqTo?: number
}

export const getArchiveStatus = async (): Promise<ArchiveStatus> => {
  try {
    const res = await request.get('/v1/enterprise/archive/status')
    return unwrapEnterprise<ArchiveStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const listArchives = async (): Promise<ArchiveMeta[]> => {
  try {
    const res = await request.get('/v1/enterprise/archive/list')
    const payload = unwrapEnterprise<{ archives?: ArchiveMeta[] }>(res.data)
    return payload.archives ?? []
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 已过保留期、等待 ADMIN 显式销毁的对象。 */
export const listPendingArchives = async (): Promise<ArchiveMeta[]> => {
  try {
    const res = await request.get('/v1/enterprise/archive/pending')
    const payload = unwrapEnterprise<{ pending?: ArchiveMeta[] }>(res.data)
    return payload.pending ?? []
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 归档包独立复核（存储完整性 + 解密 + digest 复算）。 */
export const verifyArchive = async (name: string): Promise<ArchiveVerification> => {
  try {
    const res = await request.get('/v1/enterprise/archive/verify', { params: { name } })
    return unwrapEnterprise<ArchiveVerification>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 手动补归档指定链序区间（含端点，单批上限 1000 行）。 */
export const runArchive = async (fromSeq: number, toSeq: number): Promise<ArchiveResult> => {
  try {
    const res = await request.post('/v1/enterprise/archive/archive', null, {
      params: { fromSeq, toSeq }
    })
    return unwrapEnterprise<ArchiveResult>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 期满销毁：原因必填且写进 WORM 清单；操作人由服务端认证上下文提供。 */
export const destroyArchive = async (name: string, reason: string): Promise<void> => {
  try {
    await request.post('/v1/enterprise/archive/destroy', null, { params: { name, reason } })
  } catch (error) {
    throw enterpriseError(error)
  }
}
