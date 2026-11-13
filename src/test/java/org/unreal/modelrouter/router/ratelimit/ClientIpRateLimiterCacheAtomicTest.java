package org.unreal.modelrouter.router.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #878：IP 限流器加载原子性修复。
 *
 * <p>修复前 {@code ClientIpRateLimiterCache.get} 是
 * {@code getIfPresent → loader → put} 三步非原子：并发 miss 时每个线程各自
 * 建桶再 put 覆盖，先建桶的窗口计数被丢弃 → 同一客户端瞬时并发放行总量
 * 超过 capacity。修复后 {@code cache.get(key, loader)}——同一 key 并发 miss
 * 只执行一次 loader，全部线程拿到同一实例。</p>
 */
@DisplayName("issue #878：IP 限流器原子加载")
class ClientIpRateLimiterCacheAtomicTest {

    private RateLimiter stubLimiter() {
        return new RateLimiter() {
            @Override
            public boolean tryAcquire(final RateLimitContext context) {
                return true;
            }

            @Override
            public RateLimitConfig getConfig() {
                return null;
            }
        };
    }

    @Test
    @DisplayName("32 线程并发同新 key → loader 调用数 ==1 且拿到同一实例（反证旧多次建桶互相覆盖）")
    void concurrentMissLoadsOnce() throws Exception {
        ClientIpRateLimiterCache cache = new ClientIpRateLimiterCache();
        AtomicInteger loaderCalls = new AtomicInteger(0);
        List<RateLimiter> obtained = new CopyOnWriteArrayList<>();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    RateLimiter limiter = cache.get(ServiceType.chat,
                            RateLimitKeyDimension.CLIENT_IP, "10.0.0.1",
                            key -> {
                                loaderCalls.incrementAndGet();
                                return stubLimiter();
                            });
                    obtained.add(limiter);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发 get 应在时限内完成");
        pool.shutdownNow();

        assertEquals(1, loaderCalls.get(),
                "修复前并发 miss 各自建桶 loader 被调多次；修复后同 key 只加载一次");
        assertEquals(threads, obtained.size());
        for (RateLimiter limiter : obtained) {
            assertSame(obtained.get(0), limiter, "全部线程必须拿到同一限流器实例（窗口计数单点）");
        }
    }

    @Test
    @DisplayName("loader 返回 null → get 返回 null 且不缓存（RateLimitManager null 放行语义不变）")
    void nullLoaderResultNotCached() {
        ClientIpRateLimiterCache cache = new ClientIpRateLimiterCache();

        RateLimiter result = cache.get(ServiceType.chat, RateLimitKeyDimension.CLIENT_IP,
                "no-limiter", key -> null);

        assertNull(result);
        assertEquals(0L, cache.size(), "loader 返回 null 不得缓存占位条目");
    }

    @Test
    @DisplayName("getStats 统一为 Caffeine 单一口径：miss 后 hit 计入同一套计数")
    void statsSingleSourceOfTruth() {
        ClientIpRateLimiterCache cache = new ClientIpRateLimiterCache();

        cache.get(ServiceType.chat, RateLimitKeyDimension.CLIENT_IP, "10.0.0.2", key -> stubLimiter());
        cache.getIfPresent(ServiceType.chat, RateLimitKeyDimension.CLIENT_IP, "10.0.0.2");
        cache.getIfPresent(ServiceType.chat, RateLimitKeyDimension.CLIENT_IP, "not-present");

        ClientIpRateLimiterCache.CacheStats stats = cache.getStats();
        assertTrue(stats.getMissCount() >= 1, "首次 get + not-present 查询应产生 miss（Caffeine stats）");
        assertTrue(stats.getHitCount() >= 1, "第二次 getIfPresent 同 key 应产生 hit（Caffeine stats）");
        assertTrue(stats.getHitRate() > 0.0);
    }

    @Test
    @DisplayName("同一 key 再次 get 命中不再调 loader")
    void secondGetHitsWithoutLoading() {
        ClientIpRateLimiterCache cache = new ClientIpRateLimiterCache();
        AtomicInteger loaderCalls = new AtomicInteger(0);

        cache.get(ServiceType.chat, RateLimitKeyDimension.CLIENT_IP, "10.0.0.3",
                key -> {
                    loaderCalls.incrementAndGet();
                    return stubLimiter();
                });
        cache.get(ServiceType.chat, RateLimitKeyDimension.CLIENT_IP, "10.0.0.3",
                key -> {
                    loaderCalls.incrementAndGet();
                    return stubLimiter();
                });

        assertEquals(1, loaderCalls.get(), "第二次 get 命中缓存，loader 不得再执行");
    }
}
