package org.unreal.modelrouter.router.adapter.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.common.util.LogSanitizer;
import org.unreal.modelrouter.router.fallback.FallbackStrategy;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 问题清单 M-19 / m-08 回归测试。
 *
 * <p>M-19：handleFallbackError 收到非 Exception 的 Throwable（如 Error）时，
 * 不得因 (Exception) 强转崩溃，而应包装后交给降级策略。
 * <p>m-08：LogSanitizer 清洗换行/控制字符，防止用户可控参数伪造日志行（controller 已接入）。
 */
@DisplayName("问题清单回归：降级强转与日志注入防护")
class FallbackRequestProcessorErrorTest {

    @SuppressWarnings("unchecked")
    private FallbackStrategy<ResponseEntity<?>> strategy() {
        return mock(FallbackStrategy.class);
    }

    @Test
    @DisplayName("M-19: Error 被包装为 Exception 交给策略，不抛 ClassCastException")
    void errorWrappedNotCast() {
        FallbackRequestProcessor processor = new FallbackRequestProcessor();
        FallbackStrategy<ResponseEntity<?>> strategy = strategy();
        AtomicReference<Exception> received = new AtomicReference<>();
        doAnswer(inv -> {
            received.set(inv.getArgument(0));
            return ResponseEntity.ok("degraded");
        }).when(strategy).fallback(any(Exception.class));

        StepVerifier.create(processor.handleFallbackError(new AssertionError("fatal"), strategy))
                .assertNext(resp -> assertEquals("degraded", resp.getBody()))
                .verifyComplete();

        assertNotNull(received.get());
        assertTrue(received.get() instanceof IllegalStateException);
        assertEquals("fatal", received.get().getCause().getMessage());
    }

    @Test
    @DisplayName("M-19: 普通 Exception 原样交给策略（不改变既有行为）")
    void exceptionPassedThrough() {
        FallbackRequestProcessor processor = new FallbackRequestProcessor();
        FallbackStrategy<ResponseEntity<?>> strategy = strategy();
        RuntimeException original = new RuntimeException("boom");
        AtomicReference<Exception> received = new AtomicReference<>();
        doAnswer(inv -> {
            received.set(inv.getArgument(0));
            return ResponseEntity.ok("ok");
        }).when(strategy).fallback(any(Exception.class));

        StepVerifier.create(processor.handleFallbackError(original, strategy))
                .assertNext(resp -> assertEquals("ok", resp.getBody()))
                .verifyComplete();
        assertSame(original, received.get(), "Exception 应原样传递，不应被包装");
    }

    @Test
    @DisplayName("m-08: LogSanitizer 清洗换行与 ASCII 控制字符")
    void logSanitizerStripsControlChars() {
        String malicious = "user\ninfo: fake-log\r2026\t\u0001end";
        String clean = LogSanitizer.sanitize(malicious);
        assertFalse(clean.contains("\n"));
        assertFalse(clean.contains("\r"));
        assertFalse(clean.contains("\t"));
        assertFalse(clean.contains("\u0001"));
        assertEquals("user_info: fake-log_2026__end", clean);
    }

    @Test
    @DisplayName("m-08: LogSanitizer 对 null 安全")
    void logSanitizerNullSafe() {
        // 显式转 Object，避免 null 匹配到 sanitize(Object...) 重载（那会返回空数组）。
        assertEquals("null", LogSanitizer.sanitize((Object) null));
    }
}
