package org.unreal.modelrouter.auth.security.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.unreal.modelrouter.persistence.store.StoreManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * M-14/M-16 回归：Redis 主读失败 + 降级存储读失败时，isBlacklisted 不得伪装成
 * "确定未拉黑"(false)，须按 fail-closed 开关显式决策（默认 false 可用性优先 / true 安全优先）。
 */
@DisplayName("M-14/M-16：RedisJwtBlacklistService 两级读失败决策")
class RedisJwtBlacklistServiceReadFailureTest {

    @SuppressWarnings("unchecked")
    private ReactiveRedisTemplate<String, String> redisTemplate = mock(ReactiveRedisTemplate.class);
    private StoreManager storeManager = mock(StoreManager.class);
    private RedisJwtBlacklistServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new RedisJwtBlacklistServiceImpl(redisTemplate, storeManager);
        // isBlacklisted 命中时 doOnNext 会异步回填 memoryCache（getBlacklistEntry → opsForValue().get）。
        // 统一 stub 成 empty，避免 mock 未配置导致 NPE 干扰主序列断言。
        org.springframework.data.redis.core.ReactiveValueOperations<String, String> ops =
                mock(org.springframework.data.redis.core.ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("两级读均失败 + failClosed=false → 降级放行（显式，不伪装确定未拉黑）")
    void bothFailAllowWhenFailClosedFalse() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("Redis down")));
        when(storeManager.getConfig(anyString())).thenThrow(new RuntimeException("Store down"));
        service.setFailClosedOnReadFailure(false);

        StepVerifier.create(service.isBlacklisted("token-x")).expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("两级读均失败 + failClosed=true → 按已拉黑拒绝")
    void bothFailDenyWhenFailClosedTrue() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("Redis down")));
        when(storeManager.getConfig(anyString())).thenThrow(new RuntimeException("Store down"));
        service.setFailClosedOnReadFailure(true);

        StepVerifier.create(service.isBlacklisted("token-x")).expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("Redis 读失败但降级存储命中 → 返回真实拉黑结果 true")
    void redisFailsFallbackHit() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("Redis down")));
        var data = new java.util.HashMap<String, Object>();
        try {
            var e = new org.unreal.modelrouter.common.dto.TokenBlacklistEntry();
            e.setTokenHash("token-y");
            e.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
            data.putAll(org.unreal.modelrouter.common.util.JacksonHelper.getObjectMapper()
                    .convertValue(e, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { }));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        when(storeManager.getConfig("jwt_blacklist_token-y")).thenReturn(data);

        StepVerifier.create(service.isBlacklisted("token-y")).expectNext(true).verifyComplete();
    }
}
