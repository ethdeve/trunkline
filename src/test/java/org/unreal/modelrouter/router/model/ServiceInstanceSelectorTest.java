package org.unreal.modelrouter.router.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.monitor.RoutingMonitorService;
import org.unreal.modelrouter.router.ratelimit.RateLimitContext;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #654 修复验证：{@code selectWithRateLimit} 遍历全部候选
 * （此前硬编码 {@code min(size,3)}，>3 实例时前 3 个被限流即误报 503），
 * 且 {@code RateLimitContext} 用 {@code InstanceKeys.resolve} 兜底保证限流态生效。
 */
@ExtendWith(MockitoExtension.class)
class ServiceInstanceSelectorTest {

    @Mock
    private ServiceStateManager serviceStateManager;

    @Mock
    private RateLimitManager rateLimitManager;

    @Mock
    private CircuitBreakerManager circuitBreakerManager;

    @Mock
    private RoutingMonitorService routingMonitorService;

    @Mock
    private LoadBalancer loadBalancer;

    private ServiceInstanceSelector selector;

    @BeforeEach
    void setUp() {
        selector = new ServiceInstanceSelector(
                serviceStateManager, rateLimitManager, circuitBreakerManager, routingMonitorService);
    }

    private ModelRouterProperties.ModelInstance instance(final String name, final String baseUrl) {
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName(name);
        instance.setBaseUrl(baseUrl);
        // 刻意不设置 instanceId（YAML 实例未配 id 的真实形态），验证 InstanceKeys 兜底
        return instance;
    }

    private List<ModelRouterProperties.ModelInstance> fourInstances() {
        return new ArrayList<>(Arrays.asList(
                instance("inst-1", "http://host1:8001"),
                instance("inst-2", "http://host2:8002"),
                instance("inst-3", "http://host3:8003"),
                instance("inst-4", "http://host4:8004")));
    }

    @Test
    @DisplayName("前 3 个实例被限流、第 4 个可用 → 返回第 4 个（反证修复前返回 null）")
    void testFourthInstanceSelectedWhenFirstThreeRateLimited() {
        List<ModelRouterProperties.ModelInstance> candidates = fourInstances();
        when(loadBalancer.selectInstance(anyList(), anyString(), anyString()))
                .thenReturn(candidates.get(0), candidates.get(1), candidates.get(2), candidates.get(3));
        when(rateLimitManager.tryAcquireInstance(any(RateLimitContext.class)))
                .thenReturn(false, false, false, true);

        ModelRouterProperties.ModelInstance selected = selector.selectWithRateLimit(
                candidates, loadBalancer, null, "10.0.0.1",
                ModelServiceRegistry.ServiceType.chat, "gpt-x");

        assertEquals(candidates.get(3), selected, "第 4 个实例应被选中（修复前 min(size,3) 只试前 3 个）");
        verify(rateLimitManager, times(4)).tryAcquireInstance(any(RateLimitContext.class));
    }

    @Test
    @DisplayName("全部候选被限流 → 返回 null 且调用次数有界（= 候选数）")
    void testAllRateLimitedReturnsNullWithBoundedScans() {
        List<ModelRouterProperties.ModelInstance> candidates = fourInstances();
        when(loadBalancer.selectInstance(anyList(), anyString(), anyString()))
                .thenReturn(candidates.get(0), candidates.get(1), candidates.get(2), candidates.get(3));
        when(rateLimitManager.tryAcquireInstance(any(RateLimitContext.class))).thenReturn(false);

        ModelRouterProperties.ModelInstance selected = selector.selectWithRateLimit(
                candidates, loadBalancer, null, "10.0.0.1",
                ModelServiceRegistry.ServiceType.chat, "gpt-x");

        assertNull(selected);
        verify(rateLimitManager, times(4)).tryAcquireInstance(any(RateLimitContext.class));
    }

    @Test
    @DisplayName("LB 返回列表外对象 → 不死循环、调用次数有界")
    void testOutOfListCandidateDoesNotLoopForever() {
        List<ModelRouterProperties.ModelInstance> candidates = fourInstances();
        // 列表外对象：键与候选均不同，remove/removeIf 均无法收敛 → 应触发保护并中断（不进入限流检查）
        ModelRouterProperties.ModelInstance foreign = instance("foreign", "http://foreign:9999");
        when(loadBalancer.selectInstance(anyList(), anyString(), anyString())).thenReturn(foreign);

        ModelRouterProperties.ModelInstance selected = selector.selectWithRateLimit(
                candidates, loadBalancer, null, "10.0.0.1",
                ModelServiceRegistry.ServiceType.chat, "gpt-x");

        assertNull(selected);
        verify(loadBalancer, times(1)).selectInstance(anyList(), anyString(), anyString());
        verifyNoInteractions(rateLimitManager);
    }

    @Test
    @DisplayName("未配置 instanceId 的实例也参与实例级限流（hasInstanceInfo 恒为 true）")
    void testInstanceKeysFallbackMakesRateLimitEffective() {
        List<ModelRouterProperties.ModelInstance> candidates = fourInstances();
        when(loadBalancer.selectInstance(anyList(), anyString(), anyString()))
                .thenReturn(candidates.get(0), candidates.get(1), candidates.get(2), candidates.get(3));
        when(rateLimitManager.tryAcquireInstance(any(RateLimitContext.class))).thenReturn(false);

        selector.selectWithRateLimit(candidates, loadBalancer, null, "10.0.0.1",
                ModelServiceRegistry.ServiceType.chat, "gpt-x");

        ArgumentCaptor<RateLimitContext> captor = ArgumentCaptor.forClass(RateLimitContext.class);
        verify(rateLimitManager, times(4)).tryAcquireInstance(captor.capture());
        for (RateLimitContext context : captor.getAllValues()) {
            assertTrue(context.hasInstanceInfo(),
                    "instanceId 为 null 时必须用 InstanceKeys.resolve 兜底，否则实例级限流不生效");
        }
        // 首个上下文的 instanceId 应回退为 baseUrl（id → baseUrl → name 顺序）
        assertEquals("http://host1:8001", captor.getAllValues().get(0).getInstanceId());
    }
}
