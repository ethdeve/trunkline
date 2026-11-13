package org.unreal.modelrouter.router.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.router.adapter.AdapterRegistry;
import org.unreal.modelrouter.router.adapter.ServiceCapability;
import org.unreal.modelrouter.router.adapter.config.AdapterDefinitionProperties;
import org.unreal.modelrouter.router.adapter.persistence.AdapterDefinitionPersistenceService;
import org.unreal.modelrouter.router.adapter.support.AdapterContext;
import org.unreal.modelrouter.router.adapter.support.RequestProcessingSupport;
import org.unreal.modelrouter.router.adapter.support.ResilienceSupport;
import org.unreal.modelrouter.router.adapter.transformer.OpenAiRequestTransformer;
import org.unreal.modelrouter.router.adapter.transformer.OpenAiResponseTransformer;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdapterConfigController} 写入顺序契约测试（issue #709）。
 *
 * <p>修复前「先落库后校验」：POST extend + parent 不存在时仍 {@code saveDefinition}，
 * 构造失败只返 500——配置已脏、重启加载到坏 adapter；修复后按
 * 「校验 → 构造 → 落库 → 切内存」重排，register 失败反向回滚。</p>
 */
@DisplayName("AdapterConfigController 写入顺序契约（#709）")
class AdapterConfigControllerTest {

    private AdapterRegistry adapterRegistry;
    private AdapterDefinitionProperties adapterDefinitionProperties;
    private AdapterDefinitionPersistenceService persistenceService;
    private AdapterConfigController controller;

    @BeforeEach
    void setUp() {
        adapterRegistry = mock(AdapterRegistry.class);
        adapterDefinitionProperties = new AdapterDefinitionProperties();
        persistenceService = mock(AdapterDefinitionPersistenceService.class);
        controller = new AdapterConfigController(
                adapterRegistry,
                adapterDefinitionProperties,
                persistenceService,
                mock(AdapterContext.class),
                mock(RequestProcessingSupport.class),
                mock(ResilienceSupport.class),
                mock(OpenAiRequestTransformer.class),
                mock(OpenAiResponseTransformer.class));
    }

    /** 默认请求：名称合法、非内置、未注册（mock 未 stub 的 boolean 默认 false 即满足）。 */
    private AdapterConfigController.AdapterDefinitionRequest baseRequest() {
        AdapterConfigController.AdapterDefinitionRequest request =
                new AdapterConfigController.AdapterDefinitionRequest();
        request.setName("my-adapter");
        request.setDescription("测试方言");
        return request;
    }

    @Test
    @DisplayName("ADP-701: extend 缺 parent → 400 且不落库不注册")
    void extendWithoutParentRejected() {
        AdapterConfigController.AdapterDefinitionRequest request = baseRequest();
        request.setType("extend");

        ResponseEntity<RouterResponse<Map<String, Object>>> response = controller.createAdapter(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_PARENT", response.getBody().getErrorCode());
        verify(persistenceService, never()).saveDefinition(anyString(), any());
        verify(adapterRegistry, never()).registerAdapter(anyString(), any());
        assertFalse(adapterDefinitionProperties.getAdapterDefinitions().containsKey("my-adapter"),
                "校验失败时内存配置不得被写入（反证旧 put 已发生）");
    }

    @Test
    @DisplayName("ADP-702: extend parent 不存在 → 400 且不落库不注册")
    void extendWithMissingParentRejected() {
        when(adapterRegistry.getAdapterByName("no-such-parent")).thenReturn(null);
        AdapterConfigController.AdapterDefinitionRequest request = baseRequest();
        request.setType("extend");
        request.setParent("no-such-parent");

        ResponseEntity<RouterResponse<Map<String, Object>>> response = controller.createAdapter(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("no-such-parent"),
                "400 文案应包含缺失的 parent 名称");
        verify(persistenceService, never()).saveDefinition(anyString(), any());
        verify(adapterRegistry, never()).registerAdapter(anyString(), any());
        assertFalse(adapterRegistry.isAdapterSupported("my-adapter"));
    }

    @Test
    @DisplayName("ADP-703: 合法 extend 创建成功 → 201，落库/注册各恰一次")
    void validExtendCreationSucceeds() {
        ServiceCapability parentAdapter = mock(ServiceCapability.class);
        when(adapterRegistry.getAdapterByName("parent-builtin")).thenReturn(parentAdapter);
        AdapterConfigController.AdapterDefinitionRequest request = baseRequest();
        request.setType("extend");
        request.setParent("parent-builtin");

        ResponseEntity<RouterResponse<Map<String, Object>>> response = controller.createAdapter(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(persistenceService).saveDefinition(eq("my-adapter"), any());
        verify(adapterRegistry).registerAdapter(eq("my-adapter"), any());
        assertTrue(adapterDefinitionProperties.getAdapterDefinitions().containsKey("my-adapter"),
                "成功路径应切内存");
    }

    @Test
    @DisplayName("ADP-704: auth=null 创建 openai-compatible → 201 且不 NPE（默认 auth 补齐）")
    void nullAuthDoesNotNpe() {
        // request.auth 保持 null；type 缺省 → openai-compatible
        AdapterConfigController.AdapterDefinitionRequest request = baseRequest();

        ResponseEntity<RouterResponse<Map<String, Object>>> response = controller.createAdapter(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(), "auth 缺省应补默认而非 NPE");
        verify(persistenceService).saveDefinition(eq("my-adapter"), any());
    }

    @Test
    @DisplayName("ADP-705: register 失败 → 反向回滚持久化与内存配置")
    void registerFailureRollsBack() {
        doThrow(new RuntimeException("registry boom"))
                .when(adapterRegistry).registerAdapter(anyString(), any());
        AdapterConfigController.AdapterDefinitionRequest request = baseRequest();

        ResponseEntity<RouterResponse<Map<String, Object>>> response = controller.createAdapter(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        verify(persistenceService).removeDefinition("my-adapter");
        assertFalse(adapterDefinitionProperties.getAdapterDefinitions().containsKey("my-adapter"),
                "register 失败后内存配置应回滚");
    }

    @Test
    @DisplayName("ADP-706: 删除顺序 = 先摘运行时再清配置与持久化")
    void deleteUnregistersBeforePersisting() {
        AdapterConfigController.AdapterDefinitionRequest request = baseRequest();
        controller.createAdapter(request);

        controller.deleteAdapter("my-adapter");

        InOrder inOrder = inOrder(adapterRegistry, persistenceService);
        inOrder.verify(adapterRegistry).removeAdapter("my-adapter");
        inOrder.verify(persistenceService).removeDefinition("my-adapter");
    }

    @Test
    @DisplayName("ADP-707: 更新路径同构——extend parent 不存在 → 400 且不落库")
    void updateWithMissingParentRejected() {
        // 先创建一个可更新的 adapter（持久化 + 注册 + 内存）
        ServiceCapability parentAdapter = mock(ServiceCapability.class);
        when(adapterRegistry.getAdapterByName("parent-builtin")).thenReturn(parentAdapter);
        AdapterConfigController.AdapterDefinitionRequest create = baseRequest();
        controller.createAdapter(create);

        AdapterConfigController.AdapterDefinitionRequest update = baseRequest();
        update.setType("extend");
        update.setParent("no-such");

        ResponseEntity<RouterResponse<Map<String, Object>>> response = controller.updateAdapter("my-adapter", update);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        // 落库次数恰为创建时的 1 次，更新失败不追加
        verify(persistenceService, times(1)).saveDefinition(eq("my-adapter"), any());
    }
}
