/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.access;

/**
 * 固定权限矩阵的访问级别（社区版权权固定化：不再有可配置 RBAC）
 *
 * <p>社区版权权是<b>写死在代码里</b>的三档固定矩阵，与前端菜单分组一一对应：</p>
 * <ul>
 *   <li>{@link #SELF} —— 「我的」分组：仅本人数据的自助端点（我的额度/我的密钥/
 *       我的调用记录/脱敏实例清单）与测试场，所有登录角色（USER/OPERATOR/ADMIN）可用；</li>
 *   <li>{@link #OPS} —— 「团队」「策略」分组：跨员工的运维视图（技术总览/团队用量/
 *       接入管理/调用观测/链路追踪/状态监控/策略中心），OPERATOR 与 ADMIN 可用；</li>
 *   <li>{@link #ADMIN} —— 「系统」分组及一切未显式登记的敏感面（账号管理/黑名单/
 *       令牌运维/脱敏配置等），仅 ADMIN 可用。</li>
 * </ul>
 *
 * <p>角色判定基于 JWT 的 {@code ROLE_*} authority（ROLE_USER / ROLE_OPERATOR / ROLE_ADMIN）。
 * 未显式登记且不在豁免清单的端点维持 fail-closed（仅 ADMIN 直通）——与移除前的
 * DENY_ALL 姿态一致，不因本次改造放宽任何默认边界。</p>
 *
 * @author Trunkline Team
 * @modified Trunkline Team
 */
public enum AccessLevel {

    /** 任意登录角色（USER / OPERATOR / ADMIN）——「我的」分组 */
    SELF,

    /** 运维及以上（OPERATOR / ADMIN）——「团队」「策略」分组 */
    OPS,

    /** 仅管理员——「系统」分组与未登记端点的兜底 */
    ADMIN,

    /**
     * 平台超管 + 审计员——企业只读汇总端点专用（P1）。
     *
     * <p>AUDITOR 是非等级横切只读角色，不能塞进 SELF/OPS 的 ≥ 语义（会让 OPERATOR 越权），
     * 也不能复用 ADMIN（会让审计员在 fail-closed 兜底端点拿到管理员权限）。故单独登记级别：
     * 只有显式声明本级别的端点才放行 ROLE_AUDITOR，其余未登记端点仍 fail-closed 仅 ADMIN。
     * 社区版不签发 AUDITOR，本级别在社区版无任何命中端点，行为零变化。</p>
     */
    AUDIT,

    /**
     * 平台超管（A 口径收紧）——仅<b>全局作用域</b> ADMIN 可执行的平台级端点（租户开通/停用）专用。
     *
     * <p>与 {@link #ADMIN} 的本质区别：ADMIN 用扁平 {@code ROLE_*} authority 判定，而
     * 企业二维角色下租户级 {@code {t:"t1",r:"ADMIN"}} 经 allRoles() 也持有扁平 ROLE_ADMIN，
     * 会冒充超管。PLATFORM 必须读 JWT 的 {@code tenantRoles} claim：只有全局绑定
     * {@code {t:"*",r:"ADMIN"}}（及无该 claim 的旧格式 ADMIN，社区版兼容）才算平台超管。</p>
     *
     * <p><b>本级别不能用 {@link #isSatisfiedBy(java.util.Collection)} 判定</b>——该方法只拿到
     * 扁平集合，分不出全局/租户级。正式判定在
     * {@code FixedRoleAuthorizationManager.isPlatformAdmin}。本实现恒 false 以 fail-closed，
     * 避免任何绕过专门分支的调用误放行租户管理员。</p>
     */
    PLATFORM;

    /**
     * 判断给定角色 authority 集合是否满足本级别。
     *
     * @param roleAuthorities 已持有的 ROLE_* authority（如 ROLE_USER），大小写不敏感
     * @return true 表示该级别放行
     */
    public boolean isSatisfiedBy(final java.util.Collection<String> roleAuthorities) {
        if (roleAuthorities == null || roleAuthorities.isEmpty()) {
            return false;
        }
        for (final String authority : roleAuthorities) {
            final String role = authority == null ? "" : authority.toUpperCase();
            switch (this) {
                case SELF:
                    // P2 修复：AUDITOR 也必须有「我自己的」自助能力子集——不是因为它能用个人
                    // 密钥/账单（AUDITOR_ALLOWED_CODES 不含那些码，前端不放行），而是因为前端
                    // 每个请求都要先读 /enterprise/tenants/mine 才能决定 X-Tenant-Id。若此处
                    // 不认 ROLE_AUDITOR，审计员连自己的可见租户集都拿不到，切换器恒为空、
                    // 所有隔离页都只能落在 default 租户 → 审计员的"逐租户只读"彻底失效。
                    // 数据级越权不靠这里拦：具体页面由权限码（前端）与 OPS/ADMIN/AUDIT
                    // 级别（后端）各自把关，SELF 只覆盖「读自己的身份与归属」这类端点。
                    if (role.equals("ROLE_USER") || role.equals("ROLE_OPERATOR")
                            || role.equals("ROLE_ADMIN") || role.equals("ROLE_AUDITOR")) {
                        return true;
                    }
                    break;
                case OPS:
                    if (role.equals("ROLE_OPERATOR") || role.equals("ROLE_ADMIN")) {
                        return true;
                    }
                    break;
                case ADMIN:
                    if (role.equals("ROLE_ADMIN")) {
                        return true;
                    }
                    break;
                case AUDIT:
                    // 企业只读汇总：平台超管与审计员可读；OPERATOR/USER 不得越权。
                    if (role.equals("ROLE_ADMIN") || role.equals("ROLE_AUDITOR")) {
                        return true;
                    }
                    break;
                case PLATFORM:
                    // 显式 fail-closed：二维作用域判定不走本方法（见枚举常量注释），
                    // 由 FixedRoleAuthorizationManager.isPlatformAdmin 读 tenantRoles claim 完成。
                    break;
                default:
                    break;
            }
        }
        return false;
    }
}
