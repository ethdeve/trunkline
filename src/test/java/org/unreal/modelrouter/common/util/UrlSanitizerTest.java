package org.unreal.modelrouter.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【issue #695】URL 查询参数脱敏反证：token/api_key 绝不落盘，且编码/大小写/无等号不得绕过。
 */
@DisplayName("#695 URL 查询参数脱敏")
class UrlSanitizerTest {

    @Test
    @DisplayName("反证：含 token / api_key 的 URI 落盘不含原文，非敏感参数保留")
    void sensitiveQueryRedacted() {
        URI uri = URI.create("https://api.example.com/v1/chat?token=abc123&api_key=sk-xxx&model=gpt-4");
        String out = UrlSanitizer.sanitize(uri);

        assertFalse(out.contains("abc123"), "token 明文不得落盘");
        assertFalse(out.contains("sk-xxx"), "api_key 明文不得落盘");
        assertTrue(out.contains("token=[REDACTED]"));
        assertTrue(out.contains("api_key=[REDACTED]"));
        assertTrue(out.contains("model=gpt-4"), "非敏感参数应原样保留");
        assertEquals("/v1/chat?token=[REDACTED]&api_key=[REDACTED]&model=gpt-4", out);
        assertFalse(out.contains("api.example.com"), "不落 host，避免 userInfo/内网地址进日志");
    }

    @Test
    @DisplayName("大小写与 URL 编码的敏感键不得绕过")
    void caseAndEncodingBypassBlocked() {
        assertTrue(UrlSanitizer.isSensitiveQueryParam("ToKeN"));
        assertTrue(UrlSanitizer.isSensitiveQueryParam("%74oken"));
        assertTrue(UrlSanitizer.isSensitiveQueryParam("api%5Fkey"));

        String out = UrlSanitizer.sanitizeQueryString("ToKeN=abc&%74oken=def&api%5Fkey=ghi&x=1");
        assertFalse(out.contains("abc"));
        assertFalse(out.contains("def"));
        assertFalse(out.contains("ghi"));
        assertTrue(out.contains("x=1"));
    }

    @Test
    @DisplayName("无 = 参数、%3D 编码等号、重复参数")
    void noEqualsAndEncodedEquals() {
        String out = UrlSanitizer.sanitizeQueryString("token%3Dsecret&session&token=a&token=b&q=ok");
        assertFalse(out.contains("secret"));
        assertFalse(out.contains("=a"));
        assertFalse(out.contains("=b"));
        assertTrue(out.contains(UrlSanitizer.REDACTED));
        assertTrue(out.contains("q=ok"));
        assertEquals("session", UrlSanitizer.sanitizeQueryString("session"),
                "裸敏感键名本身不泄露值，原样保留可接受");
    }

    @Test
    @DisplayName("%26 / + 不得被错当成参数边界")
    void encodedSeparatorsNotSplit() {
        String out = UrlSanitizer.sanitizeQueryString("q=a%26token%3Dshouldstay&token=leak");
        assertTrue(out.contains("q=a%26token%3Dshouldstay"));
        assertFalse(out.contains("leak"));
        assertTrue(out.contains("token=[REDACTED]"));
    }

    @Test
    @DisplayName("新增敏感词：code / sig / signature / session")
    void newSensitiveMarkers() {
        for (String key : new String[]{"code", "sig", "signature", "session", "access_token", "auth"}) {
            assertTrue(UrlSanitizer.isSensitiveQueryParam(key), key);
        }
        assertFalse(UrlSanitizer.isSensitiveQueryParam("model"));
        assertFalse(UrlSanitizer.isSensitiveQueryParam("temperature"));
    }

    @Test
    @DisplayName("完整 URL 字符串：保留 base 与 fragment，空安全")
    void sanitizeUrlStringKeepsShape() {
        String out = UrlSanitizer.sanitizeUrlString(
                "https://api.example.com/v1/models?api_key=sk-secret&page=2#section");
        assertFalse(out.contains("sk-secret"));
        assertTrue(out.startsWith("https://api.example.com/v1/models?api_key=[REDACTED]&page=2#section"));
        assertEquals("/path", UrlSanitizer.sanitizeUrlString("/path"));
        assertNull(UrlSanitizer.sanitize(null));
        assertNull(UrlSanitizer.sanitizeUrlString(null));
        assertEquals("", UrlSanitizer.sanitizeQueryString(""));
    }

    @Test
    @DisplayName("带 query 的路径字符串（RequestLogBuilder.sanitizeUrlPath 的委托目标）")
    void pathWithQuery() {
        assertEquals("/v1/chat?token=[REDACTED]",
                UrlSanitizer.sanitizePathWithQuery("/v1/chat?token=abc"));
        assertEquals("/v1/chat", UrlSanitizer.sanitizePathWithQuery("/v1/chat"));
    }
}
