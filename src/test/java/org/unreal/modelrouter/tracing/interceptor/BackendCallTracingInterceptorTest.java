package org.unreal.modelrouter.monitor.tracing.interceptor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.unreal.modelrouter.monitor.tracing.TracingContext;
import org.unreal.modelrouter.monitor.tracing.TracingContextHolder;
import org.unreal.modelrouter.monitor.tracing.logger.StructuredLogger;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackendCallTracingInterceptorTest {

    @Mock
    private StructuredLogger structuredLogger;

    @Mock
    private TracingContext tracingContext;

    @Mock
    private Span span;

    @Mock
    private ClientResponse response;

    @Mock
    private ExchangeFunction exchangeFunction;

    private BackendCallTracingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new BackendCallTracingInterceptor(structuredLogger);
    }

    @Test
    void testFilterWithoutActiveTracingContext() {
        // Given
        try (var mockStatic = mockStatic(TracingContextHolder.class)) {
            mockStatic.when(TracingContextHolder::getCurrentContext).thenReturn(null);
            
            ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://example.com")).build();
            when(exchangeFunction.exchange(request)).thenReturn(Mono.just(response));

            // When
            Mono<ClientResponse> result = interceptor.filter(request, exchangeFunction);

            // Then
            StepVerifier.create(result)
                    .expectNext(response)
                    .verifyComplete();
            verify(exchangeFunction).exchange(request);
        }
    }

    @Test
    void testFilterWithActiveTracingContext_Success() {
        // Given
        try (var mockStatic = mockStatic(TracingContextHolder.class)) {
            mockStatic.when(TracingContextHolder::getCurrentContext).thenReturn(tracingContext);
            when(tracingContext.isActive()).thenReturn(true);
            when(tracingContext.createSpan(anyString(), any(SpanKind.class))).thenReturn(span);
            
            ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://api.openai.com/v1/chat/completions")).build();
            
            ClientResponse response = mock(ClientResponse.class);
            when(response.statusCode()).thenReturn(org.springframework.http.HttpStatus.OK);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            // When & Then
            StepVerifier.create(interceptor.filter(request, exchangeFunction))
                    .expectNext(response)
                    .verifyComplete();
        }
    }

    @Test
    void testInferAdapterType() {
        // Given
        String openaiHost = "api.openai.com";
        String ollamaHost = "ollama.local";
        String unknownHost = "unknown.service.com";

        // When
        String openaiAdapter = interceptor.inferAdapterType(openaiHost);
        String ollamaAdapter = interceptor.inferAdapterType(ollamaHost);
        String unknownAdapter = interceptor.inferAdapterType(unknownHost);

        // Then
        assertEquals("openai", openaiAdapter);
        assertEquals("ollama", ollamaAdapter);
        assertEquals("unknown", unknownAdapter);
    }

    @Test
    void testFilterRedactsSensitiveQueryInSpanUrl() {
        // Given: 下游 URL 的 query 里带密钥，修复前会原样写进 CLIENT span 和结构化日志
        try (var mockStatic = mockStatic(TracingContextHolder.class)) {
            mockStatic.when(TracingContextHolder::getCurrentContext).thenReturn(tracingContext);
            when(tracingContext.isActive()).thenReturn(true);
            when(tracingContext.createSpan(anyString(), any(SpanKind.class))).thenReturn(span);

            ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create(
                    "https://api.example.com/v1/chat/completions?api_key=LEAK_KEY_123&model=gpt-4o"))
                    .build();
            ClientResponse ok = mock(ClientResponse.class);
            when(ok.statusCode()).thenReturn(org.springframework.http.HttpStatus.OK);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(ok));

            // When
            StepVerifier.create(interceptor.filter(request, exchangeFunction))
                    .expectNext(ok)
                    .verifyComplete();

            // Then: 反证——span 属性不得含明文密钥，但非敏感参数保留
            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            verify(span).setAttribute(org.mockito.ArgumentMatchers.eq("http.url"), url.capture());
            assertFalse(url.getValue().contains("LEAK_KEY_123"), "http.url 不得明文落 api_key");
            assertTrue(url.getValue().contains("api_key=[REDACTED]"));
            assertTrue(url.getValue().contains("model=gpt-4o"), "非敏感参数应保留");
        }
    }
}