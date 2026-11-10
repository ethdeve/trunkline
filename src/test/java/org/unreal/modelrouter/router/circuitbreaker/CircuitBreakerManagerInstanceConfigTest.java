/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证方案 A 的核心修复：熔断器「DB 配置 → 运行时生效」链路。
 *
 * <p>修复前 {@link CircuitBreakerManager#getCircuitBreaker} 永远用全局 YAML 默认阈值创建熔断器，
 * 实例级 {@code instance_circuit_breaker} 配置形同虚设。本测试覆盖四个关键行为：
 * <ol>
 *   <li>未配置实例级参数时回落全局默认阈值；</li>
 *   <li>{@code applyInstanceConfig} 注入后，运行时熔断器使用实例级阈值；</li>
 *   <li>{@code enabled=false} 时 {@code canExecute} 始终放行（实例级禁用真正生效）；</li>
 *   <li>实例级阈值（如 failureThreshold=3）真正参与状态机判定（连续 3 次失败即 OPEN）。</li>
 * </ol>
 * @author Trunkline Team
 */
@DisplayName("CircuitBreakerManager 实例级配置生效")
class CircuitBreakerManagerInstanceConfigTest {

    private CircuitBreakerManager manager;

    @BeforeEach
    void setUp() {
        manager = new CircuitBreakerManager();
    }

    private Map<String, Object> stateDetail(final String instanceId, final String instanceUrl) {
        return ((LockFreeCircuitBreaker) manager.getCircuitBreaker(instanceId, instanceUrl)).getStateDetail();
    }

    @Test
    @DisplayName("未注入实例级配置时回落全局默认阈值")
    void usesGlobalDefaultsWhenNoInstanceConfig() {
        final Map<String, Object> sd = stateDetail("inst-default", null);
        assertEquals(5, sd.get("failureThreshold"), "全局默认失败阈值应为 5");
        assertEquals(60000L, sd.get("timeout"), "全局默认超时应为 60000ms");
        assertEquals(2, sd.get("successThreshold"), "全局默认成功阈值应为 2");
    }

    @Test
    @DisplayName("applyInstanceConfig 后运行时熔断器使用实例级阈值")
    void appliesInstanceLevelThresholds() {
        manager.applyInstanceConfig("inst-custom", null, true, 3, 12000L, 1);

        final Map<String, Object> sd = stateDetail("inst-custom", null);
        assertEquals(3, sd.get("failureThreshold"), "应使用实例级失败阈值 3");
        assertEquals(12000L, sd.get("timeout"), "应使用实例级超时 12000ms");
        assertEquals(1, sd.get("successThreshold"), "应使用实例级成功阈值 1");
    }

    @Test
    @DisplayName("URL 兜底 key 也能命中实例级配置")
    void resolvesByUrlFallbackKey() {
        manager.applyInstanceConfig(null, "http://host/model", true, 7, 9000L, 4);

        final Map<String, Object> sd = stateDetail(null, "http://host/model");
        assertEquals(7, sd.get("failureThreshold"), "应通过 URL 兜底 key 命中实例级阈值 7");
    }

    @Test
    @DisplayName("enabled=false 时 canExecute 始终放行（实例级禁用生效）")
    void disabledInstanceAlwaysExecutable() {
        manager.applyInstanceConfig("inst-off", null, false, 1, 60000L, 1);

        // 即便连续记录大量失败，禁用实例也应始终可被执行（不会因 OPEN 被拦）
        for (int i = 0; i < 20; i++) {
            manager.recordFailure("inst-off", null);
        }
        assertTrue(manager.canExecute("inst-off", null), "禁用实例的 canExecute 必须为 true");
    }

    @Test
    @DisplayName("实例级 failureThreshold=3 真正参与状态机判定")
    void customThresholdTripsBreaker() {
        manager.applyInstanceConfig("inst-trip", null, true, 3, 60000L, 2);

        // 连续 3 次失败应触发 OPEN（全局默认是 5，此处证明实例级 3 生效）
        manager.recordFailure("inst-trip", null);
        manager.recordFailure("inst-trip", null);
        assertTrue(manager.canExecute("inst-trip", null), "2 次失败未达阈值 3，仍可执行");
        manager.recordFailure("inst-trip", null);
        assertFalse(manager.canExecute("inst-trip", null), "达到实例级阈值 3 后应 OPEN 并拒绝");
    }

    @Test
    @DisplayName("clearInstanceConfig 后回落全局默认阈值")
    void clearRevertsToGlobalDefaults() {
        manager.applyInstanceConfig("inst-clear", null, true, 9, 5000L, 8);
        assertEquals(9, stateDetail("inst-clear", null).get("failureThreshold"));

        manager.clearInstanceConfig("inst-clear", null);
        assertEquals(5, stateDetail("inst-clear", null).get("failureThreshold"), "清除后回落全局默认 5");
    }
}
