/**
 * 企业扩展模块契约 v1 —— OSS 冻结面（唯一真源）。
 *
 * 变更规则：本文件由 OSS 持有；企业仓 frontend/src/contract.ts 是它的逐字镜像。
 * 契约 minor 升版时，两侧同步更新（CI 可做 diff 比对）。企业 UI 以自包含子应用
 * （module.mjs）形态接入，菜单/路由元数据由宿主统一渲染，灰态机制对注册项同样生效。
 */

/** 宿主提供给子应用的上下文；features 在宿主 ensureLoaded 之后传入。 */
export interface ConsoleHost {
  /** 功能位全集（未解锁键为 false/缺省） */
  features: Record<string, unknown>
  /** 当前语言 */
  locale: () => 'zh-CN' | 'en-US'
  /** JWT 获取 */
  getToken: () => string | null
  /** 当前租户 ID（宿主 localStorage 的 trunkline_tenant_id） */
  getTenantId: () => string | null
  /** 401 回调：宿主负责跳登录 */
  onUnauthorized: () => void
  /**
   * 宿主导航（跳社区页，或更新承载路由的 page 段）。
   * 子应用内部切页时调用 navigate('/settings/enterprise/<page>') 同步宿主 URL。
   */
  navigate: (path: string) => void
  /** 初始页（宿主承载路由的 defaultPage / dynamicPage） */
  initialPage: string
}

export interface EnterpriseMenuChild {
  path: string
  titleKey: string
  icon?: string
  feature?: string
  subtitleKey?: string
  /** 权限口径（R3-2）：与 OSS MenuItem 对齐，供守卫校验 */
  permission?: string
  roles?: string[]
}

export interface EnterpriseMenuGroup {
  index: string
  titleKey: string
  icon?: string
  children: EnterpriseMenuChild[]
}

export interface EnterpriseModule {
  manifest: {
    version: string
    minConsoleVersion?: string
    menuGroups: EnterpriseMenuGroup[]
    i18nMessages: {
      'zh-CN': Record<string, unknown>
      'en-US': Record<string, unknown>
    }
  }
  mount(el: HTMLElement, host: ConsoleHost): Promise<void> | void
  /**
   * 宿主路由的 page 段变化时调用，切子应用内部页（R4：跨包 Ref 响应式不可靠，
   * 由子应用自维护 page ref，宿主用本方法显式驱动，而非传 Ref 跨包共享）。
   */
  setPage?(page: string): void
  unmount(): void
}
