/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.common.exception.DownstreamServiceException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #647：重试侧与熔断/统计侧共用的状态码分类口径。
 *
 * <p>缺陷背景：4xx 只在重试侧被排除（#718），熔断/统计侧沿用「所有异常一律失败」，
 * 一个坏请求连续几次就能把健康实例熔断。分类必须直接取异常状态码——
 * {@code AdapterErrorHandler.classifyError} 把下游异常一律归 "503"，会掩盖真实 4xx。</p>
 */
@DisplayName("RetryPolicy 状态码分类（issue #647）")
class RetryPolicyStatusClassificationTest {

    private final RetryPolicy policy = new RetryPolicy();

    private static WebClientResponseException webError(final HttpStatus status) {
        return WebClientResponseException.create(status.value(), status.getReasonPhrase(),
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("statusOf 覆盖四种异常形态与 cause 链")
    void statusOf_shouldReadAllExceptionShapes() {
        assertEquals(400, RetryPolicy.statusOf(new ResponseStatusException(HttpStatus.BAD_REQUEST)));
        assertEquals(404, RetryPolicy.statusOf(webError(HttpStatus.NOT_FOUND)));
        assertEquals(403, RetryPolicy.statusOf(
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "forbidden",
                        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
        assertEquals(401, RetryPolicy.statusOf(new DownstreamServiceException(
                "下游认证失败", HttpStatus.UNAUTHORIZED)));
        assertEquals(400, RetryPolicy.statusOf(new IllegalStateException("wrapped",
                new ResponseStatusException(HttpStatus.BAD_REQUEST))), "包装异常应沿 cause 链下探");
        assertEquals(-1, RetryPolicy.statusOf(new IllegalStateException("unknown")));
        assertEquals(-1, RetryPolicy.statusOf(null));
    }

    @Test
    @DisplayName("400/401/403/404 等客户端 4xx 判定为真（含下游异常）")
    void clientSide4xx_shouldBeTrueForOrdinary4xx() {
        assertTrue(RetryPolicy.isClientSide4xx(new ResponseStatusException(HttpStatus.BAD_REQUEST)));
        assertTrue(RetryPolicy.isClientSide4xx(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        assertTrue(RetryPolicy.isClientSide4xx(webError(HttpStatus.UNPROCESSABLE_ENTITY)));
        assertTrue(RetryPolicy.isClientSide4xx(new DownstreamServiceException(
                "下游请求参数错误", HttpStatus.BAD_REQUEST)),
                "classifyError 会把它归 503，按状态码分类必须仍判 4xx");
        assertTrue(RetryPolicy.isClientSide4xx(new DownstreamServiceException(
                "下游认证失败", HttpStatus.UNAUTHORIZED)));
    }

    @Test
    @DisplayName("408/429 归失败不计入客户端错误（与重试侧白名单一致）")
    void timeoutAndRateLimit_shouldNotBeClientSide4xx() {
        assertFalse(RetryPolicy.isClientSide4xx(webError(HttpStatus.REQUEST_TIMEOUT)),
                "408 请求超时按失败计熔断");
        assertFalse(RetryPolicy.isClientSide4xx(webError(HttpStatus.TOO_MANY_REQUESTS)),
                "429 是上游限流，归失败计熔断（第七节定案）");
        assertTrue(policy.isRetryable(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)),
                "反证：429 在重试侧可重试，两端口径一致");
    }

    @Test
    @DisplayName("5xx / 连接超时 / 未知异常一律不是客户端错误")
    void serverAndNetworkErrors_shouldNotBeClientSide4xx() {
        assertFalse(RetryPolicy.isClientSide4xx(webError(HttpStatus.INTERNAL_SERVER_ERROR)));
        assertFalse(RetryPolicy.isClientSide4xx(new DownstreamServiceException(
                "下游服务不可用", HttpStatus.SERVICE_UNAVAILABLE)));
        assertFalse(RetryPolicy.isClientSide4xx(new TimeoutException()));
        assertFalse(RetryPolicy.isClientSide4xx(new ConnectException()));
        assertFalse(RetryPolicy.isClientSide4xx(new IllegalStateException("unknown")));
        assertFalse(RetryPolicy.isClientSide4xx(null));
    }
}
