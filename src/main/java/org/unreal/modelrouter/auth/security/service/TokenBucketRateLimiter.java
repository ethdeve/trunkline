package org.unreal.modelrouter.auth.security.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于滑动窗口的内存速率限制器
 * 为每个 API Key 维护独立的请求计数窗口
 *
 * <p>#625（对齐上游 3.5.2）：窗口表从无界 {@code ConcurrentHashMap} 改为有界 Caffeine
 * 缓存（默认 10,000 条，闲置 2× 窗口时长后过期），防止大量一次性 API Key
 * 造成内存无上限增长。过期只在 key 闲置时发生，活跃 key 的窗口计数不受影响。</p>
 *
 * @author JAiRouter Team
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

    private static final long WINDOW_SIZE_MS = 60_000L;

    /** 有界缓存：最大 10,000 个 key，闲置 2× 窗口时长后过期 */
    private static final int MAX_WINDOWS = 10_000;
    private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMillis(2 * WINDOW_SIZE_MS);

    private final Cache<String, SlidingWindow> windows = Caffeine.newBuilder()
            .maximumSize(MAX_WINDOWS)
            .expireAfterAccess(EXPIRE_AFTER_ACCESS)
            .build();

    /**
     * 检查并消耗一个请求配额
     *
     * @param keyId         API Key ID
     * @param limitPerMinute 每分钟请求上限（0 表示不限制）
     * @return true 表示允许，false 表示超出速率限制
     */
    public boolean tryAcquire(final String keyId, final int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        SlidingWindow window = windows.get(keyId, k -> new SlidingWindow());
        return window.tryAcquire(limitPerMinute);
    }

    /**
     * 获取当前窗口内的请求数
     *
     * @param keyId API Key ID
     * @return 当前窗口内的请求数
     */
    public int getCurrentCount(final String keyId) {
        SlidingWindow window = windows.getIfPresent(keyId);
        if (window == null) {
            return 0;
        }
        return window.getCurrentCount();
    }

    /**
     * 清除指定 API Key 的速率限制窗口
     *
     * @param keyId API Key ID
     */
    public void reset(final String keyId) {
        windows.invalidate(keyId);
        log.debug("速率限制窗口已重置: {}", keyId);
    }

    /**
     * 清除所有速率限制窗口
     */
    public void resetAll() {
        windows.invalidateAll();
        log.info("所有速率限制窗口已重置");
    }

    /**
     * 获取当前活跃的窗口数量（用于监控）
     *
     * @return 活跃窗口数
     */
    public int getActiveWindowCount() {
        return (int) windows.estimatedSize();
    }

    /**
     * 滑动窗口实现
     */
    private static class SlidingWindow {
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger count = new AtomicInteger(0);

        boolean tryAcquire(final int limit) {
            long now = System.currentTimeMillis();
            long currentWindowStart = windowStart.get();

            if (now - currentWindowStart >= WINDOW_SIZE_MS) {
                if (windowStart.compareAndSet(currentWindowStart, now)) {
                    count.set(0);
                }
            }

            int current = count.incrementAndGet();
            if (current > limit) {
                count.decrementAndGet();
                return false;
            }
            return true;
        }

        int getCurrentCount() {
            long now = System.currentTimeMillis();
            if (now - windowStart.get() >= WINDOW_SIZE_MS) {
                return 0;
            }
            return count.get();
        }
    }
}
