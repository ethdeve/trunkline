package org.unreal.modelrouter.router.loadbalancer.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 问题清单 m-09 / m-04 回归测试。
 *
 * <p>m-09：CachedWeightedSelector.invalidate(instanceId) 曾忽略入参、无条件清空全部缓存；
 * 修复后只失效包含该 instanceId 的缓存项，且用 "id:" 边界避免 "svc-1" 误命中 "svc-10"。
 * <p>m-04：MemoryStoreManager 曾以 HashMap 承载共享态，改为 ConcurrentHashMap 后并发写不丢数据/不抛异常。
 */
@DisplayName("问题清单低风险修复回归：缓存精确失效与并发容器")
class CachedWeightedSelectorInvalidateTest {

    private static ModelInstance instance(final String id, final int weight) {
        ModelInstance i = new ModelInstance();
        i.setInstanceId(id);
        i.setId(id);
        i.setWeight(weight);
        i.setBaseUrl("http://" + id + ":8080");
        return i;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cacheOf(final CachedWeightedSelector selector) throws Exception {
        Field f = CachedWeightedSelector.class.getDeclaredField("selectorCache");
        f.setAccessible(true);
        return (Map<String, ?>) f.get(selector);
    }

    @Test
    @DisplayName("m-09: invalidate 只失效目标实例所在缓存，保留其他缓存项")
    void invalidateOnlyTargetCache() throws Exception {
        CachedWeightedSelector selector = new CachedWeightedSelector();
        List<ModelInstance> groupA = Arrays.asList(instance("svc-1", 1), instance("svc-2", 2));
        List<ModelInstance> groupB = Arrays.asList(instance("other-1", 1), instance("other-2", 2));

        selector.select(groupA);
        selector.select(groupB);
        assertEquals(2, cacheOf(selector).size(), "两个不同实例组应各生成一份缓存");

        selector.invalidate("svc-1");
        assertEquals(1, cacheOf(selector).size(), "只应失效含 svc-1 的那份缓存，另一份保留");
        assertFalse(cacheOf(selector).keySet().stream().anyMatch(k -> k.startsWith("svc-1:")),
                "svc-1 所在缓存项应被移除");
    }

    @Test
    @DisplayName("m-09: instanceId 前缀边界——svc-1 不得误伤 svc-10")
    void invalidatePrefixBoundary() throws Exception {
        CachedWeightedSelector selector = new CachedWeightedSelector();
        selector.select(Arrays.asList(instance("svc-10", 1), instance("svc-11", 1)));
        assertEquals(1, cacheOf(selector).size());

        selector.invalidate("svc-1");
        assertEquals(1, cacheOf(selector).size(), "svc-1 不是 svc-10/11 的前缀段，缓存不应被清空");
    }

    @Test
    @DisplayName("m-09: 空 instanceId 被忽略，不清空缓存")
    void invalidateEmptyIdIgnored() throws Exception {
        CachedWeightedSelector selector = new CachedWeightedSelector();
        selector.select(Arrays.asList(instance("svc-1", 1), instance("svc-2", 2)));
        assertEquals(1, cacheOf(selector).size());

        selector.invalidate("");
        selector.invalidate(null);
        assertEquals(1, cacheOf(selector).size(), "空入参不应触发任何清空");
    }

    @Test
    @DisplayName("m-09: 失效后再次 select 可正确重建缓存")
    void rebuildAfterInvalidate() throws Exception {
        CachedWeightedSelector selector = new CachedWeightedSelector();
        List<ModelInstance> group = Arrays.asList(instance("svc-1", 1), instance("svc-2", 2));
        selector.select(group);
        selector.invalidate("svc-1");
        assertEquals(0, cacheOf(selector).size());

        ModelInstance picked = selector.select(group);
        assertNotNull(picked, "重建后应能正常选到实例");
        assertEquals(1, cacheOf(selector).size(), "再次选择应重建缓存");
    }

    @Test
    @DisplayName("m-04: MemoryStoreManager 并发写入不丢数据、不抛异常")
    void memoryStoreConcurrentWrites() throws Exception {
        org.unreal.modelrouter.persistence.store.MemoryStoreManager store =
                new org.unreal.modelrouter.persistence.store.MemoryStoreManager();

        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        Map<String, Object> cfg = new ConcurrentHashMap<>();
                        cfg.put("thread", tid);
                        cfg.put("seq", n);
                        store.saveConfig("key-" + tid + "-" + n, cfg);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(0, failures.get(), "并发写不应抛异常（HashMap 扩容会出错，ConcurrentHashMap 不会）");
        int count = 0;
        for (String ignored : store.getAllKeys()) {
            count++;
        }
        assertEquals(threads * perThread, count, "所有写入的键都应存在，不应丢失");
    }
}
