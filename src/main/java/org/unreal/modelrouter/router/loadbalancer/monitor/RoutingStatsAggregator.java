package org.unreal.modelrouter.router.loadbalancer.monitor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 路由统计聚合器
 * 实时统计各服务类型的实例选中分布
 *
 * @author JAiRouter Team
 */
@Slf4j
@Component
public class RoutingStatsAggregator {

    /**
     * 按服务类型分组的统计数据
     */
    private final Map<String, ServiceRoutingStats> statsMap = new ConcurrentHashMap<>();

    /**
     * 用于更新统计的锁
     */
    private final ReentrantLock updateLock = new ReentrantLock();

    /**
     * 更新统计（收到路由事件时调用）
     */
    public void update(RoutingEvent event) {
        String serviceType = event.serviceType();

        ServiceRoutingStats stats = statsMap.computeIfAbsent(
            serviceType,
            k -> new ServiceRoutingStats(serviceType)
        );

        stats.recordSelection(
            event.selectedInstance(),
            event.clientId(),
            event.timestamp(),
            event.strategy(),
            event.modelName()
        );
    }

    /**
     * 获取指定服务类型的统计数据
     */
    public ServiceRoutingStats getStats(String serviceType) {
        return statsMap.get(serviceType);
    }

    /**
     * 获取所有服务类型的统计数据
     */
    public Map<String, ServiceRoutingStats> getAllStats() {
        return new HashMap<>(statsMap);
    }

    /**
     * 重置指定服务类型的统计数据
     */
    public void resetStats(String serviceType) {
        ServiceRoutingStats stats = statsMap.get(serviceType);
        if (stats != null) {
            stats.reset();
            log.info("Reset routing stats for service: {}", serviceType);
        }
    }

    /**
     * 重置所有统计数据
     */
    public void resetAllStats() {
        statsMap.values().forEach(ServiceRoutingStats::reset);
        log.info("Reset all routing stats");
    }

    /**
     * 获取轮转序列（round-robin策略验证用）
     */
    public List<String> getRotationSequence(String serviceType, int count) {
        ServiceRoutingStats stats = statsMap.get(serviceType);
        if (stats == null) {
            return Collections.emptyList();
        }
        return stats.getRecentSelectionSequence(count);
    }

    /**
     * 获取IP到实例的映射（ip-hash/consistent-hash验证用）
     */
    public Map<String, String> getIpToInstanceMapping(String serviceType, int limit) {
        ServiceRoutingStats stats = statsMap.get(serviceType);
        if (stats == null) {
            return Collections.emptyMap();
        }
        return stats.getClientToInstanceMapping(limit);
    }

    /**
     * 单个服务类型的路由统计
     */
    public static class ServiceRoutingStats {
        private final String serviceType;

        /**
         * 负载均衡策略
         */
        private volatile String strategy = "Unknown";

        /**
         * 实例选中次数统计
         */
        private final Map<String, AtomicLong> instanceSelectionCounts = new ConcurrentHashMap<>();

        /**
         * 按模型分组的实例选中统计
         * 结构: Map<模型名, Map<实例ID, 计数>>
         */
        private final Map<String, Map<String, AtomicLong>> modelInstanceCounts = new ConcurrentHashMap<>();

        /**
         * 最近选中的实例序列（用于验证 round-robin）
         */
        private final List<SelectionRecord> recentSelections = Collections.synchronizedList(new ArrayList<>());

        /**
         * 客户端到实例的映射（用于验证 ip-hash，issue #877 改有界缓存）
         */
        private final Cache<String, String> clientToInstanceMap = newBoundedClientCache();

        /**
         * 客户端请求计数（issue #877 改有界缓存）
         */
        private final Cache<String, AtomicLong> clientRequestCounts = newBoundedClientCache();

        /**
         * 统计开始时间
         */
        private volatile Instant startTime = Instant.now();

        /**
         * 保留最近选中的最大数量
         */
        private static final int MAX_RECENT_SELECTIONS = 100;

        /**
         * 客户端维度有界缓存的最大条目数（issue #877）
         *
         * <p>clientId 高基数且可被外部影响（每个客户端 IP 一个键），修复前是
         * 两个无界 CHM、只增不减（除 reset 外无任何清理），恶意/异常流量可持续
         * 膨胀内存。上限与 {@link org.unreal.modelrouter.router.ratelimit.ScopedRateLimiterWrapper}
         * 的 MAX_SIZE 对齐。</p>
         */
        private static final int MAX_CLIENT_ENTRIES = 10000;

        /**
         * 客户端条目不活跃过期时长（issue #877）
         *
         * <p>{@code expireAfterAccess}：长期不活跃的 ip-hash 映射会被淘汰，
         * 验证视图只呈现「最近活跃」的客户端；被淘汰后对应计数归零（下次访问重建）。</p>
         */
        private static final long CLIENT_ENTRY_TTL_MINUTES = 30;

        private static <K, V> Cache<K, V> newBoundedClientCache() {
            return Caffeine.newBuilder()
                .maximumSize(MAX_CLIENT_ENTRIES)
                .expireAfterAccess(CLIENT_ENTRY_TTL_MINUTES, TimeUnit.MINUTES)
                .build();
        }

        public ServiceRoutingStats(String serviceType) {
            this.serviceType = serviceType;
        }

        /**
         * 记录一次实例选择
         */
        public void recordSelection(
                String instance, String clientId, Instant timestamp,
                String strategyName, String modelName) {
            // 更新策略
            if (strategyName != null && !strategyName.isEmpty()) {
                this.strategy = strategyName;
            }

            // 更新实例选中计数
            instanceSelectionCounts.computeIfAbsent(instance, k -> new AtomicLong(0))
                .incrementAndGet();

            // 更新模型-实例统计
            if (modelName != null && !modelName.isEmpty()) {
                Map<String, AtomicLong> instanceCounts = modelInstanceCounts.computeIfAbsent(
                    modelName, k -> new ConcurrentHashMap<>());
                instanceCounts.computeIfAbsent(instance, k -> new AtomicLong(0))
                    .incrementAndGet();
            }

            // 记录最近选择序列
            SelectionRecord record = new SelectionRecord(instance, clientId, timestamp);
            synchronized (recentSelections) {
                recentSelections.add(record);
                while (recentSelections.size() > MAX_RECENT_SELECTIONS) {
                    recentSelections.remove(0);
                }
            }

            // 更新客户端到实例的映射（issue #877：cache.get 走 Caffeine 原生访问语义，
            // 每次记录都会刷新 expireAfterAccess 的活跃时间）
            if (clientId != null && !clientId.isEmpty()) {
                clientToInstanceMap.put(clientId, instance);
                clientRequestCounts.get(clientId, k -> new AtomicLong(0))
                    .incrementAndGet();
            }
        }

        /**
         * 获取总选择次数
         */
        public long getTotalSelections() {
            return instanceSelectionCounts.values().stream()
                .mapToLong(AtomicLong::get)
                .sum();
        }

        /**
         * 获取负载均衡策略
         */
        public String getStrategy() {
            return strategy;
        }

        /**
         * 获取实例选中分布（前端使用 instanceCounts）
         */
        @JsonProperty("instanceCounts")
        public Map<String, Long> getInstanceDistribution() {
            Map<String, Long> distribution = new HashMap<>();
            instanceSelectionCounts.forEach((instance, count) -> {
                distribution.put(instance, count.get());
            });
            return distribution;
        }

        /**
         * 获取按模型分组的实例分布（前端使用 modelInstanceCounts）
         * 结构: Map<模型名, Map<实例ID, 计数>>
         */
        @JsonProperty("modelInstanceCounts")
        public Map<String, Map<String, Long>> getModelInstanceDistribution() {
            Map<String, Map<String, Long>> result = new HashMap<>();
            modelInstanceCounts.forEach((modelName, instanceMap) -> {
                Map<String, Long> instanceCounts = new HashMap<>();
                instanceMap.forEach((instance, count) -> {
                    instanceCounts.put(instance, count.get());
                });
                result.put(modelName, instanceCounts);
            });
            return result;
        }

        /**
         * 获取实例选中百分比
         */
        public Map<String, Double> getInstanceDistributionPercent() {
            long total = getTotalSelections();
            Map<String, Double> percent = new HashMap<>();
            if (total > 0) {
                instanceSelectionCounts.forEach((instance, count) -> {
                    percent.put(instance, (count.get() * 100.0) / total);
                });
            }
            return percent;
        }

        /**
         * 获取最近选中的实例序列（用于 JSON 序列化）
         */
        @JsonProperty("recentSelections")
        public List<String> getRecentSelectionsForJson() {
            return getRecentSelectionSequence(MAX_RECENT_SELECTIONS);
        }

        /**
         * 获取最近选中的实例序列
         */
        public List<String> getRecentSelectionSequence(int count) {
            List<String> sequence = new ArrayList<>();
            synchronized (recentSelections) {
                int start = Math.max(0, recentSelections.size() - count);
                for (int i = start; i < recentSelections.size(); i++) {
                    sequence.add(recentSelections.get(i).instance());
                }
            }
            return sequence;
        }

        /**
         * 获取客户端到实例的映射（用于 JSON 序列化）
         */
        @JsonProperty("clientInstanceMap")
        public Map<String, String> getClientInstanceMapForJson() {
            return getClientToInstanceMapping(100);
        }

        /**
         * 获取客户端到实例的映射
         */
        public Map<String, String> getClientToInstanceMapping(int limit) {
            Map<String, String> result = new HashMap<>();
            clientToInstanceMap.asMap().entrySet().stream()
                .limit(limit)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return result;
        }

        /**
         * 获取统计时长（秒）
         */
        public long getDurationSeconds() {
            return Duration.between(startTime, Instant.now()).getSeconds();
        }

        /**
         * 获取每秒请求数
         */
        public double getRequestsPerSecond() {
            long seconds = getDurationSeconds();
            if (seconds > 0) {
                return (double) getTotalSelections() / seconds;
            }
            return 0.0;
        }

        /**
         * 重置统计
         */
        public void reset() {
            instanceSelectionCounts.clear();
            modelInstanceCounts.clear();
            synchronized (recentSelections) {
                recentSelections.clear();
            }
            clientToInstanceMap.invalidateAll();
            clientRequestCounts.invalidateAll();
            startTime = Instant.now();
        }

        /**
         * 当前客户端维度条目数（监控/测试用，issue #877 有界缓存下不应无界增长）
         */
        long clientEntryCount() {
            return clientToInstanceMap.estimatedSize();
        }

        /** 强制执行过期/淘汰维护（测试用） */
        void cleanUpClientCaches() {
            clientToInstanceMap.cleanUp();
            clientRequestCounts.cleanUp();
        }

        /**
         * 选择记录
         */
        private record SelectionRecord(String instance, String clientId, Instant timestamp) {}
    }
}
