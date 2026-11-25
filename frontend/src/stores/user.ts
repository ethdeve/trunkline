import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request, { beginAuthTransition, endAuthTransition } from '@/utils/request'
import { i18n } from '@/i18n'
import type { ApiResponse, RouterResponse } from '@/types'
import type { LogicalRole } from '@/config/menu'
import {
  roleSatisfiesPermission,
  auditorSatisfiesPermission,
  AUDITOR_ROLE
} from '@/config/permissionMatrix'

// i18n.global.t 轻量包装（兜底 Error 文案，调用时求值，随语言切换生效）
const gt = (key: string): string =>
  (i18n.global as unknown as { t: (key: string) => string }).t(key)

// 用户信息接口
export interface UserInfo {
  username: string
  roles?: string[]
  [key: string]: unknown
}

// 定义登录响应数据类型
interface LoginResponseData {
  token: string
  tokenType: string
  expiresIn: number
  message: string
  timestamp: string
}

export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(localStorage.getItem('admin_token'))
  const userInfo = ref<UserInfo | null>(null)
  let refreshTimer: number | null = null

  // 从JWT token解析角色
  const parseRolesFromToken = (jwtToken: string): string[] => {
    try {
      const payload = JSON.parse(atob(jwtToken.split('.')[1]))
      return payload.roles || payload.authorities || []
    } catch {
      return []
    }
  }

  // 从JWT token解析二维角色绑定（tenantRoles claim），用于「全局 ADMIN / 平台超管」判定。
  // 旧格式（社区版 / 存量令牌）无此 claim → 返回 []，由 isGlobalAdmin 走扁平 ADMIN 兼容分支。
  const parseTenantRolesFromToken = (
    jwtToken: string
  ): Array<{ t?: string; r?: string }> => {
    try {
      const payload = JSON.parse(atob(jwtToken.split('.')[1]))
      const claim = payload.tenantRoles
      if (!Array.isArray(claim)) return []
      return claim.filter(
        (b: unknown): b is { t?: string; r?: string } => !!b && typeof b === 'object'
      )
    } catch {
      return []
    }
  }

  // 从JWT token解析用户名
  const parseUsernameFromToken = (jwtToken: string): string => {
    try {
      const payload = JSON.parse(atob(jwtToken.split('.')[1]))
      return payload.sub || ''
    } catch {
      return ''
    }
  }

  // v3.0.3 修复：冷启动（F5 / 深链 / 新标签页）时 localStorage 只有 admin_token，
  // userInfo 为空 → 路由守卫的 meta.roles 判定（/security/*、/system/*）
  // 误判为无角色并重定向到仪表板。这里在 store 初始化时用既有 token 补齐用户信息
  // （与 setToken 同源解析），使冷启动与应用内行为一致。
  if (token.value) {
    const bootRoles = parseRolesFromToken(token.value)
    userInfo.value = {
      username: parseUsernameFromToken(token.value),
      roles: bootRoles
    }
  }

  // 检查是否为管理员
  const isAdmin = computed(() => {
    return userInfo.value?.roles?.includes('ADMIN') || false
  })

  /**
   * 平台超管判定（与后端 FixedRoleAuthorizationManager.isPlatformAdmin 纯作用域口径对称）：
   * 无 tenantRoles claim（社区版/旧令牌）→ 扁平 ADMIN 即超管，零回归；
   * 有 claim → 必须含全局绑定 {t:"*",r:"ADMIN"}；{t:"t1",r:"ADMIN"} 租户管理员为 false。
   * 仅用于菜单/路由体验层隐藏，安全底线由后端 PLATFORM URL 判定兜底。
   */
  const isGlobalAdmin = computed<boolean>(() => {
    const bindings = parseTenantRolesFromToken(token.value || '')
    if (bindings.length === 0) {
      return userInfo.value?.roles?.includes('ADMIN') || false
    }
    return bindings.some(
      b =>
        String(b.t ?? '').toLowerCase() === '*' &&
        String(b.r ?? '').toUpperCase() === 'ADMIN'
    )
  })

  /**
   * v3.5 三角色逻辑模型（早期 UI 设计规格，已归档）：
   * 把工程 JWT 角色（VIEWER/USER/OPERATOR/ADMIN）重映射为规格的 user/ops/admin。
   * - ADMIN → admin
   * - OPERATOR → ops
   * - USER / VIEWER → user（员工，VIEWER 为只读员工）
   */
  const JWT_ROLE_TO_LOGICAL: Record<string, LogicalRole> = {
    ADMIN: 'admin',
    OPERATOR: 'ops',
    USER: 'user',
    VIEWER: 'user'
  }

  /**
   * 逻辑角色由 JWT 角色唯一决定。
   * （历史上的右下角「演示角色切换徽章」及其 demoRole 覆盖已随 UI 一起移除；
   *  这里顺手清理旧版本可能残留在 localStorage 的覆盖值，避免菜单权限被陈旧状态卡住。）
   */
  localStorage.removeItem('trunkline_demo_role')

  const logicalRoles = computed<LogicalRole[]>(() => {
    const jwtRoles = userInfo.value?.roles || []
    const set = new Set<LogicalRole>()
    jwtRoles.forEach(r => {
      const lr = JWT_ROLE_TO_LOGICAL[r]
      if (lr) set.add(lr)
    })
    return [...set]
  })

  const hasLogicalRole = (role: LogicalRole): boolean => logicalRoles.value.includes(role)

  const hasLogicalRoleOneOf = (roles?: LogicalRole[]): boolean => {
    if (!roles || roles.length === 0) return true
    return roles.some(r => logicalRoles.value.includes(r))
  }

  // 检查是否有指定角色
  const hasRole = (role: string): boolean => {
    return userInfo.value?.roles?.includes(role) || false
  }

  // 检查是否有任意一个角色
  const hasAnyRole = (roles: string[]): boolean => {
    if (!userInfo.value?.roles) return false
    return roles.some(role => userInfo.value!.roles!.includes(role))
  }

  /**
   * 检查是否满足权限码的可见性要求（固定权限矩阵，config/permissionMatrix.ts）。
   *
   * 权限码在这里只是「最低逻辑角色」的键：user=我的分组 / ops=团队策略 / admin=系统。
   * 判定完全由当前逻辑角色决定，不再读取 JWT permissions claim（社区版已移除权限码体系，
   * 后端只认角色）。未登记的码不限制（兜住未知码），拼写错误由一致性巡检脚本兜底。
   */
  const hasPermission = (code: string): boolean => {
    // 二维角色地基（P0-c）：AUDITOR 是非等级角色，走独立的只读集合判定（fail-closed）。
    // 社区版不签发 AUDITOR，hasRole(AUDITOR_ROLE) 恒 false，下面逻辑逐字不变。
    if (hasRole(AUDITOR_ROLE)) {
      return auditorSatisfiesPermission(code)
    }
    const roles = logicalRoles.value
    if (roles.length === 0) return true
    return roles.some(role => roleSatisfiesPermission(role, code))
  }

  const setToken = (newToken: string) => {
    token.value = newToken
    localStorage.setItem('admin_token', newToken)
    // 从token中提取用户信息
    const username = parseUsernameFromToken(newToken)
    const roles = parseRolesFromToken(newToken)
    userInfo.value = { username, roles }
  }

  const clearToken = () => {
    token.value = null
    localStorage.removeItem('admin_token')
    userInfo.value = null

    if (refreshTimer) {
      clearInterval(refreshTimer)
      refreshTimer = null
    }
  }

  const isAuthenticated = () => {
    return !!token.value
  }

  // 启动定时刷新令牌
  const startTokenRefresh = () => {
    if (refreshTimer) {
      clearInterval(refreshTimer)
    }

    refreshTimer = window.setInterval(async () => {
      if (token.value) {
        try {
          await refreshToken()
        } catch (error) {
          console.error('自动刷新令牌失败:', error)
          clearToken()
          import('@/router').then(({ default: router }) => {
            router.push('/login')
          })
        }
      }
    }, 30 * 60 * 1000)
  }

  // 刷新令牌方法
  const refreshToken = async () => {
    try {
      const response = await request.post<ApiResponse<LoginResponseData>>('/auth/jwt/refresh', {
        token: token.value
      })

      if (response.data.success && response.data.data) {
        const newToken = response.data.data.token
        setToken(newToken)
        return response.data
      } else {
        throw new Error(response.data.message || gt('errors.tokenRefreshFailed'))
      }
    } catch (error: any) {
      console.error('令牌刷新请求失败:', error)
      throw new Error(error.response?.data?.message || error.message || gt('errors.tokenRefreshFailed'))
    }
  }

  // 登录方法
  const login = async (username: string, password: string) => {
    try {
      const response = await request.post<ApiResponse<LoginResponseData>>('/auth/jwt/login', {
        username,
        password
      })
      
      if (response.data.success && response.data.data) {
        const jwtToken = response.data.data.token
        setToken(jwtToken)
        startTokenRefresh()
        return response.data
      } else {
        throw new Error(response.data.message || gt('errors.loginFailed'))
      }
    } catch (error: any) {
      console.error('登录请求失败:', error)
      throw new Error(error.response?.data?.message || gt('errors.loginFailed'))
    }
  }

  // 登出方法
  const logout = async () => {
    try {
      if (token.value) {
        const username = parseUsernameFromToken(token.value)
        await request.post('/auth/jwt/revoke', {
          token: token.value,
          userId: username,
          reason: '用户主动登出'
        })
      }
    } catch (error) {
      console.error('登出请求失败:', error)
    } finally {
      // 登出过渡窗口：从现在起到重置完成，期间发起的请求一律静默挂起。
      // 不这么做的话，仍挂载的视图（MyQuotaView 等十余处 watch(currentTenantId)）
      // 会在 tenant store 重置时同步触发重新请求——token 已清空 → 401/403 →
      // 弹出「你还没有这个模型的使用权限」这类与登出无关的假错误。
      beginAuthTransition()
      try {
        clearToken()
        // G4-5：租户上下文随账号走。不清会导致同一浏览器换账号登录后继承上一账号的
        // X-Tenant-Id（请求拦截器每请求注入），形成跨账号数据串扰。
        localStorage.removeItem('trunkline_tenant_id')
        // 先导航到登录页卸载全部控制台视图（销毁所有 watch(currentTenantId) 的
        // watcher），再重置 tenant store——顺序反了 watcher 会立即用空 token 重发请求。
        // 调用方（ConsoleLayout / CommandPalette）随后再 push 一次是同路由幂等操作。
        try {
          const { default: router } = await import('@/router')
          await router.push({ name: 'login' })
        } catch {
          /* 导航失败不阻塞登出清理 */
        }
        // 越权修复：tenant store 的 pinia 模块单例会缓存上一账号的可见租户列表与 loaded 态，
        // 换账号后若不重置，切换器会继承上一账号能看到的其他租户（横向下拉泄漏）。动态导入
        // 规避 user/tenant store 循环依赖；即便此步失败，tenant store 的 token 指纹检测仍会兜底重拉。
        try {
          const { useTenantStore } = await import('@/stores/tenant')
          useTenantStore().resetForAccountSwitch()
        } catch {
          /* 动态导入失败不影响登出主流程，token 指纹检测兜底 */
        }
        // P1 员工侧⑤：员工白名单缓存是模块级的，跨登录会话复用会造成越权泄漏
        // （A 登出后 B 登录会继承 A 的「不限制」状态）。登出即清。
        try {
          const { resetEmployeeScopeCache } = await import('@/composables/useEmployeeModelScope')
          resetEmployeeScopeCache()
        } catch {
          /* 动态导入失败不影响登出主流程 */
        }
      } finally {
        endAuthTransition()
      }
    }
  }

  // 获取用户信息（POST /auth/jwt/validate，后端仅 @PostMapping）
  const getUserInfo = async () => {
    try {
      const response = await request.post<RouterResponse<{ valid: boolean; userId: string; message: string }>>(
        '/auth/jwt/validate',
        { token: token.value }
      )
      
      if (response.data.success && response.data.data) {
        const validation = response.data.data
        if (validation.valid && validation.userId) {
          // Validate endpoint returns userId; preserve roles parsed from JWT
          if (userInfo.value) {
            userInfo.value.username = validation.userId
          } else {
            userInfo.value = { username: validation.userId }
          }
          return userInfo.value
        }
      }
    } catch (error) {
      console.error('获取用户信息失败:', error)
      clearToken()
      throw error
    }
  }

  return {
    token,
    userInfo,
    isAdmin,
    isGlobalAdmin,
    logicalRoles,
    hasLogicalRole,
    hasLogicalRoleOneOf,
    setToken,
    clearToken,
    isAuthenticated,
    hasRole,
    hasAnyRole,
    hasPermission,
    login,
    logout,
    getUserInfo,
    refreshToken,
    startTokenRefresh,
    parseRolesFromToken,
    parseUsernameFromToken
  }
})
