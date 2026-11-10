/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JWT 刷新时的声明重算策略（纯逻辑，无 Spring 依赖）。
 *
 * <p><b>为什么必须是纯逻辑：</b>刷新链路的安全语义（不能沿用旧 jti / 旧权限码）
 * 需要被单元测试直接覆盖，而不能依赖 Spring 容器或数据库。因此这里把
 * 「哪些声明必须丢弃、哪些声明必须由权威来源重算」固化成无副作用的静态方法，
 * 生产代码与单元测试共用同一份实现，避免两套逻辑漂移。
 *
 * <p><b>对应漏洞：</b>
 * <ul>
 *   <li>漏洞 1（jti 未重新生成 → 新令牌立即失效）：刷新时复制旧声明必须剔除 {@code jti}，
 *       否则新令牌会继承旧令牌的 jti，而旧令牌在刷新瞬间已被拉黑，新令牌随即 401。</li>
 *   <li>漏洞 2（刷新不重算 permissions → 撤销的权限复活）：刷新时复制旧声明必须剔除
 *       {@code permissions}（以及 {@code roles}），改由角色/权限码权威来源重算后写入。</li>
 * </ul>
 *
 * @author Trunkline Team
 */
public final class JwtRefreshClaimResolver {

    /** 令牌唯一标识声明。 */
    public static final String CLAIM_JTI = "jti";

    /** 角色声明。 */
    public static final String CLAIM_ROLES = "roles";

    /** 权限码声明（历史遗留：旧令牌可能携带，刷新时剔除、新令牌不再写入）。 */
    public static final String CLAIM_PERMISSIONS = "permissions";

    /**
     * 刷新时**不得**沿用旧令牌值的声明集合。
     *
     * <p>{@code jti} 必须重新生成；{@code roles}/{@code permissions} 必须按当前
     * 角色重算或不再写入，否则「撤销角色权限」在刷新后失效（越权复活）。
     */
    private static final Set<String> RECOMPUTED_CLAIMS =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of(CLAIM_JTI, CLAIM_ROLES, CLAIM_PERMISSIONS)));

    private JwtRefreshClaimResolver() {
        throw new IllegalStateException("工具类不允许实例化");
    }

    /**
     * 判断某个声明是否属于「刷新时必须重算/重新生成」的声明。
     *
     * @param claimName 声明名
     * @return true 表示刷新时不得沿用旧值
     */
    public static boolean isRecomputedClaim(final String claimName) {
        return claimName != null && RECOMPUTED_CLAIMS.contains(claimName);
    }

    /**
     * 复制旧令牌中「可以沿用」的自定义声明。
     *
     * <p>剔除 {@code jti}/{@code roles}/{@code permissions}；标准声明
     * （{@code sub}/{@code iss}/{@code exp}/{@code iat}/{@code nbf}）由签发方
     * 自行重建，同样不沿用。
     *
     * @param sourceClaims 旧令牌声明（可为 null）
     * @return 可沿用的声明副本（不会为 null）
     */
    public static Map<String, Object> copyClaimsForRefresh(final Map<String, Object> sourceClaims) {
        final Map<String, Object> copied = new HashMap<>();
        if (sourceClaims == null) {
            return copied;
        }
        sourceClaims.forEach((key, value) -> {
            if (key == null || isStandardClaim(key) || isRecomputedClaim(key)) {
                return;
            }
            copied.put(key, value);
        });
        return copied;
    }

    /**
     * 组装刷新后令牌应当携带的声明：可沿用的自定义声明 + 重算出的角色。
     *
     * <p><b>fail-closed：</b>角色为 {@code null}（重算失败）时直接抛
     * {@link JwtRefreshIdentityException}，绝不退回旧令牌里的角色声明。
     * 社区版已移除权限码体系，令牌只携带角色；旧令牌若带 {@code permissions}
     * 声明会在 {@link #copyClaimsForRefresh} 中被剔除，不再写入。</p>
     *
     * @param sourceClaims 旧令牌声明（可为 null）
     * @param roles        重算得到的角色列表（不可为 null）
     * @return 刷新后待写入的声明
     * @throws JwtRefreshIdentityException 重算失败
     */
    public static Map<String, Object> buildRefreshClaims(final Map<String, Object> sourceClaims,
                                                         final List<String> roles) {
        if (roles == null) {
            throw new JwtRefreshIdentityException("刷新令牌时重算用户角色失败，拒绝签发新令牌");
        }
        final Map<String, Object> claims = copyClaimsForRefresh(sourceClaims);
        claims.put(CLAIM_ROLES, List.copyOf(roles));
        return claims;
    }

    /**
     * 标准声明（由签发方重建，不从旧令牌沿用）。
     */
    private static boolean isStandardClaim(final String claimName) {
        return "sub".equals(claimName)
                || "iss".equals(claimName)
                || "exp".equals(claimName)
                || "iat".equals(claimName)
                || "nbf".equals(claimName);
    }
}
