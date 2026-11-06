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
            return Mono.just(new ApiKeyAuthentication(apiKey));
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
        if (token != null && !token.isEmpty()) {
            return token;
        }
        // 品牌切换双读过渡（S2 任务 26a①）：默认头已从 Jairouter_Token 改为 Trunkline_Token，
        // 但存量外部调用方（脚本/旧集成）可能仍发旧头——配置头未命中时回落读取旧头，
        // 保证平滑升级；显式配置 jwt-header 的部署不受影响（配置头优先）。
        if (!LEGACY_JWT_HEADER.equalsIgnoreCase(jwtHeader)) {
            token = getHeaderIgnoreCase(exchange, LEGACY_JWT_HEADER);
            if (token != null && !token.isEmpty()) {
                log.debug("JWT token read from legacy header {} (consider migrating to {})",
                        LEGACY_JWT_HEADER, jwtHeader);
                return token;
            }
        }
        return null;
    }

    /** 旧品牌认证头（双读回落用，与 JwtConfig 旧默认值一致） */
    private static final String LEGACY_JWT_HEADER = "Jairouter_Token";

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
