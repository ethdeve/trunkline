/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.config.service.CircuitBreakerConfigService;
import org.unreal.modelrouter.persistence.jpa.entity.CircuitBreakerMetricsEntity;
import org.unreal.modelrouter.persistence.jpa.repository.CircuitBreakerMetricsRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 #653 的自适应管理器层修复：
 * <ol>
 *   <li>运行时调整链路真实生效——{@code evaluateAndAdjustThresholds} 触发一次调整后，
 *       {@code getCircuitBreakerDetail(id).get("failureThreshold") == newThreshold}
 *       （反证修复前仍为默认 5）；DB 记录 {@code adjustmentApplied=true}；</li>
 *   <li>启动补偿——{@code findPendingAdjustments()} 此前无任何调用方（重启丢失），
 *       现在 {@code applyPendingAdjustments()} 逐条应用且标记已应用；</li>
 *   <li>手工配置优先——补偿命中手工配置实例时不覆盖运行时，但仍标记已应用（避免每次重启重放）。</li>
 * </ol>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdaptiveThresholdManager 阈值落实与启动补偿（#653）")
class AdaptiveThresholdManagerPendingTest {

    @Mock
    private CircuitBreakerMetricsRepository metricsRepository;

    @Mock
    private CircuitBreakerConfigService configService;

    private CircuitBreakerManager circuitBreakerManager;

    private AdaptiveThresholdManager manager;

    @BeforeEach
    void setUp() {
        circuitBreakerManager = new CircuitBreakerManager();
        manager = new AdaptiveThresholdManager(metricsRepository, configService, circuitBreakerManager);
    }

    /** getCircuitBreakerDetail 不触发重建，须先 getCircuitBreaker 使覆盖层按新阈值实例化。 */
    private Map<String, Object> detailOf(final String key) {
        return ((LockFreeCircuitBreaker) circuitBreakerManager.getCircuitBreaker(key, null)).getStateDetail();
    }

    private CircuitBreakerMetricsEntity metrics(final String instanceId, final int totalCalls,
                                                final int failureCalls) {
        return CircuitBreakerMetricsEntity.builder()
                .instanceId(instanceId)
                .totalCalls((long) totalCalls)
                .failureCalls((long) failureCalls)
                .successCalls((long) (totalCalls - failureCalls))
                .build();
    }

    @Test
    @DisplayName("运行时调整真实生效：评估触发调整后熔断器阈值变为新值且记录已应用")
    void runtimeAdjustmentTakesEffect() {
        manager.setAdaptiveEnabled(true);

        // 产生统计缓存（totalCalls>0 才会被持久化）
        manager.recordFailure("inst-a", "inst-a-name", "chat");

        // 失败率 20% (>10%) → 严重收紧：ceil(5 * 0.6) = 3
        when(metricsRepository.findByWindowEndBetween(any(), any()))
                .thenReturn(List.of(metrics("inst-a", 100, 20)));
        when(metricsRepository.findFirstByInstanceIdOrderByWindowEndDesc("inst-a"))
                .thenReturn(java.util.Optional.of(metrics("inst-a", 100, 20)));

        manager.evaluateAndAdjustThresholds();

        final Map<String, Object> detail = detailOf("inst-a");
        assertEquals(3, detail.get("failureThreshold"),
                "反证修复前：调整后运行时阈值仍为默认 5，修复后必须为 3");

        // DB 记录必须标记已应用（save 被调用 2 次：persistCurrentMetrics + applyThresholdAdjustment）
        final ArgumentCaptor<CircuitBreakerMetricsEntity> captor =
                ArgumentCaptor.forClass(CircuitBreakerMetricsEntity.class);
        verify(metricsRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        final CircuitBreakerMetricsEntity adjusted = captor.getAllValues().stream()
                .filter(e -> e.getAdjustedFailureThreshold() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少带 adjustedFailureThreshold 的落库记录"));
        assertTrue(Boolean.TRUE.equals(adjusted.getAdjustmentApplied()),
                "adjustmentApplied 必须置 true（修复前恒为 false）");
        assertEquals(3, adjusted.getAdjustedFailureThreshold());
    }

    @Test
    @DisplayName("启动补偿：2 条 pending 记录应用后阈值生效且均标记 applied")
    void pendingAdjustmentsAppliedOnStartup() {
        final CircuitBreakerMetricsEntity p1 = metrics("inst-p1", 100, 20);
        p1.setAdjustedFailureThreshold(7);
        p1.setAdjustmentApplied(false);
        final CircuitBreakerMetricsEntity p2 = metrics("inst-p2", 100, 20);
        p2.setAdjustedFailureThreshold(9);
        p2.setAdjustmentApplied(false);
        when(metricsRepository.findPendingAdjustments()).thenReturn(List.of(p1, p2));

        manager.applyPendingAdjustments();

        assertEquals(7, detailOf("inst-p1").get("failureThreshold"),
                "inst-p1 应按 pending 阈值 7 重建");
        assertEquals(9, detailOf("inst-p2").get("failureThreshold"),
                "inst-p2 应按 pending 阈值 9 重建");

        final ArgumentCaptor<CircuitBreakerMetricsEntity> captor =
                ArgumentCaptor.forClass(CircuitBreakerMetricsEntity.class);
        verify(metricsRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        captor.getAllValues().forEach(e ->
                assertTrue(Boolean.TRUE.equals(e.getAdjustmentApplied()), "每条 pending 均须标记已应用"));
    }

    @Test
    @DisplayName("启动补偿遇手工配置实例：不覆盖运行时但标记已应用（避免重启重放）")
    void pendingSkipsManualConfigButMarksApplied() {
        circuitBreakerManager.applyInstanceConfig("inst-manual", null, true, 10, 60000L, 2);

        final CircuitBreakerMetricsEntity pending = metrics("inst-manual", 100, 20);
        pending.setAdjustedFailureThreshold(4);
        pending.setAdjustmentApplied(false);
        when(metricsRepository.findPendingAdjustments()).thenReturn(List.of(pending));

        manager.applyPendingAdjustments();

        assertEquals(10, detailOf("inst-manual").get("failureThreshold"),
                "手工配置优先，运行时阈值必须保持 10");

        final ArgumentCaptor<CircuitBreakerMetricsEntity> captor =
                ArgumentCaptor.forClass(CircuitBreakerMetricsEntity.class);
        verify(metricsRepository).save(captor.capture());
        assertTrue(Boolean.TRUE.equals(captor.getValue().getAdjustmentApplied()),
                "即使被手工层跳过也须标记已应用，避免每次重启重放");
    }

    @Test
    @DisplayName("无 pending 记录时不做任何写入")
    void noPendingRecordsDoesNothing() {
        when(metricsRepository.findPendingAdjustments()).thenReturn(List.of());

        manager.applyPendingAdjustments();

        verify(metricsRepository, org.mockito.Mockito.never()).save(any());
        assertEquals(0, circuitBreakerManager.getCircuitBreakerCount());
    }
}
