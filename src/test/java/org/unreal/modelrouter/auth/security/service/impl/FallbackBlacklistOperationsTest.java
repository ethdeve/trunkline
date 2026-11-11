package org.unreal.modelrouter.auth.security.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.dto.TokenBlacklistEntry;
import org.unreal.modelrouter.persistence.store.StoreManager;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M-14 回归：降级黑名单操作不再静默失效/伪装"未拉黑"。
 *
 * <p>覆盖：读异常上抛（不 fail-open 伪装）、批量撤销真实落库、过期清理不再空转、
 * 大小/即将过期计数基于真实数据。</p>
 */
@DisplayName("M-14：FallbackBlacklistOperations 降级存储可靠性")
class FallbackBlacklistOperationsTest {

    private StoreManager storeManager;
    private FallbackBlacklistOperations ops;

    @BeforeEach
    void setUp() {
        storeManager = mock(StoreManager.class);
        ops = new FallbackBlacklistOperations(storeManager);
    }

    private Map<String, Object> entryMap(final String hash, final LocalDateTime expiresAt) {
        TokenBlacklistEntry e = new TokenBlacklistEntry();
        e.setTokenHash(hash);
        e.setReason("test");
        e.setAddedBy("tester");
        e.setAddedAt(LocalDateTime.now());
        e.setExpiresAt(expiresAt);
        return org.unreal.modelrouter.common.util.JacksonHelper.getObjectMapper()
                .convertValue(e, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    @Test
    @DisplayName("M-14 核心回归：降级存储读异常上抛，绝不伪装成'未拉黑'(false)")
    void readFailurePropagatesNotFalse() {
        when(storeManager.getConfig("jwt_blacklist_hash-x"))
                .thenThrow(new RuntimeException("StoreManager unavailable"));

        StepVerifier.create(ops.isBlacklisted("hash-x"))
                .expectError(FallbackBlacklistOperations.BlacklistReadException.class)
                .verify();
    }

    @Test
    @DisplayName("M-14: 条目数据损坏（反序列化失败）按读失败上抛，不放行")
    void corruptEntryPropagates() {
        // expiresAt 是非法日期文本 → Jackson 反序列化 TokenBlacklistEntry 抛异常
        when(storeManager.getConfig("jwt_blacklist_corrupt"))
                .thenReturn(Map.of("tokenHash", "corrupt", "expiresAt", "NOT-A-VALID-DATE"));

        StepVerifier.create(ops.isBlacklisted("corrupt"))
                .expectError(FallbackBlacklistOperations.BlacklistReadException.class)
                .verify();
    }

    @Test
    @DisplayName("M-14: 正常未命中返回 false，命中有效条目返回 true")
    void normalLookup() {
        when(storeManager.getConfig("jwt_blacklist_none")).thenReturn(null);
        StepVerifier.create(ops.isBlacklisted("none")).expectNext(false).verifyComplete();

        when(storeManager.getConfig("jwt_blacklist_valid"))
                .thenReturn(entryMap("valid", LocalDateTime.now().plusHours(1)));
        StepVerifier.create(ops.isBlacklisted("valid")).expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("M-14: 过期条目返回 false 并触发清理")
    void expiredEntryReturnsFalseAndDeletes() {
        when(storeManager.getConfig("jwt_blacklist_expired"))
                .thenReturn(entryMap("expired", LocalDateTime.now().minusMinutes(1)));

        StepVerifier.create(ops.isBlacklisted("expired")).expectNext(false).verifyComplete();
        verify(storeManager).deleteConfig("jwt_blacklist_expired");
    }

    @Test
    @DisplayName("M-14: batchAddToBlacklist 不再空转——真实写入降级存储")
    void batchAddActuallyWrites() {
        StepVerifier.create(ops.batchAddToBlacklist(java.util.Arrays.asList("h1", "h2", "", null), "batch-revoke", "admin"))
                .verifyComplete();

        verify(storeManager, times(2)).saveConfig(anyString(), anyMap());
        verify(storeManager).saveConfig(eq("jwt_blacklist_h1"), anyMap());
        verify(storeManager).saveConfig(eq("jwt_blacklist_h2"), anyMap());
    }

    @Test
    @DisplayName("M-14: cleanupExpiredEntries 不再空转——删除到期条目并计数")
    void cleanupActuallyDeletes() {
        when(storeManager.getAllKeys()).thenReturn(List.of(
                "jwt_blacklist_old", "jwt_blacklist_fresh", "unrelated_key"));
        when(storeManager.getConfig("jwt_blacklist_old"))
                .thenReturn(entryMap("old", LocalDateTime.now().minusHours(1)));
        when(storeManager.getConfig("jwt_blacklist_fresh"))
                .thenReturn(entryMap("fresh", LocalDateTime.now().plusHours(1)));

        StepVerifier.create(ops.cleanupExpiredEntriesWithCount()).expectNext(1L).verifyComplete();
        verify(storeManager).deleteConfig("jwt_blacklist_old");
        verify(storeManager, never()).deleteConfig("jwt_blacklist_fresh");
        verify(storeManager, never()).deleteConfig("unrelated_key");
    }

    @Test
    @DisplayName("M-14: getBlacklistSize 基于真实条目数，不再恒为 0")
    void sizeReflectsRealEntries() {
        when(storeManager.getAllKeys()).thenReturn(List.of(
                "jwt_blacklist_a", "jwt_blacklist_b", "other"));
        StepVerifier.create(ops.getBlacklistSize()).expectNext(2L).verifyComplete();
    }
}
