/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.router.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.config.core.ConfigurationValidator;
import org.unreal.modelrouter.config.core.helper.ConfigConverterHelper;
import org.unreal.modelrouter.config.core.helper.ServiceTypeResolver;
import org.unreal.modelrouter.router.factory.ComponentFactory;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务级限流「配置→生效」真实链路行为测试（2026-11-22 定位用）。
 *
 * <p>用真实 ComponentFactory + 真实 TokenBucketRateLimiter（非 mock），复刻控制台 PUT 的
 * fromMap 配置，走 setRateLimiter → tryAcquire 全链路，回答一个问题：
 * <b>service 级限流链路本身是否能扣减令牌？</b></p>
 *
 * @author Trunkline Team
 */
@DisplayName("服务级限流真实链路行为")
class ServiceRateLimitBehaviorTest {

    private RateLimitManager manager;

    @BeforeEach
    void setUp() {
        // 真实工厂：tracingWrapperFactory/metricsCollector/stateManager/circuitBreaker 全传 null，
        // createRateLimiter 会 new 出真实 TokenBucketRateLimiter
        ComponentFactory factory = new ComponentFactory(
                org.mockito.Mockito.mock(ConfigurationValidator.class), null, null, null, null);
        manager = new RateLimitManager(factory, new ServiceTypeResolver(),
                new ConfigConverterHelper(), new ModelRouterProperties());
    }

    private Map<String, Object> consolePayload() {
        // 复刻控制台 PUT /api/services/chat/ratelimit 的最小 body
        return Map.of("enabled", true, "capacity", 2L, "rate", 1L, "scope", "service");
    }

    private RateLimitContext ctx() {
        return new RateLimitContext(ModelServiceRegistry.ServiceType.chat, "qwen3:4b", "127.0.0.1", 1, null, null);
    }

    @Test
    @DisplayName("控制台配置 fromMap → setRateLimiter → 连打 8 次应出现 429")
    void serviceLimitShouldRejectAfterCapacity() {
        RateLimitConfig cfg = RateLimitConfig.fromMap(consolePayload());

        // 前置断言：scope 非 null（排除「scope=null 每次新建临时桶」假设）
        assertEquals("service", cfg.getScope(), "scope 应为 service 而非 null");
        assertTrue(cfg.isEnabled());
        assertTrue(cfg.isValid());

        manager.setRateLimiter(ModelServiceRegistry.ServiceType.chat, cfg);

        int allowed = 0;
        int denied = 0;
        for (int i = 0; i < 8; i++) {
            if (manager.tryAcquire(ctx())) {
                allowed++;
            } else {
                denied++;
            }
        }

        assertTrue(denied > 0, "容量=2、连打 8 次应至少出现一次 429（实际 allowed=" + allowed
                + ", denied=" + denied + "），否则 service 级限流链路确实断裂");
        assertTrue(allowed >= 1, "前 capacity 次应放行（实际 allowed=" + allowed + "）");

        // 一锤定音：连打后 metrics 的 remaining 应小于 capacity（证明确实扣到了同一个桶）
        RateLimitManager.RateLimiterMetrics metric = manager.getRateLimiterMetrics().stream()
                .filter(m -> "service".equals(m.getScope()) && "chat".equals(m.getIdentifier()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 chat 的 service 级指标"));
        assertTrue(metric.getRemainingCapacity() < cfg.getCapacity(),
                "连打后 remaining 应小于 capacity=" + cfg.getCapacity()
                        + "，实际 remaining=" + metric.getRemainingCapacity()
                        + "（若恒满则说明 wrapper 内层 map 没被扣减）");
    }

    @Test
    @DisplayName("service 级限流桶建桶 key 稳定为 serviceType 名（chat）")
    void bucketKeyIsStableServiceTypeName() {
        RateLimitConfig cfg = RateLimitConfig.fromMap(consolePayload());
        manager.setRateLimiter(ModelServiceRegistry.ServiceType.chat, cfg);

        // 先扣一次，触发 wrapper 内层 map 建桶
        manager.tryAcquire(ctx());

        @SuppressWarnings("unchecked")
        Map<ModelServiceRegistry.ServiceType, String> status =
                (Map<ModelServiceRegistry.ServiceType, String>) manager.getAllRateLimiterStatus().get("service");
        assertTrue(status.containsKey(ModelServiceRegistry.ServiceType.chat),
                "service 级桶应以 chat 类型注册，实际 Keys=" + status.keySet());
    }
}
