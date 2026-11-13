/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.unreal.modelrouter.router.adapter.mapper.ResponseMapper;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 #1011 的日志面反证：修复前错误日志只有 {@code instance=}（无 {@code target=}），
 * 修复后日志含 {@code target=host:port}，且 query 中凭证（{@code ?key=SECRET}）绝不出现。
 *
 * @author Trunkline Team
 */
@DisplayName("下游错误日志 target= 输出与凭证脱敏（#1011）")
class ErrorTargetLogTest {

    /** 模拟下游真实 URI（含凭证 query），用于解析 target。 */
    private static final java.net.URI URI_OF_DOWNSTREAM = java.net.URI.create(
            "http://10.0.0.5:8080/v1?key=SECRET");

    private ListAppender<ILoggingEvent> appender;

    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ResponseMapper.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private String joinedLogs() {
        final StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("错误日志含 target=10.0.0.5:8080 且不含 SECRET（反证修复前无 target=）")
    void logContainsSanitizedTarget() {
        final ClientResponse clientResponse = mock(ClientResponse.class);
        when(clientResponse.statusCode()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE);
        Mockito.<Mono<String>>when(clientResponse.bodyToMono(String.class))
                .thenReturn(Mono.just("{}"));

        // target 为 resolver 对 http://10.0.0.5:8080/v1?key=SECRET 的解析结果（脱敏后）
        final String target = ErrorTargetResolver.hostPort(URI_OF_DOWNSTREAM);
        new ResponseMapper(new com.fasterxml.jackson.databind.ObjectMapper())
                .handleResponseError(clientResponse, "chat-1", "/v1/chat", target)
                .block();

        final String logs = joinedLogs();
        assertTrue(logs.contains("target=10.0.0.5:8080"), "日志必须包含脱敏 target，实际: " + logs);
        assertFalse(logs.contains("SECRET"), "日志绝不能包含 query 中的凭证");
    }

    @Test
    @DisplayName("target 为 null 时记 unknown（恒定输出，消费端免判空）")
    void nullTargetLogsUnknown() {
        final ClientResponse clientResponse = mock(ClientResponse.class);
        when(clientResponse.statusCode()).thenReturn(HttpStatus.BAD_REQUEST);
        Mockito.<Mono<String>>when(clientResponse.bodyToMono(String.class))
                .thenReturn(Mono.just("{}"));

        new ResponseMapper(new com.fasterxml.jackson.databind.ObjectMapper())
                .handleResponseError(clientResponse, "chat-1", "/v1/chat", null)
                .block();

        assertTrue(joinedLogs().contains("target=unknown"));
    }
}
