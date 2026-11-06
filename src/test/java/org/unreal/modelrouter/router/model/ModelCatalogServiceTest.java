package org.unreal.modelrouter.router.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.unreal.modelrouter.router.adapter.AdapterRegistry;
import org.unreal.modelrouter.router.pool.PoolSelector;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link ModelCatalogService} 单测（v3.1 PR-4a）.
 *
 * <p>覆盖：多服务类型汇总、字段集合、空模型跳过、适配器解析异常降级为 {@code unknown}、
 * OpenAI 列表结构包装。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelCatalogServiceTest {

    @Mock
    private ModelServiceRegistry registry;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private PoolSelector poolSelector;

    private ModelCatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = new ModelCatalogService(registry, adapterRegistry, poolSelector);
    }

    @Test
    @DisplayName("汇总多服务类型模型并保留字段集合")
    void listAllModels_shouldAggregateAcrossServiceTypes() {
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.chat))
                .thenReturn(Set.of("m-chat-1", "m-chat-2"));
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.embedding))
                .thenReturn(Set.of("m-emb-1"));

        final List<Map<String, Object>> models = catalogService.listAllModels();

        assertEquals(3, models.size(), "chat 2 个 + embedding 1 个");
        for (Map<String, Object> model : models) {
            assertNotNull(model.get("id"));
            assertEquals("model", model.get("object"));
            assertNotNull(model.get("created"));
            assertEquals("model-router", model.get("owned_by"));
            assertNotNull(model.get("service_type"));
            assertNotNull(model.get("adapter"), "适配器字段必须存在（不可用时为 unknown）");
        }
        assertTrue(models.stream().anyMatch(m -> "chat".equals(m.get("service_type"))));
        assertTrue(models.stream().anyMatch(m -> "embedding".equals(m.get("service_type"))));
    }

    @Test
    @DisplayName("空/未配置的服务类型不产生条目")
    void listAllModels_shouldSkipEmptyServiceTypes() {
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.chat)).thenReturn(Set.of());
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.embedding)).thenReturn(null);

        assertTrue(catalogService.listAllModels().isEmpty());
    }

    @Test
    @DisplayName("适配器解析异常时降级为 unknown，不影响整体列表")
    void listAllModels_shouldDegradeAdapterNameOnFailure() {
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.chat))
                .thenReturn(Set.of("m-chat-1"));
        when(adapterRegistry.getAdapter(ModelServiceRegistry.ServiceType.chat))
                .thenThrow(new IllegalStateException("adapter down"));

        final List<Map<String, Object>> models = catalogService.listAllModels();

        assertEquals(1, models.size());
        assertEquals("unknown", models.get(0).get("adapter"));
    }

    @Test
    @DisplayName("OpenAI 列表结构包装：object=list + data 为模型数组")
    void listAllModelsAsOpenAiList_shouldReturnOpenAiShape() {
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.chat))
                .thenReturn(Set.of("m-chat-1"));

        final Map<String, Object> response = catalogService.listAllModelsAsOpenAiList();

        assertEquals("list", response.get("object"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        assertEquals(1, data.size());
        assertEquals("m-chat-1", data.get(0).get("id"));
    }

    @Test
    @DisplayName("启用的模型分组(虚拟模型)出现在目录中，禁用的分组被跳过")
    void listAllModels_shouldIncludeEnabledPools() {
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.chat))
                .thenReturn(Set.of("m-chat-1"));
        final PoolDefinition enabled = new PoolDefinition();
        enabled.setPoolName("model1");
        enabled.setServiceType("chat");
        final PoolDefinition disabled = new PoolDefinition();
        disabled.setPoolName("model-off");
        disabled.setServiceType("chat");
        disabled.setEnabled(false);
        when(poolSelector.getAllPools()).thenReturn(List.of(enabled, disabled));

        final List<Map<String, Object>> models = catalogService.listAllModels();

        assertEquals(2, models.size(), "实例 1 个 + 启用分组 1 个");
        assertTrue(models.stream().anyMatch(m -> "model1".equals(m.get("id"))));
        assertTrue(models.stream().noneMatch(m -> "model-off".equals(m.get("id"))));
    }

    // ------------------------------------------------------------------
    // v3.5 单线治理：对外模型目录只列「可调用」的模型分组
    // ------------------------------------------------------------------

    @Test
    @DisplayName("可调用清单只列模型分组，不列实例名（实例已不可对外调用）")
    void listCallableModels_shouldOnlyContainGroups() {
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.chat))
                .thenReturn(Set.of("m-chat-1", "m-chat-2"));
        final PoolDefinition enabled = new PoolDefinition();
        enabled.setPoolName("chat-pool");
        enabled.setServiceType("chat");
        when(poolSelector.getAllPools()).thenReturn(List.of(enabled));

        final List<Map<String, Object>> models = catalogService.listCallableModels();

        assertTrue(models.stream().noneMatch(m -> "m-chat-1".equals(m.get("id"))),
                "实例名不得出现在对外可调用清单中");
        assertTrue(models.stream().noneMatch(m -> "m-chat-2".equals(m.get("id"))),
                "实例名不得出现在对外可调用清单中");
        assertTrue(models.stream().anyMatch(m -> "chat-pool".equals(m.get("id"))),
                "分组名必须出现在可调用清单中");
    }

    @Test
    @DisplayName("v3.6 治理收口：不再输出 auto-model 这类隐式系统分组")
    void listCallableModels_shouldNotIncludeAutoModel() {
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.chat))
                .thenReturn(Set.of("m-chat-1"));
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.embedding))
                .thenReturn(Set.of());
        when(poolSelector.getAllPools()).thenReturn(List.of());

        final List<Map<String, Object>> models = catalogService.listCallableModels();

        assertTrue(models.isEmpty(), "没有显式创建的分组时,可调用清单应为空");
        assertTrue(models.stream().noneMatch(m -> "auto-model".equals(m.get("id"))),
                "auto-model 系统分组已移除,不得出现在可调用清单中");
    }

    @Test
    @DisplayName("OpenAI 可调用清单结构：object=list + data 为分组数组")
    void listCallableModelsAsOpenAiList_shouldReturnOpenAiShape() {
        when(registry.getAvailableModels(ModelServiceRegistry.ServiceType.chat))
                .thenReturn(Set.of("m-chat-1"));
        final PoolDefinition enabled = new PoolDefinition();
        enabled.setPoolName("chat-pool");
        enabled.setServiceType("chat");
        when(poolSelector.getAllPools()).thenReturn(List.of(enabled));

        final Map<String, Object> response = catalogService.listCallableModelsAsOpenAiList();

        assertEquals("list", response.get("object"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        assertTrue(data.stream().anyMatch(m -> "chat-pool".equals(m.get("id"))));
        assertTrue(data.stream().noneMatch(m -> "m-chat-1".equals(m.get("id"))));
    }
}
