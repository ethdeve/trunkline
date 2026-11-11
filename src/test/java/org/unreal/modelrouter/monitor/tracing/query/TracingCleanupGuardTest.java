/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.monitor.tracing.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 清理窗口兜底：非正数窗口宁可不清理，也不能把 cutoff 推到未来清空全量追踪数据。 */
@DisplayName("追踪清理窗口兜底")
class TracingCleanupGuardTest {

    private static TraceQueryService service() {
        return new TraceQueryService(null, null, null);
    }

    @Test
    @DisplayName("0 与负数窗口返回 0，不执行任何删除")
    void nonPositiveWindowIsRejected() {
        assertEquals(0L, service().cleanupExpiredTraces(0).block());
        assertEquals(0L, service().cleanupExpiredTraces(-1).block());
        assertEquals(0L, service().cleanupExpiredTraces(Long.MIN_VALUE).block());
    }

    @Test
    @DisplayName("合法窗口仍正常返回（异步链不抛异常）")
    void positiveWindowIsAccepted() {
        Long removed = service().cleanupExpiredTraces(24L * 60 * 60 * 1000).block();
        assertEquals(0L, removed, "空存储下清理数量应为 0");
    }
}
