package org.unreal.modelrouter.router.fallback.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * #872/#890/#892 反证单测（2026-11-12 P1-A PR-A）。
 *
 * <p>定案口径（第七节）：
 * <ul>
 *   <li>#872：{@code cacheTtl<=0} = 永不过期（容量上限兜底）；TTL 到期命中 → 503 且移除死条目；</li>
 *   <li>#890：{@code cacheSize<=0} = 禁用缓存但保留 503 降级体（反证原实现退化成"保留最近 1 条"）；</li>
 *   <li>#892：流式响应（Publisher body / SSE contentType）不写入降级缓存。</li>
 * </ul>
 */
@DisplayName("CacheFallbackStrategy TTL/容量边界/流式防御测试（#872+#890+#892）")
class CacheFallbackStrategyTtlTest {

    private static final ModelServiceRegistry.ServiceType CHAT = ModelServiceRegistry.ServiceType.chat;

    /**
     * 可手动推进的时钟：固定起点 + advance，反证 TTL 过期行为（不依赖 sleep/真实时间）。
     */
    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(final long initialMillis) {
            this.millis = initialMillis;
        }

        void advance(final long ms) {
            this.millis += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private ServerHttpRequest reqWithKey(final String key) {
        ServerHttpRequest r = Mockito.mock(ServerHttpRequest.class);
        Map<String, Object> a = new HashMap<>();
        a.put("TRUNKLINE_RESPONSE_CACHE_KEY", key);
        when(r.getAttributes()).thenReturn(a);
        return r;
    }

    @Test
    @DisplayName("#872 反证：越过 TTL 后命中降级 503 且死条目被移除（修复前返回陈旧缓存）")
    void expiredEntryServes503AndIsRemoved() {
        MutableClock clock = new MutableClock(0L);
        CacheFallbackStrategy strategy = new CacheFallbackStrategy("chat", 10, 1000L, clock);
        strategy.cacheResponse(CHAT, "m", reqWithKey("k1"), ResponseEntity.ok("stale"));

        clock.advance(1001L);
        final ResponseEntity<?> r = strategy.fallback(new RuntimeException("boom"), "chat:k1");
        assertEquals(503, r.getStatusCodeValue());
        assertFalse(r.getBody().toString().contains("stale"), "绝不能返回过期缓存内容");
        assertEquals(0, strategy.cachedEntryCount(), "过期条目必须被移除");
    }

    @Test
    @DisplayName("#872 回归：TTL 内命中返回缓存内容")
    void withinTtlStillHits() {
        MutableClock clock = new MutableClock(0L);
        CacheFallbackStrategy strategy = new CacheFallbackStrategy("chat", 10, 1000L, clock);
        strategy.cacheResponse(CHAT, "m", reqWithKey("k1"), ResponseEntity.ok("fresh"));

        clock.advance(999L);
        assertEquals(200, strategy.fallback(new RuntimeException("boom"), "chat:k1").getStatusCodeValue());
        assertEquals("fresh", strategy.fallback(new RuntimeException("boom"), "chat:k1").getBody());
    }

    @Test
    @DisplayName("#872 定案：ttl<=0 = 永不过期——写后推进一年仍命中")
    void ttlNonPositiveMeansNeverExpire() {
        MutableClock clock = new MutableClock(0L);
        CacheFallbackStrategy strategy = new CacheFallbackStrategy("chat", 10, 0L, clock);
        strategy.cacheResponse(CHAT, "m", reqWithKey("k1"), ResponseEntity.ok("kept"));

        clock.advance(365L * 24 * 3600 * 1000);
        assertEquals("kept", strategy.fallback(new RuntimeException("boom"), "chat:k1").getBody());
    }

    @Test
    @DisplayName("#890 反证：cacheSize<=0 写后恒 503 且缓存为空（修复前退化成保留最近 1 条）")
    void nonPositiveSizeDisablesCache() {
        for (final int size : new int[] {0, -1}) {
            CacheFallbackStrategy strategy = new CacheFallbackStrategy("chat", size, 1000L);
            strategy.cacheResponse(CHAT, "m", reqWithKey("k1"), ResponseEntity.ok("should-not-cache"));
            assertEquals(0, strategy.cachedEntryCount(), "size=" + size + " 时不得写入任何条目");
            assertEquals(503,
                    strategy.fallback(new RuntimeException("boom"), "chat:k1").getStatusCodeValue(),
                    "size=" + size + " 禁用时必须保留 503 降级体");
        }
    }

    @Test
    @DisplayName("#890 回归：size=1 能写 1 条，第 2 条淘汰旧条目（容量恒 ≤1）")
    void sizeOneEvictsOldest() {
        CacheFallbackStrategy strategy = new CacheFallbackStrategy("chat", 1, 0L);
        strategy.cacheResponse(CHAT, "m", reqWithKey("k1"), ResponseEntity.ok("d1"));
        assertEquals(1, strategy.cachedEntryCount());
        strategy.cacheResponse(CHAT, "m", reqWithKey("k2"), ResponseEntity.ok("d2"));
        assertEquals(1, strategy.cachedEntryCount(), "容量不得超过 1");
        assertEquals(503, strategy.fallback(new RuntimeException("e"), "chat:k1").getStatusCodeValue());
        assertEquals("d2", strategy.fallback(new RuntimeException("e"), "chat:k2").getBody());
    }

    @Test
    @DisplayName("#872 回归：写入时清扫过期条目，容量不被死条目挤占")
    void expiredEntriesSweptBeforeCapacityCheck() {
        MutableClock clock = new MutableClock(0L);
        CacheFallbackStrategy strategy = new CacheFallbackStrategy("chat", 1, 1000L, clock);
        strategy.cacheResponse(CHAT, "m", reqWithKey("k1"), ResponseEntity.ok("old"));

        clock.advance(2000L); // k1 已过期
        strategy.cacheResponse(CHAT, "m", reqWithKey("k2"), ResponseEntity.ok("new"));
        assertEquals(1, strategy.cachedEntryCount());
        assertEquals("new", strategy.fallback(new RuntimeException("e"), "chat:k2").getBody(),
                "新条目必须写入成功（过期旧条目已清扫，不挤占容量）");
    }

    @Test
    @DisplayName("#892 反证：流式 body（Publisher）不写入降级缓存")
    void streamingBodySkipsWrite() {
        CacheFallbackStrategy strategy = new CacheFallbackStrategy("chat", 10, 0L);
        strategy.cacheResponse(CHAT, "m", reqWithKey("k1"),
                ResponseEntity.ok(Flux.just("chunk-a", "chunk-b")));
        assertEquals(0, strategy.cachedEntryCount(), "Publisher body 不可重放，禁止写入");
        assertEquals(503, strategy.fallback(new RuntimeException("boom"), "chat:k1").getStatusCodeValue());
    }

    @Test
    @DisplayName("#892 反证：SSE contentType 不写入降级缓存")
    void sseContentTypeSkipsWrite() {
        CacheFallbackStrategy strategy = new CacheFallbackStrategy("chat", 10, 0L);
        // 判定看的是【响应】的 contentType（isStreamingBody 读 response.getHeaders()），
        // 与请求 headers 无关。
        final HttpHeaders respHeaders = new HttpHeaders();
        respHeaders.setContentType(MediaType.TEXT_EVENT_STREAM);
        final ResponseEntity<?> sseResponse = new ResponseEntity<>("body", respHeaders, 200);
        strategy.cacheResponse(CHAT, "m", reqWithKey("k1"), sseResponse);
        assertEquals(0, strategy.cachedEntryCount(), "SSE contentType 视为流式，禁止写入");
    }
}
