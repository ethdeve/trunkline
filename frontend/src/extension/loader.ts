/**
 * 企业扩展模块加载器（OSS 冻结面）。
 *
 * 纯 OSS（无企业 jar / features 无 ui-modules）：零加载、零网络请求。
 * 企业部署：动态 import /admin/enterprise/module.mjs（企业 jar static），
 * 任何失败静默降级（返回 null + WARN），绝不阻塞宿主社区控制台。
 */
import type { EnterpriseModule } from './types'

const MODULE_URL = '/admin/enterprise/module.mjs'
let loaded: EnterpriseModule | null = null
let attempted = false

/**
 * 探测并加载企业模块。
 *
 * @param features 宿主探测到的功能位（来自 /api/v1/enterprise/features）
 * @returns 模块实例；纯 OSS 或加载失败返回 null
 */
export async function loadEnterpriseModule(
  features: Record<string, unknown> | null | undefined
): Promise<EnterpriseModule | null> {
  if (loaded) return loaded
  if (attempted) return null
  if (!features || features['ui-modules'] !== true) return null

  attempted = true
  const url =
    (import.meta.env.VITE_ENTERPRISE_MODULE_URL as string | undefined) || MODULE_URL
  try {
    const mod = await import(/* @vite-ignore */ url)
    const candidate = (mod.default ?? mod) as EnterpriseModule
    if (!candidate?.manifest?.menuGroups || typeof candidate.mount !== 'function') {
      throw new Error('bad enterprise module manifest')
    }
    loaded = candidate
    return loaded
  } catch (error) {
    // R3-5：不发未捕获异常；即便 URL 返回 200 HTML（SPA fallback）也会在 ESM 解析处失败并落到这里。
    console.warn('[enterprise] module load failed, degraded to community UI', error)
    return null
  }
}

/** 已加载模块（供 Host 同步读取，未加载为 null）。 */
export function getEnterpriseModule(): EnterpriseModule | null {
  return loaded
}

/** 测试/热重载用：清空缓存，允许重新探测。 */
export function resetEnterpriseModule(): void {
  loaded = null
  attempted = false
}
