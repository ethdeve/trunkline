package org.unreal.modelrouter.router.ratelimit.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.ratelimit.RateLimitConfig;
import org.unreal.modelrouter.router.ratelimit.RateLimitContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 问题清单 M-18 回归测试：限流器单次 CAS 失败即误判 429。
 *
 * <p>并发下多个请求同时 CAS 更新令牌/水量，只有一个成功，其余 CAS 失败。
 * 旧实现把 CAS 失败直接当作"限流拒绝"，导致容量明明充足却误拒；
 * 修复后循环重试直到真正资源不足才拒绝。
 */
@DisplayName("问题清单回归：限流器并发 CAS 重试")
class RateLimiterCasRetryTest {

    private static RateLimitContext ctx() {
        return new RateLimitContext(ModelServiceRegistry.ServiceType.chat, "m", "1.1.1.1", 1, "i", "u");
    }

    @Test
    @DisplayName("M-18: 令牌桶容量充足时，并发请求全部放行（不因 CAS 失败误拒）")
    void warmUpNoSpuriousReject() throws Exception {
        // 容量充足：1000 个令牌，8 并发各取 1，理论上全部放行。
        RateLimitConfig cfg = RateLimitConfig.builder().capacity(1000).rate(100).build();
        WarmUpRateLimiter limiter = new WarmUpRateLimiter(cfg);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire(ctx())) {
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

        assertEquals(threads, allowed.get(), "容量充足时不应有请求被 CAS 失败误拒");
    }

    @Test
    @DisplayName("M-18: 漏桶容量充足时，并发请求全部放行")
    void leakyBucketNoSpuriousReject() throws Exception {
        RateLimitConfig cfg = RateLimitConfig.builder().capacity(1000).rate(100).build();
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(cfg);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire(ctx())) {
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

        assertEquals(threads, allowed.get(), "容量充足时不应有请求被 CAS 失败误拒");
    }

    @Test
    @DisplayName("M-18: 令牌确实不足时仍正确拒绝（循环不会死等）")
    void warmUpStillRejectsWhenEmpty() {
        RateLimitConfig cfg = RateLimitConfig.builder().capacity(2).rate(0).build();
        WarmUpRateLimiter limiter = new WarmUpRateLimiter(cfg);
        assertTrue(limiter.tryAcquire(ctx()));
        assertTrue(limiter.tryAcquire(ctx()));
        assertFalse(limiter.tryAcquire(ctx()), "令牌耗尽后应拒绝，且循环应立即退出不死等");
    }

    @Test
    @DisplayName("M-18: 漏桶容量确实满时仍正确拒绝")
    void leakyBucketStillRejectsWhenFull() {
        RateLimitConfig cfg = RateLimitConfig.builder().capacity(2).rate(0).build();
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(cfg);
        assertTrue(limiter.tryAcquire(ctx()));
        assertTrue(limiter.tryAcquire(ctx()));
        assertFalse(limiter.tryAcquire(ctx()), "容量满后应拒绝");
    }
}
