package org.unreal.modelrouter.router.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * C-06 回归：非幂等 POST 重试的幂等键在同一逻辑请求上保持稳定。
 *
 * <p>重试链与 processor lambda 捕获同一个 httpRequest；幂等键挂在其 attributes 上，
 * 首次生成后被所有重试/实例故障转移分支复用，下游可据此去重、避免重复计费。</p>
 */
@DisplayName("C-06：请求幂等键稳定性")
class BaseAdapterIdempotencyKeyTest {

    @Test
    @DisplayName("同一请求多次 ensure 返回同一个键（贯穿重试不漂移）")
    void stableAcrossRetries() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Map<String, Object> attrs = new HashMap<>();
        when(request.getAttributes()).thenReturn(attrs);

        String first = BaseAdapter.ensureIdempotencyKey(request);
        String second = BaseAdapter.ensureIdempotencyKey(request);
        String third = BaseAdapter.ensureIdempotencyKey(request);

        assertNotNull(first);
        assertFalse(first.isEmpty());
        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(first, attrs.get(BaseAdapter.IDEMPOTENCY_KEY_ATTRIBUTE));
    }

    @Test
    @DisplayName("不同请求生成不同的键")
    void distinctPerRequest() {
        ServerHttpRequest a = mock(ServerHttpRequest.class);
        ServerHttpRequest b = mock(ServerHttpRequest.class);
        when(a.getAttributes()).thenReturn(new HashMap<>());
        when(b.getAttributes()).thenReturn(new HashMap<>());

        assertNotEquals(
                BaseAdapter.ensureIdempotencyKey(a),
                BaseAdapter.ensureIdempotencyKey(b));
    }

    @Test
    @DisplayName("请求已有幂等键时原样复用（尊重上游/网关入口预设值）")
    void reusesPreexistingKey() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(BaseAdapter.IDEMPOTENCY_KEY_ATTRIBUTE, "client-supplied-key");
        when(request.getAttributes()).thenReturn(attrs);

        assertEquals("client-supplied-key", BaseAdapter.ensureIdempotencyKey(request));
    }

    @Test
    @DisplayName("httpRequest 为 null 时安全返回 null，不抛异常")
    void nullRequestSafe() {
        assertNull(BaseAdapter.ensureIdempotencyKey(null));
    }

    @Test
    @DisplayName("属性表不可用（getAttributes 抛异常）时安全降级")
    void attributesFailureSafe() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getAttributes()).thenThrow(new RuntimeException("attrs unavailable"));

        assertDoesNotThrow(() -> assertNull(BaseAdapter.ensureIdempotencyKey(request)));
    }
}
