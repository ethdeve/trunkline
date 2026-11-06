package org.unreal.modelrouter.persistence.jpa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.util.JacksonHelper;
import org.unreal.modelrouter.persistence.jpa.entity.ConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ConfigRepository;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1（当前配置与版本快照分离）JpaStoreManager 语义回归测试。
 *
 * <p>缺陷背景：config_data 的 isLatest=true 行既是「当前配置」又是「最新版本快照」——
 * saveConfig 就地覆盖该行会篡改版本快照；applyVersion 反向覆盖会让最新版本行
 * 内容变成旧配置（历史被改写）。</p>
 *
 * <p>修复后契约（本测试全部断言基于此）：</p>
 * <ul>
 *   <li>saveConfig 只 upsert version=0 的当前行，绝不触碰 version>=1 的快照行</li>
 *   <li>saveConfigVersion 追加 isLatest=false 的不可变快照行（同号已存在则跳过）</li>
 *   <li>getConfigVersions 只列快照行（不含 v0）</li>
 *   <li>getConfigByVersion(0) = 当前配置；getConfigByVersion(N>=1) = 冻结快照</li>
 * </ul>
 *
 * <p>不使用 Mockito：真实 {@link JpaStoreManager} + 手写内存 {@link ConfigRepository}
 * 替身（动态代理实现接口，避免为 JpaRepository 的数十个方法编写样板代码，
 * 模式同 {@code InMemoryQuotaLedgerRepository}）。</p>
 *
 * @author JAiRouter Team
 * @since 3.6.0
 */
@DisplayName("JpaStoreManager 当前配置/版本快照分离（E1 整改）")
class JpaStoreManagerCurrentVersionSeparationTest {

    private InMemoryConfigRepository repository;
    private JpaStoreManager storeManager;

    @BeforeEach
    void setUp() {
        repository = new InMemoryConfigRepository();
        storeManager = new JpaStoreManager(repository.proxy());
    }

    @Test
    @DisplayName("saveConfig 只写 v0 当前行，不触碰版本快照行")
    void saveConfig_shouldOnlyTouchCurrentRow() {
        // Given：先追加一条快照 v1，再保存当前配置
        Map<String, Object> snapshot = Map.of("services", Map.of("chat", "v1-content"));
        storeManager.saveConfigVersion("trunkline-config", snapshot, 1);

        Map<String, Object> current = Map.of("services", Map.of("chat", "current-content"));
        storeManager.saveConfig("trunkline-config", current);

        // Then：v0 当前行 = current；v1 快照保持 v1-content 不被篡改
        assertEquals(current, storeManager.getConfig("trunkline-config"));
        assertEquals(snapshot, storeManager.getConfigByVersion("trunkline-config", 1));
    }

    @Test
    @DisplayName("saveConfigVersion 追加快照后，后续 saveConfig 不改写快照内容（版本隔离核心断言）")
    void saveConfigVersion_thenSaveConfig_shouldNotMutateSnapshot() {
        // Given：版本 v1 快照落库
        Map<String, Object> v1Config = new HashMap<>();
        v1Config.put("keyA", "v1-value");
        storeManager.saveConfigVersion("trunkline-config", v1Config, 1);

        // When：当前配置更新为完全不同的内容（旧实现会把 isLatest 行就地覆盖掉）
        Map<String, Object> newCurrent = new HashMap<>();
        newCurrent.put("keyA", "changed-value");
        storeManager.saveConfig("trunkline-config", newCurrent);

        // Then：v1 快照仍是原内容（E1 核心不变量）
        Map<String, Object> reloaded = storeManager.getConfigByVersion("trunkline-config", 1);
        assertEquals("v1-value", reloaded.get("keyA"), "版本快照被当前配置保存篡改");
    }

    @Test
    @DisplayName("saveConfigVersion 幂等：同 key 同版本已存在时跳过（快照不可变）")
    void saveConfigVersion_duplicateVersion_shouldSkipInsteadOfOverwrite() {
        // Given
        Map<String, Object> original = Map.of("field", "original");
        storeManager.saveConfigVersion("trunkline-config", original, 1);

        // When：同号重放不同内容
        Map<String, Object> replay = Map.of("field", "replayed");
        storeManager.saveConfigVersion("trunkline-config", replay, 1);

        // Then：快照保持原内容
        assertEquals(original, storeManager.getConfigByVersion("trunkline-config", 1));
        assertEquals(1, repository.snapshotRows("trunkline-config").size());
    }

    @Test
    @DisplayName("saveConfigVersion version<=0 抛 IllegalArgumentException（0 保留给当前行）")
    void saveConfigVersion_versionZero_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> storeManager.saveConfigVersion("trunkline-config", Map.of("a", 1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> storeManager.saveConfigVersion("trunkline-config", Map.of("a", 1), -1));
    }

    @Test
    @DisplayName("getConfigVersions 只列快照行（v0 当前行不混入版本序列）")
    void getConfigVersions_shouldExcludeCurrentRow() {
        // Given
        storeManager.saveConfig("trunkline-config", Map.of("current", true));
        storeManager.saveConfigVersion("trunkline-config", Map.of("v", 1), 1);
        storeManager.saveConfigVersion("trunkline-config", Map.of("v", 2), 2);

        // Then
        List<Integer> versions = storeManager.getConfigVersions("trunkline-config");
        assertEquals(List.of(1, 2), versions, "v0 当前行混入了版本序列");
    }

    @Test
    @DisplayName("getConfigByVersion(0) 返回当前配置（v0 语义统一）")
    void getConfigByVersion_zero_shouldReturnCurrentConfig() {
        // Given
        Map<String, Object> current = Map.of("current", "config");
        storeManager.saveConfig("trunkline-config", current);
        storeManager.saveConfigVersion("trunkline-config", Map.of("v", 1), 1);

        // Then
        assertEquals(current, storeManager.getConfigByVersion("trunkline-config", 0));
    }

    @Test
    @DisplayName("getConfig 读 v0 当前行优先；迁移前数据（无 v0 行）回退 isLatest 行")
    void getConfig_shouldFallbackToIsLatestRowWhenNoV0Row() {
        // Given：模拟迁移前的旧数据形态——只有一条 version=3 + isLatest=true 的双重身份行
        repository.save(ConfigEntity.builder()
                .configKey("legacy-key")
                .configValue(toJson(Map.of("legacy", "value")))
                .version(3)
                .isLatest(true)
                .build());

        // Then：getConfig 兼容回退到 isLatest 行
        Map<String, Object> config = storeManager.getConfig("legacy-key");
        assertNotNull(config);
        assertEquals("value", config.get("legacy"));
    }

    @Test
    @DisplayName("空 Map saveConfig = 清空该 key（当前行 + 快照行全删，防假删除）")
    void saveConfig_emptyMap_shouldDeleteAllRows() {
        // Given
        storeManager.saveConfig("trunkline-config", Map.of("current", true));
        storeManager.saveConfigVersion("trunkline-config", Map.of("v", 1), 1);

        // When
        storeManager.saveConfig("trunkline-config", new HashMap<>());

        // Then
        assertNull(storeManager.getConfig("trunkline-config"));
        assertTrue(storeManager.getConfigVersions("trunkline-config").isEmpty());
        assertFalse(repository.existsByConfigKeyAndIsLatestTrue("trunkline-config"));
    }

    @Test
    @DisplayName("版本快照行 isLatest=false（唯一 isLatest=true 行是 v0 当前行）")
    void saveConfigVersion_snapshotRowsShouldNotBeLatest() {
        // Given
        storeManager.saveConfig("trunkline-config", Map.of("current", true));
        storeManager.saveConfigVersion("trunkline-config", Map.of("v", 1), 1);
        storeManager.saveConfigVersion("trunkline-config", Map.of("v", 2), 2);

        // Then
        List<ConfigEntity> rows = repository.findAllByConfigKeyOrderByVersionAsc("trunkline-config");
        for (ConfigEntity row : rows) {
            if (row.getVersion() >= 1) {
                assertFalse(row.getIsLatest(), "快照行 v" + row.getVersion() + " 不应标记 isLatest");
            } else {
                assertTrue(row.getIsLatest(), "v0 当前行应标记 isLatest");
            }
        }
    }

    // ==================== 测试基础设施 ====================

    private static String toJson(final Map<String, Object> config) {
        try {
            return JacksonHelper.getObjectMapper().writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 内存版 ConfigRepository 替身（InvocationHandler + 动态代理）：
     * 只实现 JpaStoreManager 用到的查询，行模型用 id 递增 + (configKey, version)
     * 唯一性检查模拟 uk_config_data_key_version 约束。
     */
    private static class InMemoryConfigRepository implements InvocationHandler {

        private final AtomicLong idGen = new AtomicLong(1);
        private final List<ConfigEntity> rows = new ArrayList<>();

        ConfigRepository proxy() {
            return (ConfigRepository) Proxy.newProxyInstance(
                    ConfigRepository.class.getClassLoader(),
                    new Class<?>[]{ConfigRepository.class},
                    this);
        }

        List<ConfigEntity> snapshotRows(final String configKey) {
            return rows.stream()
                    .filter(r -> r.getConfigKey().equals(configKey) && r.getVersion() >= 1)
                    .collect(Collectors.toList());
        }

        void save(final ConfigEntity entity) {
            invokeSave(entity);
        }

        boolean existsByConfigKeyAndIsLatestTrue(final String configKey) {
            return findFirstByConfigKeyAndIsLatestTrue(configKey).isPresent();
        }

        List<ConfigEntity> findAllByConfigKeyOrderByVersionAsc(final String configKey) {
            return rows.stream()
                    .filter(r -> r.getConfigKey().equals(configKey))
                    .sorted(java.util.Comparator.comparingInt(ConfigEntity::getVersion))
                    .collect(Collectors.toList());
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            final String name = method.getName();
            if ("toString".equals(name)) {
                return "InMemoryConfigRepository(rows=" + rows.size() + ")";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            switch (name) {
                case "save":
                    return invokeSave((ConfigEntity) args[0]);
                case "findFirstByConfigKeyAndIsLatestTrue":
                    return findFirstByConfigKeyAndIsLatestTrue((String) args[0]);
                case "findByConfigKeyAndVersion":
                    return findByConfigKeyAndVersion((String) args[0], (Integer) args[1]);
                case "findAllVersionsByConfigKey":
                    return rows.stream()
                            .filter(r -> r.getConfigKey().equals(args[0]))
                            .map(ConfigEntity::getVersion)
                            .sorted()
                            .collect(Collectors.toList());
                case "findAllSnapshotVersionsByConfigKey":
                    return rows.stream()
                            .filter(r -> r.getConfigKey().equals(args[0]) && r.getVersion() >= 1)
                            .map(ConfigEntity::getVersion)
                            .sorted()
                            .collect(Collectors.toList());
                case "findAllByConfigKeyOrderByVersionAsc":
                    return findAllByConfigKeyOrderByVersionAsc((String) args[0]);
                case "findAllLatestConfigKeys":
                    return rows.stream()
                            .filter(r -> Boolean.TRUE.equals(r.getIsLatest()))
                            .map(ConfigEntity::getConfigKey)
                            .distinct()
                            .collect(Collectors.toList());
                case "deleteAllByConfigKey":
                    rows.removeIf(r -> r.getConfigKey().equals(args[0]));
                    return null;
                case "deleteByConfigKeyAndVersion":
                    rows.removeIf(r -> r.getConfigKey().equals(args[0])
                            && r.getVersion().equals(args[1]));
                    return null;
                case "markAllAsNotLatest":
                    rows.stream()
                            .filter(r -> r.getConfigKey().equals(args[0]))
                            .forEach(r -> r.setIsLatest(false));
                    return null;
                case "existsByConfigKeyAndIsLatestTrue":
                    return existsByConfigKeyAndIsLatestTrue((String) args[0]);
                case "existsByConfigKeyAndVersion":
                    return findByConfigKeyAndVersion((String) args[0], (Integer) args[1]).isPresent();
                case "findAll":
                    return new ArrayList<>(rows);
                case "findById":
                    return rows.stream()
                            .filter(r -> r.getId().equals(args[0]))
                            .findFirst();
                default:
                    throw new UnsupportedOperationException(
                            "测试假实现未覆盖的仓库方法: " + name);
            }
        }

        private Optional<ConfigEntity> findFirstByConfigKeyAndIsLatestTrue(final String configKey) {
            return rows.stream()
                    .filter(r -> r.getConfigKey().equals(configKey)
                            && Boolean.TRUE.equals(r.getIsLatest()))
                    .findFirst();
        }

        private Optional<ConfigEntity> findByConfigKeyAndVersion(final String configKey,
                                                                 final Integer version) {
            return rows.stream()
                    .filter(r -> r.getConfigKey().equals(configKey)
                            && r.getVersion().equals(version))
                    .findFirst();
        }

        private <S extends ConfigEntity> S invokeSave(final S entity) {
            if (entity.getId() == null) {
                entity.setId(idGen.getAndIncrement());
            }
            // 模拟 uk_config_data_key_version：同 (key, version) 已存在且是另一行 → 抛错
            for (ConfigEntity existing : rows) {
                if (!existing.getId().equals(entity.getId())
                        && existing.getConfigKey().equals(entity.getConfigKey())
                        && existing.getVersion().equals(entity.getVersion())) {
                    throw new IllegalStateException(
                            "uk_config_data_key_version violation: " + entity.getConfigKey()
                                    + " v" + entity.getVersion());
                }
            }
            rows.removeIf(r -> r.getId().equals(entity.getId()));
            rows.add(entity);
            return entity;
        }
    }
}
