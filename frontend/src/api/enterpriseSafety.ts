/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import { enterpriseError, unwrapEnterprise } from '@/api/enterpriseShared'

/** DLP 引擎状态：只暴露规则 id / 分类 / 动作，不含正则原文。 */
export interface DlpStatus {
  enabled?: boolean
  ruleCount?: number
  rules?: Array<{ id?: string; category?: string; action?: string }>
  skipModels?: string[]
  rulesByAction?: Record<string, number>
}

export interface DlpTestResult {
  engineFailure?: boolean
  wouldBlock?: boolean
  hits?: Array<{ ruleId?: string; category?: string; action?: string }>
}

/** KMS 状态：只暴露 provider / 版本 / 算法，不含密钥材料。 */
export interface KmsStatus {
  enabled?: boolean
  provider?: string
  available?: boolean
  activeVersion?: string
  availableVersions?: string[]
  algorithm?: string
  legacyDecrypt?: string
}

/** 内容审核状态：只暴露条数与开关，不暴露词表 / 正则原文。 */
export interface ModerationStatus {
  engineEnabled?: boolean
  engineAvailable?: boolean
  enforce?: boolean
  threshold?: number
  termCount?: number
  regexCount?: number
  inheritDlpRules?: boolean
  failClosedWhenEmpty?: boolean
  outputSideEnabled?: boolean
  outputWindowChars?: number
}

export interface ModerationTestResult {
  engineAvailable?: boolean
  wouldBlock?: boolean
  score?: number
  threshold?: number
  hits?: string[]
}

export const getDlpStatus = async (): Promise<DlpStatus> => {
  try {
    const res = await request.get('/v1/enterprise/dlp/status')
    return unwrapEnterprise<DlpStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 规则试检：响应不回显原文与命中片段。 */
export const testDlp = async (text: string): Promise<DlpTestResult> => {
  try {
    const res = await request.post('/v1/enterprise/dlp/test', { text })
    return unwrapEnterprise<DlpTestResult>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const getKmsStatus = async (): Promise<KmsStatus> => {
  try {
    const res = await request.get('/v1/enterprise/kms/status')
    return unwrapEnterprise<KmsStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const getModerationStatus = async (): Promise<ModerationStatus> => {
  try {
    const res = await request.get('/v1/enterprise/moderation/status')
    return unwrapEnterprise<ModerationStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 内容试检：只返回是否拦截 / 分数 / 命中规则 id，不回显原文。 */
export const testModeration = async (text: string): Promise<ModerationTestResult> => {
  try {
    const res = await request.post('/v1/enterprise/moderation/test', { text })
    return unwrapEnterprise<ModerationTestResult>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}
