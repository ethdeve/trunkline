/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'

/**
 * 企业能力探测（O6）——前端特性开关的唯一数据源。
 *
 * <p>社区版（无企业 jar / 未购买对应功能位）返回空能力集 `{}`；
 * 企业 jar 在场且 license 解锁对应功能位时，返回对应能力键值对。
 * 前端把「缺键或 false」一律按不可用渲染，不得假设键恒在。</p>
 *
 * <p>响应形态：该端点直接返回原始 Map（非 {@code RouterResponse} 包裹），
 * 因此读取时优先取 {@code body.data}，缺失则回落到 {@code body} 本身，
 * 兼容将来可能的包裹形态。</p>
 */
export interface EnterpriseFeatures {
  /** 版本标识：community / enterprise */
  edition?: string
  /** license 当前是否有效（签名/过期/回滚校验通过） */
  licenseValid?: boolean
  /** license 是否处于强制模式（false 时降级社区版，绝不解锁任何功能位） */
  licenseEnforce?: boolean
  /** 授权租户上限（0 表示未限制） */
  maxTenants?: number
  /** 单点登录（OIDC / 飞书 / 钉钉 / 企微） */
  sso?: boolean
  /** 多租户 */
  multiTenant?: boolean
  /** 内容审核 */
  moderation?: boolean
  /** 商业计费 */
  billing?: boolean
  /** 租户管理控制台（license 解锁 + 注册表已装配 才为 true） */
  tenantManagement?: boolean
  /** B6：安全态势中心（一屏态势 + 风险处置闭环） */
  securityDashboard?: boolean
  /** B1：审计哈希链 / 合规证据包 / B7 WORM 归档 */
  auditChain?: boolean
  /** B2：出站内容 DLP */
  dlp?: boolean
  /** B3：KMS 信封加密 */
  kms?: boolean
  /** M4：Agent 工具网关治理 */
  agentGateway?: boolean
  /** #9：数据分级出境（密级 × 地域，含人工确认） */
  dataClassification?: boolean
  /** #10：被遗忘权精确擦除 */
  erasure?: boolean
  /** 预留：企业版可注入任意扩展能力键 */
  [key: string]: unknown
}

/** 后端 features 端点返回的、与菜单 feature 标记对应的功能位键 */
export type EnterpriseFeatureKey =
  | 'sso'
  | 'multiTenant'
  | 'moderation'
  | 'billing'
  | 'tenantManagement'
  | 'securityDashboard'
  | 'auditChain'
  | 'dlp'
  | 'kms'
  | 'agentGateway'
  | 'dataClassification'
  | 'erasure'

/**
 * 菜单 MenuItem.feature 字段值 → 后端功能位键 的映射。
 *
 * 菜单配置用短横线语义（multi-tenant），后端返回驼峰（multiTenant），
 * 这里统一收口，避免渲染层散落字符串比较。
 */
export const MENU_FEATURE_TO_KEY: Record<string, EnterpriseFeatureKey> = {
  sso: 'sso',
  'multi-tenant': 'multiTenant',
  moderation: 'moderation',
  billing: 'billing',
  'tenant-management': 'tenantManagement',
  'security-dashboard': 'securityDashboard',
  'audit-chain': 'auditChain',
  dlp: 'dlp',
  kms: 'kms',
  'agent-gateway': 'agentGateway'
}

/**
 * 探测当前部署的企业能力集。
 *
 * 任意已登录角色可读（后端 SELF 级）；响应仅含能力键值对，无敏感信息。
 * 接口失败（社区版无此端点 / 网络抖动 / 登录前匿名不可达）时返回空对象，
 * 前端按「全部未解锁」渲染（缺键即 false，绝不假设恒 true）。
 */
export const getEnterpriseFeatures = async (): Promise<EnterpriseFeatures> => {
  try {
    const res = await request.get('/v1/enterprise/features')
    const body = res.data as unknown
    const payload =
      body && typeof body === 'object' && 'data' in (body as Record<string, unknown>)
        ? (body as Record<string, unknown>).data
        : body
    return (payload ?? {}) as EnterpriseFeatures
  } catch {
    return {}
  }
}
