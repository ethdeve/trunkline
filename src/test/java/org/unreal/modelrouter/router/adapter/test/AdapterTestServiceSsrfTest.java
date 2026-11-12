package org.unreal.modelrouter.router.adapter.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.router.adapter.AdapterRegistry;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【issue #928】SSRF 拒绝必须冒泡成 HTTP 400，不能变成 200 + success=true。
 */
@DisplayName("#928 适配器测试 SSRF 拒绝语义")
class AdapterTestServiceSsrfTest {

    private AdapterTestService service;

    @BeforeEach
    void setUp() {
        // allowPrivateNetwork 默认 false（字段注入仅在生产由 @Value 覆盖），即最严格口径。
        // testRegisteredAdapter 会先查注册表；mock 默认 false 会在 URL 校验前抛「适配器不存在」。
        AdapterRegistry registry = mock(AdapterRegistry.class);
        when(registry.isAdapterSupported(anyString())).thenReturn(true);
        service = new AdapterTestService(mock(WebClient.Builder.class), registry);
    }

    private static void expectInvalidBaseUrl(final reactor.core.publisher.Mono<AdapterTestResult> call) {
        StepVerifier.create(call)
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ApiException, "必须抛 ApiException: " + error);
                    ApiException api = (ApiException) error;
                    assertEquals("INVALID_BASE_URL", api.getErrorCode());
                    assertEquals(HttpStatus.BAD_REQUEST, api.getStatus(),
                            "INVALID_* 必须映射成 400，不能是 200/500");
                    assertFalse(api.getMessage().contains("127.0.0.1"));
                    assertFalse(api.getMessage().contains("169.254"));
                    assertTrue(api.getMessage().contains("不合法"));
                })
                .verify();
    }

    @Test
    @DisplayName("反证：回环地址不再返回 200 成功体")
    void loopbackRejected() {
        expectInvalidBaseUrl(service.testPreview(
                "openai", "http://127.0.0.1:8080", "Authorization", "", "PING", null));
    }

    @Test
    @DisplayName("反证：云元数据地址不再返回 200 成功体")
    void metadataAddressRejected() {
        expectInvalidBaseUrl(service.testPreview(
                "openai", "http://169.254.169.254/latest/meta-data", "Authorization", "", "PING", null));
    }

    @Test
    @DisplayName("反证：非 http(s) scheme 不再返回 200 成功体")
    void fileSchemeRejected() {
        expectInvalidBaseUrl(service.testPreview(
                "openai", "file:///etc/passwd", "Authorization", "", "PING", null));
    }

    @Test
    @DisplayName("testPing / testRegisteredAdapter 三条入口同口径")
    void allEntryPointsReject() {
        expectInvalidBaseUrl(service.testPing("http://localhost:8080", "Authorization", ""));
        expectInvalidBaseUrl(service.testRegisteredAdapter(
                "openai", "PING", "", null, "http://127.0.0.1:9"));
    }
}
