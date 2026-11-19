/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreateRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreationVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyListVO;
import org.unreal.modelrouter.enterprise.config.TenantContextHolder;
import org.unreal.modelrouter.enterprise.spi.TenantContext;
import org.unreal.modelrouter.persistence.store.StoreManager;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * API Key 归属租户「当前租户自动绑定」测试。
 *
 * <p>用户决策（2026-11）：租户只有「顶部当前租户」一个数据源——顶部切换器写入
 * {@code X-Tenant-Id}，请求拦截器每请求注入，后端从上下文取当前租户绑定新建 Key；
 * 不在创建表单里再选一次（避免两处选择不一致）。社区版维持单租户 default 行为零变化。</p>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("API Key 当前租户自动绑定")
class ApiKeyCreationTenantBindingTest {

    @Mock private StoreManager storeManager;
    @Mock private ObjectMapper objectMapper;
    @Mock private SecurityProperties securityProperties;
    @Mock private ApiKeyValidator apiKeyValidator;
    @Mock private ApiKeyBatchService apiKeyBatchService;
    @Mock private ApiKeyPersistenceService apiKeyPersistenceService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ApiKeyPlaintextCipher plaintextCipher;

    private ApiKeyService service;
    private Map<String, org.unreal.modelrouter.auth.security.config.properties.ApiKey> apiKeyCache;

    /** 可切换当前租户的多租户上下文：模拟顶部租户切换器 */
    private static final class TestMultiTenantContext implements TenantContext {
        private final String current;
        TestMultiTenantContext(final String current) { this.current = current; }
        @Override public String getTenantId() { return current; }
        @Override public boolean isMultiTenant() { return true; }
    }

    @BeforeEach
    void setUp() throws Exception {
        TenantContextHolder.setDelegate(null);
        service = new ApiKeyService(storeManager, objectMapper, securityProperties);
        inject("apiKeyValidator", apiKeyValidator);
        inject("apiKeyBatchService", apiKeyBatchService);
        inject("apiKeyPersistenceService", apiKeyPersistenceService);
        inject("eventPublisher", eventPublisher);
        inject("plaintextCipher", plaintextCipher);
        when(plaintextCipher.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0, String.class));
        Field whitelistField = ApiKeyService.class.getDeclaredField("apiKeyWhitelistValidator");
        whitelistField.setAccessible(true);
        whitelistField.set(service, new ApiKeyWhitelistValidator(nullCatalogProvider()));
        Field cacheField = ApiKeyService.class.getDeclaredField("apiKeyCache");
        cacheField.setAccessible(true);
        apiKeyCache = (Map<String, org.unreal.modelrouter.auth.security.config.properties.ApiKey>) cacheField.get(service);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.setDelegate(null);
    }

    private void inject(final String name, final Object value) throws Exception {
        Field f = ApiKeyService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static org.springframework.beans.factory.ObjectProvider<
            org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi> nullCatalogProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi getObject() { return null; }
            @Override public org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi getObject(Object... args) { return null; }
            @Override public org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi getIfAvailable() { return null; }
            @Override public org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi getIfUnique() { return null; }
        };
    }

    private static ApiKeyCreateRequest req() {
        return ApiKeyCreateRequest.builder().description("unit-test").build();
    }

    private org.unreal.modelrouter.auth.security.config.properties.ApiKey createdKey(final ApiKeyCreationVO vo) {
        return apiKeyCache.values().stream().filter(k -> k.getKeyId().equals(vo.getKeyId())).findFirst().orElseThrow();
    }

    // ============ 多租户：绑定当前租户 ============

    @Test
    @DisplayName("多租户：新建 Key 绑定当前租户")
    void bindsCurrentTenant() {
        TenantContextHolder.setDelegate(new TestMultiTenantContext("acme"));
        ApiKeyCreationVO vo = service.createApiKey(req(), "admin", null).block();
        assertEquals("acme", createdKey(vo).getTenantId());
    }

    @Test
    @DisplayName("多租户：当前租户大小写/空白归一化")
    void normalizesCurrentTenant() {
        TenantContextHolder.setDelegate(new TestMultiTenantContext("  BeTa  "));
        ApiKeyCreationVO vo = service.createApiKey(req(), "admin", null).block();
        assertEquals("beta", createdKey(vo).getTenantId());
    }

    @Test
    @DisplayName("多租户：当前租户格式非法 → 拒绝")
    void illegalCurrentTenantRejected() {
        TenantContextHolder.setDelegate(new TestMultiTenantContext("ACME_bad!"));
        StepVerifier.create(service.createApiKey(req(), "admin", null))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("无法识别当前租户"))
                .verify();
    }

    // ============ 单租户：零变化 ============

    @Test
    @DisplayName("单租户：恒为 default")
    void singleTenantAlwaysDefault() {
        ApiKeyCreationVO vo = service.createApiKey(req(), "admin", null).block();
        assertEquals("default", createdKey(vo).getTenantId());
    }

    // ============ 列表按租户过滤（保留）============

    @Test
    @DisplayName("多租户：全部密钥列表仅返回当前租户的密钥")
    void listFilteredByCurrentTenant() {
        apiKeyCache.put("acme-hash", org.unreal.modelrouter.auth.security.config.properties.ApiKey.builder()
                .keyId("acme-key").keyHash("acme-hash").tenantId("acme").createdAt(LocalDateTime.now()).build());
        apiKeyCache.put("beta-hash", org.unreal.modelrouter.auth.security.config.properties.ApiKey.builder()
                .keyId("beta-key").keyHash("beta-hash").tenantId("beta").createdAt(LocalDateTime.now()).build());
        TenantContextHolder.setDelegate(new TestMultiTenantContext("acme"));

        ApiKeyListVO acmeList = service.getAllApiKeysVO("acme").block();
        assertEquals(1, acmeList.getItems().size());
        assertEquals("acme", acmeList.getItems().get(0).getTenantId());

        ApiKeyListVO betaList = service.getAllApiKeysVO("beta").block();
        assertEquals(1, betaList.getItems().size());
        assertEquals("beta", betaList.getItems().get(0).getTenantId());
    }

    @Test
    @DisplayName("单租户：全部密钥列表不过滤（存量无 tenantId 的 key 仍可见）")
    void listNoFilterInSingleTenant() {
        apiKeyCache.put("legacy-hash", org.unreal.modelrouter.auth.security.config.properties.ApiKey.builder()
                .keyId("legacy-key").keyHash("legacy-hash").tenantId(null).createdAt(LocalDateTime.now()).build());
        ApiKeyListVO list = service.getAllApiKeysVO().block();
        assertEquals(true, list.getItems().stream().anyMatch(k -> k.getKeyId().equals("legacy-key")));
    }

    // ============ 完整租户隔离（2026-11-19）============

    /** 预置两条不同租户、都归属 admin 的密钥；同时补 keyIdIndex（keyId→hash），否则 requireInScope 视为不存在。 */
    private void seedTwoTenants() {
        apiKeyCache.put("acme-hash", org.unreal.modelrouter.auth.security.config.properties.ApiKey.builder()
                .keyId("acme-key").keyHash("acme-hash").tenantId("acme")
                .createdBy("admin").holder("admin").createdAt(LocalDateTime.now()).build());
        apiKeyCache.put("beta-hash", org.unreal.modelrouter.auth.security.config.properties.ApiKey.builder()
                .keyId("beta-key").keyHash("beta-hash").tenantId("beta")
                .createdBy("admin").holder("admin").createdAt(LocalDateTime.now()).build());
        try {
            Field indexField = ApiKeyService.class.getDeclaredField("keyIdIndex");
            indexField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> keyIdIndex = (Map<String, String>) indexField.get(service);
            keyIdIndex.put("acme-key", "acme-hash");
            keyIdIndex.put("beta-key", "beta-hash");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入 keyIdIndex", e);
        }
    }

    @Test
    @DisplayName("回归装配阶段时序坑：header 为 beta 时只返回 beta，即使 ThreadLocal 停在 acme")
    void listUsesExplicitHeaderNotThreadLocal() {
        seedTwoTenants();
        TenantContextHolder.setDelegate(new TestMultiTenantContext("acme")); // ThreadLocal 故意停在 acme
        ApiKeyListVO list = service.getAllApiKeysVO("beta").block(); // 但 header 明确是 beta
        assertEquals(1, list.getItems().size());
        assertEquals("beta", list.getItems().get(0).getTenantId());
    }

    @Test
    @DisplayName("我的密钥：多租户下只返回当前租户下、归属本人的密钥")
    void mineFilteredByTenantAndOwner() {
        seedTwoTenants();
        TenantContextHolder.setDelegate(new TestMultiTenantContext("acme"));
        ApiKeyListVO list = service.getMineApiKeysVO("admin", "beta").block();
        assertEquals(1, list.getItems().size());
        assertEquals("beta", list.getItems().get(0).getTenantId());
        // 不属于 owner 的密钥即使在同租户也不可见
        assertEquals(0, service.getMineApiKeysVO("someone-else", "beta").block().getItems().size());
    }

    @Test
    @DisplayName("跨租户读详情：视为不存在")
    void crossTenantReadDenied() {
        seedTwoTenants();
        TenantContextHolder.setDelegate(new TestMultiTenantContext("acme"));
        StepVerifier.create(service.getApiKeyByIdVO("beta-key", "acme"))
                .expectErrorMatches(e -> e instanceof org.unreal.modelrouter.auth.security.service.ApiKeyNotFoundException)
                .verify();
        // 同租户可读
        assertEquals("acme-key", service.getApiKeyByIdVO("acme-key", "acme").block().getKeyId());
    }

    @Test
    @DisplayName("跨租户写操作（update/delete/enable/disable）：全部拒绝，且不改动目标")
    void crossTenantWritesDenied() {
        seedTwoTenants();
        TenantContextHolder.setDelegate(new TestMultiTenantContext("acme"));
        org.unreal.modelrouter.auth.security.dto.ApiKeyUpdateRequest upd =
                org.unreal.modelrouter.auth.security.dto.ApiKeyUpdateRequest.builder().description("hacked").build();
        StepVerifier.create(service.updateApiKey("beta-key", upd, "acme"))
                .expectError(org.unreal.modelrouter.auth.security.service.ApiKeyNotFoundException.class).verify();
        StepVerifier.create(service.deleteApiKey("beta-key", "admin", "acme"))
                .expectError(org.unreal.modelrouter.auth.security.service.ApiKeyNotFoundException.class).verify();
        StepVerifier.create(service.enableApiKey("beta-key", "acme"))
                .expectError(org.unreal.modelrouter.auth.security.service.ApiKeyNotFoundException.class).verify();
        StepVerifier.create(service.disableApiKey("beta-key", "acme"))
                .expectError(org.unreal.modelrouter.auth.security.service.ApiKeyNotFoundException.class).verify();
        // 目标未被改动、未被删除
        org.unreal.modelrouter.auth.security.config.properties.ApiKey beta =
                apiKeyCache.get("beta-hash");
        assertEquals("beta", beta.getTenantId());
        assertEquals("hacked".equals(beta.getDescription()), false);
    }

    @Test
    @DisplayName("跨租户配额查询/重置：视为不存在、拒绝重置")
    void crossTenantQuotaDenied() {
        seedTwoTenants();
        TenantContextHolder.setDelegate(new TestMultiTenantContext("acme"));
        org.unreal.modelrouter.auth.security.service.ApiKeyQuotaService quota =
                new org.unreal.modelrouter.auth.security.service.ApiKeyQuotaService(
                        service, new org.unreal.modelrouter.auth.security.service.TokenBucketRateLimiter());
        assertEquals(true, quota.getQuotaUsage("beta-key", "acme").isEmpty());
        assertEquals(false, service.isKeyInScope("beta-key", "acme"));
        assertThrows(IllegalArgumentException.class, () -> quota.resetDailyQuota("beta-key", "acme"));
    }

    @Test
    @DisplayName("多租户新建：显式 header 优先于 ThreadLocal（controller 透传路径）")
    void createUsesExplicitHeader() {
        TenantContextHolder.setDelegate(new TestMultiTenantContext("acme")); // ThreadLocal 是 acme
        ApiKeyCreationVO vo = service.createApiKey(req(), "admin", null, "beta").block(); // header 是 beta
        assertEquals("beta", createdKey(vo).getTenantId());
    }
}
