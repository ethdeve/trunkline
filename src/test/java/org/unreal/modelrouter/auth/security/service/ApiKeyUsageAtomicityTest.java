package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.UsageStatistics;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #811/#715：用量回写原子性反证测试。
 *
 * <p>反证三个历史缺陷：
 * <ul>
 *   <li>并发 {@code incrementTokens/incrementRequest} 在普通 HashMap 上丢更新；</li>
 *   <li>单 Key 用量更新走「内存缓存整表覆盖写」时，其余 Key 的变更被陈旧视图吞掉；</li>
 *   <li>高频回写必须走 {@code mutateConfig} 读改写临界区，而非 {@code saveConfig} 整表覆盖。</li>
 * </ul></p>
 */
@DisplayName("#811/#715 用量回写原子性反证")
class ApiKeyUsageAtomicityTest {

    private static final String STORE_KEY = "security.api-keys";
    private static final String KEY_A = "key-a";
    private static final String HASH_A = "hash-a";
    private static final String KEY_B = "key-b";
    private static final String HASH_B = "hash-b";
    private static final String TODAY = LocalDate.now().toString();

    /**
     * 极简内存 StoreManager：直接持有配置 Map，mutateConfig 走接口 default 实现
     * （{@code synchronized(this)} 读改写），与真实存储契约一致。
     */
    static class FakeStoreManager implements StoreManager {
        final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

        @Override
        public void saveConfig(final String key, final Map<String, Object> config) {
            if (config == null || config.isEmpty()) {
                store.remove(key);
                return;
            }
            store.put(key, new HashMap<>(config));
        }

        @Override
        public Map<String, Object> getConfig(final String key) {
            final Map<String, Object> value = store.get(key);
            return value == null ? null : new HashMap<>(value);
        }

        @Override
        public void deleteConfig(final String key) {
            store.remove(key);
        }

        @Override
        public Iterable<String> getAllKeys() {
            return store.keySet();
        }

        @Override
        public boolean exists(final String key) {
            return store.containsKey(key);
        }

        @Override
        public void updateConfig(final String key, final Map<String, Object> config) {
            saveConfig(key, config);
        }

        @Override
        public Map<String, Object> getLatestConfig(final String configKey) {
            return getConfig(configKey);
        }
    }

    private ObjectMapper objectMapper;
    private FakeStoreManager storeManager;
    private ApiKeyService service;

    private void setUpService() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        storeManager = new FakeStoreManager();
        service = new ApiKeyService(storeManager, objectMapper, new SecurityProperties());
        ReflectionTestUtils.setField(service, "apiKeyPersistenceService",
                new ApiKeyPersistenceService(storeManager, objectMapper));
    }

    private UsageStatistics usageOf(final long todayTokens) {
        final UsageStatistics usage = UsageStatistics.builder()
                .dailyUsage(new HashMap<>())
                .dailyTokenUsage(new HashMap<>())
                .hourlyUsage(new HashMap<>())
                .hourlyTokenUsage(new HashMap<>())
                .monthlyUsage(new HashMap<>())
                .monthlyTokenUsage(new HashMap<>())
                .totalRequests(0L).successfulRequests(0L).failedRequests(0L)
                .build();
        if (todayTokens > 0) {
            usage.incrementTokens(todayTokens);
        }
        return usage;
    }

    private ApiKey cacheKeyOf(final String keyId, final String keyHash, final long todayTokens) {
        return ApiKey.builder()
                .keyId(keyId).keyHash(keyHash).enabled(true)
                .usage(usageOf(todayTokens))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<ApiKey> storedKeys() {
        final Map<String, Object> config = storeManager.getConfig(STORE_KEY);
        return (List<ApiKey>) config.get(ApiKeyPersistenceService.STORE_API_KEYS);
    }

    private ApiKey findByHash(final List<ApiKey> keys, final String keyHash) {
        return keys.stream()
                .filter(k -> keyHash.equals(k.getKeyHash()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("存储中缺少 keyHash=" + keyHash));
    }

    private void seedStore(final ApiKey storeCopyA, final ApiKey storeCopyB) {
        final Map<String, Object> config = new HashMap<>();
        config.put(ApiKeyPersistenceService.STORE_API_KEYS, new ArrayList<>(List.of(storeCopyA, storeCopyB)));
        storeManager.saveConfig(STORE_KEY, config);
    }

    // ==================== 反证 1：并发累加不丢更新 ====================

    @Test
    @DisplayName("反证：UsageStatistics 并发 incrementTokens 无丢失（原实现为普通 HashMap 读改写）")
    void concurrentIncrementTokens_shouldNotLoseUpdates() throws Exception {
        final UsageStatistics usage = usageOf(0L);
        final int threads = 16;
        final int perThread = 250;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        usage.incrementTokens(1L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发累加应在 30 秒内完成");
        pool.shutdown();

        assertEquals((long) threads * perThread,
                usage.getDailyTokenUsage().get(TODAY),
                "并发累加必须无丢失");
    }

    // ==================== 反证 2：单 Key 回写保留兄弟条目 ====================

    @Test
    @DisplayName("反证：单 Key 回写只替换目标条目，兄弟条目以存储为准不被内存视图覆盖")
    void singleKeyWrite_shouldPreserveSiblingEntries() {
        setUpService();
        // 关键：缓存与存储持「不同值」的独立对象——若走整表覆盖写，
        // 存储中 A 的 500 会被内存视图的 100 覆盖；单 Key 回写则 A 保持 500。
        final ApiKey cacheA = cacheKeyOf(KEY_A, HASH_A, 100L);
        final ApiKey storeCopyA = cacheKeyOf(KEY_A, HASH_A, 500L);
        final ApiKey cacheB = cacheKeyOf(KEY_B, HASH_B, 0L);
        final ApiKey storeCopyB = cacheKeyOf(KEY_B, HASH_B, 0L);
        service.getApiKeyCache().put(HASH_A, cacheA);
        service.getApiKeyCache().put(HASH_B, cacheB);
        service.getKeyIdIndex().put(KEY_A, HASH_A);
        service.getKeyIdIndex().put(KEY_B, HASH_B);
        seedStore(storeCopyA, storeCopyB);

        service.updateTokenUsage(KEY_B, 5L);

        final List<ApiKey> keys = storedKeys();
        assertEquals(2, keys.size(), "兄弟条目不得被吞掉");
        assertEquals(500L, findByHash(keys, HASH_A).getUsage().getDailyTokenUsage().get(TODAY),
                "兄弟条目 A 必须保持存储当前值（整表覆盖写会把它改成内存视图的 100）");
        assertEquals(5L, findByHash(keys, HASH_B).getUsage().getDailyTokenUsage().get(TODAY),
                "目标条目 B 必须更新为冻结快照值");
        assertSame(cacheA, service.getApiKeyCache().get(HASH_A), "回写不得替换内存缓存对象");
    }

    @Test
    @DisplayName("反证：高频用量回写经 mutateConfig 读改写临界区")
    void usageUpdate_shouldUseMutateConfig() {
        setUpService();
        final ApiKey cacheA = cacheKeyOf(KEY_A, HASH_A, 0L);
        service.getApiKeyCache().put(HASH_A, cacheA);
        service.getKeyIdIndex().put(KEY_A, HASH_A);

        final StoreManager spyStore = Mockito.spy(storeManager);
        ReflectionTestUtils.setField(service, "apiKeyPersistenceService",
                new ApiKeyPersistenceService(spyStore, objectMapper));

        service.updateTokenUsage(KEY_A, 3L);

        Mockito.verify(spyStore, Mockito.times(1))
                .mutateConfig(Mockito.eq(STORE_KEY), Mockito.any());
        assertEquals(3L, findByHash(storedKeys(), HASH_A).getUsage().getDailyTokenUsage().get(TODAY));
    }

    // ==================== 反证 3：双 Key 并发精确计数 ====================

    @Test
    @DisplayName("反证：双 Key 并发回写互不吞掉，最终计数精确")
    void concurrentTwoKeyUpdates_bothTotalsExact() throws Exception {
        setUpService();
        final ApiKey cacheA = cacheKeyOf(KEY_A, HASH_A, 0L);
        final ApiKey cacheB = cacheKeyOf(KEY_B, HASH_B, 0L);
        service.getApiKeyCache().put(HASH_A, cacheA);
        service.getApiKeyCache().put(HASH_B, cacheB);
        service.getKeyIdIndex().put(KEY_A, HASH_A);
        service.getKeyIdIndex().put(KEY_B, HASH_B);
        seedStore(cacheKeyOf(KEY_A, HASH_A, 0L), cacheKeyOf(KEY_B, HASH_B, 0L));

        final int threadsPerKey = 8;
        final int updatesPerThread = 100;
        final ExecutorService pool = Executors.newFixedThreadPool(threadsPerKey * 2);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadsPerKey * 2);
        for (int i = 0; i < threadsPerKey * 2; i++) {
            final String kid = (i % 2 == 0) ? KEY_A : KEY_B;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < updatesPerThread; n++) {
                        service.updateTokenUsage(kid, 1L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发回写应在 60 秒内完成");
        pool.shutdown();

        final long expected = (long) threadsPerKey * updatesPerThread;
        final List<ApiKey> keys = storedKeys();
        assertEquals(2, keys.size(), "并发回写不得吞掉任一 Key");
        assertEquals(expected, findByHash(keys, HASH_A).getUsage().getDailyTokenUsage().get(TODAY),
                "Key A 最终计数必须精确");
        assertEquals(expected, findByHash(keys, HASH_B).getUsage().getDailyTokenUsage().get(TODAY),
                "Key B 最终计数必须精确");
    }
}
