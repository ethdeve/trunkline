package org.unreal.modelrouter.router.model;

import org.unreal.modelrouter.router.circuitbreaker.CircuitBreaker;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.checker.ServiceStateManager;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可用实例诊断信息构建器（#1010）.
 *
 * <p>供普通路由（{@code NoAvailableInstanceException} 的 {@code data.members}）与池路由
 * （{@code PoolUnavailableException} 的 {@code data.members}）共用同一份逐实例明细：
 * {@code name / target(host:port 脱敏) / status(配置态) / health(探活态) / circuitBreaker /
 * failureCount / lastFailureTime(ISO)}，便于「模型分组」页与客户端排障定位哪个成员挂了。</p>
 *
 * <p>{@code target} 只取 {@code host:port}（丢弃 userinfo/query/fragment，不含凭证；
 * IPv6 用 {@code [...]} 包裹），解析失败统一 {@code unknown}，不回显原始 URL。</p>
 *
 * @author Trunkline Team
 */
public final class InstanceDiagnostics {

    private InstanceDiagnostics() {
    }

    /**
     * 构建逐实例明细列表（顺序与入参一致）.
     *
     * @param serviceType           服务类型
     * @param instances             实例列表（可为 null，返回空列表）
     * @param serviceStateManager   健康位图来源（可为 null，health 退配置字段）
     * @param circuitBreakerManager 熔断器来源（可为 null，circuitBreaker=failureCount/lastFailureTime 为 null）
     * @return 逐实例明细
     */
    public static List<Map<String, Object>> describeMembers(
            final ModelServiceRegistry.ServiceType serviceType,
            final List<ModelRouterProperties.ModelInstance> instances,
            final ServiceStateManager serviceStateManager,
            final CircuitBreakerManager circuitBreakerManager) {
        final List<Map<String, Object>> members = new ArrayList<>();
        if (instances == null) {
            return members;
        }
        for (final ModelRouterProperties.ModelInstance instance : instances) {
            members.add(describeMember(serviceType, instance, serviceStateManager, circuitBreakerManager));
        }
        return members;
    }

    /**
     * 构建单个实例的诊断明细（键恒存在，未知值为 null，消费端无需判键）.
     */
    public static Map<String, Object> describeMember(
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final ServiceStateManager serviceStateManager,
            final CircuitBreakerManager circuitBreakerManager) {
        final Map<String, Object> member = new LinkedHashMap<>();
        member.put("name", instance.getName());
        member.put("target", hostPort(instance.getBaseUrl()));
        member.put("status", instance.getStatus());
        member.put("health", resolveHealth(serviceType, instance, serviceStateManager));
        member.put("circuitBreaker", resolveCircuitBreakerState(instance, circuitBreakerManager));
        final Map<String, Object> cbDetail = resolveCircuitBreakerDetail(instance, circuitBreakerManager);
        member.put("failureCount", cbDetail == null ? null : cbDetail.get("failureCount"));
        member.put("lastFailureTime", toIsoTime(cbDetail == null ? null : cbDetail.get("lastFailureTime")));
        return member;
    }

    /**
     * N-3 探活态（HEALTHY / UNHEALTHY / UNKNOWN）：优先运行时健康位图，UNKNOWN 回退配置
     * {@code isHealthy}。与 {@code ModelServiceRegistry#resolveHealthStatus} 同口径。
     */
    public static String resolveHealth(
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final ServiceStateManager serviceStateManager) {
        if (serviceType != null && serviceStateManager != null) {
            try {
                final String runtimeStatus = serviceStateManager.getInstanceHealthStatus(
                        InstanceKeys.healthKey(serviceType.name(), instance));
                if (runtimeStatus != null && !"UNKNOWN".equals(runtimeStatus)) {
                    return runtimeStatus;
                }
            } catch (Exception ignored) {
                // 运行态不可查时退到配置字段
            }
        }
        final Boolean configured = instance.isHealthy();
        if (configured == null) {
            return "UNKNOWN";
        }
        return configured ? "HEALTHY" : "UNHEALTHY";
    }

    private static String resolveCircuitBreakerState(
            final ModelRouterProperties.ModelInstance instance,
            final CircuitBreakerManager circuitBreakerManager) {
        if (circuitBreakerManager == null) {
            return null;
        }
        try {
            final CircuitBreaker.State state =
                    circuitBreakerManager.getState(instance.getInstanceId(), instance.getBaseUrl());
            return state == null ? null : state.name();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> resolveCircuitBreakerDetail(
            final ModelRouterProperties.ModelInstance instance,
            final CircuitBreakerManager circuitBreakerManager) {
        if (circuitBreakerManager == null) {
            return null;
        }
        try {
            return circuitBreakerManager.getCircuitBreakerDetail(instance.getInstanceId());
        } catch (Exception e) {
            // instanceId 为 null 时 ConcurrentHashMap.get 会抛 NPE，统一按无明细处理
            return null;
        }
    }

    /**
     * {@code host:port} 脱敏：丢弃 userinfo/query/fragment（不含凭证），IPv6 用 {@code [...]} 包裹，
     * 未显式端口按 scheme 补默认端口；解析失败返回 {@code unknown}，不回显原始 URL。
     */
    public static String hostPort(final String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "unknown";
        }
        try {
            final URI uri = new URI(baseUrl);
            final String host = uri.getHost();
            if (host == null) {
                return "unknown";
            }
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            final String safeHost = host.contains(":") ? "[" + host + "]" : host;
            return safeHost + ":" + port;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String toIsoTime(final Object epochMillis) {
        if (epochMillis instanceof Long millis && millis > 0) {
            return Instant.ofEpochMilli(millis).toString();
        }
        return null;
    }
}
