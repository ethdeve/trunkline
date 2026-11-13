package org.unreal.modelrouter.router.loadbalancer.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 最少连接数负载均衡策略
 */
public class LeastConnectionsLoadBalancer implements LoadBalancer {
    private static final Logger logger = LoggerFactory.getLogger(LeastConnectionsLoadBalancer.class);

    private final Map<String, AtomicLong> connectionCounts = new ConcurrentHashMap<>();

    /** issue #1085：完全并列时的轮转游标；floorMod 保证 AtomicLong 溢出后下标仍非负 */
    private final AtomicLong tieCursor = new AtomicLong();

    // R2: 由 ComponentFactory 在创建时注入(原 @Autowired 字段在 new 出的实例上恒为 null)
    private final MetricsCollector metricsCollector;

    public LeastConnectionsLoadBalancer() {
        this(null);
    }

    public LeastConnectionsLoadBalancer(final MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @Override
    public ModelRouterProperties.ModelInstance selectInstance(
            final List<ModelRouterProperties.ModelInstance> instances,
            final String clientIp) {
        return selectInstance(instances, clientIp, "unknown");
    }

    @Override
    public ModelRouterProperties.ModelInstance selectInstance(
            final List<ModelRouterProperties.ModelInstance> instances,
            final String clientIp, final String serviceType) {
        if (instances == null || instances.isEmpty()) {
            logger.warn("No instances available for least connections selection");
            throw new IllegalArgumentException("No instances available");
        }

        // issue #1085: 先收集全部"加权连接数与原始连接数都并列最小"的候选，
        // 再按游标轮转；旧逻辑在完全并列时不再更新 selected，会永远返回遍历到的第一个实例。
        List<ModelRouterProperties.ModelInstance> tied = new ArrayList<>();
        double minWeightedConnections = Double.MAX_VALUE;
        long minConnections = Long.MAX_VALUE;

        for (ModelRouterProperties.ModelInstance instance : instances) {
            String key = getInstanceKey(instance);
            long connections = connectionCounts.getOrDefault(key, new AtomicLong(0)).get();

            // 考虑权重的最少连接算法: connections / weight
            double weightedConnections = instance.getWeight() > 0
                    ? (double) connections / instance.getWeight() : connections;

            int cmp = Double.compare(weightedConnections, minWeightedConnections);
            if (cmp < 0 || (cmp == 0 && connections < minConnections)) {
                minWeightedConnections = weightedConnections;
                minConnections = connections;
                tied.clear();
                tied.add(instance);
            } else if (cmp == 0 && connections == minConnections) {
                tied.add(instance);
            }
        }

        ModelRouterProperties.ModelInstance selectedInstance;
        if (tied.isEmpty()) {
            // 理论不可达（instances 非空且首轮必进 tied）；保守兜底，避免返回 null
            selectedInstance = instances.get(0);
        } else if (tied.size() == 1) {
            selectedInstance = tied.get(0);
        } else {
            selectedInstance = tied.get(Math.floorMod(tieCursor.getAndIncrement(), tied.size()));
        }

        logger.debug("Selected instance {} with {} connections using least connections strategy for service {}",
                selectedInstance.getName(), minConnections, serviceType);
        recordLoadBalancerSelection(serviceType, "least_connections", selectedInstance.getName());
        return selectedInstance;
    }

    @Override
    public void recordCall(final ModelRouterProperties.ModelInstance instance) {
        String key = getInstanceKey(instance);
        long currentCount = connectionCounts.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        logger.info("Instance {} call recorded. Current connections: {}", key, currentCount);
    }

    @Override
    public void recordCallComplete(final ModelRouterProperties.ModelInstance instance) {
        release(instance, "completed");
    }

    /**
     * issue #655：失败同样必须释放在途计数。
     *
     * <p>{@code ModelServiceRegistry.recordCallFailure} 只调本方法（带时长/错误码的
     * 4 参重载经接口默认方法委托到这里），旧实现沿用接口默认空实现 → 计数只增不减，
     * 失败过的实例会被永久判定为"最忙"而排除。</p>
     */
    @Override
    public void recordCallFailure(final ModelRouterProperties.ModelInstance instance) {
        release(instance, "failed");
    }

    private void release(final ModelRouterProperties.ModelInstance instance, final String outcome) {
        String key = getInstanceKey(instance);
        AtomicLong count = connectionCounts.get(key);
        if (count == null) {
            logger.info("Instance {} call {}. No active connection count found.", key, outcome);
            return;
        }
        // 钳制到 0：上层"统计 + 调用历史"两条完成回调可能重复释放，也不能把在途计数减成负数
        long currentCount = count.updateAndGet(v -> v > 0 ? v - 1 : 0);
        logger.info("Instance {} call {}. Current connections: {}", key, outcome, currentCount);
    }

    private String getInstanceKey(final ModelRouterProperties.ModelInstance instance) {
        return instance.getBaseUrl() + ":" + instance.getPath();
    }

    /**
     * 记录负载均衡器选择指标
     */
    private void recordLoadBalancerSelection(
            final String service, final String strategy,
            final String selectedInstance) {
        if (metricsCollector != null) {
            try {
                metricsCollector.recordLoadBalancer(service, strategy, selectedInstance);
            } catch (Exception e) {
                logger.warn("Failed to record load balancer metrics: {}", e.getMessage());
            }
        }
    }
}
