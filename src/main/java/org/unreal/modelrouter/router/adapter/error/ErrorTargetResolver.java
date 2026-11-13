/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.error;

import java.net.URI;

import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * 下游错误目标解析器（#1011）。
 *
 * <p><b>背景</b>：下游错误日志此前只有 {@code instance={}}（实例名），排障时无法定位是哪台下游；
 * 而 {@code WebClientRequestException.getMessage()} 会打印完整 URL——若凭证拼在 query
 * （如 {@code ?key=SECRET}）或 userinfo，原文入日志即凭证泄露。</p>
 *
 * <p><b>口径</b>：target 只取 {@code host:port}——丢弃 userinfo / query / fragment（不携带凭证），
 * IPv6 用 {@code [...]} 包裹；优先沿异常 cause 链（深度上限 8）取
 * {@link WebClientRequestException#getUri()}（连接级异常的真实目标），否则回退实例
 * {@code baseUrl}；两者皆不可得时返回 {@code unknown}。</p>
 *
 * <p><b>使用约束</b>：结果只允许作为 logger 占位参数输出（{@code target=}），
 * <b>不得</b>拼进异常 message/reason 或响应体——全局异常处理器会把 reason 回显给客户端。</p>
 *
 * @author Trunkline Team
 */
public final class ErrorTargetResolver {

    /** cause 链遍历深度上限，防循环引用导致的无限遍历。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    /** 无法解析时的占位值（恒定输出，消费端免判空）。 */
    public static final String UNKNOWN = "unknown";

    private ErrorTargetResolver() {
    }

    /**
     * 解析下游错误目标：沿 cause 链找 {@link WebClientRequestException} 的真实 URI，
     * 找不到则回退 {@code fallbackBaseUrl}。
     *
     * @param ex              下游调用抛出的异常（可为 null）
     * @param fallbackBaseUrl 实例 baseUrl（可为 null/非法，此时返回 {@link #UNKNOWN}）
     * @return {@code host:port}（IPv6 含方括号）或 {@link #UNKNOWN}
     */
    public static String resolveTarget(final Throwable ex, final String fallbackBaseUrl) {
        final URI uri = findUri(ex);
        if (uri != null) {
            return hostPort(uri);
        }
        return hostPortFromUrl(fallbackBaseUrl);
    }

    /**
     * 沿 cause 链提取 {@link WebClientRequestException#getUri()}（深度上限 {@value #MAX_CAUSE_DEPTH}）。
     */
    static URI findUri(final Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof WebClientRequestException webClientRequestException) {
                return webClientRequestException.getUri();
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    /**
     * 提取 URI 的 {@code host:port}：丢弃 userinfo / query / fragment（不携带凭证）；
     * IPv6 host 由 {@link URI#getHost()} 返回时自带方括号，无需再包裹；
     * 无显式端口时不补默认端口。
     */
    public static String hostPort(final URI uri) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            return UNKNOWN;
        }
        return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
    }

    /**
     * 从 baseUrl 字符串解析 {@code host:port}；null/空白/非法语法一律 {@link #UNKNOWN}。
     */
    public static String hostPortFromUrl(final String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return UNKNOWN;
        }
        try {
            return hostPort(URI.create(baseUrl.trim()));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
