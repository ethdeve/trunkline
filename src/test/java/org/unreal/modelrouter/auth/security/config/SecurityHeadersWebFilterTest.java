/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 安全响应头过滤器的行为网：CSP / Permissions-Policy 全站生效，
 * HSTS 只在安全传输下发（本地 http 开发实例不受影响），开关可整体回退。
 */
@DisplayName("CSP / HSTS / Permissions-Policy 响应头")
class SecurityHeadersWebFilterTest {

    private static HttpHeaders apply(final String uri, final String forwardedProto,
                                     final boolean enabled) {
        final HttpHeaders responseHeaders = new HttpHeaders();
        final HttpHeaders requestHeaders = new HttpHeaders();
        if (forwardedProto != null) {
            requestHeaders.set("X-Forwarded-Proto", forwardedProto);
        }

        final ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(responseHeaders);
        final ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        when(request.getHeaders()).thenReturn(requestHeaders);
        final ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getResponse()).thenReturn(response);
        when(exchange.getRequest()).thenReturn(request);
        final WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        final SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter();
        ReflectionTestUtils.setField(filter, "enabled", enabled);
        ReflectionTestUtils.setField(filter, "contentSecurityPolicy", "");
        ReflectionTestUtils.setField(filter, "hstsMaxAgeSeconds", 31_536_000L);

        filter.filter(exchange, chain).block();
        return responseHeaders;
    }

    @Test
    @DisplayName("http 请求下发 CSP 与 Permissions-Policy，但不下发 HSTS")
    void httpGetsCspAndPermissionsOnly() {
        HttpHeaders headers = apply("http://localhost:8080/admin/", null, true);
        assertTrue(headers.containsKey(SecurityHeadersWebFilter.CSP_HEADER));
        assertTrue(headers.getFirst(SecurityHeadersWebFilter.CSP_HEADER).contains("default-src 'self'"));
        assertTrue(headers.containsKey("Permissions-Policy"));
        assertFalse(headers.containsKey(SecurityHeadersWebFilter.HSTS_HEADER),
                "HSTS 不应出现在 http 响应中，避免影响本地开发实例");
    }

    @Test
    @DisplayName("https 请求追加 HSTS")
    void httpsGetsHsts() {
        HttpHeaders headers = apply("https://gateway.example.com/admin/", null, true);
        assertEquals("max-age=31536000", headers.getFirst(SecurityHeadersWebFilter.HSTS_HEADER));
    }

    @Test
    @DisplayName("可信代理标记为 https 时追加 HSTS")
    void forwardedHttpsGetsHsts() {
        HttpHeaders headers = apply("http://internal/admin/", "https", true);
        assertEquals("max-age=31536000", headers.getFirst(SecurityHeadersWebFilter.HSTS_HEADER));
    }

    @Test
    @DisplayName("关闭开关后不写入任何头（运维可一键回退）")
    void disabledWritesNothing() {
        HttpHeaders headers = apply("https://gateway.example.com/admin/", null, false);
        assertFalse(headers.containsKey(SecurityHeadersWebFilter.CSP_HEADER));
        assertFalse(headers.containsKey("Permissions-Policy"));
        assertFalse(headers.containsKey(SecurityHeadersWebFilter.HSTS_HEADER));
    }
}
