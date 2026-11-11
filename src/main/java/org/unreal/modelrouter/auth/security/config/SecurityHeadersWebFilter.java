/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 补齐 Spring Security 默认响应头之外缺失的三项：CSP、HSTS、Permissions-Policy。
 *
 * <p>默认头（{@code X-Content-Type-Options} / {@code X-Frame-Options} /
 * {@code Referrer-Policy} / Cache-Control）由 Spring Security 提供，本过滤器不重复设置，
 * 也只在头尚不存在时写入，避免与后续 HeaderWriter 冲突。</p>
 *
 * <p>CSP 策略刻意保留 {@code style-src 'unsafe-inline'}：Element Plus 在运行时注入
 * {@code <style>} 节点，禁掉会让界面样式整体失效；脚本仍限定同源外部文件（Vite 产物无内联脚本），
 * 这正是拦截 C-03 存储型 XSS 载荷执行的关键。</p>
 *
 * <p>HSTS 只在安全传输（直接 HTTPS 或可信的 {@code X-Forwarded-Proto: https}）下下发，
 * 本地 http 开发实例完全不受影响。整组头可用 {@code trunkline.security.headers.enabled=false}
 * 关闭，CSP 内容可用 {@code trunkline.security.headers.content-security-policy} 覆盖。</p>
 *
 * @author Trunkline Team
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersWebFilter implements WebFilter {

    static final String CSP_HEADER = "Content-Security-Policy";
    static final String HSTS_HEADER = "Strict-Transport-Security";

    static final String DEFAULT_CSP = "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob: https:; "
            + "font-src 'self' data:; "
            + "connect-src 'self'; "
            + "object-src 'none'; "
            + "base-uri 'none'; "
            + "frame-ancestors 'none'; "
            + "form-action 'self'";

    static final String DEFAULT_PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    private static final long DEFAULT_HSTS_MAX_AGE = 31_536_000L;

    @Value("${trunkline.security.headers.enabled:true}")
    private boolean enabled;

    @Value("${trunkline.security.headers.content-security-policy:}")
    private String contentSecurityPolicy;

    @Value("${trunkline.security.headers.hsts-max-age-seconds:31536000}")
    private long hstsMaxAgeSeconds;

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        if (enabled) {
            apply(exchange.getResponse(), exchange);
        }
        return chain.filter(exchange);
    }

    private void apply(final ServerHttpResponse response, final ServerWebExchange exchange) {
        final HttpHeaders headers = response.getHeaders();

        if (!headers.containsKey(CSP_HEADER)) {
            headers.set(CSP_HEADER,
                    contentSecurityPolicy == null || contentSecurityPolicy.isBlank()
                            ? DEFAULT_CSP : contentSecurityPolicy.trim());
        }
        if (!headers.containsKey("Permissions-Policy")) {
            headers.set("Permissions-Policy", DEFAULT_PERMISSIONS_POLICY);
        }
        if (!headers.containsKey(HSTS_HEADER) && isSecureTransport(exchange)) {
            long maxAge = hstsMaxAgeSeconds > 0 ? hstsMaxAgeSeconds : DEFAULT_HSTS_MAX_AGE;
            headers.set(HSTS_HEADER, "max-age=" + maxAge);
        }
    }

    /** 仅 HTTPS 或经可信代理标记为 https 的请求才下发 HSTS。 */
    private static boolean isSecureTransport(final ServerWebExchange exchange) {
        if ("https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())) {
            return true;
        }
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        return forwarded != null && forwarded.equalsIgnoreCase("https");
    }
}
