package org.unreal.modelrouter.auth.security.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.unreal.modelrouter.common.dto.TokenBlacklistEntry;
import org.unreal.modelrouter.auth.security.service.JwtBlacklistService;
import org.unreal.modelrouter.persistence.store.StoreManager;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于Redis缓存和StoreManager的JWT黑名单服务实现
 * 使用Redis作为主要缓存层，StoreManager作为持久化存储和故障回退
 * 结合内存缓存提高查询性能
 *
 * <p>bean 统一由 {@code JwtServiceConfiguration#redisJwtBlacklistService} 提供（那里用限定名
 * {@code jwtReactiveRedisTemplate} 注入模板），本类不做组件扫描注册，原因有两条：
 * 一是与那个 {@code @Bean} 重复；二是本类有两个构造器且均未标 {@code @Autowired}，
 * 被组件扫描注册后 Spring 无法选定构造器（{@code No default constructor found}），
 * 在 {@code blacklist.redis.enabled=true} 时同样会导致启动失败（issue #194）。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisJwtBlacklistServiceImpl implements JwtBlacklistService {

    private final RedisBlacklistOperations redisOperations;
    private final FallbackBlacklistOperations fallbackOperations;

    // 内存缓存用于提高查询性能
    private final Map<String, TokenBlacklistEntry> memoryCache = new ConcurrentHashMap<>();
    private volatile long lastCacheUpdate = 0;
    private static final long CACHE_REFRESH_INTERVAL = 60000; // 1分钟缓存刷新间隔

    /**
     * M-14/M-16：两级存储（Redis + 降级存储）读取均失败时是否 fail-closed。
     * 复用 issue #118 的 {@code blacklistFailClosedWhenUnavailable} 语义；默认 false
     * 保持历史可用性优先，由 JwtServiceConfiguration 在装配时按配置注入。
     */
    private volatile boolean failClosedOnReadFailure = false;

    public void setFailClosedOnReadFailure(final boolean failClosedOnReadFailure) {
        this.failClosedOnReadFailure = failClosedOnReadFailure;
    }

    /**
     * 构造函数 - 用于依赖注入
     */
    public RedisJwtBlacklistServiceImpl(
            final ReactiveRedisTemplate<String, String> redisTemplate,
            final StoreManager fallbackStoreManager) {
        this.redisOperations = new RedisBlacklistOperations(redisTemplate);
        this.fallbackOperations = new FallbackBlacklistOperations(fallbackStoreManager);
    }

    private static final long DEFAULT_BLACKLIST_TTL_SECONDS = 24 * 60 * 60; // 24h

    @Override
    public Mono<Void> addToBlacklist(final String tokenHash, final String reason, final String addedBy) {
        return addToBlacklist(tokenHash, reason, addedBy, DEFAULT_BLACKLIST_TTL_SECONDS);
    }

    @Override
    public Mono<Void> addToBlacklist(final String tokenHash, final String reason, final String addedBy, final long ttlSeconds) {
        if (tokenHash == null || tokenHash.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Token hash cannot be null or empty"));
        }

        LocalDateTime now = LocalDateTime.now();
        long ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_BLACKLIST_TTL_SECONDS;

        // 创建黑名单条目
        TokenBlacklistEntry entry = new TokenBlacklistEntry();
        entry.setTokenHash(tokenHash);
        entry.setReason(reason != null ? reason : "Manual revocation");
        entry.setAddedBy(addedBy != null ? addedBy : "system");
        entry.setAddedAt(now);
        entry.setExpiresAt(now.plusSeconds(ttl)); // TTL 可配置（C3）

        return redisOperations.addToBlacklist(entry)
            .onErrorResume(error -> {
                log.warn("Failed to add token to blacklist in Redis, "
                        + "falling back to StoreManager: {}", error.getMessage());
                return fallbackOperations.addToBlacklist(entry);
            })
            .doOnSuccess(unused -> {
                memoryCache.put(tokenHash, entry);
                log.debug("Successfully added token to blacklist: {}", tokenHash);
            })
            .doOnError(error -> log.error("Failed to add token to blacklist: {}", error.getMessage(), error));
    }

    @Override
    public Mono<Boolean> isBlacklisted(final String tokenHash) {
        if (tokenHash == null || tokenHash.trim().isEmpty()) {
            return Mono.just(false);
        }

        // 首先检查内存缓存
        TokenBlacklistEntry cachedEntry = memoryCache.get(tokenHash);
        if (cachedEntry != null) {
            if (cachedEntry.getExpiresAt() != null
            && cachedEntry.getExpiresAt().isBefore(LocalDateTime.now())) {
                memoryCache.remove(tokenHash);
                return Mono.just(false);
            }
            return Mono.just(true);
        }

        // 检查Redis；失败降级到 StoreManager；两级读取均失败时按 fail-closed 开关决策（M-14/M-16）。
        // 关键：降级存储读取失败抛 BlacklistReadException，绝不伪装成"确定未拉黑"放行。
        return redisOperations.isBlacklisted(tokenHash)
            .onErrorResume(redisError -> {
                log.warn("Failed to check blacklist in Redis, falling back to StoreManager: {}",
                        redisError.getMessage());
                return fallbackOperations.isBlacklisted(tokenHash);
            })
            .onErrorResume(FallbackBlacklistOperations.BlacklistReadException.class, readError -> {
                log.error("Both Redis and fallback blacklist storage failed to read; "
                        + "failClosed={} — token revocation status CANNOT be confirmed. error={}",
                        failClosedOnReadFailure, readError.getMessage());
                return Mono.just(failClosedOnReadFailure);
            })
            .doOnNext(isBlacklisted -> {
                if (isBlacklisted) {
                    getBlacklistEntry(tokenHash)
                        .subscribe(entry -> {
                            if (entry != null) {
                                memoryCache.put(tokenHash, entry);
                            }
                        });
                }
            })
            .doOnError(error -> log.error("Failed to check blacklist status for token: {}", error.getMessage(), error));
    }

    @Override
    public Mono<Void> removeFromBlacklist(final String tokenHash) {
        if (tokenHash == null || tokenHash.trim().isEmpty()) {
            return Mono.empty();
        }

        return redisOperations.removeFromBlacklist(tokenHash)
            .onErrorResume(error -> {
                log.warn("Failed to remove token from blacklist in Redis, "
                        + "falling back to StoreManager: {}", error.getMessage());
                return fallbackOperations.removeFromBlacklist(tokenHash);
            })
            .doOnSuccess(unused -> {
                memoryCache.remove(tokenHash);
                log.debug("Successfully removed token from blacklist: {}", tokenHash);
            })
            .doOnError(error -> log.error("Failed to remove token from blacklist: {}", error.getMessage(), error));
    }

    @Override
    public Mono<Long> getBlacklistSize() {
        refreshCacheIfNeeded();

        return redisOperations.getBlacklistSize()
            .onErrorResume(error -> {
                log.warn("Failed to get blacklist size from Redis, "
                        + "falling back to StoreManager: {}", error.getMessage());
                return fallbackOperations.getBlacklistSize();
            })
            .doOnNext(size -> log.debug("Blacklist size: {}", size))
            .doOnError(error -> log.error("Failed to get blacklist size: {}", error.getMessage(), error));
    }

    @Override
    public Mono<Void> cleanupExpiredEntries() {
        return redisOperations.cleanupExpiredEntries()
            .onErrorResume(error -> {
                log.warn("Failed to cleanup expired entries in Redis, "
                        + "falling back to StoreManager: {}", error.getMessage());
                return fallbackOperations.cleanupExpiredEntries();
            })
            .doOnSuccess(unused -> {
                // 清理内存缓存中的过期条目
                LocalDateTime now = LocalDateTime.now();
                memoryCache.entrySet().removeIf(entry -> {
                    TokenBlacklistEntry blacklistEntry = entry.getValue();
                    return blacklistEntry.getExpiresAt() != null
                    && blacklistEntry.getExpiresAt().isBefore(now);
                });
                log.info("Successfully cleaned up expired blacklist entries");
            })
            .doOnError(error -> log.error("Failed to cleanup expired blacklist entries: {}",
                    error.getMessage(), error));
    }

    @Override
    public Mono<Map<String, Object>> getBlacklistStats() {
        return redisOperations.getBlacklistStats()
            .onErrorResume(error -> {
                log.warn("Failed to get blacklist stats from Redis, "
                        + "falling back to StoreManager: {}", error.getMessage());
                return fallbackOperations.getBlacklistStats(memoryCache.size());
            })
            .map(stats -> {
                stats.put("memoryCache", memoryCache.size());
                stats.put("lastCacheUpdate", lastCacheUpdate);
                stats.put("cacheRefreshInterval", CACHE_REFRESH_INTERVAL);
                stats.put("currentTime", System.currentTimeMillis());
                return stats;
            })
            .doOnError(error -> log.error("Failed to get blacklist stats: {}", error.getMessage(), error));
    }

    @Override
    public Mono<TokenBlacklistEntry> getBlacklistEntry(final String tokenHash) {
        if (tokenHash == null || tokenHash.trim().isEmpty()) {
            return Mono.empty();
        }

        // 首先检查内存缓存
        TokenBlacklistEntry cachedEntry = memoryCache.get(tokenHash);
        if (cachedEntry != null) {
            return Mono.just(cachedEntry);
        }

        return redisOperations.getBlacklistEntry(tokenHash)
            .onErrorResume(error -> {
                log.warn("Failed to get blacklist entry from Redis, "
                        + "falling back to StoreManager: {}", error.getMessage());
                return fallbackOperations.getBlacklistEntry(tokenHash);
            })
            .doOnNext(entry -> {
                if (entry != null) {
                    memoryCache.put(tokenHash, entry);
                }
            })
            .doOnError(error -> log.error("Failed to get blacklist entry for token: {}", error.getMessage(), error));
    }

    @Override
    public Mono<Void> batchAddToBlacklist(final List<String> tokenHashes, final String reason, final String addedBy) {
        if (tokenHashes == null || tokenHashes.isEmpty()) {
            return Mono.empty();
        }

        return redisOperations.batchAddToBlacklist(tokenHashes, reason, addedBy)
            .onErrorResume(error -> {
                log.warn("Failed to batch add to blacklist in Redis, "
                        + "falling back to StoreManager: {}", error.getMessage());
                return fallbackOperations.batchAddToBlacklist(tokenHashes, reason, addedBy);
            })
            .doOnSuccess(unused -> log.info("Batch added {} tokens to blacklist", tokenHashes.size()))
            .doOnError(error -> log.error("Failed to batch add tokens to blacklist: {}", error.getMessage(), error));
    }

    @Override
    public Mono<Boolean> isServiceAvailable() {
        return redisOperations.isServiceAvailable()
            .doOnNext(available -> log.debug("Blacklist service availability: {}", available));
    }

    @Override
    public Mono<Long> getExpiringEntriesCount(final int hoursUntilExpiry) {
        return redisOperations.getExpiringEntriesCount(hoursUntilExpiry)
            .onErrorResume(error -> {
                log.warn("Failed to get expiring entries count from Redis, "
                        + "falling back to StoreManager: {}", error.getMessage());
                return fallbackOperations.getExpiringEntriesCount(hoursUntilExpiry);
            })
            .doOnNext(count -> log.debug("Expiring entries count ({}h): {}", hoursUntilExpiry, count))
            .doOnError(error -> log.error("Failed to get expiring entries count: {}", error.getMessage(), error));
    }

    @Override
    public Mono<Long> cleanupExpiredEntriesWithCount() {
        return redisOperations.cleanupExpiredEntries()
            .then(fallbackOperations.cleanupExpiredEntries())
            .then(Mono.fromCallable(() -> {
                // 由于Redis和fallback的清理都是Mono<Void>，我们无法直接获取清理数量
                // 这里返回0作为占位符，实际的清理统计可以通过其他方式获取
                return 0L;
            }))
            .onErrorResume(error -> {
                log.warn("Failed to cleanup expired entries with count: {}", error.getMessage());
                return Mono.just(0L);
            });
    }

    // 私有辅助方法

    private void refreshCacheIfNeeded() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastCacheUpdate > CACHE_REFRESH_INTERVAL) {
            try {
                LocalDateTime now = LocalDateTime.now();
                memoryCache.entrySet().removeIf(entry -> {
                    TokenBlacklistEntry blacklistEntry = entry.getValue();
                    return blacklistEntry.getExpiresAt() != null
                    && blacklistEntry.getExpiresAt().isBefore(now);
                });

                lastCacheUpdate = currentTime;
                log.debug("Refreshed blacklist memory cache, current size: {}", memoryCache.size());

            } catch (Exception e) {
                log.warn("Failed to refresh blacklist cache: {}", e.getMessage());
            }
        }
    }
}
