package org.unreal.modelrouter.router.loadbalancer.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.RingFingerprint;
import org.unreal.modelrouter.router.model.InstanceKeys;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP Hash 负载均衡策略
 *
 * <p>#692: 哈希环改为只读不可变快照并按指纹复用，不再每次 select 都新建
 * TreeMap 逐节点 put（原实现每请求 O(instances × weight × 100) 次哈希）。
 * #691: 虚拟节点键走 {@link InstanceKeys#resolveParts} 兜底，避免空 instance-id
 * 的实例共享 {@code null:null#i} 互相覆盖。</p>
 */
public class IpHashLoadBalancer implements LoadBalancer {
    private static final Logger logger = LoggerFactory.getLogger(IpHashLoadBalancer.class);
    private static final int NODES_PER_WEIGHT = 100;

    private final String hashAlgorithm;

    // R2: 由 ComponentFactory 在创建时注入(原 @Autowired 字段在 new 出的实例上恒为 null)
    private final MetricsCollector metricsCollector;
    // B1: 单一熔断注册表，回退 random 时透传
    private final CircuitBreakerManager circuitBreakerManager;

    // #692: 只读环快照 + 指纹；volatile 原子交换，读线程零同步
    private volatile NavigableMap<Long, ModelRouterProperties.ModelInstance> ring =
            Collections.unmodifiableNavigableMap(new TreeMap<>());
    private volatile String cachedFingerprint = "";
    private final AtomicLong ringBuildCount = new AtomicLong();

    public IpHashLoadBalancer(final String hashAlgorithm) {
        this(hashAlgorithm, null, null);
    }

    public IpHashLoadBalancer(final String hashAlgorithm, final MetricsCollector metricsCollector) {
        this(hashAlgorithm, metricsCollector, null);
    }

    public IpHashLoadBalancer(final String hashAlgorithm, final MetricsCollector metricsCollector,
                              final CircuitBreakerManager circuitBreakerManager) {
        this.hashAlgorithm = hashAlgorithm != null ? hashAlgorithm : "md5";
        this.metricsCollector = metricsCollector;
        this.circuitBreakerManager = circuitBreakerManager;
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
            logger.warn("No instances available for IP hash selection");
            throw new IllegalArgumentException("No instances available");
        }

        if (clientIp == null || clientIp.trim().isEmpty()) {
            // 回退到随机意味着「粘性」名存实亡：同一客户端不再稳定命中同一实例。
            // 保留回退（取不到客户端标识就无法选实例），但必须显式可见——日志 + 指标，
            // 否则集群 + 无反代场景下取不到真实客户端 IP 时会静默失去亲和性（#161）。
            logger.warn("No client IP provided, falling back to random selection — "
                    + "ip-hash affinity is NOT effective for this request");
            if (metricsCollector != null) {
                metricsCollector.recordLoadBalancer(serviceType, "ip-hash",
                        "random-fallback-no-client-ip");
            }
            return new RandomLoadBalancer(this.metricsCollector, this.circuitBreakerManager)
                    .selectInstance(instances, clientIp, serviceType);
        }

        // 使用一致性哈希算法
        long hash = calculateHash(clientIp);
        logger.debug("Calculated hash {} for client IP {}", hash, clientIp);

        // #692: 复用只读环快照，指纹变化才全量重建
        NavigableMap<Long, ModelRouterProperties.ModelInstance> ringSnapshot =
                getOrRebuildRing(instances);

        // 找到第一个大于等于hash值的节点
        Map.Entry<Long, ModelRouterProperties.ModelInstance> entry = ringSnapshot.ceilingEntry(hash);
        if (entry == null) {
            // 如果没有找到，使用第一个节点
            entry = ringSnapshot.firstEntry();
        }

        logger.debug("Selected instance {} using IP hash strategy for client IP {} and service {}",
                entry.getValue().getName(), clientIp, serviceType);
        recordLoadBalancerSelection(serviceType, "ip_hash", entry.getValue().getName());
        return entry.getValue();
    }

    /**
     * 获取(可能已缓存的)只读环快照。仅当指纹变化时才 synchronized 双重检查并全量重建。
     */
    private NavigableMap<Long, ModelRouterProperties.ModelInstance> getOrRebuildRing(
            final List<ModelRouterProperties.ModelInstance> instances) {
        String fingerprint = RingFingerprint.compute(instances, hashAlgorithm,
                String.valueOf(NODES_PER_WEIGHT));
        if (!fingerprint.equals(cachedFingerprint)) {
            synchronized (this) {
                // double-check: 其他线程可能已在等待期间完成了重建
                if (!fingerprint.equals(cachedFingerprint)) {
                    ring = buildRing(instances);
                    cachedFingerprint = fingerprint;
                }
            }
        }
        return ring;
    }

    /**
     * 全量构建不可变哈希环（按权重生成虚拟节点），通过 volatile 赋值原子发布。
     */
    private NavigableMap<Long, ModelRouterProperties.ModelInstance> buildRing(
            final List<ModelRouterProperties.ModelInstance> instances) {
        TreeMap<Long, ModelRouterProperties.ModelInstance> newRing = new TreeMap<>();
        for (ModelRouterProperties.ModelInstance instance : instances) {
            int weight = Math.max(1, instance.getWeight());
            // #691: 未配 instance-id 时按 baseUrl → name 兜底，键不再塌缩
            String instanceKey = InstanceKeys.resolveParts(
                    instance.getInstanceId(), instance.getBaseUrl(), instance.getName());
            for (int i = 0; i < weight * NODES_PER_WEIGHT; i++) {
                String virtualNode = instanceKey + ":" + instance.getPath() + "#" + i;
                newRing.put(calculateHash(virtualNode), instance);
            }
        }
        ringBuildCount.incrementAndGet();
        logger.debug("Rebuilt IP hash ring: {} instances, {} nodes",
                instances.size(), newRing.size());
        return Collections.unmodifiableNavigableMap(newRing);
    }

    private long calculateHash(final String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(hashAlgorithm.toUpperCase());
            byte[] digest = md.digest(input.getBytes());

            // 取前8个字节转换为long
            long hash = 0;
            for (int i = 0; i < Math.min(8, digest.length); i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }

            return toRingKey(hash);
        } catch (NoSuchAlgorithmException e) {
            logger.warn("Hash algorithm {} not available, falling back to string hash", hashAlgorithm);
            // 如果算法不支持，使用简单的字符串hash
            return toRingKey(input.hashCode());
        }
    }

    /**
     * 把任意 long 映射成非负环键。
     * #692: 原 {@code Math.abs(hash)} 在 {@link Long#MIN_VALUE} 上溢出仍为负，
     * 会让环上出现负键并破坏 {@code ceilingEntry} 的环状语义；清除符号位后
     * 全域映射非负且无折叠（Long.MIN_VALUE → 0）。
     */
    static long toRingKey(final long hash) {
        return hash & Long.MAX_VALUE;
    }

    /**
     * 包可见测试钩子：返回当前只读环快照。
     */
    NavigableMap<Long, ModelRouterProperties.ModelInstance> ringSnapshot() {
        return ring;
    }

    /**
     * 包可见测试钩子：累计的环重建次数。
     */
    long ringBuildCount() {
        return ringBuildCount.get();
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
