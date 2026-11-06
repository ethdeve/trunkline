package org.unreal.modelrouter.auth.security.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额结算 Redis 补写失败重试队列回归测试（D1 整改）。
 *
 * <p>缺陷背景：{@code settle} 的 Redis 补写（{@code publish}）失败时只打告警日志 + 记降级指标，
 * 失败的跨实例增量虽然保留在本地后端的 pending 中，却<b>没有任何后台机制</b>把它补写到 Redis——
 * Redis 抖动期间发生的所有结算增量会一直滞留，直到下一次同键流量才被动补写，长期低流量键的
 * 权威计数永久漂移（fire-and-forget 残留）。</p>
 *
 * <p>修复后：补写失败的键进入重试队列，由 {@code retrySettlements()} 定时调度
 * （{@code publish(key, 0, 0, false)} 把该键 pending 一次性刷到 Redis，不重复累加）；
 * 队列满丢弃并计 dropped 指标；应用关闭前 {@code drainSettleRetries()} 尽力补写一遍。</p>
 *
 * <p>被测对象是真实 {@link QuotaLedgerService} + 真实 {@link RedisCounterBackend} + 手写假 Redis
 * （{@link FakeReactiveRedisTemplate}）+ 内存仓库（{@link InMemoryQuotaLedgerRepository}），
 * 不使用 Mockito；等待异步落 Redis 使用有界轮询（沿用仓库既有做法）。</p>
 *
 * @author JAiRouter Team
 * @since 3.5.0
 */
@DisplayName("QuotaLedgerService 结算补写失败重试队列（D1 整改）")
class QuotaLedgerServiceSettleRetryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 14, 13, 45, 30);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 等待重试补写的上限（毫秒） */
    private static final long AWAIT_TIMEOUT_MILLIS = 5000L;

    private InMemoryQuotaLedgerRepository repository;
    private QuotaProperties properties;
    private FakeReactiveRedisTemplate template;
    private RedisCounterBackend redisBackend;

    @BeforeEach
    void setUp() {
        repository = InMemoryQuotaLedgerRepository.create();
        properties = new QuotaProperties();
        properties.setEnabled(true);
        properties.getDistributed().setEnabled(true);
        properties.getDistributed().setTimeout(Duration.ofMillis(200));
        template = new FakeReactiveRedisTemplate();
        redisBackend = new RedisCounterBackend(template, properties);
    }

    @Test
    @DisplayName("结算补写失败后 retrySettlements 把 pending 补写到 Redis，且不重复累加")
    void settleFailureThenRetry_shouldPushPendingExactlyOnce() throws Exception {
        final QuotaLedgerService service = service();
        final QuotaDimension dimension = QuotaDimension.ofApiKey("key-1");

        // 预留（估算 100）：先让 Redis 拿到预留增量
        service.reserve(QuotaRequest.of(dimension, 100));
        final String redisKey = redisKey(dimension, QuotaWindow.MINUTE);
        awaitValues(redisKey, 1L, 100L, "预留增量应先落到 Redis");

        // 模拟 Redis 断连后结算（冲正 100→60，tokenDelta = -40）
        template.setFailing(true);
        service.settle(new QuotaSettlement(dimension, 100, 60, false));

        // Redis 不可达期间，失败增量的补写会失败并进入重试队列；本地视图即时反映冲正
        assertEquals(60L, usageOf(service, dimension, QuotaWindow.MINUTE).tokenCount(),
            "结算后本地视图应立即反映冲正（重试队列只负责跨实例权威计数）");

        // Redis 恢复，触发一次重试调度：pending（-40）应被补写到 Redis，且恰好一次
        template.setFailing(false);
        final int retried = service.retrySettlements();
        assertTrue(retried >= 1, "至少应成功补写一个失败键，实际 " + retried);

        awaitValues(redisKey, 1L, 60L, "重试后 Redis 权威计数应等于本地视图（冲正恰好一次）");

        // 再次调度：无 pending 时为幂等空转，不得改变计数（防双计）
        service.retrySettlements();
        assertArrayEquals(new long[]{1L, 60L}, template.values(redisKey),
            "重复重试不得重复累加（publish(key,0,0,false) 只冲 pending）");
    }

    @Test
    @DisplayName("本地后端模式下重试队列为空转：不处理、不抛出")
    void retryOnLocalBackend_shouldBeNoOp() {
        properties.getDistributed().setEnabled(false);
        final QuotaLedgerService service = service();
        final QuotaDimension dimension = QuotaDimension.ofApiKey("key-local");

        service.reserve(QuotaRequest.of(dimension, 10));
        assertEquals(0, service.retrySettlements(), "非分布式模式重试直接返回 0");
        service.shutdown();
    }

    @Test
    @DisplayName("账本未启用时重试调度不处理任何键")
    void retryWhenDisabled_shouldBeNoOp() {
        properties.setEnabled(false);
        final QuotaLedgerService service = service();
        assertEquals(0, service.retrySettlements(), "账本未启用时重试直接返回 0");
    }

    @Test
    @DisplayName("Redis 持续不可用时重试仍失败并重新入队，恢复后可补写")
    void retryWhileRedisStillFailing_shouldRequeueAndRecover() throws Exception {
        final QuotaLedgerService service = service();
        final QuotaDimension dimension = QuotaDimension.ofApiKey("key-2");

        service.reserve(QuotaRequest.of(dimension, 50));
        template.setFailing(true);
        service.settle(new QuotaSettlement(dimension, 50, 30, false));

        // Redis 仍不可用：单轮重试全部失败（重新入队），返回 0 个成功键且必须终止（不自我循环）
        assertEquals(0, service.retrySettlements(), "Redis 不可用时本轮无成功补写");

        // 恢复后再调度：积压的 pending 最终补写成功
        template.setFailing(false);
        assertTrue(service.retrySettlements() >= 1, "恢复后应补写成功");
        final String redisKey = redisKey(dimension, QuotaWindow.MINUTE);
        awaitValues(redisKey, 1L, 30L, "恢复后 Redis 应补到本地视图值");
    }

    @Test
    @DisplayName("shutdown 前尽力补写队列中的 pending（drainSettleRetries）")
    void shutdown_shouldDrainPendingRetries() throws Exception {
        final QuotaLedgerService service = service();
        final QuotaDimension dimension = QuotaDimension.ofApiKey("key-3");

        service.reserve(QuotaRequest.of(dimension, 80));
        template.setFailing(true);
        service.settle(new QuotaSettlement(dimension, 80, 20, false));

        // 不调用 retrySettlements，直接 shutdown：关闭前的 drain 应把 pending 补到 Redis
        template.setFailing(false);
        service.shutdown();
        final String redisKey = redisKey(dimension, QuotaWindow.MINUTE);
        awaitValues(redisKey, 1L, 20L, "关闭前 drain 应把失败结算的 pending 补写到 Redis");
    }

    // ==================== 辅助 ====================

    /**
     * 构造账本服务（分布式模式，共用假 Redis 与内存仓库，时钟固定）.
     *
     * @return 账本服务
     */
    private QuotaLedgerService service() {
        return new QuotaLedgerService(repository.proxy(), properties,
            Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE), redisBackend, QuotaCounterMetrics.noop());
    }

    /**
     * 计算指定维度的 Redis 计数键.
     *
     * @param dimension 维度
     * @param window    窗口
     * @return Redis key
     */
    private String redisKey(final QuotaDimension dimension, final QuotaWindow window) {
        return redisBackend.keyOf(new QuotaCounterKey(dimension, window, window.windowStart(NOW)));
    }

    /**
     * 取指定窗口的用量（无数据直接失败，便于断言）.
     *
     * @param service   账本服务
     * @param dimension 维度
     * @param window    窗口
     * @return 用量
     */
    private static QuotaUsage usageOf(final QuotaLedgerService service,
                                      final QuotaDimension dimension,
                                      final QuotaWindow window) {
        return service.usage(dimension, window)
            .orElseThrow(() -> new IllegalStateException("账本无数据: " + dimension + " / " + window));
    }

    /**
     * 有界等待 Redis 计数桶达到期望值（仓库未引入 awaitility，按既有做法轮询）.
     *
     * @param redisKey Redis key
     * @param requests 期望请求数
     * @param tokens   期望 token 数
     * @param message  断言消息
     * @throws InterruptedException 等待被中断
     */
    private void awaitValues(final String redisKey,
                             final long requests,
                             final long tokens,
                             final String message) throws InterruptedException {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MILLIS);
        long[] current = template.values(redisKey);
        while ((current == null || current[0] != requests || current[1] != tokens)
                && System.nanoTime() < deadline) {
            Thread.sleep(5L);
            current = template.values(redisKey);
        }
        assertArrayEquals(new long[]{requests, tokens}, current,
            message + "（等待 " + AWAIT_TIMEOUT_MILLIS + "ms 后实际 "
                + (current == null ? "null" : java.util.Arrays.toString(current)) + "）");
    }
}
