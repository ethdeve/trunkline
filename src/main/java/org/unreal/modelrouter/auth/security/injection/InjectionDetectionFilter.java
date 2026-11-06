package org.unreal.modelrouter.auth.security.injection;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.unreal.modelrouter.auth.security.audit.SecurityAuditService;
import org.unreal.modelrouter.auth.security.model.SecurityAuditEvent;
import org.unreal.modelrouter.common.util.IpUtils;

import reactor.core.publisher.Mono;

/**
 * 启发式 Prompt 注入拦截过滤器（G10，整改批次二）。
 *
 * <p>执行位置：链首区（order = HIGHEST_PRECEDENCE + 2000，顺序契约见
 * {@code FilterOrderContract}）。注意 Spring Security 链由 Spring Boot 以
 * {@code @Order(-100)} 注册，故本过滤器实际执行在<b>安全认证之前</b>（此前 javadoc
 * 曾误写「安全认证之后」），仅处理 {@code /v1/**} 的 JSON POST 请求。
 * 请求体复用 {@code CachedBodyWebFilter} 已缓存的可重放 body（每次订阅返回
 * clone，重放安全），本过滤器不改变请求内容。</p>
 *
 * <p>命中处理：按 {@link InjectionDetectionProperties#getAction()} 分流——
 * BLOCK 返回 OpenAI 兼容 403 错误体；LOG 仅记审计放行（灰度观测用）。
 * 两种动作均写 {@link SecurityAuditEvent}（eventType=INJECTION_DETECTION），
 * 审计失败不影响主链路。</p>
 *
 * <p>设计取向：fail-open——扫描异常、体量超限一律放行并告警日志，
 * 不让安全组件成为可用性单点（与配额账本 fail-open 同一哲学）。</p>
 *
 * <p>本类为自研扩展（fork 维护：纯新增包，UPSTREAM-TOUCH: 0）。</p>
 *
 * @author wjy-gateway Extensions
 * @since 3.3.1
 * @modified Trunkline Team
 */
@Component
public class InjectionDetectionFilter implements WebFilter, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(InjectionDetectionFilter.class);

    /** 顺序契约见 {@code FilterOrderContract}（X2 整改：过滤器顺序唯一化）。 */
    public static final int FILTER_ORDER = org.unreal.modelrouter.common.filter.FilterOrderContract.INJECTION_DETECTION;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCAN_PATH_PATTERN = "/v1/**";

    private final InjectionDetectionProperties properties;
    private final InjectionRuleEngine ruleEngine;
    /** 可选依赖：migrate-only 等受限 profile 下审计 bean 缺席，getIfAvailable() 返回 null 时只拦截不审计 */
    private final ObjectProvider<SecurityAuditService> auditServiceProvider;

    public InjectionDetectionFilter(final InjectionDetectionProperties properties,
                                    final InjectionRuleEngine ruleEngine,
                                    @Qualifier("enhancedSecurityAuditService")
                                    final ObjectProvider<SecurityAuditService> auditServiceProvider) {
        this.properties = properties;
        this.ruleEngine = ruleEngine;
        this.auditServiceProvider = auditServiceProvider;
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        if (!properties.isEnabled() || !isScannable(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        final ServerHttpRequest request = exchange.getRequest();
        final long maxBytes = properties.getMaxScanBytes();
        // CachedBodyWebFilter 已在最前缓存并装饰了可重放 body，此处 join 为重放读取。
        // 注意：chain.filter 完成时发出空信号，故必须先用 defaultIfEmpty("")
        // 消化"空 body"信号、在其后不再使用 switchIfEmpty，否则 chain 会被二次调用。
        return DataBufferUtils.join(request.getBody())
                .map(dataBuffer -> {
                    try {
                        final int size = dataBuffer.readableByteCount();
                        final int scanLen = (int) Math.min(size, maxBytes);
                        if (size > maxBytes) {
                            LOG.warn("[G10] 请求体 {} 字节超出扫描上限 {}，仅扫描前缀（fail-open）",
                                    size, maxBytes);
                        }
                        final byte[] bytes = new byte[scanLen];
                        dataBuffer.read(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .defaultIfEmpty("")
                .flatMap(text -> {
                    if (text.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    return handleMatch(exchange, chain, text);
                })
                .onErrorResume(error -> {
                    LOG.warn("[G10] 请求体扫描异常，放行（fail-open）: {} - {}",
                            request.getPath().value(), error.getMessage());
                    return chain.filter(exchange);
                });
    }

    private boolean isScannable(final ServerHttpRequest request) {
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return false;
        }
        if (!PATH_MATCHER.match(SCAN_PATH_PATTERN, request.getPath().value())) {
            return false;
        }
        final MediaType contentType = request.getHeaders().getContentType();
        return contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }

    private Mono<Void> handleMatch(final ServerWebExchange exchange, final WebFilterChain chain,
                                   final String text) {
        final List<InjectionRule> hits = ruleEngine.match(text);
        if (hits.isEmpty()) {
            return chain.filter(exchange);
        }
        final ServerHttpRequest request = exchange.getRequest();
        final String matchedIds = hits.stream()
                .map(InjectionRule::getId).collect(Collectors.joining(","));
        LOG.warn("[G10] 注入特征命中: path={}, ip={}, rules={}",
                request.getPath().value(), IpUtils.getClientIp(request), matchedIds);
        final Mono<Void> audit = recordAudit(exchange, hits, matchedIds)
                .onErrorResume(e -> {
                    LOG.warn("[G10] 注入审计事件写入失败（不影响主链路）: {}", e.getMessage());
                    return Mono.empty();
                });
        if (properties.getAction() == InjectionDetectionProperties.DetectionAction.LOG) {
            return audit.then(chain.filter(exchange));
        }
        return audit.then(writeBlocked(exchange, matchedIds));
    }

    private Mono<Void> recordAudit(final ServerWebExchange exchange, final List<InjectionRule> hits,
                                   final String matchedIds) {
        final SecurityAuditService auditService = auditServiceProvider.getIfAvailable();
        if (auditService == null) {
            LOG.debug("[G10] 审计服务不可用（受限 profile），跳过审计事件: rules={}", matchedIds);
            return Mono.empty();
        }
        final ServerHttpRequest request = exchange.getRequest();
        final String categories = hits.stream()
                .map(InjectionRule::getCategory).distinct().collect(Collectors.joining(","));
        final SecurityAuditEvent event = SecurityAuditEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("INJECTION_DETECTION")
                .clientIp(IpUtils.getClientIp(request))
                .userAgent(request.getHeaders().getFirst("User-Agent"))
                .timestamp(LocalDateTime.now())
                .resource(request.getPath().value())
                .action(properties.getAction().name())
                .success(false)
                .failureReason("matched-rules=" + matchedIds)
                .requestId(request.getId())
                .additionalData(Map.of("rules", matchedIds, "categories", categories))
                .build();
        return auditService.recordEvent(event);
    }

    private Mono<Void> writeBlocked(final ServerWebExchange exchange, final String matchedIds) {
        final ServerHttpRequest request = exchange.getRequest();
        final ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        final byte[] body = serializeBlockedBody(matchedIds);
        final DataBuffer buffer = response.bufferFactory().wrap(body);
        LOG.info("[G10] 已拦截注入请求: path={}, rules={}", request.getPath().value(), matchedIds);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serializeBlockedBody(final String matchedIds) {
        final Map<String, Object> error = Map.of(
                "message", "请求内容触发安全策略（命中规则: " + matchedIds + "）",
                "type", "invalid_request_error",
                "code", "content_policy_violation");
        try {
            return JSON.writeValueAsBytes(Map.of("error", error));
        } catch (final Exception e) {
            // 序列化失败兜底：固定文本 403
            LOG.warn("[G10] 403 错误体序列化失败，返回固定文本: {}", e.getMessage());
            return ("{\"error\":{\"message\":\"content policy violation\","
                    + "\"type\":\"invalid_request_error\",\"code\":\"content_policy_violation\"}}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
