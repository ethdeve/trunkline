package org.unreal.modelrouter.router.adapter.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.monitor.service.TokenUsageRecorder;
import org.unreal.modelrouter.monitor.service.TokenUsageService;
import org.unreal.modelrouter.router.handler.ServiceRequestHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * issue #713: {@link TokenUsageExtractor} 非流式路径计费归因（apiKeyId）回归测试。
 *
 * <p>缺陷背景：非流式链路提取 usage 后调用 {@code recordTokenUsageNoAuth} 时
 * 未传 {@code apiKeyId}，{@code token_usage.api_key_id} 恒 NULL，按 Key 计费归因失效。</p>
 *
 * <p>修复语义：调用方透传的 {@code apiKeyId} 直接落 DTO；未透传时回退请求属性
 * {@link ServiceRequestHandler#API_KEY_ID_ATTRIBUTE}（handler 在 API Key 认证通过后写入）。</p>
 *
 * @author JAiRouter Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TokenUsageExtractor 非流式计费归因 apiKeyId（issue #713）")
class TokenUsageExtractorApiKeyIdTest {

    private static final String USAGE_BODY =
            "{\"model\":\"gpt-4\",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}";

    @Mock
    private ApiKeyService apiKeyService;

    private final AtomicReference<TokenUsageRecordDTO> captured = new AtomicReference<>();

    private TokenUsageExtractor extractor;

    @BeforeEach
    void setUp() {
        // 真实 Recorder + 手写捕获存根（不落库），捕获最终构造的 DTO
        final TokenUsageRecorder recorder = new TokenUsageRecorder(new TokenUsageService(null, null, null) {
            @Override
            public void recordTokenUsage(final TokenUsageRecordDTO record) {
                captured.set(record);
            }
        });
        extractor = new TokenUsageExtractor(
                new ObjectMapper(), recorder, apiKeyService, null, null, null);
    }

    private ServerHttpRequest requestWithAttributes(final Map<String, Object> attributes) {
        final ServerHttpRequest request = org.mockito.Mockito.mock(ServerHttpRequest.class);
        org.mockito.Mockito.when(request.getAttributes()).thenReturn(attributes);
        return request;
    }

    @Test
    @DisplayName("透传 apiKeyId：DTO 必须带上（反证修复前恒 null）")
    void shouldRecordApiKeyIdIntoDto() {
        extractor.extractAndRecordTokenUsage(
                USAGE_BODY, "openai", "instance-1", "key-1", null, null, null, "trace-1");

        final TokenUsageRecordDTO dto = captured.get();
        assertEquals("key-1", dto.getApiKeyId(), "issue #713: 非流式用量行必须归因到 API Key");
        assertEquals(7L, dto.getTotalTokens(), "token 数落库不受本修复影响");
    }

    @Test
    @DisplayName("调用方未透传时回退请求属性（handler 认证后写入的 API_KEY_ID_ATTRIBUTE）")
    void shouldFallBackToRequestAttributeWhenParamMissing() {
        final Map<String, Object> attributes = new HashMap<>();
        attributes.put(ServiceRequestHandler.API_KEY_ID_ATTRIBUTE, "key-attr");

        extractor.extractAndRecordTokenUsage(
                USAGE_BODY, "openai", "instance-1", null, null, null,
                requestWithAttributes(attributes), "trace-1");

        assertEquals("key-attr", captured.get().getApiKeyId(),
                "属性回退保证既有调用方无需改动即获得归因");
    }

    @Test
    @DisplayName("无密钥调用（属性也为空）：apiKeyId 为 null 是正确语义")
    void shouldKeepNullWhenNoApiKeyPresent() {
        extractor.extractAndRecordTokenUsage(
                USAGE_BODY, "openai", "instance-1", null, null, null,
                requestWithAttributes(new HashMap<>()), "trace-1");

        assertNull(captured.get().getApiKeyId(), "无密钥调用不得臆造 apiKeyId");
    }
}
