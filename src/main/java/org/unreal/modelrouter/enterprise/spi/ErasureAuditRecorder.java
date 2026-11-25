/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

/**
 * 擦除留痕扩展点（#10）：把擦除事实作为不可篡改审计事件写入 B1 哈希链。
 *
 * <p><b>为什么独立成 SPI</b>：擦除执行器只负责删/假名化数据；"擦除事实本身必须不可否认地
 * 留痕"是合规要求，且必须用 {@code ExtendedSecurityAuditService}（而非 {@code @Primary}
 * 的 SecurityAuditService）才能进 B1 链。这段企业侧细节通过本 SPI 注入，主干不感知。</p>
 *
 * <p><b>失败语义</b>：留痕失败不得回滚已执行的擦除（被遗忘权优先），但必须在报告里体现；
 * 实现内部自行捕获异常，不向调用方抛出。社区版默认 no-op。</p>
 *
 * <p><b>兼容性</b>：全 default 方法，新增扩展点不推 {@link EnterpriseSpiVersion#VERSION}。</p>
 *
 * @author Trunkline Team
 */
public interface ErasureAuditRecorder {

    /**
     * 记录擦除报告到审计链。社区版 no-op。
     *
     * @param report 擦除执行报告；实现方负责把摘要（不含 PII 正文）写为 DATA_ERASURE 事件
     */
    default void record(final ErasureReport report) {
        // 社区版无实现：no-op
    }
}
