/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.router.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.config.core.helper.ConfigConverterHelper;
import org.unreal.modelrouter.config.core.helper.ServiceTypeResolver;
import org.unreal.modelrouter.router.factory.ComponentFactory;
import org.unreal.modelrouter.router.model.InstanceKeys;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实例级限流器运行时注册测试（2026-11-22 整改）。
 *
 * <p>锁定两个关键修复点：
 * <ol>
 *   <li>{@code setInstanceRateLimiter} 注册后，实例级 metrics 中立即出现对应条目
 *       （原缺陷：该方法全仓零调用方，instanceLimiters 恒空）；</li>
 *   <li>注册 key 用 {@link InstanceKeys#resolveParts} 口径（id→baseUrl→name），
 *       与消费侧 ServiceInstanceSelector 完全一致，避免「写入侧 key / 消费侧 key」错位
 *       导致令牌扣不到同一个桶。</li>
 * </ol>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实例级限流器运行时注册")
class RateLimitManagerRuntimeRegistrationTest {

    @Mock
    private ComponentFactory componentFactory;

    @Mock
    private ServiceTypeResolver serviceTypeResolver;

    @Mock
    private ConfigConverterHelper configConverterHelper;

    private RateLimitManager manager;
    private RateLimiter mockLimiter;

    @BeforeEach
    void setUp() {
        ModelRouterProperties properties = new ModelRouterProperties();
        manager = new RateLimitManager(componentFactory, serviceTypeResolver, configConverterHelper, properties);
        mockLimiter = mock(RateLimiter.class);
        RateLimitConfig cfg = new RateLimitConfig();
        cfg.setEnabled(true);
        cfg.setAlgorithm("token-bucket");
        cfg.setCapacity(2L);
        cfg.setRate(1L);
        cfg.setScope("instance");
        when(mockLimiter.getConfig()).thenReturn(cfg);
        when(componentFactory.createScopedRateLimiter(any(RateLimitConfig.class))).thenReturn(mockLimiter);
    }

    private ModelRouterProperties.RateLimitConfig enabledCfg() {
        ModelRouterProperties.RateLimitConfig cfg = new ModelRouterProperties.RateLimitConfig();
        cfg.setEnabled(true);
        cfg.setAlgorithm("token-bucket");
        cfg.setCapacity(2L);
        cfg.setRate(1L);
        cfg.setScope("instance");
        return cfg;
    }

    @Test
    @DisplayName("setInstanceRateLimiter 注册后立即出现在 instance 指标中")
    void registeredLimiterAppearsInMetrics() {
        ModelRouterProperties.ModelInstance inst = new ModelRouterProperties.ModelInstance();
        inst.setInstanceId("inst-7");
        inst.setName("qwen3:4b");
        inst.setBaseUrl("http://127.0.0.1:9090");

        manager.setInstanceRateLimiter(ModelServiceRegistry.ServiceType.chat, inst, enabledCfg());

        @SuppressWarnings("unchecked")
        Map<String, String> instanceMap =
                (Map<String, String>) manager.getAllRateLimiterStatus().get("instance");
        assertEquals(1, instanceMap.size(), "注册后应立即有一个实例级限流桶");
        assertTrue(instanceMap.containsKey("chat:inst-7"), "key 应为 TYPE:instanceId");
    }

    @Test
    @DisplayName("注册 key 与消费侧 InstanceKeys.resolve 口径一致（instanceId 缺失时回退 baseUrl）")
    void registrationKeyMatchesConsumerResolve() {
        ModelRouterProperties.ModelInstance inst = new ModelRouterProperties.ModelInstance();
        // instanceId 缺失：消费侧 InstanceKeys.resolve 回退到 baseUrl（优先于 name）
        inst.setInstanceId(null);
        inst.setName("qwen3:4b");
        inst.setBaseUrl("http://127.0.0.1:9090");

        // 复刻修复后 ServiceInstanceManager 的注册 key 口径
        String registrationKey = InstanceKeys.resolveParts(
                inst.getInstanceId(), inst.getBaseUrl(), inst.getName());
        ModelRouterProperties.ModelInstance runtimeInst = new ModelRouterProperties.ModelInstance();
        runtimeInst.setInstanceId(registrationKey);
        runtimeInst.setName(inst.getName());
        runtimeInst.setBaseUrl(inst.getBaseUrl());
        manager.setInstanceRateLimiter(ModelServiceRegistry.ServiceType.chat, runtimeInst, enabledCfg());

        // 消费侧口径
        String consumerKey = InstanceKeys.resolve(inst);
        assertEquals("http://127.0.0.1:9090", consumerKey);
        assertEquals(consumerKey, registrationKey, "注册 key 必须与消费侧 resolve 一致");
        assertEquals("chat:" + consumerKey, instanceKeyOf(manager));
    }

    @Test
    @DisplayName("removeInstanceRateLimiter 后指标中实例项消失")
    void removedLimiterDisappearsFromMetrics() {
        ModelRouterProperties.ModelInstance inst = new ModelRouterProperties.ModelInstance();
        inst.setInstanceId("inst-9");
        inst.setBaseUrl("http://127.0.0.1:9090");

        manager.setInstanceRateLimiter(ModelServiceRegistry.ServiceType.chat, inst, enabledCfg());
        assertEquals(1, instanceMetricCount(manager));

        manager.removeInstanceRateLimiter(ModelServiceRegistry.ServiceType.chat, inst);
        assertEquals(0, instanceMetricCount(manager), "移除后 instance 项应消失");
    }

    @SuppressWarnings("unchecked")
    private int instanceMetricCount(final RateLimitManager manager) {
        Map<String, String> instanceMap =
                (Map<String, String>) manager.getAllRateLimiterStatus().get("instance");
        return instanceMap == null ? 0 : instanceMap.size();
    }

    @SuppressWarnings("unchecked")
    private String instanceKeyOf(final RateLimitManager manager) {
        Map<String, String> instanceMap =
                (Map<String, String>) manager.getAllRateLimiterStatus().get("instance");
        return instanceMap.keySet().iterator().next();
    }
}
