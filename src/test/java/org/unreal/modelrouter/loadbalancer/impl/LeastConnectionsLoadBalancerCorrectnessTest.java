package org.unreal.modelrouter.loadbalancer.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.router.loadbalancer.impl.LeastConnectionsLoadBalancer;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #655 + #1085：最少连接 LB 的内部正确性。
 *
 * <p>用反射直接读 {@code connectionCounts}，因为对外没有计数查询接口；
 * 断言的是可观测的在途计数，而不是日志或选择结果的间接推测。</p>
 */
@DisplayName("LeastConnectionsLoadBalancer 内部正确性（#655/#1085）")
class LeastConnectionsLoadBalancerCorrectnessTest {

    private LeastConnectionsLoadBalancer lb;
    private List<ModelRouterProperties.ModelInstance> instances;

    @BeforeEach
    void setUp() {
        lb = new LeastConnectionsLoadBalancer();
        instances = new ArrayList<>();
        instances.add(instance("A"));
        instances.add(instance("B"));
        instances.add(instance("C"));
    }

    private static ModelRouterProperties.ModelInstance instance(final String name) {
        ModelRouterProperties.ModelInstance i = new ModelRouterProperties.ModelInstance();
        i.setId(name);
        i.setName(name);
        i.setBaseUrl("http://" + name.toLowerCase() + ":8080");
        i.setPath("/api");
        i.setWeight(1);
        i.setHealthy(true);
        return i;
    }

    @SuppressWarnings("unchecked")
    private long countOf(final ModelRouterProperties.ModelInstance i) {
        Map<String, AtomicLong> counts =
                (Map<String, AtomicLong>) ReflectionTestUtils.getField(lb, "connectionCounts");
        AtomicLong value = counts.get(i.getBaseUrl() + ":" + i.getPath());
        return value == null ? 0 : value.get();
    }

    private String select() {
        return lb.selectInstance(instances, "127.0.0.1", "chat").getName();
    }

    // ---------- #655 失败计数释放 ----------

    @Test
    @DisplayName("recordCall → recordCallFailure 后计数归零（反证：修复前保持 1）")
    void failureReleasesInFlightCount() {
        ModelRouterProperties.ModelInstance a = instances.get(0);
        lb.recordCall(a);
        assertEquals(1, countOf(a));

        lb.recordCallFailure(a);

        assertEquals(0, countOf(a), "失败路径必须释放在途计数，否则实例被永久排除");
    }

    @Test
    @DisplayName("带时长/错误码的 4 参失败重载经接口默认委托同样释放")
    void failureWithDurationReleases() {
        ModelRouterProperties.ModelInstance a = instances.get(0);
        lb.recordCall(a);
        lb.recordCall(a);

        lb.recordCallFailure(a, 120L, "503");

        assertEquals(1, countOf(a));
    }

    @Test
    @DisplayName("客户端 4xx 的 3 参完成回调（#647 链路）也释放在途")
    void clientErrorCompleteReleases() {
        ModelRouterProperties.ModelInstance a = instances.get(0);
        lb.recordCall(a);

        lb.recordCallComplete(a, 120L, false);

        assertEquals(0, countOf(a));
    }

    @Test
    @DisplayName("重复释放钳制在 0，绝不把计数减成负数")
    void repeatedReleaseNeverNegative() {
        ModelRouterProperties.ModelInstance a = instances.get(0);
        lb.recordCall(a);

        lb.recordCallComplete(a);
        lb.recordCallComplete(a);
        lb.recordCallFailure(a);

        assertEquals(0, countOf(a), "上层统计/调用历史双回调时，钳制可避免负值污染选择");
    }

    @Test
    @DisplayName("失败过的实例重新参与选择（反证：修复前恒被判最忙）")
    void failedInstanceRejoinsSelection() {
        lb.recordCall(instances.get(0));
        lb.recordCallFailure(instances.get(0));
        lb.recordCall(instances.get(1));
        lb.recordCallComplete(instances.get(1));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            seen.add(select());
        }
        assertTrue(seen.contains("A"), "A 计数已归零，应重新进入并列候选");
        assertTrue(seen.contains("B"), "B 计数已归零，应重新进入并列候选");
    }

    @Test
    @DisplayName("1000 次并发混合释放后计数恰为 0")
    void concurrentMixedReleaseEndsAtZero() throws InterruptedException {
        ModelRouterProperties.ModelInstance a = instances.get(0);
        int total = 1000;
        for (int i = 0; i < total; i++) {
            lb.recordCall(a);
        }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(total);
        try {
            for (int i = 0; i < total; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        if (index % 2 == 0) {
                            lb.recordCallComplete(a);
                        } else {
                            lb.recordCallFailure(a);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发释放应在超时前完成");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, countOf(a));
    }

    // ---------- #1085 并列轮转 ----------

    @Test
    @DisplayName("3 实例完全并列时按 A,B,C,A,B,C 轮转（反证：修复前恒 A）")
    void tiedInstancesRotate() {
        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sequence.add(select());
        }

        assertEquals(List.of("A", "B", "C", "A", "B", "C"), sequence);
    }

    @Test
    @DisplayName("存在更优候选时只在同分集合内轮转，不破坏最少连接语义")
    void rotationRespectsBetterCandidate() {
        lb.recordCall(instances.get(0)); // A 在途 1，B/C 为 0

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            seen.add(select());
        }

        assertFalse(seen.contains("A"), "A 仍有在途连接，不应被选中");
        assertEquals(Set.of("B", "C"), seen, "轮转只发生在同分的 B/C 之间");

        lb.recordCallComplete(instances.get(0));
        Set<String> afterRelease = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            afterRelease.add(select());
        }
        assertEquals(Set.of("A", "B", "C"), afterRelease, "A 释放后重回并列集并参与轮转");
    }

    @Test
    @DisplayName("1000 次并发并列选择分布偏差 <5%")
    void concurrentTieDistributionIsUniform() throws InterruptedException {
        int threads = 10;
        int perThread = 100;
        int total = threads * perThread;
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            hits.computeIfAbsent(select(), k -> new AtomicInteger()).incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发选择应在超时前完成");
        } finally {
            pool.shutdownNow();
        }

        double expected = total / 3.0;
        for (String name : List.of("A", "B", "C")) {
            int count = hits.getOrDefault(name, new AtomicInteger()).get();
            assertTrue(Math.abs(count - expected) / expected < 0.05,
                    name + " 命中 " + count + "，偏离均匀值 " + expected + " 超过 5%");
        }
    }

    @Test
    @DisplayName("轮转游标溢出后 floorMod 仍返回合法下标")
    void tieCursorOverflowStaysNonNegative() {
        AtomicLong cursor = (AtomicLong) ReflectionTestUtils.getField(lb, "tieCursor");
        assertNotNull(cursor);
        cursor.set(Long.MAX_VALUE);

        for (int i = 0; i < 6; i++) {
            assertTrue(Set.of("A", "B", "C").contains(select()),
                    "AtomicLong 溢出到负值时不能用 % 取下标");
        }
    }
}
