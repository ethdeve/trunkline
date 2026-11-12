package org.unreal.modelrouter.auth.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import org.unreal.modelrouter.common.util.ClientIpResolver;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 默认的认证转换器
 */
public final class DefaultAuthenticationConverter implements ServerAuthenticationConverter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SecurityProperties securityProperties;

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthenticationConverter.class);

    public DefaultAuthenticationConverter(final SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public Mono<Authentication> convert(final ServerWebExchange exchange) {
        String apiKey = null;
        String jwtToken = null;

        // 首先尝试提取API Key（如果启用）
        if (securityProperties.getApiKey().isEnabled()) {
            apiKey = extractApiKey(exchange);
        }

        // 然后尝试提取JWT令牌（如果启用）
        if (securityProperties.getJwt().isEnabled()) {
            jwtToken = extractJwtToken(exchange);
        }

        // 头缺失时回落 query（WebSocket 握手无法携带自定义头）
        if (jwtToken == null && securityProperties.getJwt().isEnabled()) {
            jwtToken = firstQueryParam(exchange, "token", "access_token");
        }
        if (apiKey == null && securityProperties.getApiKey().isEnabled()) {
            apiKey = firstQueryParam(exchange, "api_key", "apiKey");
        }

        // 如果同时提供了API Key和JWT令牌，则优先使用JWT
        if (jwtToken != null) {
            log.debug("提取到JWT令牌，创建JWT认证对象");
            return Mono.just(new JwtAuthentication(jwtToken));
        } else if (apiKey != null) {
            log.debug("提取到API Key，创建API Key认证对象");
            ApiKeyAuthentication auth = new ApiKeyAuthentication(apiKey);
            // 【issue #693】把真实客户端 IP 带进认证链，供 ApiKey 的 IP 白名单校验。
            // 此前认证链从不带 exchange/ClientIp，validateApiKey 的 ip 恒为 null，
            // 白名单非空的 Key 一律 contains(null)=false → 全量 401。
            // ClientIpResolver 无法判定时返回 "unknown" 而非 null：白名单非空时仍拒绝（fail-closed）。
            auth.setClientIp(ClientIpResolver.resolve(exchange));
            return Mono.just(auth);
        }

        // 没有找到认证信息
        return Mono.empty();
    }

    /**
     * 提取API Key
     *
     * <p>优先取配置的自定义头（默认 {@code X-API-Key}）；该头缺失时回退标准 OpenAI 约定的
     * {@code Authorization: Bearer <api-key>}——OpenAI SDK 及各类兼容客户端
     * （含控制台"自定义 OpenAI 兼容 provider"接入）只发送这一个头。
     * 回退仅在配置头缺失时生效：既有调用方继续用 X-API-Key，Authorization 透传下游的行为不变。</p>
     */
    private String extractApiKey(final ServerWebExchange exchange) {
        String headerName = securityProperties.getApiKey().getHeaderName();
        List<String> headerValues = exchange.getRequest().getHeaders().get(headerName);

        if (headerValues != null && !headerValues.isEmpty()) {
            return headerValues.get(0);
        }

        // OpenAI 兼容回退：Authorization: Bearer <token>
        List<String> authValues = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authValues != null && !authValues.isEmpty()) {
            String authorization = authValues.get(0);
            if (authorization != null
                    && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                String token = authorization.substring(BEARER_PREFIX.length()).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }

        return null;
    }

    /**
     * 提取JWT令牌
     * 注意：Authorization 头保留给下游 AI 服务使用，Trunkline 认证使用 Trunkline_Token
     */
    private String extractJwtToken(final ServerWebExchange exchange) {
        // 从配置的自定义头中提取（大小写不敏感）
        String jwtHeader = securityProperties.getJwt().getJwtHeader();
        String token = getHeaderIgnoreCase(exchange, jwtHeader);
        return token == null || token.isEmpty() ? null : token;
    }

    /**
     * 大小写不敏感地获取请求头
     */
    private String getHeaderIgnoreCase(final ServerWebExchange exchange, final String headerName) {
        // 首先尝试直接获取
        List<String> values = exchange.getRequest().getHeaders().get(headerName);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }

        // 如果直接获取失败，进行大小写不敏感的搜索
        String lowerHeaderName = headerName.toLowerCase();
        for (String key : exchange.getRequest().getHeaders().keySet()) {
            if (key.toLowerCase().equals(lowerHeaderName)) {
                List<String> headerValues = exchange.getRequest().getHeaders().get(key);
                if (headerValues != null && !headerValues.isEmpty()) {
                    return headerValues.get(0);
                }
            }
        }
        return null;
    }

    /**
     * 按参数名顺序返回第一个非空 query 参数值（用于 WebSocket 握手 token）。
     */
    private static String firstQueryParam(final ServerWebExchange exchange, final String... names) {
        if (names == null) {
            return null;
        }
        for (String name : names) {
            String value = exchange.getRequest().getQueryParams().getFirst(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
