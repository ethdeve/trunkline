package org.unreal.modelrouter.router.adapter.request;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.monitor.tracing.TracingContext;
import org.unreal.modelrouter.monitor.tracing.TracingContextHolder;
import org.unreal.modelrouter.router.adapter.builder.RequestBuilder;
import org.unreal.modelrouter.router.adapter.metrics.AdapterMetricsRecorder;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;
import org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * issue #725 回归：下游响应体 JSON 解析失败的日志改为脱敏摘要。
 *
 * <p>修复前直接打全量响应体 + {@code e.getMessage()}——Jackson 异常 message
 * 会带原文片段（如 {@code Unrecognized token 'sk-secret123'}），敏感值随异常
 * 进日志；修复后只打 traceId/bodyLen/status/前 512 字符摘要（过
 * {@code LogSanitizer}）与异常类名。</p>
 */
@DisplayName("NonStreamingRequestProcessor 坏 JSON 日志脱敏（#725）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NonStreamingRequestProcessorBadBodyLogTest {

    @Mock
    private RequestBuilder requestBuilder;

    @Mock
    private AdapterMetricsRecorder metricsRecorder;

    @Mock
    private TokenUsageExtractor tokenUsageExtractor;

    @Mock
    private ModelInstance selectedInstance;

    @Mock
    private WebClient client;

    @Mock
    private WebClient.RequestBodyUriSpec uriSpec;

    @Mock
    private WebClient.RequestBodySpec bodySpec;

    @Mock
    private WebClient.RequestHeadersSpec<?> headersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private NonStreamingRequestProcessor processor;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger loggerUnderTest;

    @BeforeEach
    void setUp() {
        // 真实 ObjectMapper：坏 JSON 自然抛 JsonProcessingException（message 含原文片段）
        processor = new NonStreamingRequestProcessor(
                new ObjectMapper(), requestBuilder, metricsRecorder, tokenUsageExtractor);

        when(tokenUsageExtractor.extractKeyIdFromRequest(any())).thenReturn(null);
        lenient().when(tokenUsageExtractor.extractRequestModel(any())).thenReturn("gpt-4");
        lenient().when(tokenUsageExtractor.rewriteModelField(any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        lenient().when(selectedInstance.getName()).thenReturn("inst-1");
        lenient().when(selectedInstance.getHeaders()).thenReturn(null);
        lenient().when(selectedInstance.getBaseUrl()).thenReturn("https://downstream.example.com");

        lenient().when(client.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        lenient().when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        lenient().when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        doReturn(headersSpec).when(bodySpec)
                .body(any(org.springframework.web.reactive.function.BodyInserter.class));
        lenient().when(headersSpec.retrieve()).thenReturn(responseSpec);
        doReturn(responseSpec).when(responseSpec).onStatus(any(), any());

        // 挂 ListAppender 捕获日志
        loggerUnderTest = (Logger) LoggerFactory.getLogger(NonStreamingRequestProcessor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        loggerUnderTest.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        loggerUnderTest.detachAppender(logAppender);
    }

    /** 构造走到 catch(JsonProcessingException) 的完整调用（真实 Jackson 解析坏 body）。 */
    private List<String> runWithBadBody(final String badBody) {
        when(responseSpec.toEntity(String.class)).thenReturn(Mono.just(
                ResponseEntity.status(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(badBody)));

        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat").build());

        // catch(JsonProcessingException) 返回 500（ResponseStatusException）——预期错误，
        // 此处吞掉以便断言日志；failureReturns500WithGenericReason 用例单独验证异常本身
        processor.processRequest(
                Map.of("model", "gpt-4"),
                null,
                client,
                "/v1/chat",
                selectedInstance,
                ServiceType.chat,
                String.class,
                "openai",
                Function.identity(),
                Function.identity(),
                null,
                exchange.getRequest(),
                null)
                .onErrorResume(e -> Mono.empty())
                .block();

        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("坏 JSON 返回 500 通用错误，日志含 traceId/bodyLen/bodyPreview/异常类名")
    void badBodyLogsSanitizedSummary() {
        final List<String> messages = runWithBadBody("sk-secret123");
        assertTrue(messages.stream().anyMatch(m -> m.contains("无法解析下游服务的响应体")),
                "应打出解析失败日志: " + messages);
        final String line = messages.stream()
                .filter(m -> m.contains("无法解析下游服务的响应体"))
                .findFirst().orElseThrow();
        assertTrue(line.contains("traceId="), "应含 traceId 字段: " + line);
        assertTrue(line.contains("bodyLen="), "应含 bodyLen 字段: " + line);
        assertTrue(line.contains("bodyPreview="), "应含 bodyPreview 字段: " + line);
        assertTrue(line.contains("exception=JsonParseException"),
                "异常只打类名（Jackson 对裸 token 抛 JsonParseException）: " + line);
    }

    @Test
    @DisplayName("Jackson 异常 message（含原文片段）不再进入日志")
    void jacksonMessageIsNotLogged() {
        // Jackson 会把 token 原文放进 Unrecognized token '...' 的 message；修复后不打 message
        final List<String> messages = runWithBadBody("sk-secret123");
        final String line = messages.stream()
                .filter(m -> m.contains("无法解析下游服务的响应体"))
                .findFirst().orElseThrow();
        assertTrue(!line.contains("Unrecognized token"),
                "不得打印 Jackson 异常 message（含原文片段）: " + line);
        assertTrue(!line.contains("was expecting"),
                "不得打印 Jackson 异常 message 的期望提示: " + line);
    }

    @Test
    @DisplayName("超长 body 只打前 512 字符摘要，尾部哨兵不进日志")
    void longBodyIsTruncatedTo512Chars() {
        final String sentinel = "SENTINEL-SECRET-TAIL";
        final String longBadBody = "A".repeat(598) + sentinel; // 618 字符
        final List<String> messages = runWithBadBody(longBadBody);
        final String line = messages.stream()
                .filter(m -> m.contains("无法解析下游服务的响应体"))
                .findFirst().orElseThrow();
        assertTrue(line.contains("bodyLen=618"), "bodyLen 应为全文长度: " + line);
        assertTrue(!line.contains(sentinel),
                "摘要限长 512，尾部哨兵不得进入日志: " + line);
    }

    @Test
    @DisplayName("解析失败返回 500 ResponseStatusException 且 reason 为通用文案")
    void failureReturns500WithGenericReason() {
        when(responseSpec.toEntity(String.class)).thenReturn(Mono.just(
                ResponseEntity.status(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("sk-secret123")));

        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat").build());

        final Throwable error = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> processor.processRequest(
                        Map.of("model", "gpt-4"), null, client, "/v1/chat",
                        selectedInstance, ServiceType.chat, String.class, "openai",
                        Function.identity(), Function.identity(), null,
                        exchange.getRequest(), null).block());

        final org.springframework.web.server.ResponseStatusException rse =
                (org.springframework.web.server.ResponseStatusException) error;
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, rse.getStatusCode());
        assertTrue(!rse.getReason().contains("sk-secret123"),
                "reason 不得回显 body 原文");
        assertNotNull(rse.getReason());
    }

    @Test
    @DisplayName("traceId 取自追踪上下文（同步捕获而非异步丢失）")
    void traceIdFieldAlwaysPresent() {
        final TracingContext context = org.mockito.Mockito.mock(TracingContext.class);
        when(context.getTraceId()).thenReturn("trace-abc-123");
        TracingContextHolder.setCurrentContext(context);
        try {
            final List<String> messages = runWithBadBody("sk-secret123");
            final String line = messages.stream()
                    .filter(m -> m.contains("无法解析下游服务的响应体"))
                    .findFirst().orElseThrow();
            assertTrue(line.contains("traceId=trace-abc-123"),
                    "traceId 应同步捕获而非异步丢失: " + line);
        } finally {
            TracingContextHolder.clearCurrentContext();
        }
    }
}
