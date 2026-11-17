/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import { computed } from 'vue'
import { useEnterpriseStore } from '@/stores/enterprise'
import type { EnterpriseFeatureKey } from '@/api/enterprise'

/**
 * 企业能力探测 composable（O6）：在 {@link useEnterpriseStore} 之上提供的
 * 轻量入口，保持与原模块级 API（fetchEnterpriseFeatures / isEnterpriseFeatureEnabled）
 * 的兼容，同时暴露语义化 getter。
 *
 * 状态唯一来源是 stores/enterprise.ts（Pinia），本文件不再持有独立状态，避免双源漂移。
 *
 * 背景：商业版复用同一套开源 SPA，靠「后端增强 API + 前端条件渲染」区分能力。
 * 未登录 / 403 / 网络 / 无企业 jar → 一律静默保持空能力集，企业 UI 不渲染、不报错。
 */
const hasLoginToken = (): boolean => !!localStorage.getItem('admin_token')

/**
 * 拉取企业能力集（幂等：底层 store 做并发去重，重复调用共享同一 Promise）。
 *
 * 未登录时直接降级为空能力集，避免对受保护端点发起无谓的匿名请求。
 * 登录页如需在匿名态探测 SSO 可用性，请直接调用 store 的 ensureLoaded()。
 */
export const fetchEnterpriseFeatures = async (): Promise<void> => {
  if (!hasLoginToken()) {
    return
  }
  await useEnterpriseStore().ensureLoaded()
}

/**
 * 单键判定：仅当探测结果中该键显式为 true 时可用。
 *
 * @param key 能力键（如 'sso' / 'multiTenant' / 'billing'）
 */
export const isEnterpriseFeatureEnabled = (key: string): boolean =>
  useEnterpriseStore().isEnabled(key as EnterpriseFeatureKey)

/** 企业能力探测 composable 入口 */
export function useEnterpriseFeatures() {
  const store = useEnterpriseStore()
  return {
    features: computed(() => store.features),
    loaded: computed(() => store.loaded),
    fetchEnterpriseFeatures,
    isEnterpriseFeatureEnabled,
    /** 按菜单 MenuItem.feature 语义值（短横线）判定是否解锁 */
    isMenuFeatureEnabled: (menuFeature?: string) => store.isMenuFeatureEnabled(menuFeature),
    isEnabled: (key: string) => store.isEnabled(key as EnterpriseFeatureKey),
    sso: computed(() => store.sso),
    multiTenant: computed(() => store.multiTenant),
    billing: computed(() => store.billing),
    moderation: computed(() => store.moderation),
    tenantManagement: computed(() => store.tenantManagement),
    securityDashboard: computed(() => store.securityDashboard),
    auditChain: computed(() => store.auditChain),
    dlp: computed(() => store.dlp),
    kms: computed(() => store.kms),
    agentGateway: computed(() => store.agentGateway),
    enterpriseEdition: computed(() => store.enterpriseEdition),
    ensure: () => store.ensureLoaded()
  }
}
