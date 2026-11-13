package org.unreal.modelrouter.router.loadbalancer.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #877：路由统计客户端维度 Map 只增不减修复。
 *
 * <p>修复前 {@code clientToInstanceMap} / {@code clientRequestCounts} 是两个无界
 * ConcurrentHashMap，{@code recordSelection} 只 put、除 {@code reset()} 外无任何
 * 清理——clientId 高基数且可被外部影响（每客户端 IP 一个键），恶意/异常流量可
 * 持续膨胀内存。</p>
 *
 * <p>修复后换 Caffeine 有界缓存（maximumSize=10000 + expireAfterAccess=30min，
 * 与 {@code ScopedRateLimiterWrapper} 对齐）：条目数有上限，长期不活跃的
 * ip-hash 映射被淘汰（验证视图只呈现「最近活跃」客户端）。</p>
 */
@DisplayName("issue #877：路由统计客户端维度有界缓存")
class RoutingStatsAggregatorBoundedTest {

    @Test
    @DisplayName("灌 20001 个 clientId → 条目数 ≤ 上限（反证旧 Map 单调不回落）")
    void clientEntriesAreBounded() {
        RoutingStatsAggregator.ServiceRoutingStats stats =
                new RoutingStatsAggregator.ServiceRoutingStats("chat");
        for (int i = 0; i < 20001; i++) {
            stats.recordSelection("instance-1", "client-" + i, Instant.now(),
                    "ip-hash", "gpt-4");
        }
        stats.cleanUpClientCaches();

        long size = stats.clientEntryCount();
        assertTrue(size <= 10000, "修复前 size==20001 单调增长；修复后必须 ≤ MAX_CLIENT_ENTRIES=10000，实际 " + size);
    }

    @Test
    @DisplayName("reset() 后两个客户端缓存清空")
    void resetClearsBothClientCaches() {
        RoutingStatsAggregator.ServiceRoutingStats stats =
                new RoutingStatsAggregator.ServiceRoutingStats("chat");
        stats.recordSelection("instance-1", "client-a", Instant.now(), "ip-hash", "gpt-4");
        stats.recordSelection("instance-2", "client-b", Instant.now(), "ip-hash", "gpt-4");
        assertEquals(2, stats.getClientToInstanceMapping(100).size());

        stats.reset();

        assertEquals(0L, stats.clientEntryCount(), "reset 后映射必须清空");
        assertTrue(stats.getClientToInstanceMapping(100).isEmpty());
    }

    @Test
    @DisplayName("同一 clientId 重复记录 → 映射更新为最新实例（ip-hash 语义不变）")
    void repeatedClientUpdatesMapping() {
        RoutingStatsAggregator.ServiceRoutingStats stats =
                new RoutingStatsAggregator.ServiceRoutingStats("chat");
        stats.recordSelection("instance-1", "client-a", Instant.now(), "ip-hash", "gpt-4");
        stats.recordSelection("instance-2", "client-a", Instant.now(), "ip-hash", "gpt-4");

        Map<String, String> mapping = stats.getClientToInstanceMapping(100);
        assertEquals(1, mapping.size(), "同 clientId 只占一个条目");
        assertEquals("instance-2", mapping.get("client-a"), "映射为最新一次选中");
    }

    @Test
    @DisplayName("getClientToInstanceMapping 的 limit 生效（JSON 序列化仍限 100 条）")
    void mappingLimitStillApplies() {
        RoutingStatsAggregator.ServiceRoutingStats stats =
                new RoutingStatsAggregator.ServiceRoutingStats("chat");
        for (int i = 0; i < 150; i++) {
            stats.recordSelection("instance-1", "client-" + i, Instant.now(), "ip-hash", "gpt-4");
        }

        assertEquals(100, stats.getClientToInstanceMapping(100).size());
        assertEquals(10, stats.getClientToInstanceMapping(10).size());
    }

    @Test
    @DisplayName("clientId 为 null/空串不产生条目（修复前同样行为，回归守护）")
    void nullClientIdProducesNoEntry() {
        RoutingStatsAggregator.ServiceRoutingStats stats =
                new RoutingStatsAggregator.ServiceRoutingStats("chat");
        stats.recordSelection("instance-1", null, Instant.now(), "round-robin", "gpt-4");
        stats.recordSelection("instance-1", "", Instant.now(), "round-robin", "gpt-4");

        assertEquals(0L, stats.clientEntryCount());
    }
}
