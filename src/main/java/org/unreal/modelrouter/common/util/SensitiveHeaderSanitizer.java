/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 敏感 HTTP 头脱敏工具（P0-L 凭据泄漏防护）。
 *
 * <p>上游厂商的 API Key 会以 {@code Authorization: Bearer <key>} 形式落在实例 headers 里
 * （见 {@code ServiceInstanceManager#mergeProviderAuthorization}），若随 DTO 原样返回，
 * 普通员工直调实例列表接口即可拿到公司上游密钥。<b>本工具只用于出参脱敏</b>，
 * 落库与运行时调用不受影响。</p>
 *
 * <p>判定口径与项目既有 tracing 日志脱敏一致（子串匹配、忽略大小写），
 * 命中则值置 {@code "***"}（掩码而非剔除，保持 header 的存在性，避免前端行为变化）。</p>
 * @author Trunkline Team
 */
public final class SensitiveHeaderSanitizer {

    /** 脱敏后的占位值 */
    public static final String MASK = "***";

    private SensitiveHeaderSanitizer() {
    }

    /**
     * 对 header 映射做脱敏。
     *
     * @param headers 原始 header 映射，可为 {@code null}
     * @return 脱敏后的新映射（不修改入参）
     */
    public static Map<String, String> sanitize(final Map<String, String> headers) {
        final Map<String, String> sanitized = new HashMap<>();
        if (headers == null) {
            return sanitized;
        }
        for (final Map.Entry<String, String> entry : headers.entrySet()) {
            final String key = entry.getKey();
            sanitized.put(key, isSensitive(key) ? MASK : entry.getValue());
        }
        return sanitized;
    }

    /**
     * 判断 header 名是否敏感。
     *
     * @param headerName header 名，可为 {@code null}
     * @return true 表示该 header 的值需要脱敏
     */
    public static boolean isSensitive(final String headerName) {
        if (headerName == null) {
            return false;
        }
        final String lower = headerName.toLowerCase();
        return lower.contains("authorization")
                || lower.contains("token")
                || lower.contains("key")
                || lower.contains("secret")
                || lower.contains("password")
                || lower.contains("credential");
    }
}
