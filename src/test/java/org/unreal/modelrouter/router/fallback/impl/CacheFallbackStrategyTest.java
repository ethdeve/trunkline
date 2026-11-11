package org.unreal.modelrouter.router.fallback.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CacheFallbackStrategy 单元测试（M-24 请求级隔离后重写）。
 *
 * <p>核心契约：缓存降级只返回<b>本次请求自己</b>的缓存结果；未命中或请求键缺失时一律
 * 返回 503，绝不"任意取一条缓存"造成跨请求串扰/越权数据泄漏。</p>
 */
@DisplayName("CacheFallbackStrategy 测试（M-24 请求级隔离）")
class CacheFallbackStrategyTest {

    private CacheFallbackStrategy strategy;

    @Mock
    private ServerHttpRequest httpRequest;

    private Map<String, Object> attrs;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        strategy = new CacheFallbackStrategy("chat", 100);
        attrs = new HashMap<>();
        when(httpRequest.getHeaders()).thenReturn(new HttpHeaders());
        when(httpRequest.getRemoteAddress()).thenReturn(null);
        when(httpRequest.getAttributes()).thenReturn(attrs);
    }

    private void withPreciseKey(final String key) {
        attrs.put("TRUNKLINE_RESPONSE_CACHE_KEY", key);
    }

    @Test
    @DisplayName("测试 1: 无缓存时返回 503")
    void testFallbackWithNoCache() {
        ResponseEntity<?> response = strategy.fallback(new RuntimeException("Service unavailable"));

        assertNotNull(response);
        assertEquals(503, response.getStatusCodeValue());
        assertTrue(response.getBody().toString().contains("no cached data available"));
    }

    @Test
    @DisplayName("测试 2: 同请求精确键命中时返回该请求的缓存响应")
    void testFallbackWithMatchingRequestKey() {
        withPreciseKey("rc:chat:gpt-4:hash-A");
        ResponseEntity<?> cachedResponse = ResponseEntity.ok("cached data");
        strategy.cacheResponse(ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest, cachedResponse);

        String key = CacheFallbackStrategy.resolveRequestKey(
                ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest);
        ResponseEntity<?> response = strategy.fallback(new RuntimeException("Service unavailable"), key);

        assertNotNull(response);
        assertEquals(200, response.getStatusCodeValue());
        assertEquals("cached data", response.getBody());
    }

    @Test
    @DisplayName("M-24 核心回归：请求 B 用不同键，绝不命中请求 A 的缓存（无串扰）")
    void testNoCrossRequestLeak() {
        // 请求 A 写入自己的缓存
        withPreciseKey("rc:chat:gpt-4:hash-A");
        strategy.cacheResponse(ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest,
                ResponseEntity.ok("A-private-answer"));

        // 请求 B 换键降级：必须拿到 503，而不是 A 的私有结果
        withPreciseKey("rc:chat:gpt-4:hash-B");
        String keyB = CacheFallbackStrategy.resolveRequestKey(
                ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest);
        ResponseEntity<?> responseB = strategy.fallback(new RuntimeException("boom"), keyB);

        assertEquals(503, responseB.getStatusCodeValue());
        assertFalse(responseB.getBody().toString().contains("A-private-answer"));
    }

    @Test
    @DisplayName("M-24 核心回归：无请求键时降级一律 503，不遍历缓存任意返回")
    void testFallbackWithoutRequestKeyNeverServesArbitraryCache() {
        withPreciseKey("rc:chat:gpt-4:hash-A");
        strategy.cacheResponse(ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest,
                ResponseEntity.ok("someone-else-answer"));
        // 缓存非空，但无 requestKey（或精确键不匹配）时不得任意返回
        ResponseEntity<?> noKey = strategy.fallback(new RuntimeException("boom"));
        assertEquals(503, noKey.getStatusCodeValue());

        ResponseEntity<?> nullKey = strategy.fallback(new RuntimeException("boom"), null);
        assertEquals(503, nullKey.getStatusCodeValue());

        ResponseEntity<?> missKey = strategy.fallback(new RuntimeException("boom"), "unknown-key");
        assertEquals(503, missKey.getStatusCodeValue());
    }

    @Test
    @DisplayName("测试 3: 缓存大小限制内多请求各自隔离，容量不超限")
    void testCacheSizeLimit() {
        CacheFallbackStrategy small = new CacheFallbackStrategy("chat", 2);
        small.cacheResponse(ModelServiceRegistry.ServiceType.chat, "m1", reqWithKey("k1"), ResponseEntity.ok("d1"));
        small.cacheResponse(ModelServiceRegistry.ServiceType.chat, "m2", reqWithKey("k2"), ResponseEntity.ok("d2"));
        small.cacheResponse(ModelServiceRegistry.ServiceType.chat, "m3", reqWithKey("k3"), ResponseEntity.ok("d3"));

        // 容量受 maxCacheSize=2 约束；且每个键只命中自己的值
        assertEquals(200, small.fallback(new RuntimeException("e"), "chat:k1").getStatusCodeValue());
        assertEquals("d1", small.fallback(new RuntimeException("e"), "chat:k1").getBody());
        assertTrue(cacheSize(small) <= 2, "缓存条目不得超过 maxCacheSize");
    }

    private ServerHttpRequest reqWithKey(final String key) {
        ServerHttpRequest r = mock(ServerHttpRequest.class);
        Map<String, Object> a = new HashMap<>();
        a.put("TRUNKLINE_RESPONSE_CACHE_KEY", key);
        when(r.getAttributes()).thenReturn(a);
        return r;
    }

    private int cacheSize(final CacheFallbackStrategy s) {
        try {
            java.lang.reflect.Field f = CacheFallbackStrategy.class.getDeclaredField("cache");
            f.setAccessible(true);
            return ((Map<?, ?>) f.get(s)).size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("测试 4: 不同服务类型策略的降级互不影响")
    void testDifferentServiceTypes() {
        CacheFallbackStrategy chatStrategy = new CacheFallbackStrategy("chat", 100);
        CacheFallbackStrategy embeddingStrategy = new CacheFallbackStrategy("embedding", 100);

        assertEquals(503, chatStrategy.fallback(new RuntimeException("Error")).getStatusCodeValue());
        assertEquals(503, embeddingStrategy.fallback(new RuntimeException("Error")).getStatusCodeValue());
    }

    @Test
    @DisplayName("M-24: 无任何请求维度信息（无缓存键/无DTO）时不写缓存")
    void testNoRequestDimensionSkipsWrite() {
        // attrs 为空：既无 TRUNKLINE_RESPONSE_CACHE_KEY 也无 TRUNKLINE_REQUEST_DTO
        strategy.cacheResponse(ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest,
                ResponseEntity.ok("should-not-be-cached"));
        assertNull(CacheFallbackStrategy.resolveRequestKey(
                ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest));
        assertEquals(0, cacheSize(strategy));
    }

    @Test
    @DisplayName("M-24: 响应缓存默认关闭时，用原始DTO摘要仍能生成稳定隔离的降级键")
    void testDtoBasedKeyWhenResponseCacheDisabled() {
        // 仅放原始请求 DTO（各入口 Controller 必放），不放响应缓存键
        attrs.put("TRUNKLINE_REQUEST_DTO", "ChatDTO[model=gpt-4, prompt=hello]");
        attrs.put("API_KEY_ID", "key-tenant-1");

        String key1 = CacheFallbackStrategy.resolveRequestKey(
                ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest);
        String key2 = CacheFallbackStrategy.resolveRequestKey(
                ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest);
        assertNotNull(key1);
        assertEquals(key1, key2, "同一请求键必须稳定");

        // 请求 A（prompt=hello）写入并精确命中
        strategy.cacheResponse(ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest,
                ResponseEntity.ok("dto-cached"));
        assertEquals("dto-cached",
                strategy.fallback(new RuntimeException("boom"), key1).getBody());

        // 不同 DTO（不同 prompt）→ 不同键，且查不到 A 的缓存（503），互不串扰
        attrs.put("TRUNKLINE_REQUEST_DTO", "ChatDTO[model=gpt-4, prompt=world]");
        String keyB = CacheFallbackStrategy.resolveRequestKey(
                ModelServiceRegistry.ServiceType.chat, "gpt-4", httpRequest);
        assertNotEquals(key1, keyB, "不同请求体必须隔离");
        assertEquals(503,
                strategy.fallback(new RuntimeException("boom"), keyB).getStatusCodeValue());
    }

    @Test
    @DisplayName("测试 6: 空异常消息的降级")
    void testFallbackWithNullExceptionMessage() {
        ResponseEntity<?> response = strategy.fallback(new RuntimeException());
        assertEquals(503, response.getStatusCodeValue());
    }

    @Test
    @DisplayName("resolveRequestKey: 服务类型为空返回 null")
    void testResolveRequestKeyNullService() {
        withPreciseKey("rc:chat:gpt-4:h");
        assertNull(CacheFallbackStrategy.resolveRequestKey(null, "gpt-4", httpRequest));
    }
}
