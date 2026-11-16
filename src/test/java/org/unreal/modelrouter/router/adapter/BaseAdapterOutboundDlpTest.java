package org.unreal.modelrouter.router.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.common.dto.ChatDTO;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider;
import org.unreal.modelrouter.enterprise.spi.OutboundContentInspector;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;


/**
 * B2 出站 DLP 挂载测试：fail-closed 三重保险 + 脱敏回填 + 挂载顺序。
 *
 * <p>本组用例同时是行为级守卫：任何人把主干挂载改成「异常吞掉放行 / null 判定放行 /
 * 脱敏契约违约放行」，对应用例立即转红。防泄露的失败模式必须是「拒绝」。</p>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("B2：出站 DLP 挂载（fail-closed 三重保险）")
class BaseAdapterOutboundDlpTest {

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
    @Mock
    private ModerationProvider moderationProvider;

    private MockedStatic<ApplicationContextProvider> acpMock;
    private org.springframework.context.ApplicationContext springContext;
    private TestAdapter adapter;

    private static final OutboundContentInspector.InspectionDecision ALLOW =
            OutboundContentInspector.InspectionDecision.allow();

    @BeforeEach
    void setUp() {
        // DLP 链路把 doProcessRequest 挪到 boundedElastic 线程执行，MockedStatic 是
        // ThreadLocal 作用域、跨线程不生效——必须用全局静态 ApplicationContext 提供依赖。
        springContext = org.mockito.Mockito.mock(org.springframework.context.ApplicationContext.class);
        lenient().when(springContext.getBean(TracingWebClientFactory.class)).thenReturn(tracingFactory);
        ApplicationContextProvider provider = new ApplicationContextProvider();
        provider.setApplicationContext(springContext);

        lenient().when(tracingFactory.createTracingWebClient(anyString())).thenReturn(webClient);
        lenient().when(httpRequest.getHeaders()).thenReturn(new HttpHeaders());
        lenient().when(httpRequest.getAttributes()).thenReturn(new java.util.HashMap<>());

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
        SelectedInstanceHolder.clear();
        // 复位静态上下文（ApplicationContextProvider 是 static mutable，测试间不得残留）
        new ApplicationContextProvider().setApplicationContext(null);
    }

    // ==================== helper（static 方法） ====================

    @Test
    @DisplayName("chatMessageTexts：逐条提取，null 消息映射为 null 元素")
    void chatMessageTextsPreservesOrderAndNulls() {
        final ChatDTO.Request chat = chatOf(
                java.util.Arrays.asList(
                        new ChatDTO.Message("system", "You are helpful.", null),
                        null,
                        new ChatDTO.Message("user", null, null),
                        new ChatDTO.Message("user", "hello", "alice")));
        final List<String> texts = BaseAdapter.chatMessageTexts(chat);
        assertEquals(4, texts.size());
        assertEquals("You are helpful.", texts.get(0));
        assertNull(texts.get(1));
        assertNull(texts.get(2));
        assertEquals("hello", texts.get(3));
    }

    @Test
    @DisplayName("sanitizeChatRequest：等长逐条替换，role/name 与其余参数保留")
    void sanitizeRebuildsRequestPreservingFields() {
        final ChatDTO.Request chat = chatOf(List.of(
                new ChatDTO.Message("system", "sys prompt", null),
                new ChatDTO.Message("user", "my id is 110101199003077777", "alice")));
        final ChatDTO.Request sanitized = BaseAdapter.sanitizeChatRequest(chat,
                List.of("sys prompt", "my id is [REDACTED:CN_ID]"));

        assertEquals(2, sanitized.messages().size());
        assertEquals("system", sanitized.messages().get(0).role());
        assertEquals("sys prompt", sanitized.messages().get(0).content());
        assertEquals("user", sanitized.messages().get(1).role());
        assertEquals("alice", sanitized.messages().get(1).name());
        assertEquals("my id is [REDACTED:CN_ID]", sanitized.messages().get(1).content());
        assertEquals("gpt", sanitized.model());
        assertEquals("requester", sanitized.user());
    }

    @Test
    @DisplayName("sanitizeChatRequest：不等长 / 缺失 / null 输入一律返回 null（fail-closed 前置）")
    void sanitizeRejectsContractViolations() {
        final ChatDTO.Request chat = chatOf(List.of(new ChatDTO.Message("user", "a", null)));
        assertNull(BaseAdapter.sanitizeChatRequest(chat, List.of("a", "b")), "多一个元素也违约");
        assertNull(BaseAdapter.sanitizeChatRequest(chat, List.of()), "少一个元素也违约");
        assertNull(BaseAdapter.sanitizeChatRequest(chat, null), "缺失列表违约");
        assertNull(BaseAdapter.sanitizeChatRequest(null, List.of("a")), "null 请求违约");
    }

    // ==================== 挂载行为（fail-closed 守卫） ====================

    @Test
    @DisplayName("ALLOW：原请求原样进入 processor")
    void allowPassesOriginalRequest() {
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> ALLOW);
        final ChatDTO.Request chat = chatOf(List.of(new ChatDTO.Message("user", "normal text", null)));
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
    @DisplayName("SANITIZE：processor 收到脱敏后的请求，原文不出站")
    void sanitizeReplacesRequestForUpstream() {
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> OutboundContentInspector.InspectionDecision.sanitize(
                        List.of("my id is [REDACTED:CN_ID]"), "cn-id-card", "已脱敏"));
        final ChatDTO.Request chat = chatOf(List.of(
                new ChatDTO.Message("user", "my id is 110101199003077777", null)));
        final AtomicReference<ChatDTO.Request> received = new AtomicReference<>();

        final Mono<?> result = adapter.processRequest(chat, "Bearer t", httpRequest,
                ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> {
                    received.set((ChatDTO.Request) req);
                    return Mono.just(ResponseEntity.ok("{}"));
                });

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertEquals("my id is [REDACTED:CN_ID]", received.get().messages().get(0).content(),
                "上游必须只拿到脱敏文本");
    }

    @Test
    @DisplayName("BLOCK：返回 DLP_BLOCKED，上游零调用")
    void blockRejectsBeforeInstanceSelection() {
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> OutboundContentInspector.InspectionDecision.block(
                        "api-key-pattern", "出站内容包含疑似密钥"));
        final AtomicInteger processorCalls = new AtomicInteger();

        final Mono<?> result = adapter.processRequest(
                chatOf(List.of(new ChatDTO.Message("user", "sk-abcdefghijklmnop1234", null))),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> {
                    processorCalls.incrementAndGet();
                    return Mono.just(ResponseEntity.ok("{}"));
                });

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> {
                    assertTrue(t instanceof ApiException, "应为 ApiException");
                    assertEquals("DLP_BLOCKED", ((ApiException) t).getErrorCode());
                })
                .verify();
        assertEquals(0, processorCalls.get(), "阻断时上游零调用");
    }

    @Test
    @DisplayName("守卫①：SPI 实现抛异常 → fail-closed 拒绝出站，绝不放行")
    void inspectorFailureFailsClosed() {
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> {
                    throw new IllegalStateException("规则引擎数据库连接失败");
                });
        final AtomicInteger processorCalls = new AtomicInteger();

        final Mono<?> result = adapter.processRequest(
                chatOf(List.of(new ChatDTO.Message("user", "anything", null))),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> {
                    processorCalls.incrementAndGet();
                    return Mono.just(ResponseEntity.ok("{}"));
                });

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> assertEquals("DLP_BLOCKED", ((ApiException) t).getErrorCode()))
                .verify();
        assertEquals(0, processorCalls.get(), "检查失败时上游零调用——fail-closed");
    }

    @Test
    @DisplayName("守卫②：SPI 返回 null 判定 → fail-closed 拒绝出站")
    void nullDecisionFailsClosed() {
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> null);

        final Mono<?> result = adapter.processRequest(
                chatOf(List.of(new ChatDTO.Message("user", "anything", null))),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> assertEquals("DLP_BLOCKED", ((ApiException) t).getErrorCode()))
                .verify();
    }

    @Test
    @DisplayName("守卫③：SANITIZE 脱敏列表与消息列表不等长 → fail-closed 拒绝出站")
    void sanitizeContractViolationFailsClosed() {
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> OutboundContentInspector.InspectionDecision.sanitize(
                        List.of("a", "b"), "x", "违约长度"));

        final Mono<?> result = adapter.processRequest(
                chatOf(List.of(new ChatDTO.Message("user", "anything", null))),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> assertEquals("DLP_BLOCKED", ((ApiException) t).getErrorCode()))
                .verify();
    }

    @Test
    @DisplayName("挂载顺序：O7-c 审核拦截短路后 DLP 不再执行")
    void moderationBlockShortCircuitsDlp() {
        ReflectionTestUtils.setField(adapter, "moderationProvider", moderationProvider);
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> {
                    org.junit.jupiter.api.Assertions.fail("审核拦截后不应再执行 DLP");
                    return ALLOW;
                });
        lenient().when(moderationProvider.check(any(ModerationProvider.ModerationInput.class)))
                .thenReturn(ModerationProvider.ModerationDecision.block("违规内容"));

        final Mono<?> result = adapter.processRequest(
                chatOf(List.of(new ChatDTO.Message("user", "bad content", null))),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result)
                .expectErrorSatisfies(t -> assertEquals("CONTENT_BLOCKED", ((ApiException) t).getErrorCode()))
                .verify();
    }

    @Test
    @DisplayName("非 chat 请求（embedding）不经过 DLP，inspector 零调用")
    void nonChatRequestsBypassDlp() {
        final AtomicInteger inspectorCalls = new AtomicInteger();
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> {
                    inspectorCalls.incrementAndGet();
                    return ALLOW;
                });

        final org.unreal.modelrouter.common.dto.EmbeddingDTO.Request embedding =
                new org.unreal.modelrouter.common.dto.EmbeddingDTO.Request(
                        "text-embed", List.of("hello"), null, null, null, null);
        final Mono<?> result = adapter.processRequest(embedding, "Bearer t", httpRequest,
                ModelServiceRegistry.ServiceType.embedding, "text-embed",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertEquals(0, inspectorCalls.get(), "B2 第一版只接 chat，其余类型零介入");
    }

    @Test
    @DisplayName("moderation 与 DLP 同时放行：链路串联正常（既有行为不变）")
    void moderationThenDlpBothAllow() {
        ReflectionTestUtils.setField(adapter, "moderationProvider", moderationProvider);
        ReflectionTestUtils.setField(adapter, "outboundContentInspector",
                (OutboundContentInspector) input -> ALLOW);
        lenient().when(moderationProvider.check(any(ModerationProvider.ModerationInput.class)))
                .thenReturn(ModerationProvider.ModerationDecision.allow());
        lenient().when(retryPolicy.getMaxRetriesByServiceType(any())).thenReturn(0);

        final Mono<?> result = adapter.processRequest(
                chatOf(List.of(new ChatDTO.Message("user", "fine", null))),
                "Bearer t", httpRequest, ModelServiceRegistry.ServiceType.chat, "gpt",
                (req, auth, client, path, instance, st) -> Mono.just(ResponseEntity.ok("{}")));

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
    }

    // ==================== 测试用适配器 ====================

    private static ChatDTO.Request chatOf(final List<ChatDTO.Message> messages) {
        return new ChatDTO.Request("gpt", messages, false,
                null, null, null, null, null, null, null, "requester", null);
    }

    /** 与 BaseAdapterInstanceSelectionTest 同款最小适配器。 */
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
