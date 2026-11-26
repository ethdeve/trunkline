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

/**
 * 宿主预留的企业挂载位类型（OSS 冻结面机制，不含业务语义）。
 *
 * v1.1 追加：模型配置页的地域能力是企业版业务 UI，OSS 只提供空挂载位，
 * 由企业子应用 module.mjs 的 mountSlot() 渲染；老模块未实现该方法时挂载位为空。
 */
export type EnterpriseSlotKind =
  | 'instance-region-action'
  | 'residency-header'
  | 'residency-cell'
  /** 模型配置页单价列内的「高级规则」入口：宿主只给模型名，弹窗/业务 UI 全在企业侧 */
  | 'pricing-rules-entry'

/** 单价列高级计价入口上下文；模型名与 model_price 主键一致（实例名） */
export interface PricingRulesEntryCtx {
  instanceName: string
  /** 当前普通单价（后端 ¥/千 token 口径，宿主已完成 UI ¥/百万 → ¥/千 换算） */
  flatPriceInput?: number
  flatPriceOutput?: number
  flatPriceCacheInput?: number
}

/** 操作列 / 编辑弹窗的区域设置入口上下文。宿主只传数据与回调，不提供企业 API 或文案。 */
export interface InstanceRegionActionCtx {
  /** table=列表操作列（宿主 PUT 保存）；form=编辑弹窗（宿主改本地表单） */
  source: 'table' | 'form'
  instanceName: string
  /** 只读快照；企业侧合并后经 onApplyTags 交还 */
  tags: Record<string, string>
  /** 企业侧交还合并后的 tags；持久化方式由宿主决定，结果由企业侧自行提示 */
  onApplyTags: (tags: Record<string, string>) => void | Promise<boolean>
}

/** 地域列表头上下文（自动预填入口）。 */
export interface ResidencyHeaderCtx {
  /** 宿主已判定的写权限，企业侧不再重复取权限 */
  canWrite: boolean
  /** 企业侧完成预填/确认后通知宿主刷新行数据 */
  onChanged: () => void
}

/** 地域单元格上下文。 */
export interface ResidencyCellCtx {
  instanceName: string
  canWrite: boolean
  /** 企业侧完成预填/确认后通知宿主刷新行数据 */
  onChanged: () => void
}

export type EnterpriseSlotCtx =
  | InstanceRegionActionCtx
  | ResidencyHeaderCtx
  | ResidencyCellCtx
  | PricingRulesEntryCtx

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
  /**
   * 可选：在宿主预留的空挂载位渲染企业业务 UI（如模型配置页的地域能力）。
   * OSS 侧只提供 el 与数据/回调上下文；返回值用于卸载 mini app。
   * 老版本企业模块未实现时，挂载位保持为空，社区版零影响。
   */
  mountSlot?(
    el: HTMLElement,
    kind: EnterpriseSlotKind,
    ctx: EnterpriseSlotCtx
  ): (() => void) | void
  unmount(): void
}
