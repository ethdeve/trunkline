package org.unreal.modelrouter.router.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.router.fallback.FallbackStrategy;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 问题清单 m-09 / M-19 回归测试：降级响应求值。
 *
 * <p>m-09：{@code fallback()} 只能被调用一次（原三元式会调用两次）。
 * <p>M-19：非 Exception 的 Throwable（如 Error）不得强转崩溃，须包装后交给策略。
 */
@DisplayName("问题清单低风险修复回归：BaseAdapter 降级求值")
class BaseAdapterFallbackResolutionTest {

    @SuppressWarnings("unchecked")
    private FallbackStrategy<ResponseEntity<?>> strategy() {
        return mock(FallbackStrategy.class);
    }

    @Test
    @DisplayName("m-09: fallback() 只被调用一次")
    void fallbackEvaluatedExactlyOnce() {
        FallbackStrategy<ResponseEntity<?>> strategy = strategy();
        // 用 doReturn 规避 Mockito 对 ResponseEntity<?> 通配符返回值的编译期泛型推断限制。
        doReturn(ResponseEntity.ok("cached")).when(strategy).fallback(any(Exception.class));

        StepVerifier.create(BaseAdapter.resolveFallbackResponse(new RuntimeException("boom"), strategy))
                .assertNext(resp -> assertEquals("cached", resp.getBody()))
                .verifyComplete();

        verify(strategy, times(1)).fallback(any(Exception.class));
    }

    @Test
    @DisplayName("M-19: Error 不被强转，包装为 Exception 交给策略，不抛 ClassCastException")
    void errorIsWrappedNotCast() {
        FallbackStrategy<ResponseEntity<?>> strategy = strategy();
        AtomicInteger received = new AtomicInteger();
        doAnswer(inv -> {
            Exception cause = inv.getArgument(0);
            assertTrue(cause instanceof IllegalStateException, "Error 应被包装成 IllegalStateException");
            assertEquals("fatal", cause.getCause().getMessage());
            received.incrementAndGet();
            return ResponseEntity.ok("degraded");
        }).when(strategy).fallback(any(Exception.class));

        assertDoesNotThrow(() ->
                StepVerifier.create(BaseAdapter.resolveFallbackResponse(new AssertionError("fatal"), strategy))
                        .assertNext(resp -> assertEquals("degraded", resp.getBody()))
                        .verifyComplete());
        assertEquals(1, received.get());
    }

    @Test
    @DisplayName("降级无缓存时返回 503，不抛异常")
    void noFallbackReturns503() {
        FallbackStrategy<ResponseEntity<?>> strategy = strategy();
        doReturn(null).when(strategy).fallback(any(Exception.class));

        StepVerifier.create(BaseAdapter.resolveFallbackResponse(new RuntimeException("boom"), strategy))
                .expectErrorMatches(t -> t instanceof org.springframework.web.server.ResponseStatusException
                        && ((org.springframework.web.server.ResponseStatusException) t)
                                .getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value())
                .verify();

        verify(strategy, times(1)).fallback(any(Exception.class));
    }
}
