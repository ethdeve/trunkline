/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * P0-K 一致性巡检：路由 meta.permissions ↔ 菜单 permission ↔ 菜单 path ↔ 路由 path
 *
 * 验收标准（按方案要求）：**一致性巡检 100% 通过**。
 * 此前只能靠「敲 URL 被重定向」来验证 —— 那条本来就通过，无法证伪。
 * 本脚本直接比对声明本身，能发现真实的漂移。
 *
 * 检查项：
 *   ① 每个菜单 path 都有对应路由（菜单点得进去）
 *   ② 每个带 permission 的菜单项，其路由 meta.permissions 与之一致（单一来源生效）
 *   ③ 不存在「同一页面两条路由、一条有权限一条没有」的不一致
 *   ④ 每个菜单项的 permission 都是已知权限码（拼写错误 → 永远 403）
 */
import { menuGroups as _menuGroups, flattenMenuItems } from '@/config/menu'
import { routes } from '@/router'

/** 已知权限码集合（与后端 PERMISSION_GROUPS 对齐的最小集） */
const KNOWN_CODES = new Set<string>([
  'overview:dashboard:read',
  'ai:playground:use',
  'security:apikeys:manage',
  'security:apikeys:self',
  'monitoring:tokenusage:read',
  'monitoring:metrics:read',
  'monitoring:slowquery:read',
  'monitoring:quota:read',
  'monitoring:modelstats:read',
  'monitoring:routing:read',
  'callhistory:view',
  'callhistory:self',
  'config:callhistory:read',
  'config:callhistory:write',
  'tracing:dashboard:read',
  'tracing:search:read',
  'tracing:config:manage',
  'config:rules:read',
  'config:rules:write',
  'lb:monitoring:read',
  'lb:config:write',
  'cb:monitoring:read',
  'cb:history:read',
  'config:circuitbreaker:read',
  'config:circuitbreaker:write',
  'config:cache:read',
  'config:cache:write',
  'rl:monitoring:read',
  'config:quota:read',
  'config:quota:write',
  'config:instances:read',
  'config:instances:write',
  'config:pools:read',
  'config:pools:write',
  'config:services:read',
  'config:services:write',
  'config:adapters:read',
  'config:adapters:write',
  'config:versions:read',
  'config:versions:write',
  'config:persistence:read',
  'config:persistence:write',
  'config:validation:read',
  'config:validation:write',
  'system:accounts:manage',
  'system:permissions:manage',
  'security:audit:read',
  'security:sanitization:manage',
  'security:jwttokens:manage',
  'security:blacklist:manage',
  'actuator:admin:manage'
])

interface Issue {
  level: 'ERROR' | 'WARN'
  code: string
  detail: string
}

/** 递归展开路由 → 绝对路径 */
function flattenRoutes(
  list: any[],
  parentPath = ''
): Array<{ absolute: string; meta: any; component: any }> {
  const out: Array<{ absolute: string; meta: any; component: any }> = []
  for (const r of list || []) {
    const seg = String(r.path || '').replace(/^\//, '')
    const absolute = r.path === '/' ? '/' : parentPath ? `${parentPath}/${seg}` : `/${seg}`
    out.push({ absolute, meta: r.meta || {}, component: r.component })
    if (r.children?.length) out.push(...flattenRoutes(r.children, absolute === '/' ? '' : absolute))
  }
  return out
}

export function auditPermissionConsistency(): Issue[] {
  const issues: Issue[] = []
  const allRoutes = flattenRoutes(routes as any[])
  const menuItems = flattenMenuItems()
  const menuByPath = new Map(menuItems.map(m => [m.path, m]))

  // ① 每个菜单 path 都要有对应路由
  for (const m of menuItems) {
    if (!allRoutes.some(r => r.absolute === m.path)) {
      issues.push({
        level: 'ERROR',
        code: 'MENU_NO_ROUTE',
        detail: `菜单项「${m.title}」(${m.path}) 没有对应路由，点了会落到 404 兜底`
      })
    }
  }

  // ② 菜单 permission 与路由 meta.permissions 一致（单一来源）
  for (const m of menuItems) {
    const r = allRoutes.find(x => x.absolute === m.path)
    if (!r) continue
    const routePerms = (r.meta.permissions as string[] | undefined) || []
    if (m.permission) {
      if (routePerms.length !== 1 || routePerms[0] !== m.permission) {
        issues.push({
          level: 'ERROR',
          code: 'PERM_MISMATCH',
          detail: `${m.path}：菜单声明 ${m.permission}，路由 meta 为 [${routePerms.join(',')}]`
        })
      }
    } else if (routePerms.length > 0) {
      issues.push({
        level: 'WARN',
        code: 'ROUTE_ONLY_PERM',
        detail: `${m.path}：菜单未声明 permission，路由却声明了 [${routePerms.join(',')}]`
      })
    }
  }

  // ③ 同一组件的多条路由，权限必须一致
  const byComponent = new Map<any, string[]>()
  for (const r of allRoutes) {
    if (!r.component || typeof r.component === 'string') continue
    const list = byComponent.get(r.component) || []
    list.push(r.absolute)
    byComponent.set(r.component, list)
  }
  for (const [_comp, paths] of byComponent) {
    if (paths.length < 2) continue
    const perms = paths.map(p => {
      const r = allRoutes.find(x => x.absolute === p)!
      const mp = menuByPath.get(p)?.permission
      const rp = (r.meta.permissions as string[] | undefined) || []
      return mp || rp.join(',') || '(none)'
    })
    if (new Set(perms).size > 1) {
      issues.push({
        level: 'ERROR',
        code: 'DUP_ROUTE_PERM_MISMATCH',
        detail: `同一组件的路由权限不一致：${paths.map((p, i) => `${p}=${perms[i]}`).join('  ')}`
      })
    }
  }

  // ④ 权限码必须是已知码
  for (const m of menuItems) {
    if (m.permission && !KNOWN_CODES.has(m.permission)) {
      issues.push({
        level: 'ERROR',
        code: 'UNKNOWN_PERMISSION_CODE',
        detail: `${m.path}：权限码 ${m.permission} 不在已知码表中（拼写错误 → 永远 403）`
      })
    }
  }

  return issues
}

/** 命令行入口：直接执行时打印结果并以退出码表示是否通过 */
if (typeof process !== 'undefined' && process.argv?.[1]?.includes('auditPermission')) {
  const issues = auditPermissionConsistency()
  const errors = issues.filter(i => i.level === 'ERROR')
  const warns = issues.filter(i => i.level === 'WARN')
  console.log('=== P0-K 权限一致性巡检 ===')
  console.log(`菜单项 ${flattenMenuItems().length} 个`)
  console.log(`ERROR ${errors.length} / WARN ${warns.length}`)
  for (const i of issues) console.log(`  [${i.level}] ${i.code}: ${i.detail}`)
  console.log(errors.length === 0 ? '一致性巡检 100% 通过' : '巡检未通过')
  process.exit(errors.length === 0 ? 0 : 1)
}
