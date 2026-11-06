import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request from '@/utils/request'
import { i18n } from '@/i18n'
import type { ApiResponse, RouterResponse } from '@/types'
import type { LogicalRole } from '@/config/menu'
import { roleSatisfiesPermission } from '@/config/permissionMatrix'

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
   * v3.5 三角色逻辑模型（docs/Trunkline-UI设计规格.md §2）：
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
      clearToken()
      // P1 员工侧⑤：员工白名单缓存是模块级的，跨登录会话复用会造成越权泄漏
      // （A 登出后 B 登录会继承 A 的「不限制」状态）。登出即清。
      try {
        const { resetEmployeeScopeCache } = await import('@/composables/useEmployeeModelScope')
        resetEmployeeScopeCache()
      } catch {
        /* 动态导入失败不影响登出主流程 */
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
