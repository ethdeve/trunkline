package org.unreal.modelrouter.router.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.common.exception.NoAvailableInstanceException;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.monitor.RoutingMonitorService;
import org.unreal.modelrouter.router.ratelimit.RateLimitContext;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 服务实例选择器
 * 负责从可用实例中选择合适的实例，包含限流检查
 */
public class ServiceInstanceSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceInstanceSelector.class);

    /**
     * #654：单次选择扫描候选的硬上界。候选遍历已覆盖全部实例（修复前硬编码 min(size,3)），
     * 但一致性哈希等 LB 单次选择 O(n×vnode)，超大实例池全量重试会放大为 O(n²×vnode)，
     * 故再加一道常量上界兜底（超界即视为全候选不可用）。
     */
    private static final int MAX_SCAN_CANDIDATES = 32;

    private final ServiceStateManager serviceStateManager;
    private final RateLimitManager rateLimitManager;
    private final CircuitBreakerManager circuitBreakerManager;
    private final RoutingMonitorService routingMonitorService;

    public ServiceInstanceSelector(final ServiceStateManager serviceStateManager,
                                   final RateLimitManager rateLimitManager,
                                   final CircuitBreakerManager circuitBreakerManager,
                                   final RoutingMonitorService routingMonitorService) {
        this.serviceStateManager = serviceStateManager;
        this.rateLimitManager = rateLimitManager;
        this.circuitBreakerManager = circuitBreakerManager;
        this.routingMonitorService = routingMonitorService;
    }

    /**
     * 选择实例并进行实例级限流检查
     *
     * @param availableInstances 可用实例列表
     * @param loadBalancer 负载均衡器
     * @param clientIp 客户端IP
     * @param serviceType 服务类型
     * @param modelName 模型名称
     * @return 选中的实例，如果无可用实例则返回null
     */
    public ModelRouterProperties.ModelInstance selectWithRateLimit(
            final List<ModelRouterProperties.ModelInstance> availableInstances,
            final LoadBalancer loadBalancer,
            final String clientIp,
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName) {
        return selectWithRateLimit(availableInstances, loadBalancer, null, clientIp, serviceType, modelName);
    }

    /**
     * v2.9.0: 选择实例并进行实例级限流检查(带会话亲和性)
     *
     * @param availableInstances 可用实例列表
     * @param loadBalancer 负载均衡器
     * @param affinityKey 会话亲和性键(可为null)
     * @param clientIp 客户端IP
     * @param serviceType 服务类型
     * @param modelName 模型名称
     * @return 选中的实例，如果无可用实例则返回null
     */
    public ModelRouterProperties.ModelInstance selectWithRateLimit(
            final List<ModelRouterProperties.ModelInstance> availableInstances,
            final LoadBalancer loadBalancer,
            final String affinityKey,
            final String clientIp,
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName) {

        List<ModelRouterProperties.ModelInstance> candidateInstances = new ArrayList<>(availableInstances);
        // #654：遍历全部候选（此前硬编码 min(size,3)，>3 实例时前 3 个被限流即误报 503）；
        // 上界 = 初始候选数，另有 MAX_SCAN_CANDIDATES 常量兜底超大实例池
        int maxAttempts = Math.min(candidateInstances.size(), MAX_SCAN_CANDIDATES);

        for (int attempt = 0; attempt < maxAttempts && !candidateInstances.isEmpty(); attempt++) {
            ModelRouterProperties.ModelInstance candidate;
            if (affinityKey != null && !affinityKey.isBlank()) {
                candidate = loadBalancer.selectInstance(
                        candidateInstances, affinityKey, clientIp, serviceType.name().toLowerCase());
            } else {
                candidate = loadBalancer.selectInstance(
                        candidateInstances, clientIp, serviceType.name().toLowerCase());
            }

            if (candidate == null) {
                break;
            }

            // 收敛保护：每轮移除已尝试的候选（同时防 LB 返回列表外对象造成死循环）
            if (!candidateInstances.remove(candidate)) {
                boolean removedEquivalent = candidateInstances.removeIf(
                        item -> InstanceKeys.resolve(item).equals(InstanceKeys.resolve(candidate)));
                if (!removedEquivalent) {
                    LOGGER.warn("LoadBalancer returned an out-of-list candidate that cannot be removed,"
                            + " aborting instance scan for model '{}'", modelName);
                    break;
                }
            }

            // 实例级限流检查。
            // #654 关键：instanceId 可为 null（YAML 实例未配 id），而 RateLimitContext.hasInstanceInfo()
            // 要求 instanceId 与 instanceUrl 同时非空，否则实例级限流根本不生效、全量扫描白跑；
            // 统一用 InstanceKeys.resolve 兜底（id → baseUrl → name），与哈希环/健康键同口径。
            String instanceKey = InstanceKeys.resolve(candidate);
            RateLimitContext instanceContext = new RateLimitContext(
                    serviceType, modelName, clientIp, 1,
                    instanceKey,
                    candidate.getBaseUrl() != null ? candidate.getBaseUrl() : instanceKey);

            if (!rateLimitManager.tryAcquireInstance(instanceContext)) {
                LOGGER.warn("Instance rate limit exceeded for instance: {}, trying next instance",
                        candidate.getInstanceId());
                continue;
            }

            return candidate;
        }

        return null;
    }

    /**
     * 创建合适的异常信息（区分无实例、不健康、熔断三种情况）
     *
     * @param serviceType 服务类型
     * @param modelName 模型名称
     * @param allInstances 所有实例列表
     * @return 对应的异常
     */
    public ResponseStatusException createAppropriateException(
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final List<ModelRouterProperties.ModelInstance> allInstances) {

        // 检查是否有匹配模型名的实例
        List<ModelRouterProperties.ModelInstance> modelInstances = allInstances.stream()
                .filter(instance -> modelName.equals(instance.getName()))
                .filter(instance -> instance.getStatus() != null
                        && "active".equalsIgnoreCase(instance.getStatus()))
                .collect(Collectors.toList());

        if (modelInstances.isEmpty()) {
            return new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No instances found for model '" + modelName + "' in service type '" + serviceType + "'");
        }

        // 检查是否有健康实例
        List<ModelRouterProperties.ModelInstance> healthyInstances = modelInstances.stream()
                .filter(instance -> serviceStateManager.isInstanceHealthy(serviceType.name(), instance))
                .collect(Collectors.toList());

        if (healthyInstances.isEmpty()) {
            // #1010：503 带逐实例明细（data.members），前端「模型分组」页可定位哪个成员挂了
            return buildUnavailableException(serviceType, modelName, modelInstances,
                    "No healthy instances found for model '" + modelName
                            + "' in service type '" + serviceType + "'");
        }

        // 剩余情况：全部被熔断
        return buildUnavailableException(serviceType, modelName, modelInstances,
                "No available instances (all in circuit breaker state) for model '" + modelName + "'");
    }

    /**
     * #1010：构建带 {@code data.members} 逐实例明细的 503 异常。
     *
     * <p>{@code Retry-After} 与池路由 {@code POOL_UNAVAILABLE_RETRY_AFTER_SECONDS} 一致为 60s；
     * 明细由共享的 {@link InstanceDiagnostics} 产出（与池路由同口径），供异常处理器写入
     * {@code /api/**} 的 {@code RouterResponse.data} 与（开关开启时的）{@code /v1/**}
     * {@code error.details}。</p>
     *
     * @param serviceType 服务类型
     * @param modelName   模型名称
     * @param instances   参与诊断的实例列表（通常为该模型的全部 active 实例）
     * @param message     异常消息
     * @return 带 503 明细的异常
     */
    public NoAvailableInstanceException buildUnavailableException(
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final List<ModelRouterProperties.ModelInstance> instances,
            final String message) {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("serviceType", serviceType.name());
        data.put("model", modelName);
        data.put("members", InstanceDiagnostics.describeMembers(
                serviceType, instances, serviceStateManager, circuitBreakerManager));
        return new NoAvailableInstanceException(message, data);
    }
}
