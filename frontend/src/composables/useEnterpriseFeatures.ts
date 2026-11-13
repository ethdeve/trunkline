/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 企业能力探测 composable（O6）
 *
 * 背景：商业版复用同一套开源 SPA，靠「后端增强 API + 前端条件渲染」区分能力。
 * 本 composable 拉取一次 `GET /api/v1/enterprise/features` 并模块级缓存：
 *
 * - `features`：能力键值对（社区版恒为 `{}`）
 * - `isEnterpriseFeatureEnabled(key)`：单键判定（缺键或 false 一律不可用）
 * - `loaded`：是否已完成至少一次探测（成功或降级都算）
 *
 * 降级策略与 useInstanceAvailability 一致：未登录 / 403 / 网络 / 无企业 jar，
 * 一律静默保持空能力集——企业 UI 渲染「升级企业版」占位而非报错，
 * 不影响任何社区版功能。能力属于「部署形态」而非「用户」，切换账号无需重置。
 */
import { ref } from 'vue'
import { getEnterpriseFeatures, type EnterpriseFeatures } from '@/api/enterprise'

/** 全模块共享一份（多个组件/路由复用，避免重复探测） */
const features = ref<EnterpriseFeatures>({})
const loaded = ref(false)
/** 防并发重复请求（与 useInstanceAvailability 同一模式） */
let fetchPromise: Promise<void> | null = null

/** 是否已登录（与 stores/user.ts 的 admin_token 惯例一致；未登录直接降级） */
const hasLoginToken = (): boolean => !!localStorage.getItem('admin_token')

/**
 * 拉取企业能力集（幂等：并发调用共享同一 Promise）。
 *
 * 失败静默降级：保持空能力集 + 置 loaded，绝不向调用方抛错。
 */
export const fetchEnterpriseFeatures = async (): Promise<void> => {
  if (!hasLoginToken()) {
    features.value = {}
    loaded.value = true
    return
  }
  if (fetchPromise) return fetchPromise
  fetchPromise = (async () => {
    try {
      const data = await getEnterpriseFeatures()
      features.value = data && typeof data === 'object' ? data : {}
    } catch (error) {
      console.warn('企业能力探测不可用，按社区版渲染:', error)
      features.value = {}
    } finally {
      loaded.value = true
      fetchPromise = null
    }
  })()
  return fetchPromise
}

/**
 * 单键判定：仅当探测结果中该键显式为 true 时可用。
 *
 * @param key 能力键（如 'sso' / 'tenant' / 'auditChain'）
 */
export const isEnterpriseFeatureEnabled = (key: string): boolean => {
  return features.value[key] === true
}

/** 企业能力探测 composable 入口 */
export function useEnterpriseFeatures() {
  return {
    features,
    loaded,
    fetchEnterpriseFeatures,
    isEnterpriseFeatureEnabled
  }
}
