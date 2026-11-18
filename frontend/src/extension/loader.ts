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
let cssLinked = false

/**
 * 企业子应用的 scoped 样式在 lib 构建里会抽成同名 module.css。
 * 动态 import module.mjs 不会自动加载这份 CSS（历史上企业页因此丢了全部自绘版式），
 * 这里在 import 前显式注入 <link>，失败也不阻塞模块加载（宿主 Element Plus 样式仍在）。
 */
function ensureEnterpriseCss(moduleUrl: string): void {
  if (cssLinked) return
  cssLinked = true
  try {
    const cssUrl = new URL(moduleUrl, window.location.href)
    if (!cssUrl.pathname.endsWith('.mjs')) return
    cssUrl.pathname = `${cssUrl.pathname.slice(0, -4)}.css`
    const href = cssUrl.href
    if (document.querySelector(`link[data-trunkline-enterprise-css][href="${href}"]`)) return
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.dataset.trunklineEnterpriseCss = 'true'
    link.href = href
    document.head.appendChild(link)
  } catch (error) {
    console.warn('[enterprise] stylesheet link failed', error)
  }
}

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
    ensureEnterpriseCss(url)
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
  cssLinked = false
}
