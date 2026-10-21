import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/**
 * 权限管理 API 封装（v2.9.8 RBAC，Phase 4）
 *
 * 对应后端 PermissionManagementController：
 * - GET /api/security/permissions                全部权限码（48 码，ADMIN 超集）
 * - GET /api/security/permissions/roles          全部角色及其权限码（角色名 → 权限码列表）
 * - PUT /api/security/permissions/roles/{roleName} 整体替换角色权限码集合
 */

export interface PermissionGroup {
  /** 模块展示分组标题（i18n key，页面以 t(group.module) 渲染） */
  module: string
  /** 该模块下的权限码 */
  codes: string[]
}

/** 4 个种子角色（RolePermissionSeeder 模板） */
export const ROLES = ['ADMIN', 'OPERATOR', 'USER', 'VIEWER'] as const
export type RoleName = (typeof ROLES)[number]

/**
 * 角色说明 i18n key（UI 展示用；页面以 t(ROLE_DESCRIPTIONS[role]) 渲染文案）。
 * 文案位于 locales/{zh-CN,en-US}/permissions.json 的 roleDescriptions 节点。
 */
export const ROLE_DESCRIPTIONS: Record<RoleName, string> = {
  ADMIN: 'permissions.roleDescriptions.ADMIN',
  OPERATOR: 'permissions.roleDescriptions.OPERATOR',
  USER: 'permissions.roleDescriptions.USER',
  VIEWER: 'permissions.roleDescriptions.VIEWER'
}

/**
 * 48 权限码按模块展示分组（与后端 PermissionCodes 全量一致）。
 * 仅用于 UI 展示（权限树分组），不代表后端授权语义。
 * module 为分组标题的 i18n key（页面以 t(group.module) 渲染），
 * 文案位于 locales/{zh-CN,en-US}/permissions.json 的 groups 节点。
 */
/**
 * 权限分组：与左侧菜单结构保持一致（工作台 / 模型接入 / API Keys / 观测 / 策略 / 系统设置），
 * 用户按「这个功能在哪个菜单」即可找到对应权限项；权限码本身与后端 1:1 不变。
 */
export const PERMISSION_GROUPS: PermissionGroup[] = [
  // ---- 工作台 ----
  {
    module: 'permissions.groups.workbench',
    codes: ['overview:dashboard:read']
  },
  // ---- 模型接入 ----
  {
    module: 'permissions.groups.modelAccess',
    codes: [
      'config:instances:read', 'config:instances:write',
      'config:pools:read', 'config:pools:write',
      'ai:playground:use'
    ]
  },
  // ---- API Keys ----
  {
    module: 'permissions.groups.apiKeys',
    codes: ['security:apikeys:manage']
  },
  // ---- 观测（观测中心 / 链路追踪） ----
  {
    module: 'permissions.groups.observe',
    codes: [
      'monitoring:tokenusage:read', 'monitoring:metrics:read',
      'monitoring:slowquery:read', 'monitoring:quota:read',
      'monitoring:modelstats:read', 'monitoring:routing:read',
      'callhistory:view', 'config:callhistory:read', 'config:callhistory:write',
      'tracing:dashboard:read', 'tracing:search:read', 'tracing:config:manage'
    ]
  },
  // ---- 策略（策略中心：路由规则 / 负载均衡 / 熔断 / 响应缓存 / 用量配额） ----
  {
    module: 'permissions.groups.strategy',
    codes: [
      'config:rules:read', 'config:rules:write',
      'lb:monitoring:read', 'lb:config:write',
      'cb:monitoring:read', 'cb:history:read',
      'config:circuitbreaker:read', 'config:circuitbreaker:write',
      'config:cache:read', 'config:cache:write',
      'rl:monitoring:read',
      'config:quota:read', 'config:quota:write'
    ]
  },
  // ---- 系统设置（账号 / 权限 / 审计 / 配置版本 / 数据持久化 / 模型供应商模板 / 模型分类 / 模型适配器） ----
  {
    module: 'permissions.groups.systemSettings',
    codes: [
      'system:accounts:manage', 'system:permissions:manage',
      'security:audit:read', 'security:sanitization:manage',
      'security:jwttokens:manage', 'security:blacklist:manage',
      'config:versions:read', 'config:versions:write',
      'config:persistence:read', 'config:persistence:write',
      'config:services:read', 'config:services:write',
      'config:adapters:read', 'config:adapters:write',
      'config:validation:read', 'config:validation:write',
      'actuator:admin:manage'
    ]
  }
]

/** 全部权限码集合（叶子节点 key，用于 el-tree 勾选收集/校验） */
export const ALL_PERMISSION_CODES: string[] = PERMISSION_GROUPS.flatMap(group => group.codes)

/** 角色 → 权限码列表（GET /api/security/permissions/roles 返回结构） */
export interface RolePermissions {
  [roleName: string]: string[]
}

/** 获取全部权限码 */
export const getAllPermissions = async (): Promise<string[]> => {
  const response = await request.get<RouterResponse<string[]>>('/security/permissions')
  return response.data.data || []
}

/** 获取全部角色及其权限码 */
export const getRolePermissions = async (): Promise<RolePermissions> => {
  const response = await request.get<RouterResponse<RolePermissions>>('/security/permissions/roles')
  return response.data.data || {}
}

/** 更新角色权限码集合（整体替换；权限变更需重新登录生效） */
export const updateRolePermissions = async (roleName: string, permissionCodes: string[]): Promise<string[]> => {
  const response = await request.put<RouterResponse<string[]>>(
    `/security/permissions/roles/${roleName}`,
    permissionCodes
  )
  return response.data.data || []
}
