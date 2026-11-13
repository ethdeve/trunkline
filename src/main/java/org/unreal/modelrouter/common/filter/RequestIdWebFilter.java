package org.unreal.modelrouter.common.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.unreal.modelrouter.common.util.SecurityUtils;
import reactor.core.publisher.Mono;

/**
 * 请求 ID 统一回写过滤器（issue #724）。
 *
 * <p>修复前 {@code SecurityUtils.extractRequestId} 仅在入站缺失时往响应头
 * {@code add} 一条（且该路径全库无统一调用点），{@code TracingWebFilter} 不写该头
 * ——所有响应实际都没有统一的 X-Request-Id，客户端无法凭响应头关联日志/追踪。</p>
 *
 * <p>本过滤器在链首（{@code CACHED_BODY + 2}，见 {@link FilterOrderContract}）
 * 解析请求 ID（入站缺失时生成），存入 exchange attribute 并通过
 * {@code beforeCommit} 统一回写响应头（{@code set} 覆盖而非 {@code add}，
 * 保证恰一值；{@code beforeCommit} 在响应提交（含流式首帧）前注册即可生效）。</p>
 *
 * <p>读取方统一走 {@link SecurityUtils#extractRequestId(ServerWebExchange)}
 * （改读 attribute，缺失时兜底请求头），废弃硬编码字面量。</p>
 *
 * @author Trunkline Team
 */
public class RequestIdWebFilter implements WebFilter, Ordered {

    /** 请求 ID 在 exchange attribute 中的键（读取方统一从这里取）。 */
    public static final String REQUEST_ID_ATTRIBUTE = "trunkline.request-id";

    /** 响应头名（与 TracingConstants.X_REQUEST_ID 同值，本类不依赖 tracing 包）。 */
    public static final String X_REQUEST_ID_HEADER = "X-Request-ID";

    @Override
    public int getOrder() {
        return FilterOrderContract.REQUEST_ID;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        final ServerHttpRequest request = exchange.getRequest();

        final String requestId;
        final String inbound = request.getHeaders().getFirst(X_REQUEST_ID_HEADER);
        if (inbound != null && !inbound.isBlank()) {
            requestId = inbound;
        } else {
            requestId = SecurityUtils.generateId();
        }

        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);

        // beforeCommit 统一回写：set 覆盖（含下游已 add 的旧值），恰一值；
        // 异常路径（chain 出错）同样会走响应提交，保证失败响应也带请求 ID。
        final HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        exchange.getResponse().beforeCommit(() -> {
            responseHeaders.set(X_REQUEST_ID_HEADER, requestId);
            return Mono.empty();
        });

        return chain.filter(exchange);
    }
}
