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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.common.exception.DownstreamServiceException;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory;
import org.unreal.modelrouter.router.adapter.error.AdapterErrorHandler;
import org.unreal.modelrouter.router.adapter.error.ErrorResponseBuilder;
import org.unreal.modelrouter.router.adapter.metrics.AdapterMetricsRecorder;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #647：BaseAdapter 错误分流——客户端侧 4xx 不得计入实例熔断失败。
 *
 * <p>缺陷背景：所有异常统一走 {@code recordCompleteCall(..., false)} →
 * {@code recordCallFailure} → {@code circuitBreakerManager.recordFailure}；
 * 重试侧已排除 4xx（#718），熔断/统计侧没排除，一个坏请求连续几次就能熔断健康实例。</p>
 *
 * <p>本测试驱动真实 {@code BaseAdapter.processRequest}（复用 FailoverTest 的装配手法），
 * 断言错误记录分流；Registry 层「不调 recordFailure」由
 * {@code ModelServiceRegistryClientErrorTest} 断言。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BaseAdapter 客户端 4xx 熔断分流（issue #647）")
class BaseAdapterClientErrorCircuitTest {

    @Mock private ModelServiceRegistry registry;
    @Mock private InstanceSelector instanceSelector;
    @Mock private RetryPolicy retryPolicy;
    @Mock private AdapterErrorHandler errorHandler;
    @Mock private AdapterMetricsRecorder metricsRecorder;
    @Mock private AdapterTracingManager tracingManager;
    @Mock private ErrorResponseBuilder errorResponseBuilder;
    @Mock private CapabilityChecker capabilityChecker;
    @Mock private TracingWebClientFactory tracingFactory;
    @Mock private WebClient webClient;
    @Mock private ServerHttpRequest httpRequest;

    private ModelRouterProperties.ModelInstance instance;
    private MockedStatic<ApplicationContextProvider> acpMock;
    private TestAdapter adapter;

    @BeforeEach
    void setUp() {
        instance = new ModelRouterProperties.ModelInstance();
        instance.setName("instA");
        instance.setInstanceId("instA");
        instance.setBaseUrl("http://host-a:8080");
        instance.setPath("/v1/chat/completions");

        SelectedInstanceHolder.clear();
        acpMock = mockStatic(ApplicationContextProvider.class);
        acpMock.when(() -> ApplicationContextProvider.getBean(TracingWebClientFactory.class))
                .thenReturn(tracingFactory);
        lenient().when(tracingFactory.createTracingWebClient(anyString())).thenReturn(webClient);
        lenient().when(httpRequest.getHeaders()).thenReturn(new HttpHeaders());

        AdapterContext context = new AdapterContext(registry, new ObjectMapper(), null);
        RequestProcessingSupport requestSupport = new RequestProcessingSupport(
                null, null, instanceSelector, null, null, null, null, null);
        ResilienceSupport resilienceSupport = new ResilienceSupport(
                capabilityChecker, errorHandler, retryPolicy, metricsRecorder,
                tracingManager, errorResponseBuilder);
        adapter = new TestAdapter(context, requestSupport, resilienceSupport);

        lenient().when(instanceSelector.selectInstance(any(), anyString(), anyString()))
                .thenReturn(instance);
        lenient().when(instanceSelector.getModelPath(any(), anyString()))
                .thenReturn("/v1/chat/completions");
        lenient().when(errorHandler.classifyError(any())).thenReturn("503");
        lenient().when(retryPolicy.canRetry(anyInt(), any(), anyInt())).thenReturn(false);
        lenient().when(errorResponseBuilder.buildErrorResponse(any(Throwable.class)))
                .thenAnswer(inv -> Mono.error((Throwable) inv.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        SelectedInstanceHolder.clear();
        acpMock.close();
    }

    private void runWithError(final Throwable error) {
        BaseAdapter.RequestProcessor<Object> processor =
                (req, auth, client, path, inst, st) -> Mono.error(error);
        Mono<?> result = adapter.doProcessRequest(new Object(), "Bearer test", httpRequest,
                ModelServiceRegistry.ServiceType.chat, "test-model", processor);
        StepVerifier.create(result).expectError().verify();
    }

    @Test
    @DisplayName("400：走客户端错误记录，errorCode 记真实 4xx，不记失败（反证修复前记 1 次失败）")
    void badRequest_shouldBeClientErrorOnly() {
        runWithError(new ResponseStatusException(HttpStatus.BAD_REQUEST));

        final ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(metricsRecorder, times(1)).recordClientErrorCompleteCall(
                anyString(), anyString(), anyLong(), code.capture(),
                anyString(), any(), eq(instance));
        assertEquals("400", code.getValue(), "统计/调用历史应记真实 4xx，而非 classifyError 的 503");
        verify(metricsRecorder, never()).recordCompleteCall(anyString(), anyString(), anyLong(),
                eq(false), any(), any(), any(), any());
    }

    @Test
    @DisplayName("下游 400（classifyError 归 503）仍按状态码判 4xx")
    void downstream4xxMaskedAs503_shouldStillBeClientError() {
        runWithError(new DownstreamServiceException("下游请求参数错误", HttpStatus.BAD_REQUEST));

        verify(metricsRecorder, times(1)).recordClientErrorCompleteCall(
                anyString(), anyString(), anyLong(), eq("400"), anyString(), any(), eq(instance));
        verify(metricsRecorder, never()).recordCompleteCall(anyString(), anyString(), anyLong(),
                eq(false), any(), any(), any(), any());
    }

    @Test
    @DisplayName("5xx / 连接失败 / 超时：仍按失败记录")
    void serverAndNetworkErrors_shouldStillCountFailure() {
        runWithError(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
        runWithError(new ConnectException("refused"));
        runWithError(new TimeoutException("timeout"));

        // 这三类累计 3 次，各自都走常规失败记录
        verify(metricsRecorder, times(3)).recordCompleteCall(anyString(), anyString(), anyLong(),
                eq(false), any(), any(), any(), any());
        verify(metricsRecorder, never()).recordClientErrorCompleteCall(anyString(), anyString(),
                anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("429 是上游限流：按失败计熔断（反证被 4xx 规则误排除）")
    void tooManyRequests_shouldCountFailure() {
        runWithError(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));

        verify(metricsRecorder, times(1)).recordCompleteCall(anyString(), anyString(), anyLong(),
                eq(false), any(), any(), any(), any());
        verify(metricsRecorder, never()).recordClientErrorCompleteCall(anyString(), anyString(),
                anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("408 请求超时：按失败计熔断")
    void requestTimeout_shouldCountFailure() {
        runWithError(new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT));

        verify(metricsRecorder, times(1)).recordCompleteCall(anyString(), anyString(), anyLong(),
                eq(false), any(), any(), any(), any());
        verify(metricsRecorder, never()).recordClientErrorCompleteCall(anyString(), anyString(),
                anyLong(), any(), any(), any(), any());
    }

    /** 暴露 protected processRequest 的测试适配器（与 BaseAdapterFailoverTest 同款）。 */
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
