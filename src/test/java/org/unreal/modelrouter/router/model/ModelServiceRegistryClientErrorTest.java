/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.unreal.modelrouter.config.core.ConfigMergeService;
import org.unreal.modelrouter.config.core.helper.ConfigConverterHelper;
import org.unreal.modelrouter.config.core.helper.ServiceTypeResolver;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreaker;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.fallback.FallbackManager;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancerManager;
import org.unreal.modelrouter.router.loadbalancer.SelectedInstanceHolder;
import org.unreal.modelrouter.router.loadbalancer.monitor.RoutingMonitorService;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #647：Registry 层「客户端 4xx 不计入实例熔断」反证测试。
 *
 * <p>用真实 {@link ModelServiceRegistry} + 真实（spy）{@link CircuitBreakerManager} +
 * mock {@link LoadBalancer}：连续 100 次客户端错误记录后熔断器仍须 CLOSED，
 * 且只允许释放在途（{@code recordCallComplete(..., false)}），不调成功/失败。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ModelServiceRegistry 客户端 4xx 不计熔断（issue #647）")
class ModelServiceRegistryClientErrorTest {

    @Mock private LoadBalancerManager loadBalancerManager;
    @Mock private LoadBalancer loadBalancer;

    private CircuitBreakerManager circuitBreakerManager;
    private ModelServiceRegistry registry;
    private ModelRouterProperties.ModelInstance instance;

    @BeforeEach
    void setUp() {
        SelectedInstanceHolder.clear();
        instance = new ModelRouterProperties.ModelInstance();
        instance.setInstanceId("inst-1");
        instance.setName("inst-1");
        instance.setBaseUrl("http://downstream.local");

        circuitBreakerManager = spy(new CircuitBreakerManager());
        when(loadBalancerManager.getLoadBalancer(ModelServiceRegistry.ServiceType.chat))
                .thenReturn(loadBalancer);

        registry = new ModelServiceRegistry(
                mock(ModelRouterProperties.class),
                mock(ServiceStateManager.class),
                mock(RateLimitManager.class),
                loadBalancerManager,
                circuitBreakerManager,
                mock(FallbackManager.class),
                mock(ConfigMergeService.class),
                mock(ServiceTypeResolver.class),
                mock(ConfigConverterHelper.class),
                mock(WebClientCacheManager.class),
                mock(RoutingMonitorService.class));
    }

    @AfterEach
    void tearDown() {
        SelectedInstanceHolder.clear();
    }

    @Test
    @DisplayName("100 次客户端 4xx 后熔断仍 CLOSED：只释放在途，不记成功/失败")
    void hundredClientErrors_shouldNotOpenCircuitBreaker() {
        for (int i = 0; i < 100; i++) {
            registry.recordClientErrorComplete(ModelServiceRegistry.ServiceType.chat, instance, 10L);
        }

        assertEquals(CircuitBreaker.State.CLOSED,
                registry.getInstanceCircuitBreakerState(instance),
                "一个坏请求不应拖垮实例：客户端 4xx 绝不能累计熔断失败");
        verify(loadBalancer, times(100)).recordCallComplete(eq(instance), anyLong(), eq(false));
        verify(loadBalancer, never()).recordCallFailure(eq(instance), anyLong(), anyString());
        verify(circuitBreakerManager, never()).recordFailure(anyString(), anyString());
        verify(circuitBreakerManager, never()).recordSuccess(anyString(), anyString());
    }

    @Test
    @DisplayName("反证：常规 recordCallFailure 达阈值仍会 OPEN（熔断链路未被整体关闭）")
    void ordinaryFailures_shouldStillOpenCircuitBreaker() {
        for (int i = 0; i < 5; i++) {
            registry.recordCallFailure(ModelServiceRegistry.ServiceType.chat, instance, 10L, "503");
        }

        assertEquals(CircuitBreaker.State.OPEN,
                registry.getInstanceCircuitBreakerState(instance),
                "真实失败必须仍能熔断，修复不能把熔断能力一刀切关掉");
        verify(circuitBreakerManager, times(5)).recordFailure(anyString(), anyString());
    }
}
