package org.unreal.modelrouter.router.loadbalancer.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.loadbalancer.utils.WeightCalculator;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 随机负载均衡策略
 *
 * <p>B1 收敛：熔断判定统一走 {@link CircuitBreakerManager} 单一注册表，
 * 不再持有私有熔断器 Map（原私有表与 Manager 各自为政，同一实例两套熔断状态，
 * 且无法参与实例生命周期淘汰）。Manager 未注入（纯单测场景）时不做熔断过滤。</p>
 */
// 修改 RandomLoadBalancer 实现
public class RandomLoadBalancer implements LoadBalancer {
    private static final Logger logger = LoggerFactory.getLogger(RandomLoadBalancer.class);
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    // R2: 由 ComponentFactory 在创建时注入(原 @Autowired 字段在 new 出的实例上恒为 null)
    private final MetricsCollector metricsCollector;
    // B1: 单一熔断注册表，由 ComponentFactory 在创建时注入
    private final CircuitBreakerManager circuitBreakerManager;

    public RandomLoadBalancer() {
        this(null, null);
    }

    public RandomLoadBalancer(final MetricsCollector metricsCollector) {
        this(metricsCollector, null);
    }

    public RandomLoadBalancer(final MetricsCollector metricsCollector,
                              final CircuitBreakerManager circuitBreakerManager) {
        this.metricsCollector = metricsCollector;
        this.circuitBreakerManager = circuitBreakerManager;
    }

    @Override
    public ModelRouterProperties.ModelInstance selectInstance(
            final List<ModelRouterProperties.ModelInstance> instances, final String clientIp) {
        return selectInstance(instances, clientIp, "unknown");
    }

    @Override
    public ModelRouterProperties.ModelInstance selectInstance(
            final List<ModelRouterProperties.ModelInstance> instances,
            final String clientIp, final String serviceType) {

        if (instances == null || instances.isEmpty()) {
            logger.warn("No instances available for random selection");
            throw new IllegalArgumentException("No instances available");
        }

        // 过滤掉熔断状态的实例（B1: 统一走 CircuitBreakerManager，无 Manager 时不过滤）
        List<ModelRouterProperties.ModelInstance> availableInstances =
                instances.stream()
                        .filter(instance -> isInstanceAvailable(instance))
                        .collect(Collectors.toList());

        if (availableInstances.isEmpty()) {
            logger.warn("No available instances (all are in circuit breaker state)");
            throw new RuntimeException("All instances are unavailable");
        }

        // 考虑权重的随机选择 - 使用安全的权重计算
        long totalWeight = WeightCalculator.calculateTotalWeight(availableInstances);

        if (totalWeight <= 0) {
            // 如果没有权重，使用简单随机
            ModelRouterProperties.ModelInstance selected =
                    availableInstances.get(random.nextInt(availableInstances.size()));
            logger.debug("Selected instance {} using simple random strategy for service {}",
                    selected.getName(), serviceType);
            recordLoadBalancerSelection(serviceType, "random", selected.getName());
            return selected;
        }

        long randomWeight;
        try {
            randomWeight = (long) (java.security.SecureRandom.getInstanceStrong().nextDouble() * totalWeight);
        } catch (java.security.NoSuchAlgorithmException e) {
            // 如果安全随机数算法不可用，回退到类中的 SecureRandom 实例
            randomWeight = (long) (random.nextDouble() * totalWeight);
            logger.warn("SecureRandom.getInstanceStrong() not available, "
                    + "using class SecureRandom instance: {}", e.getMessage());
        }
        long currentWeight = 0;

        for (ModelRouterProperties.ModelInstance instance : availableInstances) {
            currentWeight += Math.max(0, instance.getWeight());
            if (randomWeight < currentWeight) {
                logger.debug("Selected instance {} using weighted random strategy for service {}",
                        instance.getName(), serviceType);
                recordLoadBalancerSelection(serviceType, "random", instance.getName());
                return instance;
            }
        }

        // 兜底返回最后一个
        ModelRouterProperties.ModelInstance lastInstance =
                availableInstances.get(availableInstances.size() - 1);
        logger.debug("Fallback selection of instance {} for service {}", lastInstance.getName(), serviceType);
        recordLoadBalancerSelection(serviceType, "random", lastInstance.getName());
        return lastInstance;
    }

    @Override
    public void recordCallComplete(final ModelRouterProperties.ModelInstance instance) {
        if (circuitBreakerManager != null && instance != null) {
            circuitBreakerManager.recordSuccess(instance.getInstanceId(), instance.getBaseUrl());
        }
    }

    @Override
    public void recordCallFailure(final ModelRouterProperties.ModelInstance instance) {
        if (circuitBreakerManager != null && instance != null) {
            circuitBreakerManager.recordFailure(instance.getInstanceId(), instance.getBaseUrl());
        }
    }

    /**
     * B1: 熔断可用性判定统一走 CircuitBreakerManager（单一注册表）。
     * 键语义与 Manager 一致：优先 instanceId，URL 兜底。
     */
    private boolean isInstanceAvailable(final ModelRouterProperties.ModelInstance instance) {
        if (circuitBreakerManager == null) {
            return true;
        }
        return circuitBreakerManager.canExecute(instance.getInstanceId(), instance.getBaseUrl());
    }

    /**
     * 记录负载均衡器选择指标
     */
    private void recordLoadBalancerSelection(
            final String service, final String strategy, final String selectedInstance) {
        if (metricsCollector != null) {
            try {
                metricsCollector.recordLoadBalancer(service, strategy, selectedInstance);
            } catch (Exception e) {
                logger.warn("Failed to record load balancer metrics: {}", e.getMessage());
            }
        }
    }
}