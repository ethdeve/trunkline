/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import java.util.List;

/**
 * 刷新令牌时的身份重算器（纯逻辑，不依赖 Spring 容器，可直接单元测试）。
 *
 * <p><b>语义固化</b>：刷新令牌时必须从权威来源（账户服务）重新取角色，
 * <b>绝不</b>回退到旧令牌里的角色声明——防止「角色已被调整的用户」靠旧令牌
 * 刷新出旧角色的令牌。</p>
 *
 * <p><b>fail-closed：</b>权威来源不可用、抛异常或返回 {@code null} 时，
 * 统一抛 {@link JwtRefreshIdentityException}，让整个刷新失败。</p>
 *
 * <p>社区版已移除可配置 RBAC 权限码体系：令牌只携带角色，URL 级授权由
 * 固定角色矩阵（auth.security.access 包）按角色裁决，因此本类只重算角色。</p>
 *
 * <p>角色来源以函数式接口注入，使本类既能被 Spring 装配（账户服务），
 * 也能在单元测试中用 lambda 直接构造。</p>
 *
 * @author Trunkline Team
 * @modified Trunkline Team
 */
public class JwtRefreshIdentityResolver {

    /** 角色来源：根据主体（用户名）查出当前生效的角色列表。 */
    @FunctionalInterface
    public interface RoleSource {
        List<String> rolesOf(String subject);
    }

    private final RoleSource roleSource;

    public JwtRefreshIdentityResolver(final RoleSource roleSource) {
        this.roleSource = roleSource;
    }

    /**
     * 重算刷新后令牌应当携带的角色。
     *
     * @param subject 令牌主体（用户名）
     * @return 重算结果
     * @throws JwtRefreshIdentityException 主体为空、重算失败或重算结果为 null
     */
    public RefreshIdentity resolve(final String subject) {
        if (subject == null || subject.trim().isEmpty()) {
            throw new JwtRefreshIdentityException("刷新令牌主体为空，无法重算身份，拒绝签发新令牌");
        }
        if (roleSource == null) {
            throw new JwtRefreshIdentityException("刷新令牌所需的角色来源不可用，拒绝签发新令牌");
        }

        final List<String> roles;
        try {
            roles = roleSource.rolesOf(subject);
        } catch (RuntimeException e) {
            throw new JwtRefreshIdentityException("刷新令牌时读取用户角色失败: " + e.getMessage(), e);
        }
        if (roles == null) {
            throw new JwtRefreshIdentityException("刷新令牌时读取用户角色返回空，拒绝签发新令牌");
        }

        return new RefreshIdentity(List.copyOf(roles));
    }

    /**
     * 刷新后令牌的身份信息（角色）。
     */
    public static final class RefreshIdentity {

        private final List<String> roles;

        public RefreshIdentity(final List<String> roles) {
            this.roles = roles;
        }

        public List<String> getRoles() {
            return roles;
        }
    }
}
