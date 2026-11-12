package org.unreal.modelrouter.common.exceptionhandler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.common.dto.SecurityErrorResponse;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import org.unreal.modelrouter.common.exception.DownstreamServiceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【issue #930】安全错误体的 path 必须来自 exchange，而不是恒为 "/api/security"。
 */
@DisplayName("#930 安全错误体动态 path")
class SecurityExceptionHandlerPathTest {

    private final SecurityExceptionHandler handler = new SecurityExceptionHandler();

    private static ServerWebExchange exchange(final String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(uri));
    }

    private static String pathOf(final ResponseEntity<?> response) {
        return ((SecurityErrorResponse) response.getBody()).getPath();
    }

    @Test
    @DisplayName("反证：两个不同路径返回不同 path，且不再是硬编码 /api/security")
    void pathsAreDynamic() {
        AuthenticationException ex = new AuthenticationException("认证失败", "AUTH_FAILED");

        String first = pathOf(handler.handleAuthenticationException(ex, exchange("/api/config/quota")));
        String second = pathOf(handler.handleAuthenticationException(ex, exchange("/api/security/audit")));

        assertEquals("/api/config/quota", first);
        assertEquals("/api/security/audit", second);
        assertNotEquals(first, second);
        assertNotEquals("/api/security", first);
    }

    @Test
    @DisplayName("带 query 的 URL 不得把 token 写进错误体（与 #695 联动）")
    void queryIsNotInPath() {
        AuthenticationException ex = new AuthenticationException("认证失败", "AUTH_FAILED");
        String path = pathOf(handler.handleAuthenticationException(
                ex, exchange("/api/config/quota?token=abc&api_key=sk-xxx")));

        assertEquals("/api/config/quota", path);
        assertFalse(path.contains("token"));
    }

    @Test
    @DisplayName("exchange 为 null 时返回空串，不 NPE")
    void nullExchangeIsSafe() {
        AuthenticationException ex = new AuthenticationException("认证失败", "AUTH_FAILED");
        assertEquals("", pathOf(handler.handleAuthenticationException(ex, null)));
    }

    @Test
    @DisplayName("handleDownstreamServiceException 补上 path（旧实现漏设）")
    void downstreamPathIsSet() {
        DownstreamServiceException ex = mock(DownstreamServiceException.class);
        when(ex.getStatusCode()).thenReturn(HttpStatus.BAD_GATEWAY);
        when(ex.getMessage()).thenReturn("downstream failed");

        assertEquals("/api/models/probe",
                pathOf(handler.handleDownstreamServiceException(ex, exchange("/api/models/probe"))));
    }
}
