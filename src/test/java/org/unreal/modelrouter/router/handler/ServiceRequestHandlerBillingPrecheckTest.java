/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.billing.service.BillingPrecheckService;
import org.unreal.modelrouter.enterprise.spi.BillingProvider;
import org.unreal.modelrouter.enterprise.spi.BillingProvider.PrecheckResult;
import org.unreal.modelrouter.router.adapter.AdapterRegistry;
import org.unreal.modelrouter.router.adapter.ServiceCapability;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * B2 预算前置熔断主链集成测试（方案 3.7 / DoD #6）。
 *
 * <p>走真实 {@code ServiceRequestHandler.handleRequest(exchange)}：预算耗尽必须在实例选择
 * 与下游调用之前短路成 402；社区 Noop / 账本异常 / 服务未装配时零行为变化（200 放行）。</p>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServiceRequestHandler 预算前置熔断集成测试")
class ServiceRequestHandlerBillingPrecheckTest {

    private static final String KEY_ID = "key-billing";
    private static final String BEARER = "Bearer downstream-token";

    @Mock
    private AdapterRegistry adapterRegistry;
    @Mock
    private ModelServiceRegistry registry;
    @Mock
    private ServiceStateManager serviceStateManager;
    @Mock
    private ServiceCapability adapter;
    @Mock
    private BillingProvider billingProvider;

    private ServiceRequestHandler handler;
    private ServiceRequestExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        when(serviceStateManager.isServiceHealthy(anyString())).thenReturn(true);
        final ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setInstanceId("inst-1");
        instance.setName("gpt-4");
        instance.setBaseUrl("http://downstream.local");
        instance.setAdapter("openai-compat");
        when(registry.selectInstance(any(), anyString(), anyString(), any())).thenReturn(instance);
        when(adapterRegistry.getAdapter(any(ServiceType.class), any())).thenReturn(adapter);

        executor = mock(ServiceRequestExecutor.class);
        when(executor.execute(any(), any(), any()))
                .thenReturn(Mono.just(ResponseEntity.ok("downstream-ok")));

        handler = new ServiceRequestHandler(adapterRegistry, registry, serviceStateManager, null, null);
    }

    @Test
    @DisplayName("预算耗尽：402 且不触实例选择与下游（真正的前置拒绝）")
    void budgetExhaustedRejectsBeforeRouting() throws Exception {
        when(billingProvider.precheck(any())).thenReturn(PrecheckResult.deny("当月预算已耗尽"));
        injectPrecheckService(providerOf(billingProvider));
        final ServerWebExchange exchange = buildExchange();

        StepVerifier.create(invoke(exchange))
                .expectErrorMatches(error -> error instanceof ResponseStatusException statusException
                        && statusException.getStatusCode() == HttpStatus.PAYMENT_REQUIRED
                        && statusException.getReason() != null
                        && statusException.getReason().contains("预算已耗尽"))
                .verify(Duration.ofSeconds(10));

        verify(registry, never()).selectInstance(any(), anyString(), anyString(), any());
        verify(executor, never()).execute(any(), any(), any());
        verify(billingProvider, times(1)).precheck(any());
    }

    @Test
    @DisplayName("预算充足：200 放行，前置校验恰一次")
    void budgetAvailablePassesThrough() throws Exception {
        when(billingProvider.precheck(any())).thenReturn(PrecheckResult.allow());
        injectPrecheckService(providerOf(billingProvider));
        final ServerWebExchange exchange = buildExchange();

        final ResponseEntity<?> response = invoke(exchange).block(Duration.ofSeconds(10));

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(registry, times(1)).selectInstance(any(), anyString(), anyString(), any());
        verify(executor, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("账本异常：fail-open 放行，不产生 5xx")
    void providerExceptionFailsOpen() throws Exception {
        when(billingProvider.precheck(any())).thenThrow(new IllegalStateException("billing db down"));
        injectPrecheckService(providerOf(billingProvider));

        final ResponseEntity<?> response = invoke(buildExchange()).block(Duration.ofSeconds(10));

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("前置服务未装配（社区版）：零行为变化，200 放行")
    void missingServiceDoesNotChangeBehavior() throws Exception {
        // 不注入 billingPrecheckService
        final ResponseEntity<?> response = invoke(buildExchange()).block(Duration.ofSeconds(10));

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(executor, times(1)).execute(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BillingProvider> providerOf(final BillingProvider billing) {
        final ObjectProvider<BillingProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(billing);
        return provider;
    }

    private void injectPrecheckService(final ObjectProvider<BillingProvider> provider) throws Exception {
        final Field field = ServiceRequestHandler.class.getDeclaredField("billingPrecheckService");
        field.setAccessible(true);
        field.set(handler, new BillingPrecheckService(provider));
    }

    private Mono<ResponseEntity<?>> invoke(final ServerWebExchange exchange) {
        return handler.handleRequest(ServiceEndpoint.CHAT, "gpt-4", BEARER, exchange, executor)
                .contextWrite(ReactiveSecurityContextHolder
                        .withSecurityContext(Mono.just(securityContext())));
    }

    private static ServerWebExchange buildExchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/chat/completions").build());
    }

    private static SecurityContextImpl securityContext() {
        final ApiKeyAuthentication authentication =
                new ApiKeyAuthentication(KEY_ID, "sk-" + KEY_ID, List.of("chat"));
        authentication.setAuthenticated(true);
        final SecurityContextImpl context = new SecurityContextImpl();
        context.setAuthentication(authentication);
        return context;
    }
}
