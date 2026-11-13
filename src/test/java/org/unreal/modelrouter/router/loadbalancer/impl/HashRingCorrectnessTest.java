/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.loadbalancer.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #687/#691/#692 哈希环三兄弟反证测试。
 *
 * <p>修复前：共享可变 TreeMap 每请求增删（并发 CME/环损坏）、{@code null#i}
 * 虚拟节点键互相覆盖、IpHash 每请求重建环且 {@code Math.abs(Long.MIN_VALUE)} 为负。
 * 本类断言只读快照、指纹复用、键解析兜底与并发安全。</p>
 */
@DisplayName("哈希环正确性 #687/#691/#692")
class HashRingCorrectnessTest {

    private static final int VNODES = 20;

    @Test
    @DisplayName("#687 ConsistentHash 发布不可变环快照")
    void consistentHashPublishesImmutableSnapshot() {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer(VNODES);
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 3);

        assertNotNullSelected(lb.selectInstance(instances, "10.0.0.1", "chat"), instances);
        assertEquals(1L, lb.ringBuildCount(), "首次选择应只构建一次环");
        assertThrows(UnsupportedOperationException.class,
                () -> lb.ringSnapshot().put(0L, instances.get(0)),
                "环必须只读，杜绝任何原地增删");
    }

    @Test
    @DisplayName("#687 指纹不变时跨请求复用环（buildRing 仅 1 次）")
    void consistentHashReusesRingWhenFingerprintUnchanged() {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer(VNODES);
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 5);

        for (int i = 0; i < 200; i++) {
            assertNotNullSelected(lb.selectInstance(instances, "10.0.0." + i, "chat"), instances);
        }
        assertEquals(1L, lb.ringBuildCount());
    }

    @Test
    @DisplayName("#691 两个 instanceId=null 的实例各占满自己的虚拟节点")
    void nullInstanceIdProducesDistinctVirtualNodes() {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer(VNODES);
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 2);
        // 两个实例均未配置 instanceId，修复前键恒为 "null#i" → 后者覆盖前者
        assertTrue(instances.stream().allMatch(i -> i.getInstanceId() == null));

        lb.selectInstance(instances, "10.0.0.1", "chat");
        assertEquals(2 * VNODES, lb.ringSnapshot().size(), "两实例的虚拟节点不得互相覆盖");

        Set<String> names = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            names.add(lb.selectInstance(instances, "192.168.1." + i, "chat").getName());
        }
        assertEquals(2, names.size(), "两个实例都必须能被选中（反证修复前只剩 1 个）");
    }

    @Test
    @DisplayName("#687 权重/地址变化触发重建并原子发布新快照")
    void weightAndBaseUrlChangesRebuildConsistentHashRing() {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer(VNODES);
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 3);

        lb.selectInstance(instances, "10.0.0.1", "chat");
        Object before = lb.ringSnapshot();
        assertEquals(1L, lb.ringBuildCount());

        instances.get(0).setWeight(7);
        lb.selectInstance(instances, "10.0.0.1", "chat");
        assertEquals(2L, lb.ringBuildCount(), "权重纳入指纹");
        assertNotSame(before, lb.ringSnapshot());

        instances.get(0).setBaseUrl("http://changed:8080");
        lb.selectInstance(instances, "10.0.0.1", "chat");
        assertEquals(3L, lb.ringBuildCount(), "地址变化纳入指纹");
    }

    @Test
    @DisplayName("#687 并发切换实例列表不产生异常/环损坏")
    void concurrentListSwitchNeverCorruptsConsistentHashRing() throws Exception {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer(VNODES);
        List<ModelRouterProperties.ModelInstance> listA = instances("a", 3);
        List<ModelRouterProperties.ModelInstance> listB = instances("b", 3);
        Set<String> allowedNames = new HashSet<>();
        listA.forEach(i -> allowedNames.add(i.getName()));
        listB.forEach(i -> allowedNames.add(i.getName()));

        int threads = 12;
        int perThread = 300;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger invalid = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                final List<ModelRouterProperties.ModelInstance> list = t % 2 == 0 ? listA : listB;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < perThread; j++) {
                            ModelRouterProperties.ModelInstance selected =
                                    lb.selectInstance(list, "10.0." + threadId + "." + j, "chat");
                            if (selected == null || !allowedNames.contains(selected.getName())) {
                                invalid.incrementAndGet();
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    }
                });
            }
        } finally {
            executor.shutdown();
        }
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");
        if (error.get() != null) {
            throw new AssertionError("并发选择抛出异常（修复前常见 CME）", error.get());
        }
        assertEquals(0, invalid.get(), "重建瞬间允许旧快照，但选中实例必须恒属输入并集");
        assertTrue(lb.ringBuildCount() >= 2, "两种列表指纹都应至少各构建一次环");
    }

    @Test
    @DisplayName("#692 IpHash 环不可变且跨请求复用")
    void ipHashReusesImmutableRing() {
        IpHashLoadBalancer lb = new IpHashLoadBalancer("md5");
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 3);

        for (int i = 0; i < 100; i++) {
            assertNotNullSelected(lb.selectInstance(instances, "10.0.0." + i, "chat"), instances);
        }
        assertEquals(1L, lb.ringBuildCount(), "修复前每请求都 new TreeMap，无法复用");
        assertThrows(UnsupportedOperationException.class,
                () -> lb.ringSnapshot().put(0L, instances.get(0)));
    }

    @Test
    @DisplayName("#692 权重变化重建 IpHash 环并改变节点数")
    void ipHashWeightChangeRebuildsRing() {
        IpHashLoadBalancer lb = new IpHashLoadBalancer("md5");
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 2);

        lb.selectInstance(instances, "10.0.0.1", "chat");
        int before = lb.ringSnapshot().size();
        instances.get(0).setWeight(5);
        lb.selectInstance(instances, "10.0.0.1", "chat");

        assertEquals(2L, lb.ringBuildCount());
        assertTrue(lb.ringSnapshot().size() > before, "高权重实例应生成更多虚拟节点");
    }

    @Test
    @DisplayName("#691 IpHash 空 instance-id 的实例键不塌缩")
    void ipHashNullInstanceIdKeepsBothInstances() {
        IpHashLoadBalancer lb = new IpHashLoadBalancer("md5");
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 2);

        lb.selectInstance(instances, "10.0.0.1", "chat");
        Set<String> names = new HashSet<>();
        lb.ringSnapshot().values().forEach(i -> names.add(i.getName()));
        assertEquals(2, names.size(), "两个实例都必须出现在环上");
    }

    @Test
    @DisplayName("#692 环键恒非负，Long.MIN_VALUE 不再溢出为负")
    void ringKeysAreNeverNegative() {
        assertEquals(0L, IpHashLoadBalancer.toRingKey(Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, IpHashLoadBalancer.toRingKey(-1L));
        assertEquals(42L, IpHashLoadBalancer.toRingKey(42L));

        IpHashLoadBalancer ipHash = new IpHashLoadBalancer("md5");
        ipHash.selectInstance(instances("a", 3), "10.0.0.1", "chat");
        ipHash.ringSnapshot().keySet().forEach(k -> assertTrue(k >= 0));

        ConsistentHashLoadBalancer consistent = new ConsistentHashLoadBalancer(VNODES);
        consistent.selectInstance(instances("a", 3), "10.0.0.1", "chat");
        consistent.ringSnapshot().keySet().forEach(k -> assertTrue(k >= 0));
    }

    @Test
    @DisplayName("#692 不同哈希算法各自维护独立环")
    void differentAlgorithmsBuildIndependentRings() {
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 3);
        IpHashLoadBalancer md5 = new IpHashLoadBalancer("md5");
        IpHashLoadBalancer sha256 = new IpHashLoadBalancer("sha256");

        md5.selectInstance(instances, "10.0.0.1", "chat");
        sha256.selectInstance(instances, "10.0.0.1", "chat");

        assertEquals(1L, md5.ringBuildCount());
        assertEquals(1L, sha256.ringBuildCount());
        assertNotEquals(md5.ringSnapshot().keySet(), sha256.ringSnapshot().keySet());
    }

    @Test
    @DisplayName("#687 Sticky 接入公共指纹后权重变化仍重建且环只读")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void stickyRingRebuildsWhenWeightChanges() throws Exception {
        LoadBalancer delegate = new LoadBalancer() {
            @Override
            public ModelRouterProperties.ModelInstance selectInstance(
                    final List<ModelRouterProperties.ModelInstance> items, final String ip) {
                return items.get(0);
            }
        };
        StickyLoadBalancer lb = new StickyLoadBalancer(delegate, null, null, VNODES);
        List<ModelRouterProperties.ModelInstance> instances = instances("a", 3);
        Field field = StickyLoadBalancer.class.getDeclaredField("hashCircle");
        field.setAccessible(true);

        lb.selectInstance(instances, "tenant|chat|model", "10.0.0.1", "chat");
        Object before = field.get(lb);
        Map raw = (Map) before;
        assertThrows(UnsupportedOperationException.class, () -> raw.put(0L, instances.get(0)));

        instances.get(0).setWeight(7);
        lb.selectInstance(instances, "tenant|chat|model", "10.0.0.1", "chat");
        assertNotSame(before, field.get(lb), "旧指纹只看 id/name，权重变化不会重建");
    }

    private void assertNotNullSelected(final ModelRouterProperties.ModelInstance selected,
                                       final List<ModelRouterProperties.ModelInstance> instances) {
        assertTrue(instances.contains(selected), "选中实例必须属于输入列表");
    }

    private List<ModelRouterProperties.ModelInstance> instances(final String prefix, final int count) {
        List<ModelRouterProperties.ModelInstance> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ModelRouterProperties.ModelInstance item = new ModelRouterProperties.ModelInstance();
            item.setName(prefix + "-" + i);
            item.setBaseUrl("http://" + prefix + i + ":8080");
            item.setPath("/v1/chat");
            item.setWeight(1);
            item.setHealthy(true);
            list.add(item);
        }
        return list;
    }
}
