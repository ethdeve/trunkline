/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.checker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27：配置变更后健康状态快照的失效机制。
 *
 * <p><b>背景（v3.5 治理收口时暴露的真实缺陷）：</b></p>
 * <ul>
 *   <li><b>实例级</b>：健康位键是 {@code serviceType:instanceId}，instanceId 是 UUID，
 *       <b>改上游地址不会改变键</b> → 旧地址探活得到的 {@code false} 一直挂着，
 *       直到下一轮探活（最长 30s）才被覆盖。期间实例被路由层
 *       {@code filterHealthyInstances} 过滤掉 → 表现为「改完地址还是 503」。</li>
 *   <li><b>服务级</b>：{@code serviceHealthStatus} 只由 {@code ServerChecker} 每 30s 刷新，
 *       且只对「有实例」的分类建检查任务 → 分类下实例被删光后缓存里的 {@code false}
 *       <b>永久残留</b>；分类下新增实例时缓存也停留在上一轮结论。</li>
 * </ul>
 *
 * <p>本测试锁定「配置变更即失效快照」的行为，并验证失效<b>不会</b>削弱可用性保护
 * （真正不健康的实例仍会被过滤）。</p>
 *
 * <p>刻意不使用 Mockito：这些方法只操作内部 {@link java.util.concurrent.ConcurrentHashMap}
 * 与 {@link HealthStateBitSet}，用真实对象才能验证位图语义（保留索引 vs 回收索引）。</p>
 * @author Trunkline Team
 */
@DisplayName("健康状态快照失效")
class ServiceStateManagerHealthResetTest {

    private ServiceStateManager stateManager;

    @BeforeEach
    void setUp() {
        stateManager = new ServiceStateManager();
    }

    // ==================== 实例级 ====================

    @Test
    @DisplayName("实例被标记不健康后，重置使其回落为「未检查」（默认健康）")
    void resetInstance_healthFallsBackToUnchecked() {
        final ModelRouterProperties.ModelInstance instance = instance("uuid-1", "http://old:8080");
        stateManager.updateInstanceHealthStatus("chat", instance, false);
        assertFalse(stateManager.isInstanceHealthy("chat", instance),
                "前置：实例应已被标记为不健康");

        stateManager.resetInstanceHealthStatus("chat", instance);

        assertTrue(stateManager.isInstanceHealthy("chat", instance),
                "重置后应回落为默认健康（未检查），不应再被路由层过滤");
        assertEquals("UNKNOWN", stateManager.getInstanceHealthStatus("chat:uuid-1"),
                "重置后状态应为 UNKNOWN（未检查），而非 HEALTHY/UNHEALTHY");
    }

    @Test
    @DisplayName("按 ID 重置与按实例重置等价（配置层只有 JPA 实体，没有运行时实例）")
    void resetInstanceById_matchesKeySemantics() {
        final ModelRouterProperties.ModelInstance instance = instance("uuid-2", "http://old:8080");
        stateManager.updateInstanceHealthStatus("chat", instance, false);
        assertFalse(stateManager.isInstanceHealthy("chat", instance));

        // 配置层（ServiceInstanceManager）走这条入口：serviceType + ":" + instanceId
        stateManager.resetInstanceHealthStatusById("chat", "uuid-2");

        assertTrue(stateManager.isInstanceHealthy("chat", instance),
                "按 ID 重置必须命中同一个健康键");
    }

    @Test
    @DisplayName("重置不健康实例不会影响同分类下的其它实例")
    void resetInstance_doesNotAffectSiblings() {
        final ModelRouterProperties.ModelInstance target = instance("uuid-target", "http://a:8080");
        final ModelRouterProperties.ModelInstance sibling = instance("uuid-sibling", "http://b:8080");
        stateManager.updateInstanceHealthStatus("chat", target, false);
        stateManager.updateInstanceHealthStatus("chat", sibling, false);

        stateManager.resetInstanceHealthStatusById("chat", "uuid-target");

        assertTrue(stateManager.isInstanceHealthy("chat", target), "目标实例应被重置");
        assertFalse(stateManager.isInstanceHealthy("chat", sibling),
                "同分类其它实例的判定不应被牵连");
    }

    @Test
    @DisplayName("重置保留位索引（避免频繁增删实例耗尽位槽）")
    void resetInstance_keepsSlotIndex() {
        final ModelRouterProperties.ModelInstance instance = instance("uuid-3", "http://a:8080");
        stateManager.updateInstanceHealthStatus("chat", instance, true);

        stateManager.resetInstanceHealthStatusById("chat", "uuid-3");

        // 重置后仍是同一条记录（键未消失、索引保留），只是判定回落到「未检查」
        assertEquals("UNKNOWN", stateManager.getInstanceHealthStatus("chat:uuid-3"));
        assertTrue(stateManager.isInstanceHealthy("chat", instance));
    }

    @Test
    @DisplayName("重置不存在的实例是安全的空操作")
    void resetInstance_unknownKeyIsNoop() {
        stateManager.resetInstanceHealthStatusById("chat", "never-existed");
        stateManager.resetInstanceHealthStatusById("chat", null);
        stateManager.resetInstanceHealthStatusById(null, "uuid-x");
        stateManager.resetInstanceHealthStatusById("chat", "  ");
        // 不抛异常即通过
    }

    // ==================== 服务级 ====================

    @Test
    @DisplayName("服务被标记不健康后，失效快照使其回落为默认健康")
    void resetService_healthFallsBackToHealthy() {
        stateManager.updateServiceHealthStatus("chat", false);
        assertFalse(stateManager.isServiceHealthy("chat"), "前置：服务应已被标记为不健康");

        stateManager.resetServiceHealthStatus("chat");

        // 关键：不是写入 true，而是移除缓存条目 → getOrDefault 回落默认值 true
        assertTrue(stateManager.isServiceHealthy("chat"),
                "失效快照后应回落为默认健康（未检查）");
        assertFalse(stateManager.getAllServiceHealthStatus().containsKey("chat"),
                "缓存条目应被移除而非改写为 true（便于区分「探活健康」与「未探活」）");
    }

    @Test
    @DisplayName("服务级失效只影响指定分类")
    void resetService_doesNotAffectOtherCategories() {
        stateManager.updateServiceHealthStatus("chat", false);
        stateManager.updateServiceHealthStatus("embedding", false);

        stateManager.resetServiceHealthStatus("chat");

        assertTrue(stateManager.isServiceHealthy("chat"), "目标分类应被失效");
        assertFalse(stateManager.isServiceHealthy("embedding"), "其它分类不应被牵连");
    }

    @Test
    @DisplayName("失效不存在的分类是安全的空操作")
    void resetService_unknownCategoryIsNoop() {
        stateManager.resetServiceHealthStatus("never-configured");
        stateManager.resetServiceHealthStatus(null);
        stateManager.resetServiceHealthStatus("  ");
        // 不抛异常即通过
    }

    // ==================== 可用性保护不被削弱 ====================

    @Test
    @DisplayName("实例级判定独立于服务级快照：服务级失效不会放行不健康实例")
    void serviceReset_doesNotOverrideInstanceHealth() {
        final ModelRouterProperties.ModelInstance broken = instance("uuid-broken", "http://down:8080");
        final ModelRouterProperties.ModelInstance fine = instance("uuid-fine", "http://up:8080");
        stateManager.updateInstanceHealthStatus("chat", broken, false);
        stateManager.updateInstanceHealthStatus("chat", fine, true);
        stateManager.updateServiceHealthStatus("chat", false);

        stateManager.resetServiceHealthStatus("chat");

        // 服务级快照被清掉，但实例级判定必须原样保留 ——
        // 路由层 filterHealthyInstances 仍会滤掉 broken，可用性保护未被削弱
        assertTrue(stateManager.isServiceHealthy("chat"), "服务级快照已失效");
        assertFalse(stateManager.isInstanceHealthy("chat", broken),
                "真正不健康的实例仍必须被判定为不健康（保护未削弱）");
        assertTrue(stateManager.isInstanceHealthy("chat", fine));
    }

    // ==================== 辅助 ====================

    private ModelRouterProperties.ModelInstance instance(final String instanceId, final String baseUrl) {
        final ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setInstanceId(instanceId);
        instance.setName("model-" + instanceId);
        instance.setBaseUrl(baseUrl);
        instance.setStatus("active");
        return instance;
    }
}
