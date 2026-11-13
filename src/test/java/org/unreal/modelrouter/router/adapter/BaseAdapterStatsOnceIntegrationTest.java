/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.common.dto.ChatDTO;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.monitor.callhistory.ApiCallHistoryRecorder;
import org.unreal.modelrouter.monitor.callhistory.dto.CallHistoryRecordDTO;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.monitor.service.TokenUsageService;
import org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory;
import org.unreal.modelrouter.persistence.repository.ModelCallStatsRepository;
import org.unreal.modelrouter.router.adapter.error.AdapterErrorHandler;
import org.unreal.modelrouter.router.adapter.error.ErrorResponseBuilder;
import org.unreal.modelrouter.router.adapter.metrics.AdapterMetricsRecorder;
import org.unreal.modelrouter.router.adapter.request.NonStreamingRequestProcessor;
import org.unreal.modelrouter.router.adapter.request.TokenUsageExtractor;
import org.unreal.modelrouter.router.adapter.retry.RetryPolicy;
import org.unreal.modelrouter.router.adapter.selector.InstanceSelector;
import org.unreal.modelrouter.router.adapter.support.AdapterContext;
import org.unreal.modelrouter.router.adapter.support.RequestProcessingSupport;
import org.unreal.modelrouter.router.adapter.support.ResilienceSupport;
import org.unreal.modelrouter.router.adapter.tracing.AdapterTracingManager;
import org.unreal.modelrouter.router.adapter.checker.CapabilityChecker;
import org.unreal.modelrouter.router.loadbalancer.SelectedInstanceHolder;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.monitor.service.TokenUsageRecorder;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 一次成功请求统计「恰一次」的端到端回归（v3.1 上层统计双通道收口）。
 *
 * <p>真实链路：{@code BaseAdapter.processRequest} → {@code NonStreamingRequestProcessor}
 * → 真实 {@code AdapterMetricsRecorder}。修复前 processor 层调
 * {@code recordCompleteCall}，与 BaseAdapter 的 {@code recordCompleteCallStats}
 * 各记一次统计；修复后 processor 只走 {@code recordCallHistoryOnly}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("一次成功请求统计恰一次（端到端）")
class BaseAdapterStatsOnceIntegrationTest {

    @Mock private ModelServiceRegistry registry;
    @Mock private InstanceSelector instanceSelector;
    @Mock private RetryPolicy retryPolicy;
    @Mock private AdapterErrorHandler errorHandler;
    @Mock private ErrorResponseBuilder errorResponseBuilder;
    @Mock private CapabilityChecker capabilityChecker;
    @Mock private AdapterTracingManager tracingManager;
    @Mock private TracingWebClientFactory tracingFactory;
    @Mock private MetricsCollector metricsCollector;
    @Mock private ModelCallStatsRepository statsRepository;
    @Mock private ApiCallHistoryRecorder historyRecorder;
    @Mock private TokenUsageService tokenUsageService;

    private AdapterMetricsRecorder metricsRecorder;
    private NonStreamingRequestProcessor processor;
    private TestAdapter adapter;
    private ModelRouterProperties.ModelInstance instance;
    private ServerHttpRequest httpRequest;
    private MockedStatic<ApplicationContextProvider> acpMock;

    @BeforeEach
    void setUp() throws Exception {
        instance = new ModelRouterProperties.ModelInstance();
        instance.setName("instA");
        instance.setInstanceId("instA");
        instance.setBaseUrl("http://downstream.local");
        instance.setPath("/v1/chat/completions");

        httpRequest = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/chat/completions").build()).getRequest();
        SelectedInstanceHolder.clear();

        final ObjectMapper objectMapper = new ObjectMapper();
        metricsRecorder = new AdapterMetricsRecorder(metricsCollector, statsRepository, registry);
        final Field field = AdapterMetricsRecorder.class.getDeclaredField("callHistoryRecorder");
        field.setAccessible(true);
        field.set(metricsRecorder, historyRecorder);

        final TokenUsageExtractor extractor = new TokenUsageExtractor(objectMapper,
                new TokenUsageRecorder(tokenUsageService), null, null, null, null);
        processor = new NonStreamingRequestProcessor(objectMapper, null, metricsRecorder, extractor);

        acpMock = mockStatic(ApplicationContextProvider.class);
        acpMock.when(() -> ApplicationContextProvider.getBean(TracingWebClientFactory.class))
                .thenReturn(tracingFactory);
        lenient().when(tracingFactory.createTracingWebClient(anyString()))
                .thenReturn(clientOf(okResponse()));

        final AdapterContext context = new AdapterContext(registry, objectMapper, null);
        final RequestProcessingSupport requestSupport = new RequestProcessingSupport(
                null, null, instanceSelector, null, null, null, null, null);
        final ResilienceSupport resilience = new ResilienceSupport(
                capabilityChecker, errorHandler, retryPolicy, metricsRecorder,
                tracingManager, errorResponseBuilder);
        adapter = new TestAdapter(context, requestSupport, resilience);

        lenient().when(instanceSelector.selectInstance(any(), anyString(), anyString()))
                .thenReturn(instance);
        lenient().when(instanceSelector.getModelPath(any(), anyString()))
                .thenReturn("/v1/chat/completions");
        lenient().when(errorHandler.classifyError(any())).thenReturn("503");
        lenient().when(retryPolicy.canRetry(anyInt(), any(), anyInt())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        SelectedInstanceHolder.clear();
        acpMock.close();
    }

    @Test
    @DisplayName("真实成功链路：Registry/MetricsCollector/StatsRepository 各恰一次，调用历史恰一次")
    void successRequest_shouldRecordStatsExactlyOnce() {
        final ResponseEntity<?> response = runSuccess(false);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(registry, times(1)).recordCallComplete(any(), eq(instance), anyLong(), eq(true));
        verify(metricsCollector, times(1)).recordBackendCall(anyString(), anyString(), anyLong(), eq(true));
        verify(statsRepository, times(1)).updateStats(anyString(), anyString(), eq(true), anyLong());
        verify(historyRecorder, times(1)).record(any(CallHistoryRecordDTO.class));
    }

    @Test
    @DisplayName("反证：processor 若额外调 recordCompleteCall，统计立刻变成两次")
    void legacyProcessorBehaviour_shouldDoubleCountStats() {
        runSuccess(true);

        verify(registry, times(2)).recordCallComplete(any(), eq(instance), anyLong(), eq(true));
        verify(metricsCollector, times(2)).recordBackendCall(anyString(), anyString(), anyLong(), eq(true));
        verify(statsRepository, times(2)).updateStats(anyString(), anyString(), eq(true), anyLong());
    }

    private ResponseEntity<?> runSuccess(final boolean legacyBehaviour) {
        final BaseAdapter.RequestProcessor<Object> delegate =
                (req, auth, client, path, inst, st) -> processor.processRequest(
                        req, auth, client, path, inst, st,
                        String.class, "test", r -> r, r -> r, null, httpRequest, null);

        final BaseAdapter.RequestProcessor<Object> effective = legacyBehaviour
                ? (req, auth, client, path, inst, st) -> delegate
                        .process(req, auth, client, path, inst, st)
                        .doOnSuccess(ignored -> metricsRecorder.recordCompleteCall(
                                "test", inst.getName(), 1L, true, null, "gpt-4", st, inst))
                : delegate;

        final Object request = new ChatDTO.Request("gpt-4",
                List.of(new ChatDTO.Message("user", "hello", null)),
                false, 256, 0.0, null, null, null, null, null, null, null);
        return (ResponseEntity<?>) adapter.doProcessRequest(request, "Bearer test", httpRequest,
                ModelServiceRegistry.ServiceType.chat, "gpt-4", effective)
                .block(Duration.ofSeconds(10));
    }

    private static WebClient clientOf(final ClientResponse response) {
        return WebClient.builder().exchangeFunction(ignored -> Mono.just(response)).build();
    }

    private static ClientResponse okResponse() {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"model\":\"gpt-4\",\"choices\":[]}")
                .build();
    }

    /** 暴露 protected processRequest 的测试适配器。 */
    private static class TestAdapter extends BaseAdapter {

        TestAdapter(final AdapterContext context, final RequestProcessingSupport requestSupport,
                    final ResilienceSupport resilienceSupport) {
            super(context, requestSupport, resilienceSupport);
        }

        @Override
        protected String getAdapterType() {
            return "test";
        }

        @Override
        public AdapterCapabilities supportCapability() {
            return AdapterCapabilities.builder().chat(true).build();
        }

        @SuppressWarnings("unchecked")
        Mono<?> doProcessRequest(final Object request, final String auth,
                                 final ServerHttpRequest req,
                                 final ModelServiceRegistry.ServiceType serviceType,
                                 final String modelName,
                                 final BaseAdapter.RequestProcessor<?> processor) {
            return processRequest(request, auth, req, serviceType, modelName,
                    (BaseAdapter.RequestProcessor<Object>) processor);
        }
    }
}
