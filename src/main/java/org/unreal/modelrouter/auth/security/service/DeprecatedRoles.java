/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 已废弃角色的统一登记处（P0-2：角色简化为 USER / OPERATOR / ADMIN 三档）。
 *
 * <p>产品已决定彻底移除访客角色 VIEWER。本类集中承载「废弃角色 → 替代角色」的映射，
 * 供以下几处<b>刻意</b>的兼容/清理动作使用，避免同一个字面量散落多处：</p>
 * <ul>
 *   <li>启动期清理（已随 RBAC 移除而删除）——曾删除 {@code role_permissions} 里
 *       roleName='VIEWER' 的存量行；</li>
 *   <li>{@link JwtAccountRoleMigration} —— 启动期把持有 VIEWER 的账户迁移到 USER；</li>
 *   <li>{@link AccountManager} —— 签发 JWT 前的角色归一化（存量 YAML 静态账户
 *       与迁移前签发的令牌都按 USER 处理，避免迁移窗口内被锁死）。</li>
 * </ul>
 *
 * <p><b>这些是废弃角色的兼容降级入口，不是「可被选用 / 展示」的角色清单</b>：
 * 任何「选择角色」入口（前端下拉、权限管理页角色卡）都不得再出现 VIEWER。</p>
 *
 * @author Trunkline Team
 */
public final class DeprecatedRoles {

    /** 已废弃角色名：访客（P0-2 起不再可用） */
    public static final String VIEWER = "VIEWER";

    /** VIEWER 的替代角色：USER（语义最接近——只读视角的员工账号） */
    public static final String VIEWER_REPLACEMENT = "USER";

    private DeprecatedRoles() {
    }

    /**
     * 判断角色名是否为已废弃角色（大小写不敏感）。
     *
     * @param role 角色名（可为空）
     * @return 是废弃角色返回 true
     */
    public static boolean isDeprecated(final String role) {
        return role != null && VIEWER.equalsIgnoreCase(role.trim());
    }

    /**
     * 角色归一化（签发 JWT 用）：把已废弃角色映射到替代角色，其余角色转大写。
     *
     * @param role 角色名（可为空）
     * @return 归一化后的角色名；入参为空时返回空串
     */
    public static String normalizeRole(final String role) {
        if (role == null) {
            return "";
        }
        final String trimmed = role.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (isDeprecated(trimmed)) {
            return VIEWER_REPLACEMENT;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * 角色列表归一化（存量迁移用）：把废弃角色替换为替代角色，
     * 其余角色<b>保留原始大小写</b>（避免迁移顺带改写账户里已有的其它角色写法），
     * 并按大小写不敏感去重（保持首次出现顺序）。
     *
     * @param roles 角色列表（可为空）
     * @return 归一化后的角色列表（不可变）
     */
    public static List<String> migrateRoles(final List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        final LinkedHashSet<String> migrated = new LinkedHashSet<>();
        for (final String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            final String trimmed = role.trim();
            final String effective = isDeprecated(trimmed) ? VIEWER_REPLACEMENT : trimmed;
            final String key = effective.toUpperCase(Locale.ROOT);
            if (seen.add(key)) {
                migrated.add(effective);
            }
        }
        return List.copyOf(migrated);
    }
}
