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
    ADMIN;

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
                    if (role.equals("ROLE_USER") || role.equals("ROLE_OPERATOR") || role.equals("ROLE_ADMIN")) {
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
                default:
                    break;
            }
        }
        return false;
    }
}
