/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 企业功能位 store（FE 功能位渲染的状态源）。
 *
 * <p><b>为什么独立成 store</b>：功能位决定是否显示 SSO 入口、租户切换、商业版菜单，
 * 是跨组件（登录页 / 顶栏 / 侧边栏 / 租户切换器）共享的开关。集中在一处，避免各组件
 * 各自打接口、状态漂移。</p>
 *
 * <p><b>只请求一次</b>：功能位在一次会话内不会变（改了要重启 / 换 license），因此做进程内
 * 缓存 + 并发去重。登录页、控制台、租户切换器共用同一份数据。</p>
 *
 * <p><b>失败静默降级</b>：接口挂了 / 社区版无此端点 / 登录前匿名不可达 → 全部功能位按
 * false 处理（不显示任何企业 UI）。误报缺失比误报存在更安全——企业功能不显示只是「少一个
 * 入口」，误显示未授权的企业功能才是越权风险。</p>
 */
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import {
  getEnterpriseFeatures,
  MENU_FEATURE_TO_KEY,
  type EnterpriseFeatures,
  type EnterpriseFeatureKey
} from '@/api/enterprise'

/** 社区版/未加载时的兜底：所有功能位默认未解锁 */
const EMPTY_FEATURES: EnterpriseFeatures = {}

export const useEnterpriseStore = defineStore('enterprise', () => {
  const features = ref<EnterpriseFeatures>(EMPTY_FEATURES)
  const loaded = ref(false)

  /** 并发去重：多个组件同时 onMounted 时只发一次请求 */
  let pending: Promise<EnterpriseFeatures> | null = null

  const edition = computed(() => features.value.edition ?? 'community')
  const licenseValid = computed(() => features.value.licenseValid ?? false)
  const licenseEnforce = computed(() => features.value.licenseEnforce ?? false)
  const maxTenants = computed(() => features.value.maxTenants ?? 0)

  const sso = computed(() => !!features.value.sso)
  const multiTenant = computed(() => !!features.value.multiTenant)
  const moderation = computed(() => !!features.value.moderation)
  const billing = computed(() => !!features.value.billing)
  const tenantManagement = computed(() => !!features.value.tenantManagement)
  const securityDashboard = computed(() => !!features.value.securityDashboard)

  /** 按后端功能位键判断是否解锁（缺键即未解锁，绝不假设恒 true） */
  const isEnabled = (key: EnterpriseFeatureKey): boolean => !!features.value[key]

  /**
   * 按菜单 MenuItem.feature 语义值（短横线）判断是否解锁。
   *
   * 菜单配置用 multi-tenant，后端返回 multiTenant，经 MENU_FEATURE_TO_KEY 收口。
   * 未声明 feature 的菜单项恒返回 true（不受功能位约束）。
   */
  const isMenuFeatureEnabled = (menuFeature?: string): boolean => {
    if (!menuFeature) return true
    const key = MENU_FEATURE_TO_KEY[menuFeature]
    return key ? isEnabled(key) : false
  }

  /** 加载企业功能位（幂等；重复调用复用同一 Promise） */
  const ensureLoaded = async (): Promise<EnterpriseFeatures> => {
    if (loaded.value) return features.value
    if (pending) return pending

    pending = (async () => {
      try {
        const data = await getEnterpriseFeatures()
        features.value = { ...EMPTY_FEATURES, ...data }
      } catch {
        // 接口不可用 → 按「全部未解锁」处理，不抛给调用方
        features.value = EMPTY_FEATURES
      } finally {
        loaded.value = true
        pending = null
      }
      return features.value
    })()

    return pending
  }

  /** 强制刷新（如切换 license 后） */
  const refresh = async (): Promise<EnterpriseFeatures> => {
    loaded.value = false
    return ensureLoaded()
  }

  return {
    features,
    loaded,
    edition,
    licenseValid,
    licenseEnforce,
    maxTenants,
    sso,
    multiTenant,
    moderation,
    billing,
    tenantManagement,
    securityDashboard,
    isEnabled,
    isMenuFeatureEnabled,
    ensureLoaded,
    refresh
  }
})
