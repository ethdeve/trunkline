package org.unreal.modelrouter.common.exceptionhandler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.unreal.modelrouter.monitor.monitoring.error.ErrorTracker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 【issue #856】兜底 500 不得把未知异常原文回显给客户端（/api 与 /v1 双契约）。
 */
@DisplayName("#856 兜底 500 不回显异常原文")
class ReactiveGlobalExceptionHandlerLeakTest {

    private static final String SECRET_DETAIL = "jdbc:h2:mem:internal-host;PASSWORD=topsecret";

    private static String bodyFor(final String path) {
        ReactiveGlobalExceptionHandler handler =
                new ReactiveGlobalExceptionHandler(mock(ErrorTracker.class));
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get(path));
        handler.handle(exchange, new RuntimeException(SECRET_DETAIL)).block();
        return exchange.getResponse().getBodyAsString().block();
    }

    @Test
    @DisplayName("反证：/api/** 响应不含 JDBC 连接串等内部信息")
    void apiBodyDoesNotLeak() {
        String body = bodyFor("/api/config/quota");
        assertNotNull(body);
        assertFalse(body.contains("internal-host"), body);
        assertFalse(body.contains("topsecret"), body);
        assertTrue(body.contains("服务内部错误，请稍后重试"), body);
    }

    @Test
    @DisplayName("反证：/v1/** 客户端协议形状同样不含异常原文")
    void v1BodyDoesNotLeak() {
        String body = bodyFor("/v1/chat/completions");
        assertNotNull(body);
        assertFalse(body.contains("internal-host"), body);
        assertFalse(body.contains("topsecret"), body);
    }

    @Test
    @DisplayName("异常消息为 null 时也不回显类名")
    void nullMessageStillGeneric() {
        ReactiveGlobalExceptionHandler handler =
                new ReactiveGlobalExceptionHandler(mock(ErrorTracker.class));
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/foo"));
        handler.handle(exchange, new RuntimeException()).block();
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertFalse(body.contains("RuntimeException"), body);
        assertTrue(body.contains("服务内部错误，请稍后重试"), body);
    }
}
