package org.unreal.modelrouter.persistence.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConfigKeyCompatStoreManager} 行为验证（品牌清理：model-router-config → trunkline-config）。
 *
 * <p>覆盖：新 key 读回落旧 key、写落新 key、删除连带清理、版本方法回落、非主配置 key 直通。</p>
 */
@DisplayName("ConfigKeyCompatStoreManager 主配置 key 兼容")
class ConfigKeyCompatStoreManagerTest {

    private static final String CURRENT = ConfigKeyCompatStoreManager.CURRENT_CONFIG_KEY;
    private static final String LEGACY = ConfigKeyCompatStoreManager.LEGACY_CONFIG_KEY;
    private static final String OTHER = "some-other-key";

    private StoreManager delegate;
    private ConfigKeyCompatStoreManager manager;

    @BeforeEach
    void setUp() {
        delegate = mock(StoreManager.class);
        manager = new ConfigKeyCompatStoreManager(delegate);
    }

    private void stubPresence(final boolean currentExists, final boolean legacyExists) {
        when(delegate.exists(CURRENT)).thenReturn(currentExists);
        when(delegate.exists(LEGACY)).thenReturn(legacyExists);
    }

    @Test
    @DisplayName("getConfig：新 key 不存在且旧 key 存在时回落读取")
    void getConfig_fallsBackToLegacy() {
        stubPresence(false, true);
        final Map<String, Object> legacy = Map.of("k", "v");
        when(delegate.getConfig(LEGACY)).thenReturn(legacy);

        assertSame(legacy, manager.getConfig(CURRENT));
        verify(delegate).getConfig(LEGACY);
        verify(delegate, never()).getConfig(CURRENT);
    }

    @Test
    @DisplayName("getConfig：新 key 存在时直接读新 key，不碰旧 key")
    void getConfig_usesCurrentWhenPresent() {
        stubPresence(true, true);
        final Map<String, Object> current = Map.of("k", "new");
        when(delegate.getConfig(CURRENT)).thenReturn(current);

        assertSame(current, manager.getConfig(CURRENT));
        verify(delegate, never()).getConfig(LEGACY);
    }

    @Test
    @DisplayName("getConfig：非主配置 key 直通")
    void getConfig_passthroughForOtherKeys() {
        final Map<String, Object> other = Map.of("k", "x");
        when(delegate.getConfig(OTHER)).thenReturn(other);

        assertSame(other, manager.getConfig(OTHER));
        verify(delegate).getConfig(OTHER);
        verify(delegate, never()).getConfig(CURRENT);
    }

    @Test
    @DisplayName("exists：任一 key 存在即视为存在")
    void exists_eitherCounts() {
        stubPresence(true, false);
        assertTrue(manager.exists(CURRENT));

        stubPresence(false, true);
        assertTrue(manager.exists(CURRENT));

        stubPresence(false, false);
        assertFalse(manager.exists(CURRENT));

        when(delegate.exists(OTHER)).thenReturn(false);
        assertFalse(manager.exists(OTHER));
    }

    @Test
    @DisplayName("deleteConfig：删除新 key 时连带删除旧 key，防止回落复活")
    void deleteConfig_removesLegacyToo() {
        stubPresence(false, true);

        manager.deleteConfig(CURRENT);

        verify(delegate).deleteConfig(CURRENT);
        verify(delegate).deleteConfig(LEGACY);
    }

    @Test
    @DisplayName("deleteConfig：非主配置 key 仅直通删除")
    void deleteConfig_passthroughForOtherKeys() {
        when(delegate.exists(LEGACY)).thenReturn(false);

        manager.deleteConfig(OTHER);

        verify(delegate).deleteConfig(OTHER);
        verify(delegate, never()).deleteConfig(LEGACY);
        verify(delegate, never()).deleteConfig(CURRENT);
    }

    @Test
    @DisplayName("updateConfig：旧 key 存量时写入新 key 完成自然迁移")
    void updateConfig_migratesLegacyToCurrent() {
        stubPresence(false, true);
        final Map<String, Object> config = new HashMap<>(Map.of("k", "v2"));

        manager.updateConfig(CURRENT, config);

        verify(delegate).saveConfig(CURRENT, config);
        verify(delegate, never()).updateConfig(anyString(), any());
    }

    @Test
    @DisplayName("updateConfig：新 key 存量时直通更新")
    void updateConfig_passthroughWhenCurrentExists() {
        stubPresence(true, false);
        final Map<String, Object> config = Map.of("k", "v3");

        manager.updateConfig(CURRENT, config);

        verify(delegate).updateConfig(CURRENT, config);
        verify(delegate, never()).saveConfig(anyString(), any());
    }

    @Test
    @DisplayName("getConfigVersions：新 key 无版本时回落旧 key 版本列表")
    void getConfigVersions_fallsBackToLegacy() {
        when(delegate.getConfigVersions(CURRENT)).thenReturn(List.of());
        when(delegate.getConfigVersions(LEGACY)).thenReturn(List.of(3, 2, 1));

        assertEquals(List.of(3, 2, 1), manager.getConfigVersions(CURRENT));
    }

    @Test
    @DisplayName("getConfigVersions：新 key 有版本时不回落")
    void getConfigVersions_noFallbackWhenCurrentHasVersions() {
        when(delegate.getConfigVersions(CURRENT)).thenReturn(List.of(5));
        when(delegate.getConfigVersions(LEGACY)).thenReturn(List.of(3));

        assertEquals(List.of(5), manager.getConfigVersions(CURRENT));
        verify(delegate, never()).getConfigVersions(LEGACY);
    }

    @Test
    @DisplayName("getConfigByVersion：新 key 无该版本时回落旧 key")
    void getConfigByVersion_fallsBackToLegacy() {
        final Map<String, Object> legacyVersion = Map.of("k", "old");
        when(delegate.getConfigByVersion(CURRENT, 2)).thenReturn(null);
        when(delegate.getConfigByVersion(LEGACY, 2)).thenReturn(legacyVersion);

        assertSame(legacyVersion, manager.getConfigByVersion(CURRENT, 2));
    }

    @Test
    @DisplayName("getLatestConfig：新 key 无最新版本时回落旧 key")
    void getLatestConfig_fallsBackToLegacy() {
        final Map<String, Object> legacyLatest = Map.of("k", "old-latest");
        when(delegate.getLatestConfig(CURRENT)).thenReturn(null);
        when(delegate.getLatestConfig(LEGACY)).thenReturn(legacyLatest);

        assertSame(legacyLatest, manager.getLatestConfig(CURRENT));
    }

    @Test
    @DisplayName("getLatestConfig：非主配置 key 直通")
    void getLatestConfig_passthroughForOtherKeys() {
        final Map<String, Object> other = Map.of("k", "o");
        when(delegate.getLatestConfig(OTHER)).thenReturn(other);

        assertSame(other, manager.getLatestConfig(OTHER));
        verify(delegate, never()).getLatestConfig(CURRENT);
    }

    @Test
    @DisplayName("写与版本保存方法直通委托")
    void writeMethodsPassthrough() {
        final Map<String, Object> config = Map.of("k", "v");
        stubPresence(false, false);

        manager.saveConfig(CURRENT, config);
        manager.saveConfigVersion(CURRENT, config, 1);
        manager.deleteConfigVersion(CURRENT, 1);

        verify(delegate).saveConfig(CURRENT, config);
        verify(delegate).saveConfigVersion(CURRENT, config, 1);
        verify(delegate).deleteConfigVersion(CURRENT, 1);
    }

    @Test
    @DisplayName("getAllKeys 与 version 元数据方法直通委托")
    void metadataMethodsPassthrough() {
        stubPresence(false, false);
        when(delegate.getAllKeys()).thenReturn(List.of(CURRENT));
        when(delegate.versionExists(CURRENT, 1)).thenReturn(false);
        when(delegate.getVersionFilePath(CURRENT, 1)).thenReturn(null);
        when(delegate.getVersionCreatedTime(CURRENT, 1)).thenReturn(LocalDateTime.of(2026, 1, 1, 0, 0));

        assertTrue(manager.getAllKeys().iterator().hasNext());
        assertFalse(manager.versionExists(CURRENT, 1));
        assertNull(manager.getVersionFilePath(CURRENT, 1));
        assertNotNull(manager.getVersionCreatedTime(CURRENT, 1));

        assertDoesNotThrow(() -> manager.saveConfig(OTHER, Map.of()));
        verify(delegate).saveConfig(eq(OTHER), any());
        verify(delegate, never()).saveConfigVersion(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("currentConfigKey() 返回新 key")
    void currentConfigKeyHelper() {
        assertEquals("trunkline-config", ConfigKeyCompatStoreManager.currentConfigKey());
    }
}
