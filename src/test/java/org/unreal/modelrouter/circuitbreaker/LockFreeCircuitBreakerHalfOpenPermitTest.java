package org.unreal.modelrouter.router.circuitbreaker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 问题清单 M-18 回归测试：HALF_OPEN 全量放行。
 *
 * <p>旧实现 HALF_OPEN 直接 return true，探针洪峰会瞬间全部打到刚恢复的下游。
 * 修复后放行探针数受 successThreshold 限制（首个转半开的探针计入上限）。
 */
@DisplayName("问题清单回归：熔断器半开探针限额")
class LockFreeCircuitBreakerHalfOpenPermitTest {

    @Test
    @DisplayName("M-18: 半开期最多放行 successThreshold 个探针，其余拒绝")
    void halfOpenLimitsProbeCount() throws InterruptedException {
        // failureThreshold=1（一次失败即打开），timeout=100ms，successThreshold=3（半开最多 3 探针）
        LockFreeCircuitBreaker cb = new LockFreeCircuitBreaker("probe", 1, 100, 3);
        cb.onFailure(); // CLOSED -> OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        Thread.sleep(150); // 等待超时
        assertTrue(cb.canExecute(), "首个调用应转半开并放行");
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // 不调用 onSuccess/onFailure，模拟探针在途：剩余令牌应只够 2 个
        assertTrue(cb.canExecute(), "第 2 个探针放行");
        assertTrue(cb.canExecute(), "第 3 个探针放行");
        assertFalse(cb.canExecute(), "第 4 个探针应被拒（已达 successThreshold 上限）");
        assertFalse(cb.canExecute(), "第 5 个探针应被拒");
    }

    @Test
    @DisplayName("M-18: successThreshold=1 时半开只放行首个探针")
    void halfOpenSingleProbe() throws InterruptedException {
        LockFreeCircuitBreaker cb = new LockFreeCircuitBreaker("probe1", 1, 100, 1);
        cb.onFailure();
        Thread.sleep(150);
        assertTrue(cb.canExecute(), "转半开的首个探针放行");
        assertFalse(cb.canExecute(), "successThreshold=1 时不应再有并发探针放行");
    }

    @Test
    @DisplayName("M-18: 并发探针不放行超过上限")
    void halfOpenConcurrentProbeBound() throws Exception {
        LockFreeCircuitBreaker cb = new LockFreeCircuitBreaker("probeC", 1, 100, 3);
        cb.onFailure();
        Thread.sleep(150);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (cb.canExecute()) {
                    allowed.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(3, allowed.get(), "并发下也最多放行 successThreshold 个探针");
    }

    @Test
    @DisplayName("A-F3: 从持久化恢复 HALF_OPEN 后探针令牌重建，不再永久熔断")
    void restoreHalfOpenRebuildsProbePermits() {
        LockFreeCircuitBreaker cb = new LockFreeCircuitBreaker("restored", 1, 100, 3);
        Map<String, Object> saved = new HashMap<>();
        saved.put("instanceId", "restored");
        saved.put("state", "HALF_OPEN");
        cb.restoreState(saved);

        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.canExecute(), "恢复后首个探针应放行（旧实现恒 0 令牌，永久拒绝）");
        assertTrue(cb.canExecute(), "恢复后第 2 个探针应放行");
        assertTrue(cb.canExecute(), "恢复后第 3 个探针应放行");
        assertFalse(cb.canExecute(), "恢复后仍须遵守 successThreshold 上限");
    }
}
