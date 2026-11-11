package org.unreal.modelrouter.monitor.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 问题清单 M-10 回归测试：QPS 只增不减。
 *
 * <p>record() 复用过期槽时必须先从 totalCount 回扣旧计数，
 * 否则同一槽反复被复用会让 totalCount（进而 QPS）单调上涨。
 */
@DisplayName("问题清单回归：环形缓冲区过期槽计数回扣")
class CircularTimeBufferCountDecayTest {

    @Test
    @DisplayName("M-10: 单槽复用时旧计数被回扣，totalCount 不虚高")
    void expiredSlotCountIsSubtracted() throws InterruptedException {
        // 2ms 窗口、1 个槽（槽大小 2ms，索引恒为 0，必然复用同一槽）
        CircularTimeBuffer buffer = new CircularTimeBuffer(2, 1);
        buffer.record(5);
        assertEquals(5, buffer.getTotalCount());

        Thread.sleep(6); // 确保槽已过期（slotSizeMs=2ms）
        buffer.record(3); // 复用槽 0：先回扣旧的 5，再加 3

        assertEquals(3, buffer.getTotalCount(),
                "复用过期槽后 totalCount 应只反映当前槽的计数，而非 5+3");
    }

    @Test
    @DisplayName("M-10: 连续多个过期周期仍保持单窗口量级，不单调增长")
    void repeatedExpiryDoesNotGrow() throws InterruptedException {
        CircularTimeBuffer buffer = new CircularTimeBuffer(2, 1);
        for (int round = 0; round < 5; round++) {
            buffer.record(4);
            assertEquals(4, buffer.getTotalCount(), "第 " + round + " 轮应稳定为 4");
            Thread.sleep(6);
        }
    }
}
