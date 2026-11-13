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
 * 验证 #653 的管理器层修复：自适应阈值覆盖层「写值 → 重建 → 生效」链路。
 *
 * <p>修复前 {@code AdaptiveThresholdManager} 只写 DB 且 {@code adjustmentApplied} 恒为 false，
 * 运行时走「裸 reset」重建后仍取全局默认阈值——自适应结果从未真正生效
 * （反证：调整后 {@code getCircuitBreakerDetail(id).get("failureThreshold")} 仍为 5）。</p>
 *
 * <p>本测试覆盖三个关键行为：
 * <ol>
 *   <li>{@code applyAdaptiveThreshold} 后运行时熔断器按新阈值重建；</li>
 *   <li>手工 DB 配置优先：存在 {@code instanceConfigs} 记录时自适应写入被拒绝；</li>
 *   <li>自适应先应用、手工后注入时，手工层在重建时覆盖自适应层。</li>
 * </ol></p>
 *
 * @author Trunkline Team
 */
@DisplayName("CircuitBreakerManager 自适应阈值覆盖层（#653）")
class CircuitBreakerManagerAdaptiveThresholdTest {

    private CircuitBreakerManager manager;

    @BeforeEach
    void setUp() {
        manager = new CircuitBreakerManager();
    }

    private Map<String, Object> stateDetail(final String key) {
        return ((LockFreeCircuitBreaker) manager.getCircuitBreaker(key, null)).getStateDetail();
    }

    @Test
    @DisplayName("applyAdaptiveThreshold 后熔断器按新阈值重建（反证修复前仍为默认 5）")
    void adaptiveThresholdRebuildsBreaker() {
        // 先触发旧熔断器以默认阈值创建
        assertEquals(5, stateDetail("inst-a").get("failureThreshold"), "初始应为全局默认阈值 5");

        assertTrue(manager.applyAdaptiveThreshold("inst-a", 8), "无手工配置时应应用成功");

        assertEquals(8, stateDetail("inst-a").get("failureThreshold"),
                "调整后运行时熔断器必须使用新阈值 8");
    }

    @Test
    @DisplayName("手工 DB 配置优先：applyInstanceConfig 后自适应写入被拒绝且阈值不变")
    void manualConfigTakesPrecedence() {
        manager.applyInstanceConfig("inst-manual", null, true, 10, 60000L, 2);
        assertEquals(10, stateDetail("inst-manual").get("failureThreshold"), "手工阈值应为 10");

        assertFalse(manager.applyAdaptiveThreshold("inst-manual", 3),
                "存在手工配置时自适应写入必须被拒绝");

        assertEquals(10, stateDetail("inst-manual").get("failureThreshold"),
                "被拒绝后阈值必须保持手工值 10");
    }

    @Test
    @DisplayName("自适应先应用、手工后注入：重建时手工层覆盖自适应层")
    void manualInjectionOverridesEarlierAdaptive() {
        assertTrue(manager.applyAdaptiveThreshold("inst-y", 4));
        assertEquals(4, stateDetail("inst-y").get("failureThreshold"));

        // 管理员随后显式配置实例级参数 → 手工意图应在重建时生效
        manager.applyInstanceConfig("inst-y", null, true, 12, 60000L, 2);

        assertEquals(12, stateDetail("inst-y").get("failureThreshold"),
                "手工配置注入后重建应使用手工阈值 12");
    }

    @Test
    @DisplayName("null/空 key 拒绝应用")
    void rejectsNullOrBlankKey() {
        assertFalse(manager.applyAdaptiveThreshold(null, 6));
        assertFalse(manager.applyAdaptiveThreshold("  ", 6));
    }
}
