package org.unreal.modelrouter.common.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import org.unreal.modelrouter.common.util.SecurityUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * issue #724 回归：请求 ID 统一解析与响应头恰一值回写。
 *
 * <p>修复前仅{@code SecurityUtils.extractRequestId}的缺失分支往响应头 add 一条
 * （且全库无统一调用点），所有响应实际都没有统一的 {@code X-Request-ID}，
 * 客户端无法凭响应头关联日志/追踪。</p>
 */
@DisplayName("RequestIdWebFilter 请求 ID 回写（#724）")
class RequestIdWebFilterTest {

    private RequestIdWebFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RequestIdWebFilter();
        chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("入站携带 X-Request-ID：沿用原值，提交后响应头恰一值")
    void inboundIdIsReusedAndWrittenBackExactlyOnce() {
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/models")
                        .header(RequestIdWebFilter.X_REQUEST_ID_HEADER, "abc")
                        .build());

        filter.filter(exchange, chain).block();
        assertEquals("abc", exchange.getAttribute(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE),
                "入站 ID 应存入 attribute 供读取方统一取用");

        exchange.getResponse().setComplete().block();
        assertEquals(List.of("abc"),
                exchange.getResponse().getHeaders().get(RequestIdWebFilter.X_REQUEST_ID_HEADER),
                "提交后 X-Request-ID 应恰为一值且等于入站 ID");
    }

    @Test
    @DisplayName("下游遗留旧值时 set 覆盖：提交后仍恰一值（不再叠加 stale）")
    void setOverwritesStaleDownstreamValue() {
        // 模拟下游 filter 又往响应头 add 了一条旧 ID
        when(chain.filter(any())).thenAnswer(inv -> {
            final MockServerWebExchange ex = inv.getArgument(0);
            ex.getResponse().getHeaders().add(RequestIdWebFilter.X_REQUEST_ID_HEADER, "stale-value");
            return Mono.empty();
        });
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/models")
                        .header(RequestIdWebFilter.X_REQUEST_ID_HEADER, "abc")
                        .build());

        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        assertEquals(List.of("abc"),
                exchange.getResponse().getHeaders().get(RequestIdWebFilter.X_REQUEST_ID_HEADER),
                "beforeCommit 用 set（非 add）覆盖，stale 旧值不得保留");
    }

    @Test
    @DisplayName("入站缺失：生成非空 ID，提交后回写恰一值")
    void missingIdIsGeneratedAndWrittenBack() {
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/models").build());

        filter.filter(exchange, chain).block();
        final String generated = exchange.getAttribute(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE);
        assertNotNull(generated, "缺失时应生成请求 ID 存入 attribute");
        assertFalse(generated.isBlank());

        exchange.getResponse().setComplete().block();
        assertEquals(List.of(generated),
                exchange.getResponse().getHeaders().get(RequestIdWebFilter.X_REQUEST_ID_HEADER),
                "提交后 X-Request-ID 应恰为一值且等于生成的 ID");
    }

    @Test
    @DisplayName("两次独立请求生成的 ID 互不相同")
    void generatedIdsDifferAcrossRequests() {
        final MockServerWebExchange first = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/models").build());
        final MockServerWebExchange second = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/models").build());

        filter.filter(first, chain).block();
        filter.filter(second, chain).block();

        final String id1 = first.getAttribute(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE);
        final String id2 = second.getAttribute(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE);
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2, "每次请求应生成独立 ID");
    }

    @Test
    @DisplayName("SecurityUtils.extractRequestId 优先读 attribute")
    void extractRequestIdPrefersAttribute() {
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/models")
                        .header(RequestIdWebFilter.X_REQUEST_ID_HEADER, "abc")
                        .build());

        filter.filter(exchange, chain).block();
        assertEquals("abc", SecurityUtils.extractRequestId(exchange),
                "读取方应从 attribute 取（而非重新走请求头/生成路径）");
    }

    @Test
    @DisplayName("SecurityUtils.extractRequestId 兜底：未装配过滤器时读请求头")
    void extractRequestIdFallsBackToRequestHeader() {
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/models")
                        .header(RequestIdWebFilter.X_REQUEST_ID_HEADER, "xyz")
                        .build());

        assertEquals("xyz", SecurityUtils.extractRequestId(exchange),
                "RequestIdWebFilter 未装配时按旧行为兜底读请求头");
    }

    @Test
    @DisplayName("order 取自 FilterOrderContract.REQUEST_ID")
    void orderComesFromContract() {
        assertEquals(FilterOrderContract.REQUEST_ID, filter.getOrder());
        Mockito.verifyNoInteractions(chain);
    }
}
