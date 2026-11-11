/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 重试白名单：4xx 不再被重放，瞬时故障与 5xx 的既有容错能力保持不变。 */
@DisplayName("RetryPolicy 重试白名单")
class RetryPolicyClientErrorTest {

    private final RetryPolicy policy = new RetryPolicy();

    private static WebClientResponseException webError(final HttpStatus status) {
        return WebClientResponseException.create(status.value(), status.getReasonPhrase(),
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("WebFlux / WebServer 的 4xx 不再重试（避免重复生成与重复计费）")
    void clientErrorsAreNotRetried() {
        assertFalse(policy.isRetryable(webError(HttpStatus.BAD_REQUEST)));
        assertFalse(policy.isRetryable(webError(HttpStatus.UNAUTHORIZED)));
        assertFalse(policy.isRetryable(webError(HttpStatus.FORBIDDEN)));
        assertFalse(policy.isRetryable(webError(HttpStatus.NOT_FOUND)));
        assertFalse(policy.isRetryable(new ResponseStatusException(HttpStatus.BAD_REQUEST)));
    }

    @Test
    @DisplayName("408 / 429 / 5xx 与瞬时网络故障仍可重试")
    void transientAndServerErrorsAreRetried() {
        assertTrue(policy.isRetryable(webError(HttpStatus.REQUEST_TIMEOUT)));
        assertTrue(policy.isRetryable(webError(HttpStatus.TOO_MANY_REQUESTS)));
        assertTrue(policy.isRetryable(webError(HttpStatus.INTERNAL_SERVER_ERROR)));
        assertTrue(policy.isRetryable(webError(HttpStatus.BAD_GATEWAY)));
        assertTrue(policy.isRetryable(new TimeoutException()));
        assertTrue(policy.isRetryable(new ConnectException()));
    }

    @Test
    @DisplayName("未知异常沿用原有容错语义，null 不重试")
    void unknownAndNull() {
        assertTrue(policy.isRetryable(new IllegalStateException("unknown")));
        assertFalse(policy.isRetryable(null));
    }
}
