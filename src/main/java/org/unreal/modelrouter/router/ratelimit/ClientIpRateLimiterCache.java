package org.unreal.modelrouter.router.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType;

import java.util.concurrent.TimeUnit;

/**
 * 客户端 IP 限流器缓存
 *
 * 使用 Caffeine 替代 ConcurrentHashMap，解决内存泄漏风险：
 * - 自动过期清理：30 分钟无访问自动移除
 * - 内存上限：最多 10000 条目
 * - 统计功能：命中率、淘汰数
 *
 * @author JAiRouter Team
 */
public final class ClientIpRateLimiterCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientIpRateLimiterCache.class);

    // 缓存配置
    private static final int MAX_SIZE = 10000;
    private static final int EXPIRE_MINUTES = 30;

    // Caffeine 缓存：serviceType:dimension:keyValue -> RateLimiter
    private final Cache<String, RateLimiter> cache;

    public ClientIpRateLimiterCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterAccess(EXPIRE_MINUTES, TimeUnit.MINUTES)
                .removalListener((String key, RateLimiter value, RemovalCause cause) -> {
                    if (cause.wasEvicted()) {
                        LOGGER.debug("Rate limiter evicted: key={}, cause={}", key, cause);
                    }
                })
                .recordStats()
                .build();

        LOGGER.info("ClientIpRateLimiterCache initialized: maxSize={}, expireAfterAccess={}min",
                MAX_SIZE, EXPIRE_MINUTES);
    }

    /**
     * 获取限流器（Caffeine 原子加载，issue #878）
     *
     * <p>修复前 {@code getIfPresent → loader → put} 三步非原子：并发 miss 时每个
     * 线程各自建桶再 put 覆盖，先建桶的窗口计数被丢弃 → 同一客户端放行总量超过
     * capacity。修复后 {@link Cache#get(Object, java.util.function.Function)}：
     * 同一 key 并发 miss 只执行一次 loader，全部线程拿到同一实例——窗口计数
     * 单点串行化（与 {@link ScopedRateLimiterWrapper} 的现成写法一致）。</p>
     *
     * @param serviceType 服务类型
     * @param dimension   限流键维度（#161）
     * @param keyValue    维度对应的键值（客户端 IP / API Key ID / 租户）
     * @param loader      加载器（缓存未命中时调用）；接收生成的缓存键，
     *                    分布式实现需要它作为 Redis key 的一部分（#161）
     * @return 限流器；loader 返回 null 时不缓存、返回 null（调用方 null 放行语义不变）
     */
    public RateLimiter get(final ServiceType serviceType, final RateLimitKeyDimension dimension,
                           final String keyValue,
                           final java.util.function.Function<String, RateLimiter> loader) {
        String key = generateKey(serviceType, dimension, keyValue);
        return cache.get(key, loader::apply);
    }

    /**
     * 获取已存在的限流器（不创建）
     */
    public RateLimiter getIfPresent(final ServiceType serviceType, final RateLimitKeyDimension dimension,
                                    final String keyValue) {
        String key = generateKey(serviceType, dimension, keyValue);
        return cache.getIfPresent(key);
    }

    /**
     * 放入限流器
     */
    public void put(final ServiceType serviceType, final RateLimitKeyDimension dimension,
                    final String keyValue, final RateLimiter limiter) {
        String key = generateKey(serviceType, dimension, keyValue);
        cache.put(key, limiter);
    }

    /**
     * 移除限流器
     */
    public void invalidate(final ServiceType serviceType, final RateLimitKeyDimension dimension,
                           final String keyValue) {
        String key = generateKey(serviceType, dimension, keyValue);
        cache.invalidate(key);
    }

    /**
     * 清空缓存
     */
    public void invalidateAll() {
        cache.invalidateAll();
        LOGGER.info("ClientIpRateLimiterCache cleared");
    }

    /**
     * 获取缓存大小
     */
    public long size() {
        return cache.estimatedSize();
    }

    /**
     * 获取缓存统计信息（issue #878：统一为 Caffeine 单一口径）
     *
     * <p>修复前 hit/miss 是本类手工 AtomicLong、eviction 是 removalListener
     * 计数，而 Caffeine 又有自己一套 stats——双口径数值互相矛盾。修复后全部
     * 取自 {@link Cache#stats()}（{@code recordStats()} 已开启，getIfPresent /
     * cache.get 的命中与未命中都计入）。</p>
     */
    public CacheStats getStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
        return new CacheStats(
                stats.hitCount(),
                stats.missCount(),
                stats.evictionCount(),
                size(),
                stats.hitRate()
        );
    }

    /**
     * 生成缓存键。
     *
     * <p>带维度前缀：同一服务在切换维度（如 {@code client-ip} → {@code api-key}）后，
     * 即使键值字面相同也不会复用彼此的限流器状态（#161）。</p>
     */
    private String generateKey(final ServiceType serviceType, final RateLimitKeyDimension dimension,
                               final String keyValue) {
        final RateLimitKeyDimension dim =
                dimension != null ? dimension : RateLimitKeyDimension.CLIENT_IP;
        return serviceType.name() + ":" + dim.configValue() + ":" + keyValue;
    }

    /**
     * 缓存统计信息
     */
    public static final class CacheStats {
        private final long hitCount;
        private final long missCount;
        private final long evictionCount;
        private final long size;
        private final double hitRate;

        public CacheStats(final long hitCount, final long missCount, final long evictionCount,
                          final long size, final double hitRate) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
            this.size = size;
            this.hitRate = hitRate;
        }

        public long getHitCount() {
            return hitCount;
        }

        public long getMissCount() {
            return missCount;
        }

        public long getEvictionCount() {
            return evictionCount;
        }

        public long getSize() {
            return size;
        }

        public double getHitRate() {
            return hitRate;
        }

        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, evictions=%d, size=%d, hitRate=%.2f%%}",
                    hitCount, missCount, evictionCount, size, hitRate * 100);
        }
    }
}
