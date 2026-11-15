package org.unreal.modelrouter.auth.security.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A3 验收硬化③：3 副本并发下限额不被放大（M2 多副本检查项①）。
 *
 * <p>《待开发功能清单》第 4 节要求：「3 副本下 A3 限额 1000 次/日压测 3000 次实际放行 ≤ 1000」。
 * 既有 {@code QuotaEnforcementServiceDistributedCasTest} 只验证<b>单实例</b>内 8 线程并发
 * （靠 {@code reserveLocks} 进程内串行化 + Redis CAS），没有证明「跨进程锁各管各的」时
 * 共享 Redis 才是唯一权威计数。</p>
 *
 * <p>本测试构造 3 个完全独立的副本：每个副本有自己的 {@link QuotaLedgerService}、
 * 本地 {@code LocalCounterBackend}、内存账本仓库与 {@code QuotaEnforcementService}
 * （因此各有自己的 {@code reserveLocks}，进程内锁在副本间完全不起作用），
 * 只共享一个 {@link RedisCounterBackend}（假 Redis 模板，模拟同一 Redis 集群）。
 * 3 个线程同时各发 1000 次预留，日限额 1000，断言总放行恰好 1000 且 Redis 权威计数为 1000。</p>
 *
 * <p>另设反例对照组：若 3 副本<b>各用各的计数器</b>（错误部署、共享态未开），
 * 同样 300 次会放行 300 —— 证明本测试不是空跑，且「启用分布式共享计数」
 * 是限额不放大成立的必要条件。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("A3 硬化③：3 副本并发限额不放大")
class QuotaMultiReplicaLimitTest {

    private static final String KEY_ID = "key-multi-replica";
    private static final String KEY_HASH = "hash-multi-replica";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private QuotaProperties properties;
    private ApiKeyService apiKeyService;
    private Clock clock;
    private Map<String, ApiKey> apiKeyCache;
    private Map<String, String> keyIdIndex;

    @BeforeEach
    void setUp() {
        properties = new QuotaProperties();
        properties.setEnabled(true);
        properties.getDistributed().setEnabled(true);
        // 只启用 DAY，使每次预留恰好对应一次 Redis CAS（压测目标聚焦日限额）
        properties.setWindows(List.of(QuotaWindow.DAY));

        clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        apiKeyService = mock(ApiKeyService.class);
        apiKeyCache = new HashMap<>();
        keyIdIndex = new HashMap<>();
        when(apiKeyService.getApiKeyCache()).thenReturn(apiKeyCache);
        when(apiKeyService.getKeyIdIndex()).thenReturn(keyIdIndex);
    }

    private void registerDailyRequestLimit(final long limit) {
        final ApiKey key = new ApiKey();
        key.setKeyId(KEY_ID);
        key.setDailyRequestLimit(limit);
        key.setDailyTokenLimit(0L);
        key.setRateLimitPerMinute(0);
        apiKeyCache.put(KEY_HASH, key);
        keyIdIndex.put(KEY_ID, KEY_HASH);
    }

    private static ServerHttpRequest request() {
        return MockServerHttpRequest.post("/v1/chat/completions").build();
    }

    /** 造一个独立副本：自己的账本服务（内含本地计数）+ 自己的限额判定服务（内含预留锁）。 */
    private QuotaEnforcementService replica(final RedisCounterBackend sharedBackend) {
        final QuotaLedgerService ledger = new QuotaLedgerService(
                new InMemoryQuotaLedgerRepository().proxy(), properties, clock, sharedBackend, null);
        return new QuotaEnforcementService(ledger, properties, apiKeyService, clock);
    }

    @Test
    @DisplayName("3 副本共享 Redis：日限额 1000、并发 3000 次，实际放行恰好 1000")
    void threeReplicasSharingRedisNeverExceedDailyLimit() throws Exception {
        registerDailyRequestLimit(1000L);
        final FakeReactiveRedisTemplate sharedRedis = new FakeReactiveRedisTemplate();
        final RedisCounterBackend sharedBackend = new RedisCounterBackend(sharedRedis, properties);

        final List<QuotaEnforcementService> replicas = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            replicas.add(replica(sharedBackend));
        }

        final AtomicInteger allowed = new AtomicInteger();
        runConcurrent(replicas, 1000, allowed);

        assertEquals(1000, allowed.get(),
                "3 副本各有进程内锁，只有共享 Redis CAS 能把全局放行量钉在 1000");

        final QuotaCounterKey dayKey = new QuotaCounterKey(
                QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY, QuotaWindow.DAY.windowStart(NOW));
        final long[] authoritative = sharedRedis.values(sharedBackend.keyOf(dayKey));
        assertTrue(authoritative != null, "共享 Redis 上必须存在 DAY 权威计数");
        assertEquals(1000L, authoritative[0], "Redis 权威计数必须与放行量一致，不多不少");
    }

    @Test
    @DisplayName("反例：3 副本各用独立计数器时限额放大 3 倍（证明共享态是必要条件）")
    void isolatedCountersOvershootLimitThreeFold() throws Exception {
        registerDailyRequestLimit(100L);
        final List<QuotaEnforcementService> replicas = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final FakeReactiveRedisTemplate isolatedRedis = new FakeReactiveRedisTemplate();
            replicas.add(replica(new RedisCounterBackend(isolatedRedis, properties)));
        }

        final AtomicInteger allowed = new AtomicInteger();
        runConcurrent(replicas, 100, allowed);

        assertEquals(300, allowed.get(),
                "共享计数缺失时每副本各放行 100，正是「多副本放大」风险，必须显式开启共享态");
    }

    /** 每个副本一个线程、同时起跑，各自发起 {@code perReplica} 次预留。 */
    private static void runConcurrent(final List<QuotaEnforcementService> replicas,
                                      final int perReplica,
                                      final AtomicInteger allowed) throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(replicas.size());
        final ExecutorService pool = Executors.newFixedThreadPool(replicas.size());
        try {
            for (final QuotaEnforcementService service : replicas) {
                pool.execute(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < perReplica; i++) {
                            if (service.tryReserve(request(), KEY_ID, 1L).isEmpty()) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(90, TimeUnit.SECONDS), "3 副本并发压测须在 90 秒内完成");
        } finally {
            pool.shutdownNow();
        }
    }
}
