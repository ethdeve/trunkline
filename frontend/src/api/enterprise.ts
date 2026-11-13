/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'

/**
 * 企业能力探测（O6）——前端特性开关的唯一数据源
 *
 * 社区版返回空能力集 `{}`；企业 jar 在场时返回对应能力键值对
 * （如 `{ sso: true, tenant: true, auditChain: true }`）。
 * 前端把「缺键或 false」一律按不可用渲染，不得假设键恒在。
 */
export interface EnterpriseFeatures {
  /** 单点登录 */
  sso?: boolean
  /** 多租户 */
  tenant?: boolean
  /** 审计哈希链 */
  auditChain?: boolean
  /** 出站 DLP */
  dlp?: boolean
  /** 商业计费 */
  billing?: boolean
  /** 预留：企业版可注入任意扩展能力键 */
  [key: string]: unknown
}

/**
 * 探测当前部署的企业能力集。
 *
 * 任意已登录角色可读（后端 URL 矩阵 SELF）；响应仅含能力键值对，无敏感信息。
 */
export const getEnterpriseFeatures = async (): Promise<EnterpriseFeatures> => {
  const response = await request.get('/v1/enterprise/features')
  return (response.data?.data ?? {}) as EnterpriseFeatures
}
