/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内置模板「隐藏 / 恢复」逻辑测试（无 Mockito，使用内存版 StoreManager 真对象）
 * @author Trunkline Team
 */
@DisplayName("AdapterTemplateService 隐藏与恢复测试")
class AdapterTemplateHideRestoreTest {

    /** 共享内存 StoreManager：验证隐藏状态可持久化跨实例读取 */
    static class InMemoryStoreManager implements StoreManager {
        private final Map<String, Map<String, Object>> store;

        InMemoryStoreManager(final Map<String, Map<String, Object>> backing) {
            this.store = backing;
        }

        @Override
        public void saveConfig(final String key, final Map<String, Object> config) {
            store.put(key, new HashMap<>(config));
        }

        @Override
        public Map<String, Object> getConfig(final String key) {
            return store.get(key);
        }

        @Override
        public void deleteConfig(final String key) {
            store.remove(key);
        }

        @Override
        public Iterable<String> getAllKeys() {
            return new ArrayList<>(store.keySet());
        }

        @Override
        public boolean exists(final String key) {
            return store.containsKey(key);
        }

        @Override
        public void updateConfig(final String key, final Map<String, Object> config) {
            store.put(key, new HashMap<>(config));
        }

        @Override
        public Map<String, Object> getLatestConfig(final String configKey) {
            return store.get(configKey);
        }
    }

    private Map<String, Map<String, Object>> backing;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        backing = new HashMap<>();
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }

    private AdapterTemplateService newService() {
        return new AdapterTemplateService(
                new ProviderTemplatePersistenceService(new InMemoryStoreManager(backing), objectMapper),
                Mockito.mock(org.unreal.modelrouter.config.dependency.DependencyCheckService.class));
    }

    @Test
    @DisplayName("HR-001: 新实例默认 13 个模板，无隐藏")
    void testDefaultNoHidden() {
        AdapterTemplateService svc = newService();
        assertEquals(13, svc.getAllTemplates().size());
        assertTrue(svc.getHiddenTemplates().isEmpty());
    }

    @Test
    @DisplayName("HR-002: 隐藏内置模板后从列表移除，且可查到隐藏项")
    void testHide_builtin_removedFromList() {
        AdapterTemplateService svc = newService();
        svc.hideTemplate("groq");
        assertEquals(12, svc.getAllTemplates().size());
        assertTrue(svc.getAllTemplates().stream().noneMatch(t -> t.getId().equals("groq")));

        List<AdapterTemplate> hidden = svc.getHiddenTemplates();
        assertEquals(1, hidden.size());
        assertEquals("groq", hidden.get(0).getId());
    }

    @Test
    @DisplayName("HR-003: 隐藏后持久化，新实例仍能读取隐藏状态")
    void testHide_persisted_acrossInstances() {
        AdapterTemplateService svc1 = newService();
        svc1.hideTemplate("groq");
        svc1.hideTemplate("together");

        // 共享同一 backing store，模拟重启后重新加载
        AdapterTemplateService svc2 = newService();
        assertEquals(11, svc2.getAllTemplates().size());
        assertEquals(2, svc2.getHiddenTemplates().size());
    }

    @Test
    @DisplayName("HR-004: 隐藏不存在/自定义模板应抛异常")
    void testHide_custom_throws() {
        AdapterTemplateService svc = newService();
        assertThrows(IllegalArgumentException.class, () -> svc.hideTemplate("nonexistent"));
    }

    @Test
    @DisplayName("HR-005: 恢复隐藏模板后重新出现在列表")
    void testRestore_builtin_backToList() {
        AdapterTemplateService svc = newService();
        svc.hideTemplate("groq");
        assertEquals(12, svc.getAllTemplates().size());

        svc.restoreTemplate("groq");
        assertEquals(13, svc.getAllTemplates().size());
        assertTrue(svc.getAllTemplates().stream().anyMatch(t -> t.getId().equals("groq")));
        assertTrue(svc.getHiddenTemplates().isEmpty());
    }

    @Test
    @DisplayName("HR-006: 重复隐藏同一模板幂等")
    void testHide_idempotent() {
        AdapterTemplateService svc = newService();
        svc.hideTemplate("groq");
        svc.hideTemplate("groq");
        assertEquals(1, svc.getHiddenTemplates().size());
        assertEquals(12, svc.getAllTemplates().size());
    }
}
