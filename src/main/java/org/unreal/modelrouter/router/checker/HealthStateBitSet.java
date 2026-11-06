package org.unreal.modelrouter.router.checker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.HashMap;
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
 * <p><b>R8 线程安全口径（2026-10-28）：</b>写路径采用<b>写时复制（COW）</b>——
 * 在锁内克隆 BitSet、改位、整体换引用；读路径无锁、只做 volatile 读。
 * 原实现「volatile 引用 + 锁内原地改位」是错的：引用创建后从不更换，
 * volatile 形同虚设，无锁读者可能读到半更新的字（BitSet 非线程安全）。</p>
 *
 * <p><b>超限语义：</b>位槽耗尽（&gt;{@value #MAX_INSTANCES}）时<b>拒绝写入并告警</b>，
 * 不再把哨兵值 -1 存进索引表——否则 {@link #getHealth(String)} 会拿 -1 去查
 * BitSet 触发数组越界。</p>
 *
 * @author JAiRouter Team
 * @since v2.7.11
 * @modified Trunkline Team
 */
public final class HealthStateBitSet {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthStateBitSet.class);

    // 实例ID -> 位索引映射
    private final Map<String, Integer> instanceIndexMap = new ConcurrentHashMap<>();

    // 下一个可用索引
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    // 健康状态 BitSet（true = 健康，false = 不健康）；COW：只在锁内整体换引用
    private volatile BitSet healthBits = new BitSet(1024);

    // 已知状态 BitSet（true = 已检查，false = 未检查）；COW：只在锁内整体换引用
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
        if (index < 0) {
            return; // 位槽耗尽，getOrCreateIndex 已告警
        }
        synchronized (this) {
            BitSet newHealth = (BitSet) healthBits.clone();
            newHealth.set(index, healthy);
            BitSet newKnown = (BitSet) knownBits.clone();
            newKnown.set(index, true);
            healthBits = newHealth;
            knownBits = newKnown;
        }
        LOGGER.debug("Set health status: {} = {}", instanceKey, healthy);
    }

    /**
     * 获取实例健康状态
     *
     * @param instanceKey 实例键
     * @return 健康状态，如果未检查过则返回 null
     */
    public Boolean getHealth(final String instanceKey) {
        Integer index = instanceIndexMap.get(instanceKey);
        // 越界防护：索引表残留的非法值（如历史哨兵 -1）一律视为未知，绝不用于查 BitSet
        if (index == null || index < 0 || index >= MAX_INSTANCES) {
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
            clearBits(index);
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
        clearBits(index);
        LOGGER.debug("Cleared health state (index kept): {}", instanceKey);
    }

    /**
     * 锁内清掉指定索引的「健康 / 已知」位（COW 换引用）
     */
    private void clearBits(final int index) {
        synchronized (this) {
            BitSet newHealth = (BitSet) healthBits.clone();
            newHealth.clear(index);
            BitSet newKnown = (BitSet) knownBits.clone();
            newKnown.clear(index);
            healthBits = newHealth;
            knownBits = newKnown;
        }
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
     *
     * <p>R8：索引表清空与索引归零<b>必须在锁内</b>与位图清空原子完成——
     * 否则并发写线程可能刚拿到新索引、位图就被清空，出现「有索引无状态」的漂移。</p>
     */
    public void clear() {
        synchronized (this) {
            healthBits = new BitSet(1024);
            knownBits = new BitSet(1024);
            instanceIndexMap.clear();
            nextIndex.set(0);
        }
        LOGGER.info("HealthStateBitSet cleared");
    }

    /**
     * 快照所有「已知」实例的健康状态（R8：替代遗留双存储 Map 的读视图）。
     *
     * <p>只包含 {@code knownBits} 为 true 的键——「重置后未知」的实例不出现，
     * 与原 Map「清除即移除键」的语义一致。返回的是某一时刻的一致性快照。</p>
     *
     * @return 实例键 -> 健康状态
     * @since 2026-10-28
     */
    public Map<String, Boolean> snapshotKnownStates() {
        BitSet currentKnown = knownBits;
        BitSet currentHealth = healthBits;
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<String, Integer> entry : instanceIndexMap.entrySet()) {
            int index = entry.getValue();
            if (index >= 0 && index < MAX_INSTANCES && currentKnown.get(index)) {
                result.put(entry.getKey(), currentHealth.get(index));
            }
        }
        return result;
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
     *
     * <p>R8：位槽耗尽时<b>拒绝写入</b>（不往索引表塞哨兵 -1），调用方拿到 -1 直接放弃，
     * {@link #getHealth(String)} 对查不到的键返回未知。</p>
     */
    private int getOrCreateIndex(final String instanceKey) {
        Integer existing = instanceIndexMap.get(instanceKey);
        if (existing != null) {
            return existing;
        }
        int index = nextIndex.getAndIncrement();
        if (index >= MAX_INSTANCES) {
            LOGGER.warn("HealthStateBitSet 位槽耗尽（上限 {}），拒绝记录实例健康状态: {}",
                    MAX_INSTANCES, instanceKey);
            return -1;
        }
        Integer raced = instanceIndexMap.putIfAbsent(instanceKey, index);
        return raced != null ? raced : index;
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
