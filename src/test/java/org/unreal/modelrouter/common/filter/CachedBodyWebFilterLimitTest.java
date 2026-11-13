package org.unreal.modelrouter.common.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #700 回归：请求体超限不再放行。
 *
 * <p>修复前超限分支 {@code chain.filter(exchange)} 直接放行——body 已被
 * {@code DataBufferUtils.join} 消费，下游只会读到空 body，语义静默损坏；
 * 修复后改返 502（BAD_GATEWAY）让调用方明确感知，codec 层
 * {@code DataBufferLimitException} 也统一转 502。</p>
 */
@DisplayName("CachedBodyWebFilter 超限拒绝（#700）")
class CachedBodyWebFilterLimitTest {

    private final WebFilterChain chain = mock(WebFilterChain.class);

    private MockServerWebExchange exchangeOf(final String body) {
        // MockServerHttpRequest 不会自动设置 Content-Length，必须显式声明（hasBody 依据）
        final MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length())
                .body(body);
        return MockServerWebExchange.from(request);
    }

    private MockServerWebExchange exchangeOfChunked(final Flux<DataBuffer> body) {
        final MockServerHttpRequest request = MockServerHttpRequest.post("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Transfer-Encoding", "chunked")
                .body(body);
        return MockServerWebExchange.from(request);
    }

    @Test
    @DisplayName("body 超过缓存上限：返回 502 且 chain.filter 未被调用")
    void oversizedBodyReturns502AndSkipsChain() {
        // 上限 16 字节，实际 32 字节
        final CachedBodyWebFilter filter = new CachedBodyWebFilter(16);
        final MockServerWebExchange exchange = exchangeOf("0123456789abcdef0123456789abcdef");

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> filter.filter(exchange, chain).block());

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode(), "超限应返回 502 而非放行");
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("codec 层 DataBufferLimitException 统一转 502（不再是裸 500）")
    void dataBufferLimitExceptionBecomes502() {
        final CachedBodyWebFilter filter = new CachedBodyWebFilter(16);
        final MockServerWebExchange exchange = exchangeOfChunked(
                Flux.<DataBuffer>error(new DataBufferLimitException("limit exceeded")));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> filter.filter(exchange, chain).block());

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode(),
                "DataBufferLimitException 应特判转 502");
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("未超限的 body 照常缓存并交给下游，且可重复读取")
    void bodyWithinLimitIsCachedAndReplayable() {
        final CachedBodyWebFilter filter = new CachedBodyWebFilter(16);
        final String body = "{\"a\":1}";
        final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        final MockServerWebExchange exchange = exchangeOf(body);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        final ArgumentCaptor<ServerWebExchange> captor =
                ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        final ServerWebExchange mutated = captor.getValue();

        // 重复读两次，验证可重放（修复目标本身）
        final DataBuffer first = mutated.getRequest().getBody().blockFirst();
        assertNotNull(first);
        assertEquals(bodyBytes.length, first.readableByteCount());
        final DataBuffer second = mutated.getRequest().getBody().blockFirst();
        assertNotNull(second);
        assertEquals(bodyBytes.length, second.readableByteCount());
    }

    @Test
    @DisplayName("超限 502 的 reason 不回显 body 原文")
    void oversizedBodyReasonDoesNotEchoBody() {
        final CachedBodyWebFilter filter = new CachedBodyWebFilter(16);
        final String secretPayload = "TOKEN-SENTINEL-ABC" + "0123456789abcdef0123456789abcdef";
        final MockServerWebExchange exchange = exchangeOf(secretPayload);

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> filter.filter(exchange, chain).block());

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        final String reason = ex.getReason() == null ? "" : ex.getReason();
        assertFalse(reason.contains("TOKEN-SENTINEL-ABC"), "502 reason 不得包含请求体原文");
    }
}
