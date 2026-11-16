package org.unreal.modelrouter.router.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.common.dto.ChatDTO;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.enterprise.spi.ToolGovernanceProvider;
import org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory;
import org.unreal.modelrouter.router.adapter.checker.CapabilityChecker;
import org.unreal.modelrouter.router.adapter.error.AdapterErrorHandler;
import org.unreal.modelrouter.router.adapter.error.ErrorResponseBuilder;
import org.unreal.modelrouter.router.adapter.metrics.AdapterMetricsRecorder;
import org.unreal.modelrouter.router.adapter.retry.RetryPolicy;
import org.unreal.modelrouter.router.adapter.selector.InstanceSelector;
import org.unreal.modelrouter.router.adapter.support.AdapterContext;
import org.unreal.modelrouter.router.adapter.support.RequestProcessingSupport;
import org.unreal.modelrouter.router.adapter.support.ResilienceSupport;
import org.unreal.modelrouter.router.adapter.tracing.AdapterTracingManager;
import org.unreal.modelrouter.router.loadbalancer.SelectedInstanceHolder;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * M4 Agent 工具治理挂载测试：fail-closed 三重保险 + STRIP 语义 + 挂载顺序。
 *
 * <p>本组用例同时是行为级守卫：任何人把主干挂载改成「异常吞掉放行 / null 判定放行 /
 * 剥离契约违约放行 / 纯文本请求误拦截」，对应用例立即转红。工具治理的失败模式
 * 必须是「拒绝」。</p>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("M4：Agent 工具治理挂载（fail-closed 三重保险 + STRIP 语义）")
class BaseAdapterToolGovernanceTest {

    @Mock
    private ModelServiceRegistry registry;
    @Mock
    private InstanceSelector instanceSelector;
    @Mock
    private RetryPolicy retryPolicy;
    @Mock
    private AdapterErrorHandler errorHandler;
    @Mock
    private AdapterMetricsRecorder metricsRecorder;
    @Mock
    private AdapterTracingManager tracingManager;
    @Mock
    private ErrorResponseBuilder errorResponseBuilder;
    @Mock
    private CapabilityChecker capabilityChecker;
    @Mock
    private TracingWebClientFactory tracingFactory;
    @Mock
    private WebClient webClient;
    @Mock
    private ServerHttpRequest httpRequest;

    private org.springframework.context.ApplicationContext springContext;
    private TestAdapter adapter;

    private static final ToolGovernanceProvider.GovernanceDecision ALLOW =
            ToolGovernanceProvider.GovernanceDecision.allow();

    private static Map<String, Object> wireTool(final String name) {
        final Map<String, Object> function = new HashMap<>();
        function.put("name", name);
        function.put("description", "d-" + name);
        final Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private static Map<String, Object> wireToolMessage(final String content) {
        final Map<String, Object> message = new HashMap<>();
        message.put("role", "tool");
        message.put("content", content);
        return message;
    }

    @BeforeEach
    void setUp() {
        springContext = org.mockito.Mockito.mock(org.springframework.context.ApplicationContext.class);
        lenient().when(springContext.getBean(TracingWebClientFactory.class)).thenReturn(tracingFactory);
        ApplicationContextProvider provider = new ApplicationContextProvider();
        provider.setApplicationContext(springContext);

        lenient().when(tracingFactory.createTracingWebClient(anyString())).thenReturn(webClient);
        lenient().when(httpRequest.getHeaders()).thenReturn(new HttpHeaders());
        lenient().when(httpRequest.getAttributes()).thenReturn(new HashMap<>());

        AdapterContext context = new AdapterContext(registry, new ObjectMapper(), null);
        RequestProcessingSupport requestSupport = new RequestProcessingSupport(
                null, null, instanceSelector, null, null, null, null, null);
        ResilienceSupport resilienceSupport = new ResilienceSupport(
                capabilityChecker, errorHandler, retryPolicy, metricsRecorder,
                tracingManager, errorResponseBuilder);

        adapter = new TestAdapter(context, requestSupport, resilienceSupport);

        lenient().when(errorHandler.classifyError(any())).thenReturn("503");
        lenient().when(errorResponseBuilder.buildErrorResponse(any(Throwable.class)))
                .thenAnswer(inv -> Mono.error((Throwable) inv.getArgument(0)));
        lenient().when(instanceSelector.selectInstance(any(), any(), any()))
                .thenReturn(createInstance("inst-a", "http://host-a:8080"));
        lenient().when(instanceSelector.getModelPath(any(), anyString())).thenReturn("/v1/chat/completions");
    }

    private static ModelRouterProperties.ModelInstance createInstance(final String name, final String baseUrl) {
        final ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName(name);
        instance.setBaseUrl(baseUrl);
        instance.setPath("/v1/chat/completions");
        return instance;
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(adapter, "moderationProvider", null);
        ReflectionTestUtils.setField(adapter, "outboundContentInspector", null);
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider", null);
        SelectedInstanceHolder.clear();
        new ApplicationContextProvider().setApplicationContext(null);
    }

    // ==================== 静态 helper ====================

    @Test
    @DisplayName("extractToolResultTexts：role=tool 的 String 与分段数组均可提取，其余角色跳过")
    void extractToolResultTextsSupportsBothShapes() {
        final Map<String, Object> segment = new HashMap<>();
        segment.put("type", "text");
        segment.put("text", "segment text");
        final Map<String, Object> listContentMessage = new HashMap<>();
        listContentMessage.put("role", "tool");
        listContentMessage.put("content", List.of(segment));
        final Map<String, Object> assistant = new HashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "not a tool result");

        final List<String> texts = BaseAdapter.extractToolResultTexts(List.of(
                wireToolMessage("plain result"), assistant, listContentMessage));
        assertEquals(List.of("plain result", "segment text"), texts);
    }

    @Test
    @DisplayName("extractToolNames：wire 形状非法位置映射为 null，顺序保持")
    void extractToolNamesMapsIllegalShapeToNull() {
        final Map<String, Object> broken = new HashMap<>();
        broken.put("type", "function");
        assertEquals(java.util.Arrays.asList("a", null, "b"),
                BaseAdapter.extractToolNames(List.of(wireTool("a"), broken, wireTool("b"))));
    }

    @Test
    @DisplayName("stripToolDefinitions：全剥清空 tools/tool_choice/wire_messages，请求降级纯文本")
    void stripAllClearsToolFields() {
        final ChatDTO.Request chat = toolChat(List.of(wireTool("a")), List.of(wireToolMessage("r")));
        final ChatDTO.Request stripped = BaseAdapter.stripToolDefinitions(chat, List.of());

        assertNull(stripped.options().getTools());
        assertNull(stripped.options().getToolChoice());
        assertNull(stripped.options().getWireMessages());
        assertEquals(chat.messages(), stripped.messages(), "消息文本视图不变");
        assertEquals(chat.user(), stripped.user());
    }

    @Test
    @DisplayName("stripToolDefinitions：部分剥仅替换 tools，tool_choice 与历史保留")
    void stripPartialReplacesToolsOnly() {
        final ChatDTO.Request chat = toolChat(List.of(wireTool("a"), wireTool("b")),
                List.of(wireToolMessage("r")));
        final ChatDTO.Request stripped = BaseAdapter.stripToolDefinitions(chat,
                List.of(wireTool("keep")));

        assertEquals(List.of("keep"), BaseAdapter.extractToolNames(stripped.options().getTools()));
        assertTrue(stripped.options().getToolChoice() != null, "tool_choice 保留");
        assertTrue(stripped.options().getWireMessages() != null, "工具历史保留");
    }

    @Test
    @DisplayName("stripToolDefinitions：allowedTools 为 null（契约违约）返回 null（fail-closed 前置）")
    void stripRejectsContractViolation() {
        final ChatDTO.Request chat = toolChat(List.of(wireTool("a")), null);
        assertNull(BaseAdapter.stripToolDefinitions(chat, null), "null 列表违约");
    }

    // ==================== 挂载行为（fail-closed 守卫） ====================

    @Test
    @DisplayName("纯文本请求（无工具且无回传）：governor 零调用，零开销直通")
    void plainTextRequestsBypassGovernance() {
        final AtomicInteger governorCalls = new AtomicInteger();
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input -> {
                    governorCalls.incrementAndGet();
                    return ALLOW;
                });

        final Mono<?> result = adapter.processRequest(
                new ChatDTO.Request("gpt", List.of(new ChatDTO.Message("user", "plain", null)),
                        false, null, null, null, null, null, null, null, "requester", null),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertEquals(0, governorCalls.get(), "纯文本请求零介入");
    }

    @Test
    @DisplayName("ALLOW：带工具请求原样进入 processor")
    void allowPassesOriginalToolRequest() {
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input -> ALLOW);
        final ChatDTO.Request chat = toolChat(List.of(wireTool("search")), null);
        final AtomicReference<Object> received = new AtomicReference<>();

        final Mono<?> result = adapter.processRequest(chat, "Bearer t", httpRequest,
                ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> {
                    received.set(req);
                    return Mono.just(ResponseEntity.ok("{}"));
                });

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertEquals(chat, received.get(), "放行时 processor 必须收到原始请求对象");
    }

    @Test
    @DisplayName("BLOCK：返回 TOOL_BLOCKED，上游零调用")
    void blockRejectsBeforeInstanceSelection() {
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input -> ToolGovernanceProvider.GovernanceDecision.block(
                        "agent-tool-registry", "请求包含未注册或未授权的 Agent 工具"));
        final AtomicInteger processorCalls = new AtomicInteger();

        final Mono<?> result = adapter.processRequest(toolChat(List.of(wireTool("evil")), null),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> {
                    processorCalls.incrementAndGet();
                    return Mono.just(ResponseEntity.ok("{}"));
                });

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> {
                    assertTrue(t instanceof ApiException, "应为 ApiException");
                    assertEquals("TOOL_BLOCKED", ((ApiException) t).getErrorCode());
                })
                .verify();
        assertEquals(0, processorCalls.get(), "阻断时上游零调用");
    }

    @Test
    @DisplayName("STRIP 全剥：processor 收到降级纯文本请求")
    void stripAllDowngradesToPlainText() {
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input -> ToolGovernanceProvider.GovernanceDecision.stripAll(
                        "agent-tool-registry", "全部工具未授权"));
        final AtomicReference<ChatDTO.Request> received = new AtomicReference<>();

        final Mono<?> result = adapter.processRequest(
                toolChat(List.of(wireTool("a")), List.of(wireToolMessage("r"))),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> {
                    received.set((ChatDTO.Request) req);
                    return Mono.just(ResponseEntity.ok("{}"));
                });

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertNull(received.get().options().getTools(), "全剥后 tools 必须为 null");
        assertNull(received.get().options().getToolChoice());
        assertNull(received.get().options().getWireMessages());
    }

    @Test
    @DisplayName("守卫①：SPI 实现抛异常 → fail-closed 拒绝出站，绝不放行")
    void governorFailureFailsClosed() {
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input -> {
                    throw new IllegalStateException("注册表连接失败");
                });
        final AtomicInteger processorCalls = new AtomicInteger();

        final Mono<?> result = adapter.processRequest(toolChat(List.of(wireTool("a")), null),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> {
                    processorCalls.incrementAndGet();
                    return Mono.just(ResponseEntity.ok("{}"));
                });

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> assertEquals("TOOL_BLOCKED", ((ApiException) t).getErrorCode()))
                .verify();
        assertEquals(0, processorCalls.get(), "治理失败时上游零调用——fail-closed");
    }

    @Test
    @DisplayName("守卫②：SPI 返回 null 判定 → fail-closed 拒绝出站")
    void nullDecisionFailsClosed() {
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input -> null);

        final Mono<?> result = adapter.processRequest(toolChat(List.of(wireTool("a")), null),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> assertEquals("TOOL_BLOCKED", ((ApiException) t).getErrorCode()))
                .verify();
    }

    @Test
    @DisplayName("守卫③：STRIP 携带 null allowedTools（契约违约）→ fail-closed 拒绝出站")
    void stripContractViolationFailsClosed() {
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input ->
                        new ToolGovernanceProvider.GovernanceDecision(
                                ToolGovernanceProvider.Verdict.STRIP, null, "x", "违约"));

        final Mono<?> result = adapter.processRequest(toolChat(List.of(wireTool("a")), null),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> assertEquals("TOOL_BLOCKED", ((ApiException) t).getErrorCode()))
                .verify();
    }

    @Test
    @DisplayName("无 tools 但有 tool_result 回传：同样进入治理（回传文本可被复检）")
    void toolResultOnlyRequestsAreGoverned() {
        final AtomicReference<ToolGovernanceProvider.GovernanceInput> seen = new AtomicReference<>();
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input -> {
                    seen.set(input);
                    return ALLOW;
                });

        final Mono<?> result = adapter.processRequest(
                toolChat(null, List.of(wireToolMessage("tool output"))),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertTrue(seen.get() != null, "仅工具结果回传也应触发治理");
        assertEquals(List.of("tool output"), seen.get().toolResultTexts());
    }

    @Test
    @DisplayName("governor 收到预提取工具名与租户上下文（契约：toolNames 与 tools 一一对应）")
    void governorReceivesToolNamesAndTenant() {
        final AtomicReference<ToolGovernanceProvider.GovernanceInput> seen = new AtomicReference<>();
        ReflectionTestUtils.setField(adapter, "toolGovernanceProvider",
                (ToolGovernanceProvider) input -> {
                    seen.set(input);
                    return ALLOW;
                });

        final Mono<?> result = adapter.processRequest(toolChat(List.of(wireTool("a"), wireTool("b")), null),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertEquals(java.util.Arrays.asList("a", "b"), seen.get().toolNames());
        assertEquals(2, seen.get().tools().size());
    }

    // ==================== 测试用适配器 ====================

    /** 带工具 options 的 chat 请求。 */
    private static ChatDTO.Request toolChat(final List<Map<String, Object>> tools,
            final List<Map<String, Object>> wireMessages) {
        ChatDTO.Options options = null;
        if (tools != null) {
            options = ChatDTO.Options.builder().tools(tools).toolChoice("auto")
                    .wireMessages(wireMessages).build();
        } else if (wireMessages != null) {
            options = ChatDTO.Options.builder().wireMessages(wireMessages).build();
        }
        return new ChatDTO.Request("gpt", List.of(new ChatDTO.Message("user", "hi", null)),
                false, null, null, null, null, null, null, null, "requester", options);
    }

    /** 与 BaseAdapterOutboundDlpTest 同款最小适配器。 */
    static class TestAdapter extends BaseAdapter {
        TestAdapter(final AdapterContext context, final RequestProcessingSupport requestSupport,
                    final ResilienceSupport resilienceSupport) {
            super(context, requestSupport, resilienceSupport);
        }

        @Override
        public AdapterCapabilities supportCapability() {
            return null;
        }

        @Override
        protected String getAdapterType() {
            return "test";
        }
    }
}
