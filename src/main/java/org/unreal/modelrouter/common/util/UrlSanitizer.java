/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 【issue #695】URL 查询参数脱敏的唯一实现。
 *
 * <p>此前 {@code RequestLogBuilder} 里写好的 {@code sanitizeQueryString} 只被
 * {@code sanitizeUrlPath} 调用，而落盘的 {@code url} 字段直接取
 * {@code request.getURI().toString()}（含 query）——脱敏逻辑实际是死代码，
 * {@code ?token=...&api_key=...} 会明文写进结构化日志与 OTel span 属性。</p>
 *
 * <p>本类同时供 {@code RequestLogBuilder#buildRequestFields} 与
 * {@code TracingSanitizationService#sanitizeMapRecursively} 使用，规则只维护一份。</p>
 */
public final class UrlSanitizer {

    /** 敏感参数值的统一替换文本（便于日志检索与测试断言）。 */
    public static final String REDACTED = "[REDACTED]";

    /** 键名包含以下任一标记即视为敏感；匹配在 URL 解码后、忽略大小写进行。 */
    private static final String[] SENSITIVE_MARKERS = {
            "token", "key", "password", "secret", "auth", "credential",
            "code", "sig", "signature", "session"
    };

    private UrlSanitizer() {
    }

    /**
     * 脱敏整个 URI（保留 raw path + 脱敏后的 raw query；不含 scheme/host/userInfo/fragment）。
     */
    public static String sanitize(final URI uri) {
        if (uri == null) {
            return null;
        }
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        final String query = uri.getRawQuery();
        return query == null ? path : path + "?" + sanitizeQueryString(query);
    }

    /**
     * 脱敏完整 URL 字符串（用于日志 Map 里已经序列化好的 "url" 值，issue #695 第二道保险）。
     */
    public static String sanitizeUrlString(final String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        final int queryStart = rawUrl.indexOf('?');
        if (queryStart < 0) {
            return rawUrl;
        }
        String base = rawUrl.substring(0, queryStart);
        String query = rawUrl.substring(queryStart + 1);
        String fragment = "";
        final int fragmentStart = query.indexOf('#');
        if (fragmentStart >= 0) {
            fragment = query.substring(fragmentStart);
            query = query.substring(0, fragmentStart);
        }
        return base + "?" + sanitizeQueryString(query) + fragment;
    }

    /**
     * 脱敏「可能自带 ?query 的路径字符串」，供 {@code sanitizeUrlPath} 保持旧签名。
     */
    public static String sanitizePathWithQuery(final String pathWithQuery) {
        if (pathWithQuery == null) {
            return null;
        }
        final int idx = pathWithQuery.indexOf('?');
        if (idx < 0) {
            return pathWithQuery;
        }
        return pathWithQuery.substring(0, idx) + "?"
                + sanitizeQueryString(pathWithQuery.substring(idx + 1));
    }

    /**
     * 脱敏 raw query：敏感键的值替换为 {@link #REDACTED}，非敏感参数原样保留。
     *
     * <p>只按真实 {@code &} / {@code =} 拆分，不解码后重拆，避免把值里编码的
     * {@code %26} / {@code +} 错当成参数边界；键名则先解码再判敏感，防止
     * {@code %74oken} 之类的编码绕过。</p>
     */
    public static String sanitizeQueryString(final String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return rawQuery;
        }
        final String[] params = rawQuery.split("&", -1);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            final String param = params[i];
            final int eq = param.indexOf('=');
            if (eq < 0) {
                // 没有真实 '='：可能是 "token" 裸参数，也可能是 "token%3Dabc" 编码形式。
                final String decoded = decode(param);
                final int decodedEq = decoded.indexOf('=');
                if (decodedEq >= 0 && isSensitiveQueryParam(decoded.substring(0, decodedEq))) {
                    out.append(REDACTED);
                } else {
                    out.append(param);
                }
                continue;
            }
            final String key = param.substring(0, eq);
            if (isSensitiveQueryParam(key)) {
                out.append(key).append('=').append(REDACTED);
            } else {
                out.append(param);
            }
        }
        return out.toString();
    }

    /** 键名（解码后、忽略大小写）是否命中敏感标记。 */
    public static boolean isSensitiveQueryParam(final String rawKey) {
        if (rawKey == null) {
            return false;
        }
        final String key = decode(rawKey).toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_MARKERS) {
            if (key.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String decode(final String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法 % 转义：退回原文判断，绝不让脱敏逻辑本身抛异常打断日志链路。
            return raw;
        }
    }
}
