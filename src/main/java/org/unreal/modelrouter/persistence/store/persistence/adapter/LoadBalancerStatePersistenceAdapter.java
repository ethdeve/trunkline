package org.unreal.modelrouter.persistence.store.persistence.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.impl.LeastConnectionsLoadBalancer;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.persistence.store.persistence.StatePersistenceService;
import org.unreal.modelrouter.persistence.store.persistence.StatePersistenceService.StateType;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负载均衡器状态持久化适配器
 *
 * <p>v2.4.4: 使用三层退坡策略持久化负载均衡器状态</p>
 *
 * <p>存活路径（X3 死代码清理后）：</p>
 * <ul>
 *   <li>恢复：{@code StateRecoveryService} 经 {@link #restoreLoadBalancerState}
 *       回放 LeastConnections 连接计数；</li>
 *   <li>管理面：{@code StatePersistenceManagementController} 经 {@link #getAllLoadBalancerStates}
 *       与 {@link #getPendingSyncCount} 做状态查询。</li>
 * </ul>
 *
 * <p>原「事件驱动同步」一族（onInstanceSelected/onCallStarted/onCallCompleted/
 * onConfigurationChanged/onStrategySwitched/syncPendingStates）与 save/delete/batch/clearAll
 * 路径自始至终没有任何调用方，已按 X3 核实后移除；{@link #pendingSync} 由此不再有写入方，
 * 仅保留以维持管理端点契约（恒为空）。</p>
 *
 * @author JAiRouter Team
 * @since 2.4.4
 * @modified Trunkline Team
 */
@Component
public class LoadBalancerStatePersistenceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(LoadBalancerStatePersistenceAdapter.class);

    private static final String STRATEGY_KEY = "strategy";
    private static final String CONNECTION_COUNTS_KEY = "connectionCounts";

    @Autowired
    @Qualifier("compositeStatePersistenceServiceImpl")
    private StatePersistenceService persistenceService;

    /**
     * 状态变更标记（历史事件驱动同步遗留；当前无写入方，恒为空，仅为管理端点契约保留）
     */
    private final Map<ModelServiceRegistry.ServiceType, Boolean> pendingSync = new ConcurrentHashMap<>();

    /**
     * 加载负载均衡器状态
     *
     * @param serviceType 服务类型
     * @return 状态数据
     */
    public Mono<Map<String, Object>> loadLoadBalancerState(
            final ModelServiceRegistry.ServiceType serviceType) {

        String key = serviceType.name().toLowerCase();

        return persistenceService.load(StateType.LOAD_BALANCER, key)
                .doOnNext(data -> {
                    if (!data.isEmpty()) {
                        logger.debug("Load balancer state loaded: {} (tier: {})",
                                serviceType, persistenceService.getTierName());
                    }
                });
    }

    /**
     * 恢复负载均衡器状态
     *
     * @param serviceType 服务类型
     * @param loadBalancer 负载均衡器实例
     * @return 是否恢复成功
     */
    public Mono<Boolean> restoreLoadBalancerState(
            final ModelServiceRegistry.ServiceType serviceType,
            final LoadBalancer loadBalancer) {

        return loadLoadBalancerState(serviceType)
                .flatMap(stateData -> {
                    if (stateData.isEmpty()) {
                        logger.info("No saved state found for load balancer: {}", serviceType);
                        return Mono.just(false);
                    }

                    try {
                        applyLoadBalancerState(loadBalancer, stateData);
                        logger.info("Load balancer state restored: {} (strategy: {})",
                                serviceType, stateData.get(STRATEGY_KEY));
                        return Mono.just(true);
                    } catch (Exception e) {
                        logger.error("Failed to restore load balancer state: {}", serviceType, e);
                        return Mono.just(false);
                    }
                });
    }

    /**
     * 获取所有负载均衡器状态
     *
     * @return 服务类型 -> 状态数据映射
     */
    public Mono<Map<ModelServiceRegistry.ServiceType, Map<String, Object>>> getAllLoadBalancerStates() {
        return persistenceService.getAllKeys(StateType.LOAD_BALANCER)
                .flatMap(keys -> {
                    Map<String, Map<String, Object>> states = new HashMap<>();
                    for (String key : keys) {
                        Map<String, Object> data = persistenceService
                                .load(StateType.LOAD_BALANCER, key).block(org.unreal.modelrouter.common.util.ReactorTimeouts.BLOCK);
                        if (data != null && !data.isEmpty()) {
                            states.put(key, data);
                        }
                    }

                    // 转换为 ServiceType 映射
                    Map<ModelServiceRegistry.ServiceType, Map<String, Object>> result =
                            new EnumMap<>(ModelServiceRegistry.ServiceType.class);
                    for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
                        try {
                            ModelServiceRegistry.ServiceType serviceType =
                                    ModelServiceRegistry.ServiceType.valueOf(entry.getKey().toUpperCase());
                            result.put(serviceType, entry.getValue());
                        } catch (IllegalArgumentException e) {
                            logger.warn("Unknown service type in state: {}", entry.getKey());
                        }
                    }

                    return Mono.just(result);
                })
                // flatMap 内逐 key load().block() 属阻塞调用，整体调度到 boundedElastic，
                // 避免管理控制器在事件循环上订阅时触发 block() 异常
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 应用负载均衡器状态数据
     */
    private void applyLoadBalancerState(final LoadBalancer loadBalancer, final Map<String, Object> stateData) {
        if (loadBalancer instanceof LeastConnectionsLoadBalancer) {
            LeastConnectionsLoadBalancer lclb = (LeastConnectionsLoadBalancer) loadBalancer;

            Map<String, Long> connectionCounts = (Map<String, Long>) stateData.get(CONNECTION_COUNTS_KEY);
            if (connectionCounts != null) {
                // 通过反射设置 connectionCounts
                try {
                    java.lang.reflect.Field field = LeastConnectionsLoadBalancer.class
                            .getDeclaredField("connectionCounts");
                    field.setAccessible(true);
                    Map<String, java.util.concurrent.atomic.AtomicLong> counts =
                            new ConcurrentHashMap<>();
                    for (Map.Entry<String, Long> entry : connectionCounts.entrySet()) {
                        counts.put(entry.getKey(), new java.util.concurrent.atomic.AtomicLong(entry.getValue()));
                    }
                    field.set(lclb, counts);

                    logger.debug("Restored {} connection counts for LeastConnectionsLoadBalancer",
                            counts.size());

                } catch (Exception e) {
                    logger.warn("Failed to apply connection counts to LeastConnectionsLoadBalancer", e);
                }
            }
        }

        // ConsistentHash 哈希环可动态重建，无需恢复
    }

    /**
     * 获取待同步状态数量
     */
    public int getPendingSyncCount() {
        return pendingSync.size();
    }
}
