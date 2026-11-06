/**
 * Trunkline Console 路由表 + RBAC 守卫（一期骨架）
 *
 * - 一期真实页面 2 个（总览/快速开始占位），其余 8 项 comingSoon 占位，
 *   菜单树一次写全（config/menu.ts），二期起只增组件不改配置
 * - 守卫逻辑收编自旧台：认证 → token 过期 → 权限码（meta.permissions 需全部拥有，ADMIN 恒通过）
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { menuGroups, flattenMenuItems } from '@/config/menu'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'
import BlankLayout from '@/layouts/BlankLayout.vue'

/** 解析 JWT exp（秒），过期返回 true；非 JWT 格式视为不过期（与旧台语义一致） */
function isTokenExpired(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]))
    if (typeof payload.exp === 'number') {
      return payload.exp * 1000 < Date.now()
    }
    return false
  } catch {
    return false
  }
}

/**
 * 路由表（导出供 P0-K 权限一致性巡检使用，见 utils/auditPermissionConsistency.ts）。
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: BlankLayout,
    children: [
      {
        path: '',
        name: 'login',
        component: () => import('@/views/login/Login.vue'),
        meta: { public: true }
      }
    ]
  },
  {
    path: '/',
    component: ConsoleLayout,
    redirect: '/my-quota',
    children: [
      // ---- 二期接入链路 ----
      {
        // 【P0-0 已下线】B 套「接入模型」(/models) —— 其「价格与额度」tab 是纯 mock：
        // 7 条硬编码假数据、保存只关弹窗不写库，页面却写着「改完立即生效」。
        // 属"会骗人"页面，已随 P0-0 移出代码库（frontend/src/views/models/ModelPricing.vue）。
        // 现统一收敛到 A 套「接入管理」(/providers)，本路由仅保留重定向以承接
        // 历史链接与深链。
        // 【2026-11-02】A 套的「价格与额度」与「已接入模型」两表已合并为「模型配置」
        // （tab key = models），旧的 pricing / instances 一律映射到 models。
        path: 'models',
        redirect: to => {
          const tab = (to.query.tab as string) || ''
          const query = { ...to.query }
          const tabByLegacy: Record<string, string> = {
            providers: 'providers',
            models: 'models',
            pricing: 'models',
            instances: 'models'
          }
          query.tab = tabByLegacy[tab] || 'providers'
          return { path: '/providers', query }
        }
      },
      {
        path: 'model-categories',
        name: 'model-categories',
        component: () => import('@/views/config/ServiceManagement.vue'),
        meta: { title: '模型分类' }
      },
      {
        path: 'model-pools',
        name: 'model-pools',
        component: () => import('@/views/config/pools/PoolManagement.vue'),
        meta: { title: '虚拟模型' }
      },
      {
        // 傻瓜式快速接入向导：选服务商 → 填密钥 → 导入模型 → 自动建分组+测试密钥+复制 curl
        path: 'quick-start',
        name: 'quick-start',
        component: () => import('@/views/tools/QuickStartWizard.vue'),
        meta: { title: '快速接入' }
      },
      {
        path: 'protocol-adapters',
        name: 'protocol-adapters',
        component: () => import('@/views/config/AdapterManagement.vue'),
        meta: { title: '模型适配器' }
      },
      {
        path: 'playground',
        name: 'playground',
        component: () => import('@/views/playground/PlaygroundView.vue'),
        meta: { title: '测试场' }
      },
      {
        path: 'keys',
        name: 'keys',
        component: () => import('@/views/security/KeysView.vue'),
        meta: { title: '密钥管理' }
      },
      {
        // 接入指南不占菜单（定稿）：顶栏帮助图标 + 向导第 3 步两处入口
        path: 'guide',
        name: 'guide',
        component: () => import('@/views/tools/GuideView.vue'),
        meta: { public: false, title: '接入指南' }
      },
      {
        // 内置帮助文档（后端 resources/docs 提供 md 原文，前端渲染）
        path: 'docs',
        name: 'docs',
        component: () => import('@/views/docs/DocView.vue'),
        meta: { public: false, title: '帮助文档' }
      },
      // ---- 三期观测 ----
      {
        path: 'observe',
        name: 'observe',
        component: () => import('@/views/observe/ObserveView.vue'),
        meta: { title: '观测中心' }
      },
      {
        // 【2026-11-05】「调用详情」菜单已下线，内容并入「调用观测」(/observability)：
          //   - 调用列表 tab 与调用历史重叠 → 调用历史点 Trace ID 打开追踪详情抽屉
          //   - 调用分析 → 调用观测页 tab（analysis）
          //   - 高级：追踪管理 → 调用观测页权限折叠面板（tracing:config:manage）
          //   - 【2026-11-06】原「高级查询」tab 已移除，深链 tab=search 回落到 history
        // 本路由仅保留重定向承接历史链接与深链（tab 名映射）。
        path: 'tracing',
        redirect: to => {
          const tab = (to.query.tab as string) || ''
          const query = { ...to.query }
          const tabByLegacy: Record<string, string> = {
            list: 'history',
            dashboard: 'analysis',
            search: 'history'
          }
          if (tabByLegacy[tab]) query.tab = tabByLegacy[tab]
          else delete query.tab
          return { path: '/observability', query }
        }
      },
      // ---- 四期治理与系统 ----
      {
        path: 'strategy',
        name: 'strategy',
        component: () => import('@/views/strategy/StrategyView.vue'),
        meta: { title: '路由规则' }
      },
      {
        path: 'quota',
        name: 'quota',
        component: () => import('@/views/strategy/QuotaView.vue'),
        meta: { title: '用量配额' }
      },
      {
        // 旧入口 /settings → 按深度链参数重定向到对应分组路径（menu.ts 已拆 4 个菜单项）
        path: 'settings',
        redirect: to => {
          const tab = (to.query.tab as string) || ''
          const query = { ...to.query }
          delete query.tab
          const pathByTab: Record<string, string> = {
            // 新分组名
            access: 'settings/accounts',
            modelConfig: 'settings/model-config',
            auditVersions: 'settings/audit-versions',
            monitoring: 'settings/monitoring',
            // 旧 tab 名（SettingsView 历史深链）
            accounts: 'settings/accounts',
            permissions: 'settings/accounts',
            providerTemplates: 'settings/model-config',
            modelCategories: 'settings/model-config',
            protocolAdapters: 'settings/model-config',
            audit: 'settings/audit-versions',
            versions: 'settings/audit-versions',
            persistence: 'settings/monitoring'
          }
          return { path: '/' + (pathByTab[tab] || 'settings/accounts'), query }
        }
      },
      {
        path: 'settings/accounts',
        name: 'settings-accounts',
        component: () => import('@/views/settings/SettingsView.vue'),
        meta: { title: '账号管理', defaultGroup: 'access' }
      },
      {
        path: 'settings/model-config',
        name: 'settings-model-config',
        component: () => import('@/views/settings/SettingsView.vue'),
        meta: { title: '模型配置', defaultGroup: 'modelConfig' }
      },
      {
        path: 'settings/audit-versions',
        name: 'settings-audit-versions',
        component: () => import('@/views/settings/SettingsView.vue'),
        meta: { title: '审计与版本', defaultGroup: 'auditVersions' }
      },
      {
        path: 'settings/monitoring',
        name: 'settings-monitoring',
        component: () => import('@/views/settings/SettingsView.vue'),
        meta: { title: '状态监控', defaultGroup: 'monitoring' }
      },
      {
        // v3.5 Trunkline：网关级默认配置的集中入口
        // （记录治理 / 负载均衡默认策略 / 熔断阈值 / 用量配额）
        path: 'settings/global',
        name: 'settings-global',
        component: () => import('@/views/settings/GlobalSettingsView.vue'),
        meta: { title: '全局配置' }
      },
      // ---- v3.5 四组菜单新增路由 ----
      {
        // 「我的额度」：员工版额度首页（v3.5 Trunkline UI 改造，按原型实现）
        // 与技术治理台的 /overview 并存——本页只讲「我的额度、我的密钥、我的账单」
        path: 'my-quota',
        name: 'my-quota',
        component: () => import('@/views/my/MyQuotaView.vue'),
        meta: { title: '我的额度' }
      },
      {
        path: 'my-keys',
        name: 'my-keys',
        component: () => import('@/views/my/MyKeysView.vue'),
        meta: { title: '我的密钥' }
      },
      {
        path: 'my-bills',
        name: 'my-bills',
        component: () => import('@/views/my/MyBillsView.vue'),
        meta: { title: '我的账单' }
      },
      {
        path: 'team',
        name: 'team',
        component: () => import('@/views/team/TeamUsageView.vue'),
        meta: { title: '团队用量' }
      },
      {
        path: 'quickstart',
        name: 'quickstart',
        component: () => import('@/views/my/QuickstartView.vue'),
        meta: { title: '快速上手' }
      },
      {
        // 接入管理：接厂商 + 价格与额度（v3.5 原型化）
        path: 'providers',
        name: 'providers',
        component: () => import('@/views/providers/ProviderConsoleView.vue'),
        meta: { title: '接入管理' }
      },
      {
        // 全部密钥：复用既有密钥管理视图（管理员视角）
        path: 'all-keys',
        name: 'all-keys',
        component: () => import('@/views/security/KeysView.vue'),
        meta: { title: '全部密钥' }
      },
      {
        // 调用观测：复用既有观测中心（后续瘦身为「仅调用历史列表」）
        path: 'observability',
        name: 'observability',
        component: () => import('@/views/observe/ObserveView.vue'),
        meta: { title: '调用观测' }
      },
      {
        // P0-B 撞墙页：无权限访问时落地这里。
        // 刻意不声明 meta.permissions —— 否则会被守卫自己拦下形成死循环。
        // 放在 ConsoleLayout 内而非独立布局：用户仍能看到侧边栏，有别的出口可选。
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/system/ForbiddenView.vue'),
        meta: { title: '无权访问' }
      }
    ]
  },
  // 兜底：未匹配路由回「我的额度」
  { path: '/:pathMatch(.*)*', redirect: '/my-quota' }
]

/**
 * P0-K：把菜单的 permission 派生到路由的 meta.permissions。
 *
 * <p><b>为什么</b>：此前路由 meta.permissions 与菜单 permission 是<b>两份独立声明</b>，
 * 靠人工保持一致。实测已有 6 处漂移：/all-keys /observability /providers /team /quickstart
 * 的路由没声明权限（其中 /all-keys 是「管理员视角的所有员工密钥」，真的没保护）。
 * 守卫之所以还能拦住，是因为它对菜单命中的路由以菜单配置为准 ——
 * 但这是巧合，不是设计。</p>
 *
 * <p><b>修法</b>：菜单 config/menu.ts 的 permission 成为<b>唯一权威</b>。
 * 路由表里手写的 meta.permissions 全部删除，改为在此处按绝对路径反查菜单项并注入。
 * 菜单加了新权限码，路由自动跟着变，不可能再漂移。</p>
 */
function applyMenuPermissions(routeList: RouteRecordRaw[], parentPath = ''): void {
  for (const route of routeList) {
    if (route.children?.length) {
      applyMenuPermissions(route.children, route.path === '/' ? '' : parentPath + '/' + route.path.replace(/^\//, ''))
    }
    // 绝对路径：父路径 + 自身相对路径
    const absolute = route.path.startsWith('/')
      ? route.path
      : (parentPath ? parentPath + '/' + route.path.replace(/^\//, '') : '/' + route.path.replace(/^\//, ''))
    const menuItem = flattenMenuItems().find(c => c.path === absolute)
    if (menuItem?.permission) {
      route.meta = { ...(route.meta || {}), permissions: [menuItem.permission] }
    } else if (route.meta?.permissions) {
      // 菜单里没有该路径 → 保留路由自身的声明（非菜单路由，如 /guide /docs）
      // （本次改造已把手写的 permissions 全删，故此分支当前不会命中）
    }
  }
}

applyMenuPermissions(routes as RouteRecordRaw[])

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const userStore = useUserStore()

  if (to.meta.public) {
    // 已登录访问登录页 → 直达总览
    if (userStore.isAuthenticated()) return { name: 'my-quota' }
    return true
  }

  if (!userStore.isAuthenticated()) {
    return { name: 'login' }
  }

  const token = localStorage.getItem('admin_token')
  if (token && isTokenExpired(token)) {
    userStore.clearToken()
    return { name: 'login' }
  }

  // 菜单项权威可见性（含 roles 逻辑角色）：菜单项存在时以菜单配置为准
  const menuItem = menuGroups.flatMap(g => g.children).find(c => c.path === to.path)
  if (menuItem) {
    // P0-B：撞墙不再静默跳「我的额度」——跳 403 页并带上被拦路径与缺失权限，
    // 让用户知道「被拦的是哪个页面 / 缺什么 / 找谁开」，而不是以为系统坏了。
    if (menuItem.permission && !userStore.hasPermission(menuItem.permission)) {
      return {
        path: '/403',
        query: { from: to.path, missing: menuItem.permission }
      }
    }
    if (menuItem.roles && !userStore.hasLogicalRoleOneOf(menuItem.roles)) {
      return {
        path: '/403',
        query: { from: to.path, role: menuItem.roles.join(',') }
      }
    }
    return true
  }

  // 非菜单路由（旧台遗留 / 工具页）：沿用 meta.permissions 粒度判定
  const required = to.meta.permissions as string[] | undefined
  if (required && required.length > 0 && !required.every(code => userStore.hasPermission(code))) {
    // P0-B：同样跳 403 页，并把缺失的权限码全部带上（用户可能同时缺多个）
    const missing = required.filter(code => !userStore.hasPermission(code))
    return { path: '/403', query: { from: to.path, missing: missing.join(',') } }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · Trunkline 控制台` : 'Trunkline 控制台'
})

export default router
