/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.unreal.modelrouter.auth.security.service.impl.RedisJwtBlacklistServiceImpl;
import org.unreal.modelrouter.persistence.store.StoreManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C2 键命名空间拆分契约测试（撤销 → 重放 → 401）.
 *
 * <p>背景：撤销侧按令牌哈希写入、校验侧按 jti 与哈希双查，二者历史上共用 {@code jwt:blacklist:}
 * 前缀，字符串"blacklisted"与 JSON 条目在同一前缀下存在互踩风险（issue 设计报告 C2）。
 * 拆分后：jti 维度落到 {@code jwt:blacklist:jti:*}、按哈希撤销维度落到
 * {@code jwt:blacklist:entry:*}。</p>
 *
 * <p>本测试在无 Redis 环境下锁定两条契约：
 * <ol>
 *   <li>撤销侧 {@link RedisJwtBlacklistServiceImpl#addToBlacklist(String, String, String, long)}
 *       写入的 Redis 键必须以 {@code jwt:blacklist:entry:} 开头；</li>
 *   <li>校验侧 {@link EnhancedJwtBlacklistService#isBlacklisted(String)} 在仅存在 entry 命名空间键时
 *       对哈希返回 true、对 jti 返回 false——即"撤销（按哈希）→ 重放（按哈希查）"必然命中。</li>
 * </ol>
 * 两条契约同时成立，即"撤销 → 立即重放 → 401"在命名空间层面闭环。
 * @author Trunkline Team
 */
@DisplayName("C2 黑名单键命名空间拆分契约测试")
class C2BlacklistNamespaceContractTest {

    private static final String ENTRY_PREFIX = "jwt:blacklist:entry:";

    @Test
    @DisplayName("契约1：撤销侧写入的键落在 jwt:blacklist:entry: 命名空间")
    void revokeWritesToEntryNamespace() {
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        ReactiveSetOperations<String, String> setOps = mock(ReactiveSetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);

        ArgumentCaptor<String> setKey = ArgumentCaptor.forClass(String.class);
        when(valueOps.set(setKey.capture(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(setOps.add(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(setOps.remove(anyString(), anyString())).thenReturn(Mono.just(1L));
        // RedisBlacklistOperations.addToBlacklist 还会调用 redisTemplate.expire(...)（更新索引 TTL）
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StoreManager storeManager = mock(StoreManager.class);
        RedisJwtBlacklistServiceImpl revokeService = new RedisJwtBlacklistServiceImpl(redis, storeManager);

        StepVerifier.create(revokeService.addToBlacklist("hash-abc", "logout", "admin", 3600))
                .verifyComplete();

        List<String> writtenKeys = setKey.getAllValues();
        assertTrue(writtenKeys.stream().anyMatch(k -> k.startsWith(ENTRY_PREFIX)),
                "撤销写入的键必须落在 entry 命名空间，实际: " + writtenKeys);
    }

    @Test
    @DisplayName("契约2：校验侧按哈希查 entry 命名空间命中撤销、按 jti 不误命中")
    void validatorReadsEntryNamespaceForHash() {
        ReactiveStringRedisTemplate validatorRedis = mock(ReactiveStringRedisTemplate.class);

        // 仅存在 entry 命名空间键（撤销按哈希写入）。
        // 注意：EnhancedJwtBlacklistService.isBlacklisted 直接调用 redisTemplate.hasKey(...)，
        // 因此必须在 ReactiveStringRedisTemplate mock 上 stub，而非其内层的 opsForValue()。
        // Mockito 遵循「后注册的匹配桩优先」：必须先注册宽匹配 anyString()，
        // 再注册精确匹配 eq(entry)，否则宽桩会把精确桩整体覆盖。
        when(validatorRedis.hasKey(anyString())).thenReturn(Mono.just(false));
        when(validatorRedis.hasKey(eq(ENTRY_PREFIX + "hash-abc"))).thenReturn(Mono.just(true));

        EnhancedJwtBlacklistService validator = new EnhancedJwtBlacklistService(validatorRedis);

        // 重放：校验侧先查 jti、再查 hash；hash 命中 entry 命名空间 → 必须拦截
        StepVerifier.create(validator.isBlacklisted("hash-abc")).expectNext(true).verifyComplete();
        // jti 维度未写入撤销信息 → 不误拦截
        StepVerifier.create(validator.isBlacklisted("some-jti")).expectNext(false).verifyComplete();
    }
}
