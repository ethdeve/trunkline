/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.access;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.model.JwtPrincipal;
import org.unreal.modelrouter.auth.security.util.RoleScopeParser;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 固定角色授权管理器——{@code /api/**} 的唯一 URL 级授权入口。
 *
 * <p>判定流程：</p>
 * <ol>
 *   <li>从 {@link FixedRoleAccessPolicy} 匹配 {method, path} → {@link AccessLevel}</li>
 *   <li>命中规则：校验认证主体携带的 {@code ROLE_*} authority 是否满足级别
 *     （SELF=任意登录角色 / OPS=运维+管理员 / ADMIN=仅管理员）</li>
 *   <li>未命中规则：命中 {@link AccessExemptEndpoints} 豁免 → 已认证放行；
 *     否则 fail-closed（仅 ADMIN 直通）</li>
 * </ol>
 *
 * <p>authority 语义：角色为 {@code ROLE_} 前缀（hasRole）。API-Key 主体仅携带
 * {@code ROLE_<SERVICE>}，只对豁免的服务代理面生效，不满足任何管理级别——
 * 与移除前的权限码矩阵行为一致。</p>
 *
 * @author Trunkline Team
 * @modified Trunkline Team
 */
@Slf4j
@Component
public class FixedRoleAuthorizationManager
        implements ReactiveAuthorizationManager<AuthorizationContext> {

    private final FixedRoleAccessPolicy accessPolicy;

    public FixedRoleAuthorizationManager(final FixedRoleAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    @Override
    public Mono<AuthorizationDecision> check(
            final Mono<Authentication> authentication, final AuthorizationContext context) {
        final HttpMethod method = context.getExchange().getRequest().getMethod();
        final String path = context.getExchange().getRequest().getPath().value();

        final Optional<AccessRule> rule = accessPolicy.findRule(method, path);
        if (rule.isEmpty()) {
            return checkUnmatched(authentication, method, path);
        }

        final AccessLevel level = rule.get().level();
        return authentication
                .map(auth -> new AuthorizationDecision(
                        isAuthenticated(auth) && isSatisfied(auth, level)))
                .doOnNext(decision -> log.debug("固定权限判定: method={}, path={}, level={}, decision={}",
                        method, path, level, decision.isGranted()))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    /**
     * 按级别裁决。{@link AccessLevel#PLATFORM} 必须读 JWT 的 {@code tenantRoles} claim 做
     * 二维作用域判定——扁平 {@code ROLE_ADMIN} 无法区分「全局超管」与「租户级 ADMIN」
     * （后者经 allRoles() 同样持有 ROLE_ADMIN）。其余级别沿用扁平 authority 判定。
     */
    private boolean isSatisfied(final Authentication auth, final AccessLevel level) {
        if (level == AccessLevel.PLATFORM) {
            return isPlatformAdmin(auth);
        }
        return level.isSatisfiedBy(roleAuthorities(auth));
    }

    /**
     * 平台超管判定（纯作用域表达，不新增 PLATFORM_ADMIN 角色名）：
     * <ol>
     *   <li>无 {@code JwtPrincipal} details（API-Key / 内部主体）→ false，fail-closed；</li>
     *   <li>无 {@code tenantRoles} claim（社区版 / 旧令牌）→ 扁平 ROLE_ADMIN 即全局超管，
     *       判定链与改造前逐字一致（社区版零回归）；</li>
     *   <li>有 claim → 仅全局作用域 ADMIN（{@code {t:"*",r:"ADMIN"}}）放行；
     *       {@code {t:"t1",r:"ADMIN"}} 租户管理员拒绝。</li>
     * </ol>
     */
    private boolean isPlatformAdmin(final Authentication auth) {
        if (!(auth.getDetails() instanceof JwtPrincipal principal)) {
            return false;
        }
        if (!principal.hasTenantRoles()) {
            return roleAuthorities(auth).stream()
                    .anyMatch(role -> "ROLE_ADMIN".equalsIgnoreCase(role));
        }
        return RoleScopeParser.fromList(principal.getTenantRoles())
                .globalRoles().contains("ADMIN");
    }

    /**
     * 未命中规则时的判定：豁免清单 → 已认证放行；否则 fail-closed（仅 ADMIN）。
     */
    private Mono<AuthorizationDecision> checkUnmatched(
            final Mono<Authentication> authentication, final HttpMethod method, final String path) {
        if (!AccessExemptEndpoints.isExempt(method, path)) {
            // fail-closed：未命中规则且未豁免 → 仅 ADMIN 直通，其余拒绝
            return authentication
                    .map(auth -> new AuthorizationDecision(
                            isAuthenticated(auth)
                                    && AccessLevel.ADMIN.isSatisfiedBy(roleAuthorities(auth))))
                    .doOnNext(decision -> log.debug(
                            "固定权限判定(fail-closed 未登记): method={}, path={}, decision={}",
                            method, path, decision.isGranted()))
                    .defaultIfEmpty(new AuthorizationDecision(false));
        }
        // 豁免命中：已认证放行
        return authentication
                .map(auth -> new AuthorizationDecision(isAuthenticated(auth)))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    /** 提取认证主体的 ROLE_* authority（权限级别只认角色，不认其他 authority） */
    private static List<String> roleAuthorities(final Authentication authentication) {
        final List<String> roles = new ArrayList<>();
        if (authentication.getAuthorities() != null) {
            for (final GrantedAuthority grantedAuthority : authentication.getAuthorities()) {
                final String authority = grantedAuthority.getAuthority();
                if (authority != null && authority.startsWith("ROLE_")) {
                    roles.add(authority);
                }
            }
        }
        return roles;
    }

    private boolean isAuthenticated(final Authentication authentication) {
        return authentication != null && authentication.isAuthenticated();
    }
}
