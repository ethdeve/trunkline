/**
 * Trunkline Console 菜单配置（v3.5 UI 改造：四组菜单）
 *
 * 设计决策（docs/ui-restyle-plan.md §1 + 用户确认）：
 * - 菜单重组成三组：我的（员工）/ 团队（运维·管理员）/ 系统（仅管理员）。
 *   【2026-11-05】原「策略」组仅剩路由规则一项，并入「团队」组（更名「路由规则」）。
 * - 三角色模型（user / ops / admin）来自 docs/Trunkline-UI设计规格.md §2，与工程现有
 *   JWT 角色（USER/OPERATOR/ADMIN）重映射（见 stores/user.ts 的 logicalRoles）。
 *   社区版权权已固定化（无可配置 RBAC）：`permission` 字段只是「码 → 最低逻辑角色」
 *   可见性映射的键（config/permissionMatrix.ts），后端授权由固定角色矩阵按角色裁决。
 * - 「我的密钥」「我的账单」对普通用户(user/ops)可见、admin 不可见（用户决策 #3）。
 * - 「审计与版本」维持现状（旧皮肤、旧菜单位置，不搬进新视觉改造，用户决策 #4）。
 * - 标题为字面中文（'menu.' 前缀视为 i18n key，ConsoleLayout 渲染时走 t()）。
 */

/** 逻辑角色（规格三角色模型，与 JWT 角色重映射，见 stores/user.ts） */
export type LogicalRole = 'user' | 'ops' | 'admin'

export interface MenuItem {
  /** 路由路径 */
  path: string
  /** 菜单标题（字面中文；'menu.' 前缀视为 i18n key） */
  title: string
  /** 权限码；缺省表示所有已登录用户可见 */
  permission?: string
  /** 逻辑角色可见性（规格三角色）；缺省表示所有角色可见 */
  roles?: LogicalRole[]
  /** Element Plus 图标 kebab-case 名 */
  icon?: string
  /** 页内副标题（承载技术术语） */
  subtitle?: string
  /**
   * P0-B：置灰可见（灰条但可点）。
   *
   * 菜单默认是「无权即隐藏」，但隐藏有个致命问题：用户根本不知道功能存在 →
   * 别人告诉他后他输 URL → 被静默跳走 → 困惑。全量置灰又会让菜单出现一堆灰条，
   * 非技术管理员会以为系统坏了。
   *
   * 因此只对「跨角色高频页」开启置灰（如 ops 也常需要看的全局配置）：
   * 该角色仍能看到这一项，但呈灰色，点击后走 403 页说明缺什么权限、找谁开。
   * 这是「可解释、可点击、指向答案」的入口，而不是死路。
   */
  greyFor?: LogicalRole[]
  /**
   * P0-B 运行时标记：该项当前处于「置灰但可点」状态（由 filterMenuByPermission 写入）。
   * 不是配置字段 —— menu.ts 里不写它。
   */
  greyed?: boolean
  /**
   * 多租户/商业版功能标记（S2 任务 41 预埋）。
   *
   * 取值：'billing' | 'sso' | 'moderation' | 'multi-tenant' | 'audit-proof' | 'alerting'。
   * 社区版当前无 ENT 专属菜单，故尚无菜单项使用此字段；等商业版功能上线后，
   * 带此标记的菜单项在 license 未解锁时应渲染为灰态 + 锁图标 + 「商业版」徽章，
   * 点击弹出升级说明抽屉（渲染逻辑随 ENT-MVP 落地）。
   */
  feature?: string
}

export interface MenuGroup {
  /** 组标识 */
  index: string
  /** 组标题 */
  title: string
  icon: string
  children: MenuItem[]
}

/**
 * 菜单配置：三组 16 项（我的 5 / 团队 7 / 系统 4）。
 * 其中「审计与版本」为保留项（不改造）。与路由表一一对应。
 *
 * 可见性 = (无 permission 或拥有该权限码) 且 (无 roles 或命中其一逻辑角色)。
 */
export const menuGroups: MenuGroup[] = [
  {
    index: 'my',
    title: '我的',
    icon: 'user',
    children: [
      {
        // v3.5 Trunkline：员工版额度首页（按原型实现）
        path: '/my-quota',
        title: '我的额度',
        icon: 'odometer',
        subtitle: '本月花了多少、还剩多少'
      },
      {
        path: '/my-keys',
        title: '我的密钥',
        // 原型 data-roles="user,ops,admin"——管理员也有自己的密钥与账单，
        // 原先漏了 admin，管理员访问这两页会被路由守卫踢走
        roles: ['user', 'ops', 'admin'],
        icon: 'key',
        subtitle: '你名下的密钥和能用的模型'
      },
      {
        path: '/quickstart',
        title: '快速上手',
        permission: 'ai:playground:use',
        icon: 'magic-stick',
        subtitle: '三步把密钥装进你常用的 AI 工具'
      },
      {
        path: '/playground',
        title: '测试场',
        permission: 'ai:playground:use',
        icon: 'chat-dot-round',
        subtitle: '先在这里试一句，确认能用再去工具里配'
      },
      {
        path: '/my-bills',
        title: '我的账单',
        roles: ['user', 'ops', 'admin'],
        icon: 'money',
        subtitle: '按月看自己花了多少钱'
      }
    ]
  },
  {
    index: 'team',
    title: '团队',
    icon: 'office-building',
    children: [
      {
        path: '/team',
        title: '团队用量',
        permission: 'monitoring:tokenusage:read',
        roles: ['ops', 'admin'],
        icon: 'data-analysis',
        subtitle: '全公司花了多少、谁在用'
      },
      {
        path: '/providers',
        title: '接入管理',
        permission: 'config:instances:read',
        roles: ['ops', 'admin'],
        icon: 'connection',
        subtitle: '接新厂商、配模型与价格'
      },
      {
        path: '/model-pools',
        title: '虚拟模型',
        permission: 'config:instances:read',
        roles: ['ops', 'admin'],
        icon: 'collection-tag',
        subtitle: '员工用的模型名，背后可以挂多个厂商模型'
      },
      {
        // 【2026-11-05】原「策略」分组只有这一项（策略中心，页面仅剩路由规则一个 tab），
        // 单项成组浪费一层菜单层级 → 并入「团队」分组，更名「路由规则」如实描述页面内容。
        // 路由路径 /strategy 不变，旧链接与 ?tab= 深链均不受影响。
        path: '/strategy',
        title: '路由规则',
        permission: 'config:rules:read',
        roles: ['ops', 'admin'],
        icon: 'set-up',
        subtitle: '按条件把请求转给不同的模型'
      },
      {
        path: '/all-keys',
        title: '全部密钥',
        // P0-K：此前缺 permission —— 同一页面还有一条 /keys 路由声明了
        // security:apikeys:manage，两条路径权限不一致（其中一条等于没保护）。
        // 菜单的 permission 是唯一权威，两条路由都会自动派生到这里。
        permission: 'security:apikeys:manage',
        roles: ['ops', 'admin'],
        icon: 'stamp',
        subtitle: '员工手上的所有密钥'
      },
      {
        // 【2026-11-05】原「调用详情」(/tracing) 菜单已并入本页：
        // 调用历史点 Trace ID 即可打开追踪详情抽屉；调用分析/高级查询收编为本页 tab，
        // 旧深链 /tracing 由路由重定向承接。
        path: '/observability',
        title: '调用观测',
        permission: 'monitoring:tokenusage:read',
        roles: ['ops', 'admin'],
        icon: 'document',
        subtitle: '谁在什么时候调了什么、链路追踪与分析'
      },
      {
        path: '/settings/monitoring',
        title: '状态监控',
        permission: 'monitoring:tokenusage:read',
        roles: ['ops', 'admin'],
        icon: 'monitor',
        subtitle: '现在有什么坏了、哪些模型被暂时停用'
      }
    ]
  },
  {
    index: 'system',
    title: '系统',
    icon: 'setting',
    children: [
      {
        path: '/settings/accounts',
        title: '账号管理',
        permission: 'system:accounts:manage',
        roles: ['admin'],
        icon: 'user',
        subtitle: '员工的账号和权限'
      },
      {
        path: '/settings/model-config',
        title: '模型配置',
        permission: 'system:accounts:manage',
        roles: ['admin'],
        // P0-B：ops 也常需要看供应商模板 / 分类 / 适配器 → 置灰可见，点击进 403 页
        greyFor: ['ops'],
        icon: 'box',
        subtitle: '厂商预设 / 模型分类 / 自定义接入协议'
      },
      {
        // 保留项（用户决策 #4）：维持现状皮肤与位置，不搬进新视觉改造
        path: '/settings/audit-versions',
        title: '审计与版本',
        permission: 'system:accounts:manage',
        roles: ['admin'],
        icon: 'document',
        subtitle: '审计日志 / 配置版本'
      },
      {
        // v3.5 Trunkline：网关级默认配置的集中入口
        // （记录治理 / 负载均衡默认策略 / 熔断阈值 / 用量配额）
        path: '/settings/global',
        title: '全局配置',
        permission: 'system:accounts:manage',
        roles: ['admin'],
        // P0-B：ops 排查问题时常需要看网关级默认设置 → 置灰可见，点击进 403 页
        greyFor: ['ops'],
        icon: 'setting',
        // P0-G：原文案全是内部术语（记录治理 / 负载均衡 / 熔断阈值 / 用量配额），
        // 改为运维管理员能直接理解的说法（tab 内的详细说明见 GlobalSettingsView）。
        subtitle: '网关级的统一默认设置，可单独调某个模型'
      }
    ]
  }
]

/** 扁平化全部菜单项（命令面板用） */
export function flattenMenuItems(): MenuItem[] {
  return menuGroups.flatMap(g => g.children)
}

/**
 * 取某个菜单项的副标题（页面目标说明的单一文案源）。
 *
 * P0-I：此前说明文案有 4 种平行实现（page-tip / page-heading__sub /
 * PageHeader prop / menu.ts 死数据），必然「有的页面有、有的没有、风格不一」。
 * 统一后：menu.ts 的 subtitle 是唯一文案源，页面通过本函数取用，
 * 侧边栏 tooltip 也用同一份值 —— 菜单与页面永远说同一句话。
 *
 * 注意：menu.ts 是纯配置模块（不 import 组件 / router / stores），
 * 页面 import 它不会形成循环依赖。
 *
 * @param path 菜单项的路由路径（如 '/providers'）
 * @returns 副标题文案；未匹配到菜单项时返回空串（调用方自行决定兜底）
 */
export function menuSubtitle(path: string): string {
  return flattenMenuItems().find(i => i.path === path)?.subtitle ?? ''
}
