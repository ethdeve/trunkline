package org.unreal.modelrouter.router.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.common.exception.NoAvailableInstanceException;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreaker;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * #1010 修复验证：{@code createAppropriateException} 的 503 分支改抛
 * {@link NoAvailableInstanceException}，携带 {@code data.members} 逐实例明细
 * （name / target(脱敏 host:port) / status / health / circuitBreaker / failureCount /
 * lastFailureTime(ISO)）。反证修复前仅有 message、{@code data} 恒 null。
 */
@ExtendWith(MockitoExtension.class)
class NoAvailableInstanceExceptionDetailTest {

    @Mock
    private ServiceStateManager serviceStateManager;

    @Mock
    private CircuitBreakerManager circuitBreakerManager;

    private ServiceInstanceSelector selector;

    @BeforeEach
    void setUp() {
        selector = new ServiceInstanceSelector(serviceStateManager, null, circuitBreakerManager, null);
    }

    private List<ModelRouterProperties.ModelInstance> threeActiveInstances() {
        List<ModelRouterProperties.ModelInstance> instances = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
            instance.setName("gpt-x");
            instance.setBaseUrl("http://host" + i + ":800" + i);
            instance.setStatus("active");
            instances.add(instance);
        }
        return instances;
    }

    @Test
    @DisplayName("全不健康 3 实例 → 503 + data.members 3 项各含明细键（反证修复前 data==null）")
    void testAllUnhealthyCarriesMembersDetail() {
        when(serviceStateManager.getInstanceHealthStatus(anyString())).thenReturn("UNHEALTHY");
        when(circuitBreakerManager.getState(any(), any())).thenReturn(CircuitBreaker.State.OPEN);
        Map<String, Object> cbDetail = new HashMap<>();
        cbDetail.put("failureCount", 3);
        cbDetail.put("lastFailureTime", 1700000000000L);
        when(circuitBreakerManager.getCircuitBreakerDetail(any())).thenReturn(cbDetail);

        ResponseStatusException ex = selector.createAppropriateException(
                ModelServiceRegistry.ServiceType.chat, "gpt-x", threeActiveInstances());

        assertInstanceOf(NoAvailableInstanceException.class, ex,
                "503 分支应改抛带明细的 NoAvailableInstanceException（仍兼容 ResponseStatusException 契约）");
        NoAvailableInstanceException naie = (NoAvailableInstanceException) ex;
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, naie.getStatusCode());
        assertEquals(60, naie.getRetryAfterSeconds(), "Retry-After 与池路由一致为 60s");
        assertTrue(naie.getMessage().contains("gpt-x"));

        Map<String, Object> data = naie.getData();
        assertNotNull(data);
        assertTrue(data.containsKey("serviceType") && data.containsKey("model"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) data.get("members");
        assertEquals(3, members.size());
        for (Map<String, Object> member : members) {
            assertTrue(member.containsKey("lastFailureTime"), "members 各项应含 lastFailureTime");
            assertTrue(member.containsKey("failureCount"), "members 各项应含 failureCount");
            assertTrue(member.containsKey("circuitBreaker"), "members 各项应含 circuitBreaker");
            assertEquals("OPEN", member.get("circuitBreaker"));
            assertEquals(3, member.get("failureCount"));
            assertEquals("UNHEALTHY", member.get("health"));
            // target 脱敏：只含 host:port，不含 scheme 与原始 URL
            assertTrue(String.valueOf(member.get("target")).matches("host\\d:800\\d"),
                    "target 应为脱敏 host:port，实际=" + member.get("target"));
        }
        // ISO 时间格式（反证修复前无此字段）
        assertEquals("2023-11-14T22:13:20Z", members.get(0).get("lastFailureTime"));
    }

    @Test
    @DisplayName("健康但全熔断 → 503 熔断文案 + 明细；404 分支保持普通 ResponseStatusException")
    void testCircuitBrokenBranchAnd404Untouched() {
        when(serviceStateManager.isInstanceHealthy(anyString(), any())).thenReturn(true);
        when(serviceStateManager.getInstanceHealthStatus(anyString())).thenReturn("HEALTHY");
        when(circuitBreakerManager.getState(any(), any())).thenReturn(CircuitBreaker.State.OPEN);
        when(circuitBreakerManager.getCircuitBreakerDetail(any())).thenReturn(new HashMap<>());

        ResponseStatusException cbEx = selector.createAppropriateException(
                ModelServiceRegistry.ServiceType.chat, "gpt-x", threeActiveInstances());
        assertInstanceOf(NoAvailableInstanceException.class, cbEx);
        assertTrue(cbEx.getMessage().contains("circuit breaker"));

        // 404 分支（无匹配实例）保持普通 ResponseStatusException，不带明细
        ResponseStatusException notFound = selector.createAppropriateException(
                ModelServiceRegistry.ServiceType.chat, "no-such-model",
                Arrays.asList(threeActiveInstances().get(0)));
        assertTrue(!(notFound instanceof NoAvailableInstanceException));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    }
}
