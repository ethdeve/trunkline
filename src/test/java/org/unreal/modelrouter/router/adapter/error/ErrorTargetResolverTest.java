/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 #1011 的错误目标解析：target 只取 host:port（丢 userinfo/query/fragment，不携带凭证），
 * cause 链提取连接级异常的真实 URI，深度有界、回退有据。
 *
 * <p><b>反证</b>：修复前错误日志只有 {@code instance=}（实例名），且
 * {@code WebClientRequestException.getMessage()} 原文含完整 URL——凭证拼在 query 时入日志即泄露。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("ErrorTargetResolver 目标解析与脱敏（#1011）")
class ErrorTargetResolverTest {

    private WebClientRequestException webClientRequestException(final String uri) {
        return new WebClientRequestException(
                new IOException("connect timeout"), HttpMethod.POST, URI.create(uri), new HttpHeaders());
    }

    @Test
    @DisplayName("hostPort 丢弃 userinfo/query/fragment——key=SECRET 不出现在结果中")
    void hostPortDropsCredentials() {
        final URI uri = URI.create("http://user:pass@10.0.0.5:8080/v1?key=SECRET&token=x");
        final String target = ErrorTargetResolver.hostPort(uri);

        assertEquals("10.0.0.5:8080", target);
        assertFalse(target.contains("SECRET"), "query 中的凭证必须被丢弃");
        assertFalse(target.contains("user:pass"), "userinfo 中的凭证必须被丢弃");
    }

    @Test
    @DisplayName("IPv6 host 用方括号包裹")
    void ipv6HostIsBracketed() {
        assertEquals("[2001:db8::1]:9000",
                ErrorTargetResolver.hostPort(URI.create("http://[2001:db8::1]:9000/v1")));
        assertEquals("[2001:db8::1]",
                ErrorTargetResolver.hostPort(URI.create("http://[2001:db8::1]/v1")));
    }

    @Test
    @DisplayName("无显式端口不补默认端口；null/无 host 记 unknown")
    void hostPortEdgeCases() {
        assertEquals("example.com", ErrorTargetResolver.hostPort(URI.create("https://example.com/v1")));
        assertEquals(ErrorTargetResolver.UNKNOWN, ErrorTargetResolver.hostPort(null));
        assertEquals(ErrorTargetResolver.UNKNOWN, ErrorTargetResolver.hostPort(URI.create("mailto:a@b.c")));
    }

    @Test
    @DisplayName("cause 链中层的 WebClientRequestException 真实 URI 被提取（回退前）")
    void resolveViaCauseChain() {
        final WebClientRequestException leaf =
                webClientRequestException("http://10.0.0.5:8080/v1?key=SECRET");
        final RuntimeException mid = new RuntimeException("mapper failure", leaf);
        final RuntimeException top = new RuntimeException("request failure", mid);

        final String target = ErrorTargetResolver.resolveTarget(top, "https://fallback.example.com");

        assertEquals("10.0.0.5:8080", target);
        assertFalse(target.contains("SECRET"));
    }

    @Test
    @DisplayName("cause 链无连接异常时回退 baseUrl；深度上限 8 防循环")
    void fallbackAndDepthCap() {
        // 回退 baseUrl
        assertEquals("api.example.com",
                ErrorTargetResolver.resolveTarget(new RuntimeException("boom"), "https://api.example.com/v1"));

        // 8 层之外的异常不再遍历（构造 9 层纯 RuntimeException 链）→ 回退 baseUrl
        RuntimeException deep = new RuntimeException("l0");
        for (int i = 1; i <= 9; i++) {
            deep = new RuntimeException("l" + i, deep);
        }
        assertEquals("api.example.com",
                ErrorTargetResolver.resolveTarget(deep, "https://api.example.com/v1"));
    }

    @Test
    @DisplayName("null 异常/null baseUrl/非法 baseUrl 一律 unknown")
    void unknownForGarbage() {
        assertNull(ErrorTargetResolver.findUri(null));
        assertEquals(ErrorTargetResolver.UNKNOWN, ErrorTargetResolver.resolveTarget(null, null));
        assertEquals(ErrorTargetResolver.UNKNOWN, ErrorTargetResolver.resolveTarget(null, "   "));
        assertEquals(ErrorTargetResolver.UNKNOWN, ErrorTargetResolver.hostPortFromUrl("not a url\\x"));
        assertEquals(ErrorTargetResolver.UNKNOWN, ErrorTargetResolver.hostPortFromUrl(""));
    }

    @Test
    @DisplayName("baseUrl 含凭证（userinfo/query）时同样脱敏")
    void baseUrlCredentialsAreDropped() {
        assertEquals("10.0.0.9:9000",
                ErrorTargetResolver.hostPortFromUrl("http://admin:pw@10.0.0.9:9000/v1?api_key=SECRET"));
    }
}
