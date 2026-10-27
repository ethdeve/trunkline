package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥白名单与权限分类一致性校验测试。
 *
 * <p>覆盖三处核心语义：</p>
 * <ol>
 *   <li>白名单项分类不被权限覆盖 → 拒绝（400 的来源）</li>
 *   <li>白名单项分类被权限覆盖 → 放行</li>
 *   <li>名字在目录中查不到 → 无法判定，放行（不误伤存量/外部模型名）</li>
 * </ol>
 *
 * <p><b>v3.5 治理收口</b>：密钥白名单只剩<b>分组白名单</b>一维（模型维度已移除，
 * 单线治理下「可调用的模型」= 分组的成员），因此校验器签名由三参数
 * （permissions / allowedModels / allowedGroups）收敛为两参数
 * （permissions / allowedGroups）。</p>
 *
 * <p>本测试不依赖 Mockito，直接构造真实对象与手写的 catalog 桩，
 * 避免沙箱环境 self-attach 限制。</p>
 */
class ApiKeyWhitelistValidatorTest {

    /** 手写 catalog 桩：名字 → 分类集合 */
    private static final class StubCatalog implements ServiceTypeCatalogApi {
        private final Map<String, Set<String>> catalog = new HashMap<>();

        StubCatalog put(final String name, final String... categories) {
            catalog.put(name, new LinkedHashSet<>(List.of(categories)));
            return this;
        }

        @Override
        public Map<String, Set<String>> resolveServiceTypes(final Collection<String> names) {
            final Map<String, Set<String>> result = new HashMap<>();
            for (final String name : names) {
                final Set<String> categories = catalog.get(name);
                if (categories != null) {
                    result.put(name, categories);
                }
            }
            return result;
        }
    }

    private ApiKeyWhitelistValidator validator(final ServiceTypeCatalogApi catalog) {
        final ObjectProvider<ServiceTypeCatalogApi> provider = new ObjectProvider<>() {
            @Override
            public ServiceTypeCatalogApi getObject() {
                return catalog;
            }

            @Override
            public ServiceTypeCatalogApi getObject(final Object... args) {
                return catalog;
            }

            @Override
            public ServiceTypeCatalogApi getIfAvailable() {
                return catalog;
            }

            @Override
            public ServiceTypeCatalogApi getIfUnique() {
                return catalog;
            }
        };
        return new ApiKeyWhitelistValidator(provider);
    }

    @Test
    @DisplayName("白名单分组分类不被权限覆盖 → 拒绝")
    void rejectsGroupOutsideGrantedCategory() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("chat-pool", "chat").put("tts-pool", "tts"));

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(List.of("chat"), List.of("tts-pool")));
        assertTrue(ex.getMessage().contains("tts-pool"), "报错应指出具体分组: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("tts"), "报错应指出所属分类: " + ex.getMessage());
    }

    @Test
    @DisplayName("白名单分组分类不被权限覆盖 → 拒绝（多分组时任一不一致即拒绝）")
    void rejectsWhenAnyGroupOutsideGrantedCategory() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("chat-pool", "chat").put("tts-pool", "tts"));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(List.of("chat"), List.of("chat-pool", "tts-pool")));
    }

    @Test
    @DisplayName("白名单分类被权限覆盖 → 放行")
    void allowsMatchingCategory() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("chat-pool", "chat").put("glm-5.1", "chat"));

        assertDoesNotThrow(() -> validator.validate(List.of("chat"), List.of("chat-pool")));
    }

    @Test
    @DisplayName("权限覆盖多个分类 → 各分类白名单项均放行")
    void allowsAnyGrantedCategory() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("chat-pool", "chat").put("tts-pool", "tts"));

        assertDoesNotThrow(() -> validator.validate(List.of("chat", "tts"),
                List.of("chat-pool", "tts-pool")));
    }

    @Test
    @DisplayName("名字在目录中查不到 → 放行（不误伤已删除实例/外部模型名）")
    void allowsUnknownNames() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("chat-pool", "chat"));

        assertDoesNotThrow(() -> validator.validate(List.of("chat"),
                List.of("deleted-instance", "external-model")));
    }

    @Test
    @DisplayName("权限为空 → 放行（运行时本就全 403，不额外拦截）")
    void allowsEmptyPermissions() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("tts-pool", "tts"));

        assertDoesNotThrow(() -> validator.validate(List.of(), List.of("tts-pool")));
        assertDoesNotThrow(() -> validator.validate(null, List.of("tts-pool")));
    }

    @Test
    @DisplayName("ADMIN 权限 → 放行所有分类（运行时 ROLE_ADMIN 直通）")
    void allowsAdminPermission() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("tts-pool", "tts"));

        assertDoesNotThrow(() -> validator.validate(List.of("admin"), List.of("tts-pool")));
        assertDoesNotThrow(() -> validator.validate(List.of("ADMIN"), List.of("tts-pool")));
    }

    @Test
    @DisplayName("同名实例跨分类 → 只要有一个分类被授权即放行")
    void allowsWhenAnyCategoryGranted() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("shared-name", "chat", "tts"));

        assertDoesNotThrow(() -> validator.validate(List.of("chat"), List.of("shared-name")));
    }

    @Test
    @DisplayName("分类值大小写无关（IMGGEN 与 imgGen 等价）")
    void normalizesCategoryCase() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("image-model", "imgGen"));

        assertDoesNotThrow(() -> validator.validate(List.of("IMGGEN"), List.of("image-model")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(List.of("chat"), List.of("image-model")));
    }

    @Test
    @DisplayName("findInvalidByCategory 返回按分类归组的不一致项")
    void findInvalidByCategoryGroupsByCategory() {
        final ApiKeyWhitelistValidator validator = validator(
                new StubCatalog().put("tts-pool", "tts").put("stt-pool", "stt"));

        final Map<String, Set<String>> invalid = validator.findInvalidByCategory(
                List.of("chat"), List.of("tts-pool", "stt-pool"));

        assertEquals(2, invalid.size());
        assertTrue(invalid.containsKey("tts"));
        assertTrue(invalid.containsKey("stt"));
        assertTrue(invalid.get("tts").contains("tts-pool"));
        assertFalse(invalid.get("tts").contains("stt-pool"));
    }

    @Test
    @DisplayName("catalog 未装配 → 放行（单测/裁剪部署场景）")
    void allowsWhenCatalogAbsent() {
        final ApiKeyWhitelistValidator validator = validator(null);

        assertDoesNotThrow(() -> validator.validate(List.of("chat"), List.of("tts-pool")));
        assertTrue(validator.findInvalidByCategory(List.of("chat"), List.of("tts-pool")).isEmpty());
    }
}
