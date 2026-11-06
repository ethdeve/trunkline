package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.dto.ApiKeyListVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyVO;
import org.unreal.modelrouter.auth.security.model.UsageStatistics;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我的密钥」归属口径回归测试。
 *
 * <p><b>背景</b>：管理员在控制台创建密钥并「分配给」某员工时，
 * {@code createdBy=admin}（操作者）、{@code holder=员工}（使用者）。
 * 修复前 {@code belongsTo} 用「或」语义（{@code createdBy == owner || holder == owner}），
 * 于是管理员的「我的密钥」里会<b>多出</b>发给别人的密钥；而「全部密钥」页的「持有人」列
 * 显示的却是那个员工 —— 两页口径分叉，同一把密钥归属对不上。</p>
 *
 * <p><b>期望</b>：归属一律以<b>有效持有人</b>（{@code holder || createdBy}）为准，
 * 与「全部密钥」页「持有人」列、调用观测「员工」列完全一致。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiKeyService 密钥归属口径测试")
class ApiKeyServiceHolderOwnershipTest {

    @Mock
    private StoreManager storeManager;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private SecurityProperties securityProperties;

    @Mock
    private ApiKeyValidator apiKeyValidator;

    @Mock
    private ApiKeyBatchService apiKeyBatchService;

    @Mock
    private ApiKeyPersistenceService apiKeyPersistenceService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ApiKeyService apiKeyService;
    private Map<String, ApiKey> apiKeyCache;
    private Map<String, String> keyIdIndex;

    @BeforeEach
    void setUp() throws Exception {
        apiKeyService = new ApiKeyService(storeManager, objectMapper, securityProperties);

        inject("apiKeyValidator", apiKeyValidator);
        inject("apiKeyBatchService", apiKeyBatchService);
        inject("apiKeyPersistenceService", apiKeyPersistenceService);
        inject("eventPublisher", eventPublisher);
        inject("apiKeyWhitelistValidator", new ApiKeyWhitelistValidator(nullCatalogProvider()));

        Field cacheField = ApiKeyService.class.getDeclaredField("apiKeyCache");
        cacheField.setAccessible(true);
        apiKeyCache = (Map<String, ApiKey>) cacheField.get(apiKeyService);

        // findHolderByKeyId 走 keyIdIndex（keyId → keyHash）反查缓存，必须一并注入
        Field indexField = ApiKeyService.class.getDeclaredField("keyIdIndex");
        indexField.setAccessible(true);
        keyIdIndex = (Map<String, String>) indexField.get(apiKeyService);
    }

    private void inject(final String fieldName, final Object value) throws Exception {
        Field field = ApiKeyService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(apiKeyService, value);
    }

    /** 空 catalog：查不到任何名字 → 白名单校验器一律放行，不干扰本用例断言 */
    private static org.springframework.beans.factory.ObjectProvider<
            org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi> nullCatalogProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi getObject() {
                return null;
            }

            @Override
            public org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi getObject(
                    final Object... args) {
                return null;
            }

            @Override
            public org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi getIfAvailable() {
                return null;
            }

            @Override
            public org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi getIfUnique() {
                return null;
            }
        };
    }

    /** 构造一把密钥：keyId 唯一，便于断言 */
    private ApiKey newKey(final String keyId, final String createdBy, final String holder) {
        ApiKey key = ApiKey.builder()
                .keyId(keyId)
                .keyHash("hash-" + keyId)
                .keyPrefix("sk-")
                .description("desc-" + keyId)
                .permissions(List.of("chat"))
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .createdBy(createdBy)
                .holder(holder)
                .usage(UsageStatistics.builder()
                        .totalRequests(0L)
                        .successfulRequests(0L)
                        .failedRequests(0L)
                        .dailyUsage(new HashMap<>())
                        .build())
                .build();
        apiKeyCache.put(key.getKeyHash(), key);
        keyIdIndex.put(key.getKeyId(), key.getKeyHash());
        return key;
    }

    private List<ApiKeyVO> mineAs(final String owner) {
        ApiKeyListVO list = apiKeyService.getMineApiKeysVO(owner).block();
        assertTrue(list != null, "getMineApiKeysVO 不应返回 null");
        return list.getItems();
    }

    // ==================== 核心回归：管理员不应看到发给别人的密钥 ====================

    @Test
    @DisplayName("HOLD-001: admin 创建并分配给 test2 的密钥，不应出现在 admin 的「我的密钥」")
    void adminCreatedButAssignedToOther_shouldNotAppearInAdminMyKeys() {
        // admin 在控制台创建一把密钥，「分配给谁」选了 test2
        newKey("key-to-test2", "admin", "test2");

        List<ApiKeyVO> mine = mineAs("admin");

        assertTrue(mine.isEmpty(),
                "admin 只创建了但分配给 test2 的密钥，不应算作 admin 的密钥，实际返回："
                        + mine.stream().map(ApiKeyVO::getKeyId).toList());
    }

    @Test
    @DisplayName("HOLD-002: 分配给 test2 的密钥，应出现在 test2 的「我的密钥」")
    void assignedToTest2_shouldAppearInTest2MyKeys() {
        newKey("key-to-test2", "admin", "test2");

        List<ApiKeyVO> mine = mineAs("test2");

        assertEquals(1, mine.size(), "test2 应看到分配给自己的那一把密钥");
        assertEquals("key-to-test2", mine.get(0).getKeyId());
    }

    @Test
    @DisplayName("HOLD-003: 两页口径一致——admin 的「我的密钥」与「全部密钥」中持有人为 admin 的条目数相同")
    void mineKeysCountMatchesAllKeysHolderColumnCount() {
        // admin 自己一把 + admin 创建分配给 test2 一把
        newKey("key-admin-own", "admin", "admin");
        newKey("key-to-test2", "admin", "test2");

        long allKeysHeldByAdmin = apiKeyService.getAllApiKeysVO().block().getItems().stream()
                // 前端「持有人」列的口径：row.holder || row.createdBy
                .filter(k -> "admin".equals(k.getHolder() != null && !k.getHolder().isBlank()
                        ? k.getHolder() : k.getCreatedBy()))
                .count();

        long mineHeldByAdmin = mineAs("admin").size();

        assertEquals(allKeysHeldByAdmin, mineHeldByAdmin,
                "「我的密钥」条目数必须与「全部密钥」页中持有人为当前用户的条目数一致");
        assertEquals(1, mineHeldByAdmin, "admin 名下只有自己那把密钥");
    }

    // ==================== 存量与边界行为不变 ====================

    @Test
    @DisplayName("HOLD-004: 存量密钥（无 holder）按 createdBy 归属，行为与改造前一致")
    void legacyKeyWithoutHolder_fallsBackToCreatedBy() {
        newKey("legacy-key", "test2", null);

        List<ApiKeyVO> mine = mineAs("test2");

        assertEquals(1, mine.size(), "存量密钥没有 holder，应按 createdBy 归属");
        assertEquals("legacy-key", mine.get(0).getKeyId());
    }

    @Test
    @DisplayName("HOLD-005: holder 为空白串时同样回落 createdBy")
    void blankHolder_fallsBackToCreatedBy() {
        newKey("blank-holder-key", "test2", "   ");

        assertEquals(1, mineAs("test2").size(), "空白 holder 应视为未指定，回落 createdBy");
        assertTrue(mineAs("admin").isEmpty(), "admin 不应看到 test2 的密钥");
    }

    @Test
    @DisplayName("HOLD-006: 自己创建且分配给自己的密钥，双方归属一致")
    void selfAssignedKey_visibleToOwner() {
        newKey("self-key", "test2", "test2");

        assertEquals(1, mineAs("test2").size(), "自己创建分配给自己的密钥应可见");
        assertTrue(mineAs("admin").isEmpty(), "admin 不应看到 test2 的密钥");
    }

    @Test
    @DisplayName("HOLD-007: owner 为空时不过滤任何密钥（不因空值误判归属）")
    void blankOwner_matchesNothing() {
        newKey("key-to-test2", "admin", "test2");

        assertTrue(mineAs(null).isEmpty(), "owner 为 null 时不应返回任何密钥");
        assertTrue(mineAs("").isEmpty(), "owner 为空串时不应返回任何密钥");
    }

    // ==================== 调用观测「员工」列同口径 ====================

    @Test
    @DisplayName("HOLD-008: 调用观测的「员工」列归属与「我的密钥」口径一致")
    void findHolderByKeyId_usesSameEffectiveHolder() {
        ApiKey key = newKey("key-to-test2", "admin", "test2");

        String holder = apiKeyService.findHolderByKeyId(key.getKeyId());

        assertEquals("test2", holder,
                "「员工」列应显示使用者 test2，而非操作者 admin");
    }

    @Test
    @DisplayName("HOLD-009: 存量密钥在调用观测中仍归属创建者")
    void findHolderByKeyId_legacyFallsBackToCreatedBy() {
        ApiKey key = newKey("legacy-key", "test2", null);

        assertEquals("test2", apiKeyService.findHolderByKeyId(key.getKeyId()));
    }

    @Test
    @DisplayName("HOLD-010: 查不到密钥时返回 null（不抛异常、不误归因）")
    void findHolderByKeyId_unknownKey_returnsNull() {
        assertEquals(null, apiKeyService.findHolderByKeyId("no-such-key"));
        assertFalse(mineAs("test2").stream().anyMatch(k -> "no-such-key".equals(k.getKeyId())));
    }
}
