/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

/**
 * 令牌归属校验器（纯逻辑，不依赖 Spring 容器，可直接单元测试）。
 *
 * <p><b>对应漏洞 3：</b>刷新端点 {@code POST /api/auth/jwt/refresh} 从请求体取待刷新的令牌，
 * 而 {@code authentication} 仅用于审计，导致任意已认证用户只要拿到别人的令牌串，
 * 就能把别人的令牌刷新成自己的新令牌（实测 USER 刷出了 roles=[ADMIN,USER] 的令牌）。
 *
 * <p>本类把「待刷新的令牌必须属于当前认证主体」这一判定固化为纯逻辑：
 * <ul>
 *   <li>认证主体缺失 → {@link Result#UNAUTHENTICATED}（映射 401）</li>
 *   <li>待刷新令牌没有 subject → {@link Result#INVALID_TOKEN}（映射 401）</li>
 *   <li>subject 与认证主体不一致 → {@link Result#FORBIDDEN}（映射 403）</li>
 *   <li>完全一致 → {@link Result#ALLOWED}</li>
 * </ul>
 *
 * <p>注意：<b>ADMIN 也不能刷新他人的令牌</b> —— 归属判定一律要求 subject 一致，
 * 避免把「刷新」变成令牌提权通道。
 *
 * @author Trunkline Team
 */
public final class JwtTokenOwnershipValidator {

    private JwtTokenOwnershipValidator() {
        throw new IllegalStateException("工具类不允许实例化");
    }

    /**
     * 判定待刷新的令牌是否属于当前认证主体。
     *
     * @param tokenSubject        待刷新令牌的 subject（可为 null）
     * @param authenticatedName   当前认证主体名（可为 null）
     * @return 判定结果
     */
    public static Result check(final String tokenSubject, final String authenticatedName) {
        if (authenticatedName == null || authenticatedName.trim().isEmpty()) {
            return Result.UNAUTHENTICATED;
        }
        if (tokenSubject == null || tokenSubject.trim().isEmpty()) {
            return Result.INVALID_TOKEN;
        }
        if (!tokenSubject.trim().equals(authenticatedName.trim())) {
            return Result.FORBIDDEN;
        }
        return Result.ALLOWED;
    }

    /**
     * 判定结果。
     */
    public enum Result {
        /** 允许刷新：待刷新令牌属于当前认证主体。 */
        ALLOWED,
        /** 当前请求没有认证主体 → 401。 */
        UNAUTHENTICATED,
        /** 待刷新的令牌无法解析出 subject（令牌本身无效）→ 401。 */
        INVALID_TOKEN,
        /** 待刷新的令牌属于他人 → 403。 */
        FORBIDDEN
    }
}
