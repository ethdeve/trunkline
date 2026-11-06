package org.unreal.modelrouter.auth.security.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QuotaRedisKeyMigrator} 行为验证（品牌清理：jairouter:quota → trunkline:quota）。
 *
 * <p>覆盖：旧计数并入新前缀（求和语义）、迁移后删除旧 key、无旧 key 时零操作、
 * legacy 前缀置空 / 与新前缀相同时跳过、新前缀已有计数时正确合并。</p>
 */
@DisplayName("QuotaRedisKeyMigrator 旧前缀迁移")
class QuotaRedisKeyMigratorTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 14, 13, 45, 30);

    private FakeReactiveRedisTemplate template;
    private QuotaProperties properties;
    private QuotaRedisKeyMigrator migrator;

    @BeforeEach
    void setUp() {
        template = new FakeReactiveRedisTemplate();
        properties = new QuotaProperties();
        properties.setEnabled(true);
        properties.getDistributed().setEnabled(true);
        properties.getDistributed().setKeyPrefix("trunkline:quota");
        properties.getDistributed().setLegacyKeyPrefix("jairouter:quota");
        migrator = new QuotaRedisKeyMigrator(template, properties);
    }

    @Test
    @DisplayName("旧前缀计数并入新前缀并删除旧 key")
    void migratesLegacyCounterIntoNewPrefix() {
        template.seed("jairouter:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000", 3L, 300L, 86400L);

        final Long migrated = migrator.migrate().block(WAIT);

        assertEquals(1L, migrated);
        assertArrayEquals(new long[]{3L, 300L},
            template.values("trunkline:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000"));
        assertNull(template.values("jairouter:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000"));
        // TTL 按窗口保留期设置
        assertTrue(template.ttlSeconds("trunkline:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000") > 0);
    }

    @Test
    @DisplayName("新前缀已有计数时按求和合并，不覆盖")
    void mergesIntoExistingNewKey() {
        template.seed("jairouter:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000", 3L, 300L, 86400L);
        template.seed("trunkline:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000", 2L, 100L, 86400L);

        migrator.migrate().block(WAIT);

        assertArrayEquals(new long[]{5L, 400L},
            template.values("trunkline:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000"));
        assertNull(template.values("jairouter:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000"));
    }

    @Test
    @DisplayName("无旧 key 时迁移零操作")
    void noLegacyKeysIsNoop() {
        assertEquals(0L, migrator.migrate().block(WAIT));
        assertEquals(1, template.commandCount()); // 仅 1 次 SCAN
    }

    @Test
    @DisplayName("legacy 前缀置空或与新前缀相同时跳过迁移")
    void skipsWhenLegacyDisabled() {
        properties.getDistributed().setLegacyKeyPrefix("");
        assertEquals(0L, migrator.migrate().block(WAIT));

        properties.getDistributed().setLegacyKeyPrefix("trunkline:quota");
        assertEquals(0L, migrator.migrate().block(WAIT));

        template.seed("trunkline:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000", 1L, 1L, 86400L);
        assertEquals(0L, migrator.migrate().block(WAIT));
        // 数据未被触碰（新前缀与自己"相同前缀"不构成迁移对象）
        assertArrayEquals(new long[]{1L, 1L},
            template.values("trunkline:quota:t1:k1:u1:chat:gpt-4:grp:DAY:20260314000000"));
    }

    @Test
    @DisplayName("窗口段无法解析时按 1 天 TTL 兜底迁移")
    void fallsBackToDayTtlWhenWindowUnparsable() {
        template.seed("jairouter:quota:t1:k1:u1:chat:gpt-4:grp:BAD:20260314000000", 1L, 10L, 60L);

        final Long migrated = migrator.migrate().block(WAIT);

        assertEquals(1L, migrated);
        final Long ttl = template.ttlSeconds("trunkline:quota:t1:k1:u1:chat:gpt-4:grp:BAD:20260314000000");
        assertTrue(ttl != null && ttl == 86400L);
    }
}
