/**
 * 固定权限矩阵（社区版权权固定化，2026-11 用户决策）
 *
 * 社区版不做可配置 RBAC：权限 = 角色 × 菜单分组，与后端
 * `FixedRoleAccessPolicy`（SELF/OPS/ADMIN 三档）一一对应：
 *   - user  = 「我的」分组（SELF）：自助端点 + 测试场
 *   - ops   = 「我的」+「团队」「策略」分组（OPS）：跨员工运维视图
 *   - admin = 全部（ADMIN）：系统分组与兜底
 *
 * 本表是「历史权限码 → 最低逻辑角色」的映射，用途仅两个：
 *   1. `stores/user.ts` 的 hasPermission —— 菜单/路由守卫可见性判定；
 *   2. 各页面内部的按钮/区块可见性（usePermission.hasPermission）。
 * 它不再代表后端授权语义（后端只认角色），仅保证界面「不展示用户点不动的入口」。
 * 新增权限码时必须同步后端 FixedRoleAccessPolicy 的登记级别。
 */

export type MinLogicalRole = 'user' | 'ops' | 'admin'

/** 权限码 → 最低逻辑角色（与后端 FixedRoleAccessPolicy 对齐） */
export const PERMISSION_MIN_ROLE: Record<string, MinLogicalRole> = {
  // ---- SELF：员工自助（「我的」分组） ----
  'billing:self': 'user',
  'security:apikeys:self': 'user',
  'callhistory:self': 'user',
  'config:instances:self': 'user',
  'ai:playground:use': 'user',

  // ---- OPS：团队/策略分组（跨员工运维视图） ----
  'overview:dashboard:read': 'ops',
  'monitoring:tokenusage:read': 'ops',
  'monitoring:metrics:read': 'ops',
  'monitoring:slowquery:read': 'ops',
  'monitoring:quota:read': 'ops',
  'monitoring:modelstats:read': 'ops',
  'monitoring:routing:read': 'ops',
  'monitoring:exceptions:read': 'ops',
  'monitoring:exceptions:write': 'ops',
  'monitoring:config:write': 'ops',
  'callhistory:view': 'ops',
  'config:callhistory:read': 'ops',
  'config:callhistory:write': 'ops',
  'config:instances:read': 'ops',
  'config:instances:write': 'ops',
  'config:pools:read': 'ops',
  'config:pools:write': 'ops',
  'config:services:read': 'ops',
  'config:services:write': 'ops',
  'config:adapters:read': 'ops',
  'config:adapters:write': 'ops',
  'config:rules:read': 'ops',
  'config:rules:write': 'ops',
  'config:circuitbreaker:read': 'ops',
  'config:circuitbreaker:write': 'ops',
  'config:cache:read': 'ops',
  'config:cache:write': 'ops',
  'config:quota:read': 'ops',
  'config:quota:write': 'ops',
  'config:versions:read': 'ops',
  'config:versions:write': 'ops',
  'config:persistence:read': 'ops',
  'config:persistence:write': 'ops',
  'config:validation:read': 'ops',
  'config:validation:write': 'ops',
  'lb:monitoring:read': 'ops',
  'lb:config:write': 'ops',
  'cb:monitoring:read': 'ops',
  'cb:history:read': 'ops',
  'rl:monitoring:read': 'ops',
  'tracing:dashboard:read': 'ops',
  'tracing:search:read': 'ops',
  'security:apikeys:manage': 'ops',
  'security:audit:read': 'ops',
  'security:audit:write': 'ops',

  // ---- ADMIN：系统分组与敏感面 ----
  'tracing:config:manage': 'admin',
  'security:jwttokens:manage': 'admin',
  'security:blacklist:manage': 'admin',
  'security:sanitization:manage': 'admin',
  'system:accounts:manage': 'admin',
  'system:permissions:manage': 'admin',
  'actuator:admin:manage': 'admin'
}

/** 逻辑角色层级（用于 ≥ 比较） */
const ROLE_ORDER: Record<MinLogicalRole, number> = {
  user: 0,
  ops: 1,
  admin: 2
}

/**
 * 判断当前逻辑角色是否满足权限码的最低角色要求。
 *
 * 未登记的码返回 true（不限制）——用于兜住未知码，避免新页面被静默隐藏；
 * 拼写错误由 utils/auditPermissionConsistency.ts 巡检兜底。
 */
export function roleSatisfiesPermission(
  role: MinLogicalRole,
  code: string
): boolean {
  const min = PERMISSION_MIN_ROLE[code]
  if (!min) return true
  return ROLE_ORDER[role] >= ROLE_ORDER[min]
}
