/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 【issue #704】JWT 验证期的账户状态守卫。
 *
 * <p>删除/禁用账户只改 {@code jwt_accounts} 表，此前 {@code validateToken} 只查黑名单 +
 * 解析 claims，已签发的令牌在过期前仍然可用。这里在验证期用权威来源
 * {@link AccountManager#loadUserByUsername(String)} 重新确认账户仍启用/存在：
 * 该方法同时过滤 YAML 静态账户的 {@code isEnabled} 与 DB 账户的 {@code getEnabled()}，
 * 删除或禁用都会抛 {@link UsernameNotFoundException}。</p>
 *
 * <p>热路径查库用 30 秒 TTL 缓存摊薄；账户被删/禁用/启用时由 {@code JwtAccountService}
 * 调 {@link #invalidate(String)} 立即失效。查库失败一律 fail-closed，不让「存储抖动」
 * 变成绕过账户状态的窗口。</p>
 */
@Slf4j
@Component
public class AccountStatusGuard {

    /** 缓存窗口：多副本部署下，未收到失效通知的实例最多滞后 30 秒。 */
    static final long CACHE_TTL_MILLIS = 30_000L;

    /** 可选依赖：安全功能整体关闭时该 Bean 不装配，此时拒绝所有令牌（fail-closed）。 */
    @Autowired(required = false)
    private AccountManager accountManager;

    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 缓存条目：active=账户可用，expiresAtMillis=过期时间戳（绝对毫秒）。 */
    private record CacheEntry(boolean active, long expiresAtMillis) {
    }

    /**
     * 校验账户仍处于启用状态；通过返回 {@code Mono.empty()}，否则返回带错误码的 {@code Mono.error}。
     */
    public Mono<Void> requireActive(final String subject) {
        if (subject == null || subject.isBlank()) {
            return Mono.error(new AuthenticationException(
                    "令牌缺少账户标识，无法校验账户状态", "ACCOUNT_STORE_UNAVAILABLE"));
        }
        if (accountManager == null) {
            return Mono.error(new AuthenticationException(
                    "账户存储不可用，无法校验账户状态", "ACCOUNT_STORE_UNAVAILABLE"));
        }

        final long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(subject);
        if (hit != null && hit.expiresAtMillis() > now) {
            return hit.active() ? Mono.empty() : Mono.error(disabled(subject));
        }

        // JPA 是阻塞调用，必须切到 boundedElastic，不能占 WebFlux event loop。
        return Mono.fromCallable(() -> {
                    accountManager.loadUserByUsername(subject);
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(ignored -> cache.put(subject,
                        new CacheEntry(true, System.currentTimeMillis() + CACHE_TTL_MILLIS)))
                .onErrorResume(error -> {
                    cache.put(subject,
                            new CacheEntry(false, System.currentTimeMillis() + CACHE_TTL_MILLIS));
                    log.warn("账户状态校验未通过: subject={}, reason={}", subject, error.toString());
                    if (error instanceof UsernameNotFoundException) {
                        return Mono.error(disabled(subject));
                    }
                    return Mono.error(new AuthenticationException(
                            "账户状态校验失败，拒绝令牌", "ACCOUNT_STORE_UNAVAILABLE"));
                })
                .then();
    }

    /**
     * 账户被删除 / 禁用 / 重新启用后立即清缓存，避免最多 30 秒的旧状态窗口。
     */
    public void invalidate(final String username) {
        if (username != null) {
            cache.remove(username);
        }
    }

    /** 仅供测试与运维观测：当前缓存条目数。 */
    public int cachedAccountCount() {
        return cache.size();
    }

    private static AuthenticationException disabled(final String subject) {
        return new AuthenticationException("账户已删除或禁用: " + subject, "ACCOUNT_DISABLED");
    }
}
