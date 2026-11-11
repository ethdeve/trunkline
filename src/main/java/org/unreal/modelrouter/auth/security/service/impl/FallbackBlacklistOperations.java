package org.unreal.modelrouter.auth.security.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unreal.modelrouter.common.dto.TokenBlacklistEntry;
import org.unreal.modelrouter.common.util.JacksonHelper;
import org.unreal.modelrouter.persistence.store.StoreManager;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fallback黑名单操作封装
 * 处理当Redis不可用时的StoreManager降级存储操作
 */
@Slf4j
@RequiredArgsConstructor
public final class FallbackBlacklistOperations {

    private final StoreManager fallbackStoreManager;

    private static final String BLACKLIST_KEY_PREFIX = "jwt_blacklist_";

    /**
     * 添加黑名单条目到Fallback存储
     */
    public Mono<Void> addToBlacklist(final TokenBlacklistEntry entry) {
        return Mono.fromRunnable(() -> {
            try {
                Map<String, Object> entryData = convertToMap(entry);
                String blacklistKey = BLACKLIST_KEY_PREFIX + entry.getTokenHash();
                fallbackStoreManager.saveConfig(blacklistKey, entryData);
            } catch (Exception e) {
                log.warn("Failed to add token to blacklist in fallback storage: {}", e.getMessage());
            }
        });
    }

    /**
     * 检查token是否在Fallback黑名单中（M-14）。
     *
     * <p>读取异常不再静默返回 false（那会把"降级存储-query 失败"伪装成"确定未拉黑"，
     * 使 Redis 不可用期间的撤销/登出令牌绕过校验）。改为抛出带语义的
     * {@link BlacklistReadException}，由上层复合/Redis 服务按统一的 fail-closed 开关决策：
     * 无法确认时要么拒绝（安全优先），要么显式降级放行并告警，但绝不伪装成确定的否定答案。</p>
     */
    public Mono<Boolean> isBlacklisted(final String tokenHash) {
        return Mono.fromCallable(() -> {
            String blacklistKey = BLACKLIST_KEY_PREFIX + tokenHash;
            Map<String, Object> entryData;
            try {
                entryData = fallbackStoreManager.getConfig(blacklistKey);
            } catch (Exception e) {
                throw new BlacklistReadException(
                        "降级黑名单存储读取失败，无法确认令牌状态: " + e.getMessage(), e);
            }

            if (entryData == null) {
                return false;
            }

            TokenBlacklistEntry entry;
            try {
                entry = convertFromMap(entryData);
            } catch (Exception e) {
                // 数据已损坏：不能当作"未拉黑"放行，按读失败上抛交由上层按开关决策。
                throw new BlacklistReadException(
                        "降级黑名单条目解析失败，无法确认令牌状态: " + e.getMessage(), e);
            }

            if (entry.getExpiresAt() != null
                    && entry.getExpiresAt().isBefore(LocalDateTime.now())) {
                try {
                    fallbackStoreManager.deleteConfig(blacklistKey);
                } catch (Exception ignore) {
                    log.debug("清理降级黑名单过期条目失败: {}", ignore.getMessage());
                }
                return false;
            }

            return true;
        });
    }

    /**
     * 降级黑名单读取失败（存储不可用或数据损坏）——区别于"确定不在黑名单"。
     */
    public static final class BlacklistReadException extends RuntimeException {
        public BlacklistReadException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 从Fallback存储移除黑名单条目
     */
    public Mono<Void> removeFromBlacklist(final String tokenHash) {
        return Mono.fromRunnable(() -> {
            try {
                String blacklistKey = BLACKLIST_KEY_PREFIX + tokenHash;
                if (fallbackStoreManager.exists(blacklistKey)) {
                    fallbackStoreManager.deleteConfig(blacklistKey);
                }
            } catch (Exception e) {
                log.warn("Failed to remove token from blacklist in fallback storage: {}", e.getMessage());
            }
        });
    }

    /**
     * 获取Fallback黑名单大小（M-14：按降级存储真实条目计数，不再恒返回 0）。
     */
    public Mono<Long> getBlacklistSize() {
        return Mono.fromCallable(() -> {
            long count = 0;
            try {
                for (String key : fallbackStoreManager.getAllKeys()) {
                    if (key != null && key.startsWith(BLACKLIST_KEY_PREFIX)) {
                        count++;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to count fallback blacklist entries: {}", e.getMessage());
            }
            return count;
        });
    }

    /**
     * 清理Fallback过期条目（M-14：不再空转，遍历降级存储删除到期条目）。
     * 保持 {@code Mono<Void>} 签名以兼容 {@link RedisJwtBlacklistServiceImpl} 的降级链。
     */
    public Mono<Void> cleanupExpiredEntries() {
        return cleanupExpiredEntriesWithCount().then();
    }

    /**
     * 清理Fallback过期条目并返回清理数量（M-14）。
     */
    public Mono<Long> cleanupExpiredEntriesWithCount() {
        return Mono.fromCallable(() -> {
            long removed = 0;
            LocalDateTime now = LocalDateTime.now();
            try {
                for (String key : fallbackStoreManager.getAllKeys()) {
                    if (key == null || !key.startsWith(BLACKLIST_KEY_PREFIX)) {
                        continue;
                    }
                    try {
                        Map<String, Object> data = fallbackStoreManager.getConfig(key);
                        if (data == null) {
                            continue;
                        }
                        TokenBlacklistEntry entry = convertFromMap(data);
                        if (entry.getExpiresAt() != null && entry.getExpiresAt().isBefore(now)) {
                            fallbackStoreManager.deleteConfig(key);
                            removed++;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to clean fallback blacklist entry {}: {}", key, e.getMessage());
                    }
                }
                if (removed > 0) {
                    log.info("Cleaned {} expired entries from fallback blacklist storage", removed);
                }
            } catch (Exception e) {
                log.warn("Failed to iterate fallback blacklist for cleanup: {}", e.getMessage());
            }
            return removed;
        });
    }

    /**
     * 获取Fallback黑名单统计信息
     */
    public Mono<Map<String, Object>> getBlacklistStats(final int memoryCacheSize) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("error", "Fallback storage stats not available");
        stats.put("memoryCache", memoryCacheSize);
        return Mono.just(stats);
    }

    /**
     * 获取单个黑名单条目
     */
    public Mono<TokenBlacklistEntry> getBlacklistEntry(final String tokenHash) {
        return Mono.fromCallable(() -> {
            try {
                String blacklistKey = BLACKLIST_KEY_PREFIX + tokenHash;
                Map<String, Object> entryData = fallbackStoreManager.getConfig(blacklistKey);
                return entryData != null ? convertFromMap(entryData) : null;
            } catch (Exception e) {
                log.warn("Failed to get blacklist entry from fallback storage: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * 批量添加黑名单条目（M-14：不再空转——Redis 不可用时的批量撤销必须真实落库，
     * 否则管理台"批量撤销"在降级态静默失效）。
     */
    public Mono<Void> batchAddToBlacklist(final List<String> tokenHashes, final String reason, final String addedBy) {
        if (tokenHashes == null || tokenHashes.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            int added = 0;
            LocalDateTime now = LocalDateTime.now();
            for (String tokenHash : tokenHashes) {
                if (tokenHash == null || tokenHash.trim().isEmpty()) {
                    continue;
                }
                try {
                    TokenBlacklistEntry entry = new TokenBlacklistEntry();
                    entry.setTokenHash(tokenHash);
                    entry.setReason(reason != null ? reason : "Manual revocation");
                    entry.setAddedBy(addedBy != null ? addedBy : "system");
                    entry.setAddedAt(now);
                    // 降级存储无原始令牌剩余寿命，采用与单条一致的默认 TTL（24h）。
                    entry.setExpiresAt(now.plusHours(24));
                    fallbackStoreManager.saveConfig(BLACKLIST_KEY_PREFIX + tokenHash, convertToMap(entry));
                    added++;
                } catch (Exception e) {
                    log.warn("Failed to batch-add token {} to fallback blacklist: {}", tokenHash, e.getMessage());
                }
            }
            log.debug("Batch-added {} tokens to fallback blacklist storage", added);
        });
    }

    /**
     * 获取即将过期的条目数量（M-14：按降级存储真实数据估算）。
     */
    public Mono<Long> getExpiringEntriesCount(final int hoursUntilExpiry) {
        return Mono.fromCallable(() -> {
            long count = 0;
            LocalDateTime cutoff = LocalDateTime.now().plusHours(hoursUntilExpiry);
            try {
                for (String key : fallbackStoreManager.getAllKeys()) {
                    if (key == null || !key.startsWith(BLACKLIST_KEY_PREFIX)) {
                        continue;
                    }
                    Map<String, Object> data = fallbackStoreManager.getConfig(key);
                    if (data == null) {
                        continue;
                    }
                    TokenBlacklistEntry entry = convertFromMap(data);
                    if (entry.getExpiresAt() != null
                            && entry.getExpiresAt().isBefore(cutoff)
                            && entry.getExpiresAt().isAfter(LocalDateTime.now())) {
                        count++;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to count expiring fallback blacklist entries: {}", e.getMessage());
            }
            return count;
        });
    }

    // 私有辅助方法

    private Map<String, Object> convertToMap(final TokenBlacklistEntry entry) {
        try {
            return JacksonHelper.getObjectMapper().convertValue(entry, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert blacklist entry to map", e);
        }
    }

    private TokenBlacklistEntry convertFromMap(final Map<String, Object> entryData) {
        try {
            return JacksonHelper.getObjectMapper().convertValue(entryData, TokenBlacklistEntry.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert map to blacklist entry", e);
        }
    }
}
