/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计哈希链扩展点（B1）：安全审计行落库后的链上快照出口。
 *
 * <p><b>契约定位</b>：开源主干只负责「审计行持久化后把不可变快照交给订阅者」；
 * canonical JSON 规范化、{@code row_hash = SHA-256(prev_hash ‖ canonical)}、
 * 链式存储 / 校验 / 证据包全部是企业侧实现细节，主干不感知、不参与。</p>
 *
 * <p><b>调用时机</b>：{@code ExtendedSecurityAuditServiceImpl} 的统一落库出口
 * {@code persist()} 在 {@code repository.save()} 之后、同一工作线程内同步回调。
 * 实现方失败<b>必须自行兜底</b>：主干只记 WARN 不重试、不回滚——商业账本故障
 * 不得变成网关 5xx 的同一纪律在此同样适用（审计主流程永不因链故障而失败）。</p>
 *
 * <p><b>兼容性</b>：本接口全部方法带 {@code default} 实现，属新增扩展点而非
 * 破坏性变更，不推 {@link EnterpriseSpiVersion#VERSION}；社区版无 Bean 时
 * 出口零开销（{@code ObjectProvider.getIfAvailable()} 为 null 即跳过）。</p>
 *
 * @author Trunkline Team
 */
public interface AuditChainListener {

    /**
     * 链是否启用。返回 {@code false} 时主干落库出口跳过快照构建（无对象分配）。
     * 企业实现恒返回 {@code true}（装配层已按 {@code auditChain} 功能位门控注册）。
     */
    default boolean chainEnabled() {
        return false;
    }

    /**
     * 单条审计行持久化后回调。实现内部必须自行捕获全部异常，不得向主干抛出。
     *
     * @param event 审计行的不可变链上快照（字段与落库实体对齐）
     */
    default void onAuditEventPersisted(final AuditChainEvent event) {
        // 社区版无实现：no-op
    }

    /**
     * 批量审计行持久化后回调。实现内部必须自行捕获全部异常，不得向主干抛出。
     *
     * @param events 审计行的不可变链上快照（顺序与落库顺序一致）
     */
    default void onAuditEventsPersisted(final List<AuditChainEvent> events) {
        // 社区版无实现：no-op
    }

    /**
     * 审计行的链上快照。全部组件取自 {@code SecurityAuditEventEntity} 落库后的最终值
     * （{@code @PrePersist} 填充的 category / riskLevel / timestamp 已包含在内）。
     * 可空组件允许 {@code null}，企业实现负责规范化。
     */
    record AuditChainEvent(
            String eventId,
            String eventType,
            String eventCategory,
            String userId,
            String resourceId,
            String clientIp,
            String userAgent,
            LocalDateTime timestamp,
            String resource,
            String action,
            String details,
            Boolean success,
            String failureReason,
            String metadata,
            String requestId,
            String sessionId,
            String riskLevel) {
    }
}
