package org.unreal.modelrouter.common.exceptionhandler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #1010 修复验证：{@link V1ErrorBodyMapper} 支持在 {@code error} 内附带 {@code details}
 * （503 明细），既有字段（message/type/code）不变；未提供 details 时输出与原四参重载完全一致。
 */
class V1ErrorBodyMapperDetailsTest {

    @Test
    @DisplayName("OpenAI 面：提供 details 时 error.details 存在且既有字段不变")
    void testDetailsIncludedOnOpenAiFace() {
        Map<String, Object> details = Map.of("members", List.of(Map.of("name", "inst-1")));

        Map<String, Object> body = V1ErrorBodyMapper.toErrorBody(
                "/v1/chat/completions", 503, "boom", "503", details);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertSame(details, error.get("details"));
        assertEquals("boom", error.get("message"));
        assertEquals("api_error", error.get("type"));
        assertEquals("503", error.get("code"));
    }

    @Test
    @DisplayName("OpenAI 面：details 为 null 时输出与原四参重载一致（无 details 字段）")
    void testDetailsAbsentWhenNull() {
        Map<String, Object> body = V1ErrorBodyMapper.toErrorBody(
                "/v1/chat/completions", 503, "boom", "503", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertFalse(error.containsKey("details"));
        assertEquals("boom", error.get("message"));
        assertEquals("503", error.get("code"));
    }

    @Test
    @DisplayName("Anthropic 面：details 挂在 error 内，type 保持 error 对象形状")
    void testDetailsOnAnthropicFace() {
        Map<String, Object> body = V1ErrorBodyMapper.toErrorBody(
                "/v1/messages", 503, "boom", "503", Map.of("k", "v"));

        assertEquals("error", body.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals("api_error", error.get("type"));
        assertEquals("boom", error.get("message"));
        assertEquals("v", ((Map<?, ?>) error.get("details")).get("k"));
    }

    @Test
    @DisplayName("非 /v1 路径仍返回 null（沿用 RouterResponse 形状）")
    void testNonV1PathReturnsNull() {
        assertNull(V1ErrorBodyMapper.toErrorBody("/api/models", 503, "boom", "503", Map.of("k", "v")));
    }

    @Test
    @DisplayName("原四参 toClientErrorResponse 仍可用（委托 details=null）")
    void testLegacyClientErrorResponseStillWorks() {
        var response = V1ErrorBodyMapper.toClientErrorResponse(null, 503, "boom", "503");
        // exchange 为 null → requestPathOf 为 null → 非 /v1 → 返回 null，行为不变
        assertNull(response);
    }
}
