/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.dependency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.persistence.store.StoreManager;
import org.unreal.modelrouter.router.pool.PoolPersistenceService;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除前依赖检测的单元测试。
 *
 * <p>不使用 Mockito：Spring Data 仓库用 {@link Proxy} 造最小替身，密钥缓存用反射注入，
 * 池持久化用真实 {@link PoolPersistenceService} + 假 {@link StoreManager}。</p>
 * @author Trunkline Team
 */
@DisplayName("删除前依赖检测")
class DependencyCheckServiceTest {

    private static final String POOL_STORE_KEY = "pool_definitions";

    private final Map<Long, ModelProviderEntity> providers = new LinkedHashMap<>();
    private final Map<Long, ServiceInstanceEntity> instances = new LinkedHashMap<>();
    private final Map<String, Object> storeData = new HashMap<>();

    private DependencyCheckService service;

    @BeforeEach
    void setUp() throws Exception {
        final ModelProviderRepository providerRepo = proxy(ModelProviderRepository.class, (method, args) -> {
            if ("findById".equals(method)) {
                return Optional.ofNullable(providers.get((Long) args[0]));
            }
            if ("findAll".equals(method)) {
                return new ArrayList<>(providers.values());
            }
            return null;
        });
        final ServiceInstanceRepository instanceRepo = proxy(ServiceInstanceRepository.class, (method, args) -> {
            if ("findById".equals(method)) {
                return Optional.ofNullable(instances.get((Long) args[0]));
            }
            if ("findByProviderId".equals(method)) {
                final Long providerId = (Long) args[0];
                final List<ServiceInstanceEntity> hit = new ArrayList<>();
                for (final ServiceInstanceEntity e : instances.values()) {
                    if (providerId.equals(e.getProviderId())) {
                        hit.add(e);
                    }
                }
                return hit;
            }
            if ("findAll".equals(method)) {
                return new ArrayList<>(instances.values());
            }
            return null;
        });

        final StoreManager storeManager = proxy(StoreManager.class, (method, args) -> {
            if ("getConfig".equals(method)) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> value = (Map<String, Object>) storeData.get(args[0]);
                return value;
            }
            return null;
        });
        final PoolPersistenceService poolPersistence = new PoolPersistenceService(storeManager, new ObjectMapper());

        final ApiKeyService apiKeyService = new ApiKeyService(storeManager, new ObjectMapper(), new SecurityProperties());
        final Field cacheField = ApiKeyService.class.getDeclaredField("apiKeyCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<String, ApiKey> cache = (Map<String, ApiKey>) cacheField.get(apiKeyService);

        this.service = new DependencyCheckService(providerRepo, instanceRepo, poolPersistence, apiKeyService);
        this.apiKeyCache = cache;
    }

    private Map<String, ApiKey> apiKeyCache;

    // ------------------------------------------------------------------
    // 供应商
    // ------------------------------------------------------------------

    @Test
    @DisplayName("供应商下还有已接入模型时不允许删除")
    void providerWithInstancesIsNotDeletable() {
        providers.put(1L, ModelProviderEntity.builder().id(1L).name("DeepSeek").providerUid("deepseek").build());
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).providerId(1L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        instances.put(11L, ServiceInstanceEntity.builder()
                .id(11L).providerId(1L).instanceName("deepseek-coder").instanceId("uuid-b").build());

        final DependencyReport report = service.checkProvider(1L);

        assertFalse(report.isDeletable());
        assertEquals(2, report.getBlockerCount());
        assertEquals("instance", report.getBlockers().get(0).getType());
        assertTrue(report.summary().contains("DeepSeek"));
    }

    @Test
    @DisplayName("供应商下没有已接入模型时可以删除")
    void providerWithoutInstancesIsDeletable() {
        providers.put(1L, ModelProviderEntity.builder().id(1L).name("DeepSeek").providerUid("deepseek").build());

        final DependencyReport report = service.checkProvider(1L);

        assertTrue(report.isDeletable());
        assertEquals(0, report.getBlockerCount());
    }

    // ------------------------------------------------------------------
    // 已接入模型
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模型被模型分组引用时不允许删除")
    void instanceReferencedByPoolIsNotDeletable() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");

        final DependencyReport report = service.checkInstance(10L);

        assertFalse(report.isDeletable());
        assertEquals(1, report.getBlockerCount());
        assertEquals("pool", report.getBlockers().get(0).getType());
        assertEquals("auto-chat", report.getBlockers().get(0).getId());
    }

    @Test
    @DisplayName("密钥白名单未引用实例所属分组时不产生密钥阻断项")
    void instanceNotReferencedByApiKeyGroupIsNotBlockedByKey() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");
        // 密钥只引用了另一个分组 → 与本实例无引用关系
        apiKeyCache.put("hash-1", ApiKey.builder()
                .keyId("key-001").description("测试密钥").enabled(true)
                .allowedGroups(List.of("other-pool")).build());

        final DependencyReport report = service.checkInstance(10L);

        // 仅剩「模型分组成员」一项阻断，不含 apiKey 阻断
        assertEquals(1, report.getBlockerCount());
        assertEquals("pool", report.getBlockers().get(0).getType());
    }

    @Test
    @DisplayName("密钥通过分组间接关联模型时同样不允许删除")
    void instanceReferencedViaPoolByApiKeyIsNotDeletable() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");
        apiKeyCache.put("hash-1", ApiKey.builder()
                .keyId("key-001").description("测试密钥").enabled(true)
                .allowedGroups(List.of("auto-chat")).build());

        final DependencyReport report = service.checkInstance(10L);

        assertFalse(report.isDeletable());
        assertEquals(2, report.getBlockerCount());
    }

    @Test
    @DisplayName("停用密钥引用分组只提示不阻断（密钥侧不产生 apiKey 阻断项）")
    void disabledApiKeyOnlyWarns() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");
        apiKeyCache.put("hash-1", ApiKey.builder()
                .keyId("key-001").description("停用密钥").enabled(false)
                .allowedGroups(List.of("auto-chat")).build());

        final DependencyReport report = service.checkInstance(10L);

        // 密钥维度：停用密钥只产生 warning，不产生 apiKey 阻断项
        assertEquals(1, report.getWarnings().size(), "停用密钥应产生 1 条提示");
        assertTrue(report.getBlockers().stream().noneMatch(b -> "apiKey".equals(b.getType())),
                "停用密钥不得产生 apiKey 阻断项，实际阻断项: " + report.getBlockers());
        // 分组成员本身仍是阻断项（实例必须归属至少一个分组才可对外调用）
        assertEquals(1, report.getBlockerCount());
        assertEquals("pool", report.getBlockers().get(0).getType());
    }

    @Test
    @DisplayName("停用密钥引用分组时，删除分组可放行（只提示不阻断）")
    void disabledApiKeyDoesNotBlockPoolRemoval() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");
        // 实例在其它分组仍有归属 → 删除 auto-chat 不产生游离实例
        putPool("backup-chat", "deepseek-chat");
        apiKeyCache.put("hash-1", ApiKey.builder()
                .keyId("key-001").description("停用密钥").enabled(false)
                .allowedGroups(List.of("auto-chat")).build());

        final DependencyReport report = service.checkPool("auto-chat");

        assertTrue(report.isDeletable(), "停用密钥只提示不阻断，阻断项: " + report.getBlockers());
        assertEquals(1, report.getWarnings().size());
        assertTrue(report.getWarnings().get(0).contains("停用密钥"));
    }

    @Test
    @DisplayName("没有任何引用时可以删除")
    void instanceWithoutReferenceIsDeletable() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());

        assertTrue(service.checkInstance(10L).isDeletable());
    }

    // ------------------------------------------------------------------
    // 模型分组
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分组被密钥白名单引用时不允许删除")
    void poolReferencedByApiKeyIsNotDeletable() {
        apiKeyCache.put("hash-1", ApiKey.builder()
                .keyId("key-001").description("测试密钥").enabled(true)
                .allowedGroups(List.of("auto-chat")).build());

        final DependencyReport report = service.checkPool("auto-chat");

        assertFalse(report.isDeletable());
        assertEquals(1, report.getBlockerCount());
        assertEquals("apiKey", report.getBlockers().get(0).getType());
    }

    @Test
    @DisplayName("分组无密钥引用时可以删除")
    void poolWithoutReferenceIsDeletable() {
        assertTrue(service.checkPool("auto-chat").isDeletable());
    }

    // ------------------------------------------------------------------
    // 强制实例先入组（游离实例：整组删除只提示，成员变更才阻断）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除分组会导致成员实例变成游离实例时：放行删除 + 给出提示（避免删不掉的死锁）")
    void poolRemovalOrphaningInstanceWarnsButAllowsDelete() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");

        final DependencyReport report = service.checkPool("auto-chat");

        // 阻断会形成死锁：删实例被「仍是分组成员」阻断、删分组被「会造成游离」阻断，
        // 两条路都走不通 → 管理员无法清理配置。故整组删除放行，只提示。
        assertTrue(report.isDeletable(), "整组删除应放行，阻断项: " + report.getBlockers());
        assertEquals(0, report.getBlockerCount());
        assertEquals(1, report.getWarnings().size(), "应产生 1 条游离提示，实际: " + report.getWarnings());
        assertTrue(report.getWarnings().get(0).contains("deepseek-chat"),
                "提示应列出游离实例名: " + report.getWarnings().get(0));
    }

    @Test
    @DisplayName("成员实例在其它分组仍有归属时允许删除分组")
    void poolRemovalWithoutOrphanIsDeletable() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");
        putPool("backup-chat", "deepseek-chat");

        final DependencyReport report = service.checkPool("auto-chat");

        assertTrue(report.isDeletable());
        assertEquals(0, report.getBlockerCount());
    }

    @Test
    @DisplayName("移除成员后实例仍有其它分组归属时不产生游离实例")
    void removingMemberKeepsInstanceGrouped() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");
        putPool("backup-chat", "deepseek-chat");

        final List<String> orphans = service.findOrphanedInstances("auto-chat", List.of("deepseek-chat"));

        assertTrue(orphans.isEmpty());
    }

    @Test
    @DisplayName("移除成员后实例无其它分组归属时列出游离实例")
    void removingMemberOrphansInstance() {
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).instanceName("deepseek-chat").instanceId("uuid-a").build());
        putPool("auto-chat", "deepseek-chat");

        final List<String> orphans = service.findOrphanedInstances("auto-chat", List.of("deepseek-chat"));

        assertEquals(1, orphans.size());
        assertEquals("deepseek-chat", orphans.get(0));
    }

    // ------------------------------------------------------------------
    // 供应商模板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模板启用了供应商时不允许删除")
    void templateWithProviderIsNotDeletable() {
        providers.put(1L, ModelProviderEntity.builder()
                .id(1L).name("自建网关").providerUid("tpl-my-template").build());

        final DependencyReport report = service.checkTemplate("my-template");

        assertFalse(report.isDeletable());
        assertEquals(1, report.getBlockerCount());
        assertEquals("provider", report.getBlockers().get(0).getType());
    }

    @Test
    @DisplayName("模板无供应商引用时可以删除")
    void templateWithoutProviderIsDeletable() {
        providers.put(1L, ModelProviderEntity.builder()
                .id(1L).name("DeepSeek").providerUid("deepseek").build());

        assertTrue(service.checkTemplate("my-template").isDeletable());
    }

    // ------------------------------------------------------------------
    // 统一断言
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有阻断项时抛出 409 业务异常")
    void assertDeletableThrowsConflict() {
        providers.put(1L, ModelProviderEntity.builder().id(1L).name("DeepSeek").providerUid("deepseek").build());
        instances.put(10L, ServiceInstanceEntity.builder()
                .id(10L).providerId(1L).instanceName("deepseek-chat").instanceId("uuid-a").build());

        final ApiException ex = assertThrows(ApiException.class,
                () -> service.assertDeletable(service.checkProvider(1L)));
        assertEquals("CONFLICT_PROVIDER_IN_USE", ex.getErrorCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName("无阻断项时不抛异常")
    void assertDeletablePassesWhenNoBlocker() {
        service.assertDeletable(service.checkProvider(999L));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 写入一个池（成员按实例名匹配，与向导创建池的口径一致） */
    private void putPool(final String poolName, final String... memberInstanceIds) {
        final PoolDefinition pool = new PoolDefinition();
        pool.setPoolName(poolName);
        pool.setName(poolName);
        pool.setServiceType("chat");
        for (final String memberInstanceId : memberInstanceIds) {
            final PoolMember member = new PoolMember();
            member.setInstanceId(memberInstanceId);
            member.setWeight(1);
            pool.getMembers().add(member);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> pools = (Map<String, Object>) storeData.get(POOL_STORE_KEY);
        if (pools == null) {
            pools = new LinkedHashMap<>();
            storeData.put(POOL_STORE_KEY, pools);
        }
        pools.put(poolName, pool);
    }

    /** 为接口造最小替身（只处理显式实现的方法，其余返回 null） */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (p, method, args) -> handler.apply(method.getName(), args == null ? new Object[0] : args));
    }
}
