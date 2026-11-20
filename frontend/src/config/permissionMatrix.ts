/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

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

  // P1 企业用量总览：专用码，绝不能复用 ops 级的 monitoring:tokenusage:read，
  // 否则 OPERATOR 可越权读跨租户金额。AUDITOR 走下方 AUDITOR_ALLOWED_CODES 二维判定。
  'enterprise:usage:read': 'admin',

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

// ============================================================================
// 二维角色地基（P0-c）：AUDITOR 非等级角色支持
// ============================================================================
// AUDITOR 是"横切只读"角色，不在 user<ops<admin 线性等级链上，故 ROLE_ORDER 的 ≥
// 比较撑不住它。这里用"权限码 → 允许角色集合"的二维判定：AUDITOR 只看下列只读页，
// 不含任何写权限、不含「我的」自助分组（保持审计独立性）。
//
// 社区版零变化：社区版不签发 AUDITOR，hasAuditorRole() 恒 false，以下分支不会进入，
// 原有 ≥ 判定逐字保留。

/** JWT 中的审计员角色码（企业版签发；社区版绝不出现）。 */
export const AUDITOR_ROLE = 'AUDITOR'

/**
 * AUDITOR 可见的只读权限码集合。命中=可见，其余一律不可见（fail-closed）。
 * 对应文档B §4 授权矩阵中 AUDITOR 的 🔶/✅ 项，均为只读语义。
 */
export const AUDITOR_ALLOWED_CODES: ReadonlySet<string> = new Set([
  'monitoring:tokenusage:read',      // 团队用量（只读汇总）
  'callhistory:view',                // 调用历史（只读）
  'monitoring:metrics:read',         // 状态监控 / 安全态势只读
  'security:audit:read',             // 审计与版本（只读）
  'enterprise:usage:read'            // 企业用量总览（P1 新增，只读汇总）
])

/**
 * AUDITOR 对权限码的二维判定。仅当确认是 AUDITOR 身份时调用。
 * 未列入 AUDITOR_ALLOWED_CODES 的码一律拒绝（含所有写操作与「我的」自助分组）。
 */
export function auditorSatisfiesPermission(code: string): boolean {
  return AUDITOR_ALLOWED_CODES.has(code)
}
