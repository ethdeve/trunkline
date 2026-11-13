package org.unreal.modelrouter.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #703 回归：base URL 携带 query 的校验期拒绝（定案 A，breaking change）。
 *
 * <p>修复前 {@code normalize} 只拼 path、{@code getRawQuery()} 被静默丢弃
 * （Azure {@code ?api-version=} 消失导致请求失败且无人察觉）；定案不保留 query
 * （否则令牌会写进 currentConfig 被 #695 覆盖面捕获），校验期直接拒绝并
 * 引导把参数移到请求时传递。</p>
 */
@DisplayName("出站 base URL 禁止携带 query（#703）")
class UrlSecurityValidatorQueryTest {

    @Test
    @DisplayName("携带 query 的 base URL 被校验期拒绝，报错引导移到请求时")
    void queryInBaseUrlIsRejected() {
        final SecurityException ex = assertThrows(SecurityException.class,
                () -> UrlSecurityValidator.validateOutboundBaseUrl(
                        "https://api.azure.example.com/openai/deployments/gpt4?api-version=2024-02-01"));
        assertTrue(ex.getMessage().contains("query"),
                "报错应说明禁止 query 并引导请求时传参: " + ex.getMessage());
    }

    @Test
    @DisplayName("两种口径（严格/常规）下 query 一律拒绝")
    void queryRejectedInBothModes() {
        assertThrows(SecurityException.class, () -> UrlSecurityValidator
                .validateOutboundBaseUrl("http://10.0.0.1:11434/v1?x=1", true));
        assertThrows(SecurityException.class, () -> UrlSecurityValidator
                .validateOutboundBaseUrl("http://10.0.0.1:11434/v1?x=1", false));
    }

    @Test
    @DisplayName("裸 ?（空 query）同样拒绝（rawQuery 为空串而非 null）")
    void emptyQueryAlsoRejected() {
        assertThrows(SecurityException.class, () -> UrlSecurityValidator
                .validateOutboundBaseUrl("https://api.example.com/v1?"));
    }

    @Test
    @DisplayName("无 query 的合法 URL 照常通过，不受新规则影响")
    void noQueryStillAccepted() {
        // .example 是 IANA 保留 TLD，DNS 解析失败在实现里被吞（留给连接阶段处理）
        assertEquals("https://api.invalid-reserved-tld.example/v1",
                UrlSecurityValidator.validateOutboundBaseUrl("https://api.invalid-reserved-tld.example/v1"));
        assertEquals("http://127.0.0.1:11434",
                UrlSecurityValidator.validateOutboundBaseUrl("http://127.0.0.1:11434"));
    }

    @Test
    @DisplayName("169.254 云元数据拒绝不回归（含 query 与不含 query 两种形态）")
    void metadataIpStillRejected() {
        assertThrows(SecurityException.class, () -> UrlSecurityValidator
                .validateOutboundBaseUrl("http://169.254.169.254/latest/meta-data"));
        assertThrows(SecurityException.class, () -> UrlSecurityValidator
                .validateOutboundBaseUrl("http://169.254.169.254/latest/meta-data?x=1"));
    }
}
