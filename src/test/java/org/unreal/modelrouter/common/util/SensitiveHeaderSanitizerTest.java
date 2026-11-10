/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感 HTTP 头脱敏工具测试（P0-L 凭据泄漏防护）。
 *
 * <p>锁死两条底线：</p>
 * <ol>
 *   <li>命中敏感关键词的 header，其值必须被替换为 {@code "***"}（不能泄露原文）；</li>
 *   <li>非敏感 header 的值必须<b>原样保留</b>（不能把整张 header 表一刀切成 {@code "***"}，
 *       否则前端行为会变）。</li>
 * </ol>
 *
 * @author Trunkline Team
 */
@DisplayName("SensitiveHeaderSanitizer 测试")
class SensitiveHeaderSanitizerTest {

    @Test
    @DisplayName("Authorization 被脱敏为 ***（上游厂商 API Key 的实际载体）")
    void authorizationIsMasked() {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer sk-live-abcdef123456");
        headers.put("Content-Type", "application/json");

        final Map<String, String> result = SensitiveHeaderSanitizer.sanitize(headers);

        assertEquals("***", result.get("Authorization"), "Authorization 必须脱敏");
        assertEquals("application/json", result.get("Content-Type"), "非敏感 header 必须原样保留");
        assertFalse(result.toString().contains("sk-live-abcdef123456"), "脱敏结果中不得出现原文");
    }

    @Test
    @DisplayName("大小写不敏感：authorization / X-API-Key / api_key 均命中")
    void matchingIsCaseInsensitive() {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer abc");
        headers.put("X-API-Key", "key-123");
        headers.put("api_key", "key-456");
        headers.put("X-Access-Token", "token-789");
        headers.put("X-Client-Secret", "secret-000");
        headers.put("user-password", "pwd-111");
        headers.put("my-credential", "cred-222");

        final Map<String, String> result = SensitiveHeaderSanitizer.sanitize(headers);

        headers.keySet().forEach(key -> assertEquals("***", result.get(key), key + " 必须脱敏"));
    }

    @Test
    @DisplayName("非敏感 header 全部保留（掩码而非剔除，保持 header 存在性）")
    void nonSensitiveHeadersArePreserved() {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Trace-Id", "trace-1");
        headers.put("Accept", "*/*");

        final Map<String, String> result = SensitiveHeaderSanitizer.sanitize(headers);

        assertEquals(3, result.size());
        assertEquals("application/json", result.get("Content-Type"));
        assertEquals("trace-1", result.get("X-Trace-Id"));
        assertEquals("*/*", result.get("Accept"));
    }

    @Test
    @DisplayName("null 入参返回空 Map（不抛 NPE）")
    void nullHeadersYieldEmptyMap() {
        final Map<String, String> result = SensitiveHeaderSanitizer.sanitize(null);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("不修改入参（返回新 Map）")
    void inputMapIsNotMutated() {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer real-key");

        final Map<String, String> result = SensitiveHeaderSanitizer.sanitize(headers);

        assertNotSame(headers, result);
        assertEquals("Bearer real-key", headers.get("Authorization"), "入参不得被就地修改");
    }

    @Test
    @DisplayName("isSensitive 判定口径")
    void isSensitivePredicate() {
        assertTrue(SensitiveHeaderSanitizer.isSensitive("Authorization"));
        assertTrue(SensitiveHeaderSanitizer.isSensitive("x-token"));
        assertTrue(SensitiveHeaderSanitizer.isSensitive("KEY"));
        assertFalse(SensitiveHeaderSanitizer.isSensitive("Content-Type"));
        assertFalse(SensitiveHeaderSanitizer.isSensitive(null));
    }

    /**
     * 端到端断言「员工自助端点出参」：{@code ServiceInstanceDTO} 的 headers 经脱敏后
     * 序列化成 JSON，敏感值必须是 {@code "***"}，且 JSON 全文不得出现上游密钥原文。
     *
     * <p>这里复刻 {@code ServiceInstanceManager#getAllInstancesSanitized} 的出参口径
     * （convertToDTO 后再 sanitize），保证「员工看到的 JSON」与本断言一致。</p>
     */
    @Test
    @DisplayName("实例 DTO 出参序列化后：Authorization 为 ***，全文无密钥原文")
    void instanceDtoJsonContainsMaskedAuthorization() throws Exception {
        final ServiceInstanceDTO dto = ServiceInstanceDTO.builder()
                .id(7L)
                .name("gpt-4o-chat")
                .baseUrl("https://api.openai.com/v1")
                .path("/chat/completions")
                .weight(1)
                .status("ACTIVE")
                .serviceType("chat")
                .headers(new LinkedHashMap<>(Map.of(
                        "Authorization", "Bearer sk-live-SECRET-123",
                        "Content-Type", "application/json")))
                .build();

        // 与 ServiceInstanceManager#getAllInstancesSanitized 相同的处理
        dto.setHeaders(SensitiveHeaderSanitizer.sanitize(dto.getHeaders()));

        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        final String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"Authorization\":\"***\""),
                "出参 JSON 中 Authorization 的值必须是 ***，实际: " + json);
        assertFalse(json.contains("sk-live-SECRET-123"), "出参 JSON 不得包含上游密钥原文");
        assertTrue(json.contains("\"Content-Type\":\"application/json\""),
                "非敏感 header 必须原样出现在出参中");
    }
}
