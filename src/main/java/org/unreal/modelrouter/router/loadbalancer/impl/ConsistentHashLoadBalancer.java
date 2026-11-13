package org.unreal.modelrouter.router.loadbalancer.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.RingFingerprint;
import org.unreal.modelrouter.router.model.InstanceKeys;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.checker.ServiceStateManager;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一致性哈希负载均衡器
 *
 * <p>使用一致性哈希算法解决 IP Hash 冲突问题，支持虚拟节点机制。
 * #687/#691: 环为只读不可变快照，按实例列表指纹变化时才全量重建并通过
 * volatile 原子交换发布；读线程零同步，杜绝 WebFlux 并发下对共享 TreeMap
 * 原地增删导致的 ConcurrentModificationException / 环损坏。虚拟节点键统一
 * 走 {@link InstanceKeys#resolve}，避免未配置 instance-id 的实例塌缩到
 * {@code null#0} 互相覆盖。</p>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
public class ConsistentHashLoadBalancer implements LoadBalancer {

    private static final Logger logger = LoggerFactory.getLogger(ConsistentHashLoadBalancer.class);

    // 默认虚拟节点数量
    private static final int DEFAULT_VIRTUAL_NODES = 150;

    // #687: 只读不可变环快照，volatile 原子交换；读线程零同步，禁止任何原地增删
    private volatile NavigableMap<Long, ModelRouterProperties.ModelInstance> hashCircle =
            Collections.unmodifiableNavigableMap(new TreeMap<>());
    // 当前指纹；仅在变化时 synchronized 双重检查后全量重建
    private volatile String cachedFingerprint = "";
    private final AtomicLong ringBuildCount = new AtomicLong();

    // 虚拟节点数量
    private final int virtualNodeCount;

    // R2: 由 ComponentFactory 在创建时注入(原 @Autowired 字段在 new 出的实例上恒为 null)
    private final MetricsCollector metricsCollector;
    private final ServiceStateManager serviceStateManager;

    // 为测试添加 getter 方法
    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public ServiceStateManager getServiceStateManager() {
        return serviceStateManager;
    }

    public ConsistentHashLoadBalancer() {
        this(DEFAULT_VIRTUAL_NODES, null, null);
    }

    public ConsistentHashLoadBalancer(final int virtualNodeCount) {
        this(virtualNodeCount, null, null);
    }

    public ConsistentHashLoadBalancer(final int virtualNodeCount,
                                      final MetricsCollector metricsCollector,
                                      final ServiceStateManager serviceStateManager) {
        this.virtualNodeCount = virtualNodeCount;
        this.metricsCollector = metricsCollector;
        this.serviceStateManager = serviceStateManager;
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
            final String clientIp,
            final String serviceType) {

        if (instances == null || instances.isEmpty()) {
            logger.warn("No instances available for consistent hash selection");
            throw new IllegalArgumentException("No instances available");
        }

        // #687: 取只读环快照；指纹不变则跨请求复用，不再逐请求原地增删
        NavigableMap<Long, ModelRouterProperties.ModelInstance> ring = getOrRebuildRing(instances);

        if (ring.isEmpty()) {
            logger.error("Hash circle is empty, unable to select instance");
            throw new RuntimeException("Hash circle is empty");
        }

        // 计算客户端IP的哈希值
        long hash = hash(clientIp);

        // 在哈希环上找到大于等于该哈希值的第一个节点
        Map.Entry<Long, ModelRouterProperties.ModelInstance> entry = ring.higherEntry(hash);

        // 如果没有找到，则使用环上的第一个节点（形成环状结构）
        if (entry == null) {
            entry = ring.firstEntry();
        }

        // 从哈希环中选择健康实例，如果当前选中的是不健康的，则查找下一个健康的
        ModelRouterProperties.ModelInstance selected = findHealthyInstance(entry, serviceType, ring);

        if (selected == null) {
            logger.warn("No healthy instance found, selecting any instance");
            // 如果找不到健康实例，尝试使用原始哈希值找到的实例
            selected = entry.getValue(); // 如果没有健康实例，返回任意实例
        }

        logger.debug("Selected instance {} using consistent hash for service {}, client IP: {}",
                selected.getName(), serviceType, clientIp);

        recordLoadBalancerSelection(serviceType, "consistent_hash", selected.getName());

        return selected;
    }

    /**
     * 从哈希环快照中查找健康实例。
     * 如果当前实例不健康，则继续查找下一个实例，直到找到健康实例或遍历完整个环。
     * #687: 环读取全部基于本次请求的局部快照，避免重建时读到被并发修改的 Map。
     *
     * @param startEntry 起始条目
     * @param serviceType 服务类型
     * @param ring 本次请求使用的只读环快照
     * @return 健康实例或 null（如果没有健康实例）
     */
    private ModelRouterProperties.ModelInstance findHealthyInstance(
            final Map.Entry<Long, ModelRouterProperties.ModelInstance> startEntry,
            final String serviceType,
            final NavigableMap<Long, ModelRouterProperties.ModelInstance> ring) {

        if (startEntry == null) {
            return null;
        }

        // 如果起始实例是健康的，直接返回
        if (isInstanceHealthy(serviceType, startEntry.getValue())) {
            return startEntry.getValue();
        }

        // 从哈希环中查找下一个健康实例
        Map.Entry<Long, ModelRouterProperties.ModelInstance> currentEntry = startEntry;
        int iterationCount = 0; // 防止无限循环
        int maxIterations = ring.size(); // 最大迭代次数为哈希环大小

        do {
            // 找到下一个条目
            currentEntry = ring.higherEntry(currentEntry.getKey());

            // 如果到达末尾，回到开头（形成环状）
            if (currentEntry == null) {
                currentEntry = ring.firstEntry();
            }

            // 检查当前实例是否健康
            if (isInstanceHealthy(serviceType, currentEntry.getValue())) {
                return currentEntry.getValue();
            }

            iterationCount++;

        } while (iterationCount < maxIterations && currentEntry != null
                && !currentEntry.getKey().equals(startEntry.getKey()));

        return null; // 没有找到健康实例
    }

    /**
     * 检查实例是否健康
     *
     * @param serviceType 服务类型
     * @param instance 实例
     * @return 是否健康
     */
    private boolean isInstanceHealthy(final String serviceType,
                                      final ModelRouterProperties.ModelInstance instance) {
        if (serviceStateManager != null) {
            return serviceStateManager.isInstanceHealthy(serviceType, instance);
        }
        // 如果没有服务状态管理器，则使用实例的健康状态字段
        return instance.isHealthy() != null ? instance.isHealthy() : true;
    }

    /**
     * 获取(可能已缓存的)只读环快照。仅当指纹变化时才 synchronized 双重检查并全量重建。
     */
    private NavigableMap<Long, ModelRouterProperties.ModelInstance> getOrRebuildRing(
            final List<ModelRouterProperties.ModelInstance> instances) {
        String fingerprint = RingFingerprint.compute(instances, "murmur3",
                String.valueOf(virtualNodeCount));
        if (!fingerprint.equals(cachedFingerprint)) {
            synchronized (this) {
                // double-check: 其他线程可能已在等待期间完成了重建
                if (!fingerprint.equals(cachedFingerprint)) {
                    hashCircle = buildRing(instances);
                    cachedFingerprint = fingerprint;
                }
            }
        }
        return hashCircle;
    }

    /**
     * 全量构建不可变哈希环，调用者通过 volatile 赋值原子发布。
     */
    private NavigableMap<Long, ModelRouterProperties.ModelInstance> buildRing(
            final List<ModelRouterProperties.ModelInstance> instances) {
        TreeMap<Long, ModelRouterProperties.ModelInstance> ring = new TreeMap<>();
        for (ModelRouterProperties.ModelInstance instance : instances) {
            // #691: id → baseUrl → name → unknown 兜底，未配 id 的实例不再共用 "null#i"
            String instanceKey = InstanceKeys.resolve(instance);
            for (int i = 0; i < virtualNodeCount; i++) {
                ring.put(hash(instanceKey + "#" + i), instance);
            }
        }
        ringBuildCount.incrementAndGet();
        logger.debug("Rebuilt consistent hash ring: {} instances, {} nodes",
                instances.size(), ring.size());
        return Collections.unmodifiableNavigableMap(ring);
    }

    /**
     * 计算字符串的哈希值（MurmurHash，与 StickyLoadBalancer 同口径）。
     *
     * @param key 输入字符串
     * @return 非负哈希值
     */
    private long hash(final String key) {
        String effectiveKey = key != null ? key : "";
        return murmurHash3(effectiveKey);
    }

    /**
     * MurmurHash3 算法实现
     *
     * @param input 输入字符串
     * @return 哈希值
     */
    private long murmurHash3(final String input) {
        byte[] data = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = data.length;
        int seed = 0xcc9e2d51;
        int hash = seed;

        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        final int r1 = 15;
        final int r2 = 13;
        final int m = 5;
        final int n = 0xe6546b64;

        int i = 0;
        while (i <= len - 4) {
            int k = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16) | (data[i + 3] << 24);
            k *= c1;
            k = (k << r1) | (k >>> (32 - r1));
            k *= c2;

            hash ^= k;
            hash = (hash << r2) | (hash >>> (32 - r2));
            hash = hash * m + n;

            i += 4;
        }

        // 处理剩余字节
        int remaining = len - i;
        if (remaining > 0) {
            int k = 0;
            if (remaining == 3) {
                k ^= data[i + 2] << 16;
            }
            if (remaining >= 2) {
                k ^= data[i + 1] << 8;
            }
            if (remaining >= 1) {
                k ^= data[i];
            }
            k *= c1;
            k = (k << r1) | (k >>> (32 - r1));
            k *= c2;
            hash ^= k;
        }

        hash ^= len;
        hash ^= (hash >>> 16);
        hash *= 0x85ebca6b;
        hash ^= (hash >>> 13);
        hash *= 0xc2b2ae35;
        hash ^= (hash >>> 16);

        // int 提升到 long，永远不可能是 Long.MIN_VALUE，Math.abs 安全
        return Math.abs((long) hash);
    }

    @Override
    public void recordCallComplete(final ModelRouterProperties.ModelInstance instance) {
        // 可在此处添加统计逻辑
    }

    @Override
    public void recordCallFailure(final ModelRouterProperties.ModelInstance instance) {
        // 可在此处添加统计逻辑
    }

    /**
     * 包可见测试钩子：返回当前只读环快照。
     */
    NavigableMap<Long, ModelRouterProperties.ModelInstance> ringSnapshot() {
        return hashCircle;
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
    private void recordLoadBalancerSelection(final String service, final String strategy,
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
