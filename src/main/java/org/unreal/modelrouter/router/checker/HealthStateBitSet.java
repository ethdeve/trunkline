package org.unreal.modelrouter.router.checker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 健康状态 BitSet 缓存
 *
 * 使用 BitSet 替代 Map<String, Boolean> 存储健康状态：
 * - 内存占用降低：每实例仅需 1 bit（vs Boolean 对象 ~16 bytes）
 * - 查询延迟：<0.01ms（位运算 vs Map 查找）
 * - 支持 O(1) 批量操作
 *
 * @author JAiRouter Team
 * @since v2.7.11
 */
public final class HealthStateBitSet {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthStateBitSet.class);

    // 实例ID -> 位索引映射
    private final Map<String, Integer> instanceIndexMap = new ConcurrentHashMap<>();

    // 下一个可用索引
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    // 健康状态 BitSet（true = 健康，false = 不健康）
    private volatile BitSet healthBits = new BitSet(1024);

    // 已知状态 BitSet（true = 已检查，false = 未检查）
    private volatile BitSet knownBits = new BitSet(1024);

    // 最大实例数
    private static final int MAX_INSTANCES = 10000;

    /**
     * 设置实例健康状态
     *
     * @param instanceKey 实例键（格式：serviceType:instanceId）
     * @param healthy 是否健康
     */
    public void setHealth(final String instanceKey, final boolean healthy) {
        int index = getOrCreateIndex(instanceKey);
        if (index >= 0 && index < MAX_INSTANCES) {
            synchronized (this) {
                healthBits.set(index, healthy);
                knownBits.set(index, true);
            }
            LOGGER.debug("Set health status: {} = {}", instanceKey, healthy);
        }
    }

    /**
     * 获取实例健康状态
     *
     * @param instanceKey 实例键
     * @return 健康状态，如果未检查过则返回 null
     */
    public Boolean getHealth(final String instanceKey) {
        Integer index = instanceIndexMap.get(instanceKey);
        if (index == null) {
            return null; // 未知实例
        }

        BitSet currentKnown = knownBits; // volatile read
        if (!currentKnown.get(index)) {
            return null; // 未检查过
        }

        BitSet currentHealth = healthBits; // volatile read
        return currentHealth.get(index);
    }

    /**
     * 检查实例是否健康
     *
     * @param instanceKey 实例键
     * @param defaultValue 默认值（实例不存在或未检查时）
     * @return 是否健康
     */
    public boolean isHealthy(final String instanceKey, final boolean defaultValue) {
        Boolean health = getHealth(instanceKey);
        return health != null ? health : defaultValue;
    }

    /**
     * 移除实例
     *
     * @param instanceKey 实例键
     */
    public void removeInstance(final String instanceKey) {
        Integer index = instanceIndexMap.remove(instanceKey);
        if (index != null) {
            synchronized (this) {
                healthBits.clear(index);
                knownBits.clear(index);
            }
            LOGGER.debug("Removed instance: {}", instanceKey);
        }
    }

    /**
     * 重置实例的健康判定，但保留其位索引。
     *
     * <p>与 {@link #removeInstance(String)} 的区别：后者会把 {@code instanceIndexMap}
     * 里的索引一并删除，被删的位槽<b>永远不会被回收复用</b>——频繁增删实例会不断消耗
     * 位槽，直到触顶 {@code MAX_INSTANCES} 后新实例的健康状态再也记不下
     * （被静默当作健康）。</p>
     *
     * <p>本方法只清掉「健康 / 已知」两个标志位、保留索引：重置后
     * {@link #getHealth(String)} 返回 {@code null}（未检查过），
     * {@link #isHealthy(String, boolean)} 回落到调用方给定的默认值。
     * 用于「实例配置变了（如换了上游地址），旧的探活结论已不适用」的场景。</p>
     *
     * @param instanceKey 实例键
     * @since 2026-10-27
     */
    public void clearHealth(final String instanceKey) {
        Integer index = instanceIndexMap.get(instanceKey);
        if (index == null) {
            return;
        }
        synchronized (this) {
            healthBits.clear(index);
            knownBits.clear(index);
        }
        LOGGER.debug("Cleared health state (index kept): {}", instanceKey);
    }

    /**
     * 获取健康实例数量
     */
    public int getHealthyCount() {
        return healthBits.cardinality();
    }

    /**
     * 获取已知实例数量
     */
    public int getKnownCount() {
        return knownBits.cardinality();
    }

    /**
     * 清空所有状态
     */
    public void clear() {
        synchronized (this) {
            healthBits.clear();
            knownBits.clear();
        }
        instanceIndexMap.clear();
        nextIndex.set(0);
        LOGGER.info("HealthStateBitSet cleared");
    }

    /**
     * 获取统计信息
     */
    public Stats getStats() {
        return new Stats(
                instanceIndexMap.size(),
                getHealthyCount(),
                getKnownCount()
        );
    }

    /**
     * 获取或创建实例索引
     */
    private int getOrCreateIndex(final String instanceKey) {
        return instanceIndexMap.computeIfAbsent(instanceKey, k -> {
            int index = nextIndex.getAndIncrement();
            if (index >= MAX_INSTANCES) {
                LOGGER.warn("Max instances reached, cannot add: {}", instanceKey);
                return -1;
            }
            return index;
        });
    }

    /**
     * 统计信息
     */
    public static final class Stats {
        private final int totalInstances;
        private final int healthyCount;
        private final int knownCount;

        public Stats(final int totalInstances, final int healthyCount, final int knownCount) {
            this.totalInstances = totalInstances;
            this.healthyCount = healthyCount;
            this.knownCount = knownCount;
        }

        public int getTotalInstances() {
            return totalInstances;
        }

        public int getHealthyCount() {
            return healthyCount;
        }

        public int getKnownCount() {
            return knownCount;
        }

        public int getUnhealthyCount() {
            return knownCount - healthyCount;
        }

        @Override
        public String toString() {
            return String.format("Stats{total=%d, healthy=%d, unhealthy=%d, unknown=%d}",
                    totalInstances, healthyCount, getUnhealthyCount(), totalInstances - knownCount);
        }
    }
}
