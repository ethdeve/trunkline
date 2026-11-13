package org.unreal.modelrouter.monitor.tracing.async;

import io.opentelemetry.api.trace.SpanContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.monitor.tracing.config.TracingConfiguration;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * issue #1083：追踪丢弃率双计修复。
 *
 * <p>修复前 {@code AsyncTracingProcessor.processBatch} 处理完批次从不从
 * {@code traceQueue} 移除，到期被 {@code cleanupExpiredData} 二次 poll 并计入
 * dropped → 同一项 processed 与 dropped 各记一次，dropRate 恒约 50%。</p>
 *
 * <p>修复后三道闸门：批次处理成功项 {@code setProcessed(true)} + 从队列移除；
 * cleanup 只对「未被处理就到期」的项计 dropped；getDropRate 分母补上
 * failureCount（批次失败项此前既不算 processed 也不算 dropped）。</p>
 *
 * <p>反射调用 private 的 {@code processBatch} / {@code traceQueue}：
 * 管道在 {@code start()} 里订阅接线，单测直接驱动内部方法更可控（不引入
 * bufferTimeout 的异步时序抖动）。</p>
 */
@DisplayName("issue #1083：追踪丢弃率双计修复")
class AsyncTracingProcessorDropRateTest {

    private AsyncTracingProcessor processor;

    private ConcurrentLinkedQueue<AsyncTracingProcessor.TraceData> traceQueue;

    private Method processBatch;

    private Method cleanupExpiredData;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        processor = new AsyncTracingProcessor(new TracingConfiguration());
        Field queueField = AsyncTracingProcessor.class.getDeclaredField("traceQueue");
        queueField.setAccessible(true);
        traceQueue = (ConcurrentLinkedQueue<AsyncTracingProcessor.TraceData>) queueField.get(processor);
        processBatch = AsyncTracingProcessor.class.getDeclaredMethod("processBatch", List.class);
        processBatch.setAccessible(true);
        cleanupExpiredData = AsyncTracingProcessor.class.getDeclaredMethod("cleanupExpiredData");
        cleanupExpiredData.setAccessible(true);
    }

    private void cleanup() throws Exception {
        cleanupExpiredData.invoke(processor);
    }

    @AfterEach
    void tearDown() {
        processor.stop();
    }

    private AsyncTracingProcessor.TraceData expiredData(String traceId) {
        // timestamp 早于 maxWaitTime(默认30s)，cleanupExpiredData 会将其判定为过期
        return new AsyncTracingProcessor.TraceData(
                traceId, "span-" + traceId, "op", 0L, 1L, true,
                SpanContext.getInvalid(), Instant.now().minusSeconds(60));
    }

    @Test
    @DisplayName("提交100条 → 批次处理完成 → cleanup 后 droppedCount==0、processedCount==100、dropRate==0")
    void processedItemsAreNotDoubleCountedAsDropped() throws Exception {
        List<AsyncTracingProcessor.TraceData> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            AsyncTracingProcessor.TraceData data = expiredData("t-" + i);
            traceQueue.offer(data);
            batch.add(data);
        }

        // Mono.fromCallable 惰性执行，必须 subscribe 触发 callable 主体
        @SuppressWarnings("unchecked")
        Mono<AsyncTracingProcessor.BatchResult> mono =
                (Mono<AsyncTracingProcessor.BatchResult>) processBatch.invoke(processor, batch);
        mono.block();

        // 处理完即出队：批次里的项全部离开队列（修复前 100 条仍留在队列中）
        assertEquals(0, traceQueue.size(), "已处理项必须从 traceQueue 移除，否则会被 cleanup 二次处理");

        cleanup();

        AsyncTracingProcessor.ProcessingStats stats = processor.getProcessingStats();
        assertEquals(100L, stats.getProcessedCount());
        assertEquals(0L, stats.getDroppedCount(),
                "已处理项被 cleanup 二次移除时不得计入 dropped（修复前 dropped≈100）");
        assertEquals(0.0, stats.getDropRate(), 0.0,
                "修复前 processed≈dropped 导致 dropRate 恒约 0.5");
    }

    @Test
    @DisplayName("未被处理就到期的项仍计入 dropped（真丢弃语义保留）")
    void unprocessedExpiredItemsStillCountedAsDropped() throws Exception {
        traceQueue.offer(expiredData("never-processed"));

        cleanup();

        AsyncTracingProcessor.ProcessingStats stats = processor.getProcessingStats();
        assertEquals(1L, stats.getDroppedCount(), "未处理即到期才是真正的丢弃");
        assertEquals(0L, stats.getProcessedCount());
    }

    @Test
    @DisplayName("批次处理失败项保留在队列中，cleanup 按到期兜底并计入 dropped")
    void failedItemsFallBackToCleanup() throws Exception {
        // 直接构造未标记 processed 的过期项模拟「批次失败」残留
        traceQueue.offer(expiredData("failed-item"));

        cleanup();

        assertEquals(1L, processor.getProcessingStats().getDroppedCount());
    }

    @Test
    @DisplayName("getDropRate 分母补上 failureCount：processed=100/failure=5/dropped=1 → 1/106")
    void dropRateDenominatorIncludesFailureCount() {
        AsyncTracingProcessor.ProcessingStats stats = new AsyncTracingProcessor.ProcessingStats(
                100L, 1L, 10L, 5L, 0, true);

        assertEquals(1.0 / 106.0, stats.getDropRate(), 1e-12,
                "修复前分母只算 processed+dropped(=101)，failureCount 既不算成功也不算丢弃");
    }

    @Test
    @DisplayName("全空统计 → dropRate 0.0 不抛异常")
    void dropRateZeroTotalReturnsZero() {
        AsyncTracingProcessor.ProcessingStats stats = new AsyncTracingProcessor.ProcessingStats(
                0L, 0L, 0L, 0L, 0, false);

        assertEquals(0.0, stats.getDropRate(), 0.0);
    }
}
