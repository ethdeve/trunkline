/**
 * JAiRouter Console 路由表 + RBAC 守卫（一期骨架）
 *
 * - 一期真实页面 2 个（总览/快速开始占位），其余 8 项 comingSoon 占位，
 *   菜单树一次写全（config/menu.ts），二期起只增组件不改配置
 * - 守卫逻辑收编自旧台：认证 → token 过期 → 权限码（meta.permissions 需全部拥有，ADMIN 恒通过）
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
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

const routes: RouteRecordRaw[] = [
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
    redirect: '/overview',
    children: [
      {
        path: 'overview',
        name: 'overview',
        component: () => import('@/views/workbench/Overview.vue'),
        meta: { permissions: ['overview:dashboard:read'], title: '总览' }
      },
      {
        path: 'quick-start',
        name: 'quick-start',
        component: () => import('@/views/workbench/QuickStart.vue'),
        meta: { permissions: ['config:instances:read'], title: '快速开始' }
      },
      // ---- 二期接入链路 ----
      {
        path: 'models',
        name: 'models',
        component: () => import('@/views/models/ModelsView.vue'),
        meta: { permissions: ['config:instances:read'], title: '接入的模型' }
      },
      {
        path: 'model-categories',
        name: 'model-categories',
        component: () => import('@/views/config/ServiceManagement.vue'),
        meta: { permissions: ['config:instances:read'], title: '模型分类管理' }
      },
      {
        path: 'model-pools',
        name: 'model-pools',
        component: () => import('@/views/config/pools/PoolManagement.vue'),
        meta: { permissions: ['config:instances:read'], title: '公共模型池' }
      },
      {
        path: 'protocol-adapters',
        name: 'protocol-adapters',
        component: () => import('@/views/config/AdapterManagement.vue'),
        meta: { permissions: ['config:instances:read'], title: '适配器设置' }
      },
      {
        path: 'playground',
        name: 'playground',
        component: () => import('@/views/playground/PlaygroundView.vue'),
        meta: { permissions: ['ai:playground:use'], title: '测试场' }
      },
      {
        path: 'keys',
        name: 'keys',
        component: () => import('@/views/security/KeysView.vue'),
        meta: { permissions: ['security:apikeys:manage'], title: '密钥管理' }
      },
      {
        // 接入指南不占菜单（定稿）：顶栏帮助图标 + 向导第 3 步两处入口
        path: 'guide',
        name: 'guide',
        component: () => import('@/views/tools/GuideView.vue'),
        meta: { public: false, title: '接入指南' }
      },
      // ---- 三期观测 ----
      {
        path: 'observe',
        name: 'observe',
        component: () => import('@/views/observe/ObserveView.vue'),
        meta: { permissions: ['monitoring:tokenusage:read'], title: '观测中心' }
      },
      {
        path: 'tracing',
        name: 'tracing',
        component: () => import('@/views/tracing/TracingView.vue'),
        meta: { permissions: ['tracing:dashboard:read'], title: '链路追踪' }
      },
      // ---- 四期治理与系统 ----
      {
        path: 'strategy',
        name: 'strategy',
        component: () => import('@/views/strategy/StrategyView.vue'),
        meta: { permissions: ['config:rules:read'], title: '策略中心' }
      },
      {
        path: 'quota',
        name: 'quota',
        component: () => import('@/views/strategy/QuotaView.vue'),
        meta: { permissions: ['config:quota:read'], title: '用量配额' }
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/settings/SettingsView.vue'),
        meta: { permissions: ['system:accounts:manage'], title: '系统设置' }
      }
    ]
  },
  // 兜底：未匹配路由回总览
  { path: '/:pathMatch(.*)*', redirect: '/overview' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const userStore = useUserStore()

  if (to.meta.public) {
    // 已登录访问登录页 → 直达总览
    if (userStore.isAuthenticated()) return { name: 'overview' }
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

  const required = to.meta.permissions as string[] | undefined
  if (required && required.length > 0 && !required.every(code => userStore.hasPermission(code))) {
    // 无权限 → 回总览（普通角色访问治理页时的兜底；菜单层已提前过滤）
    return { name: 'overview' }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · JAiRouter 控制台` : 'JAiRouter 控制台'
})

export default router
