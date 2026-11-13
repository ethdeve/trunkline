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
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.monitor.service.TokenUsageRecorder;
import org.unreal.modelrouter.monitor.service.TokenUsageService;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #712: 非流式响应缺 usage / total≤0 时的 token 估算兜底。
 *
 * <p>修复前 {@code TokenUsageExtractor.extractAndRecordTokenUsage} 在
 * {@code !has("usage")} 与 {@code totalTokens<=0} 两处提前 return——不落库、
 * 不结算（「预留即计费」），而流式链路在同样情况下已按估算落库+结算，
 * 两链路口径不一致。</p>
 *
 * <p>修复后：按响应文本估算 completion（{@code QuotaTokenEstimator.estimateFromText}：
 * HAN/2、其余/4，与流式同系数），照常落库+结算，{@code metadata.estimated=true}
 * 标记估算行（定案：估算仅配额兜底，不作对外计费依据）。响应完全无文本信号时
 * 保留旧行为（不落库不结算）。</p>
 *
 * @author JAiRouter Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TokenUsageExtractor 非流式估算兜底（issue #712）")
class TokenUsageExtractorEstimateTest {

    /** 响应缺 usage：choices 文本为英文短句（约 8 token） */
    private static final String NO_USAGE_BODY =
            "{\"model\":\"gpt-4\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello world\"}}]}";

    /** usage 存在但 total=0（prompt 有值）：补估算 completion */
    private static final String ZERO_TOTAL_BODY =
            "{\"model\":\"gpt-4\",\"choices\":[{\"message\":{\"content\":\"你好世界\"}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":0,\"total_tokens\":0}}";

    /** usage 完整：以实测为准，不得标 estimated */
    private static final String FULL_USAGE_BODY =
            "{\"model\":\"gpt-4\",\"choices\":[{\"message\":{\"content\":\"ignored\"}}],"
            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}";

    /** 空响应：无文本信号，不落库不结算（回归旧行为） */
    private static final String EMPTY_BODY =
            "{\"model\":\"gpt-4\",\"choices\":[]}";

    @Mock
    private ApiKeyService apiKeyService;

    private final AtomicReference<TokenUsageRecordDTO> captured = new AtomicReference<>();

    private TokenUsageExtractor extractor;

    @BeforeEach
    void setUp() {
        final TokenUsageRecorder recorder = new TokenUsageRecorder(new TokenUsageService(null, null, null) {
            @Override
            public void recordTokenUsage(final TokenUsageRecordDTO record) {
                captured.set(record);
            }
        });
        extractor = new TokenUsageExtractor(
                new ObjectMapper(), recorder, apiKeyService, null, null, null);
    }

    @Test
    @DisplayName("响应无 usage → 按文本估算落库 total>0（反证修复前 never 落库）")
    void missingUsageFallsBackToEstimate() {
        extractor.extractAndRecordTokenUsage(
                NO_USAGE_BODY, "openai", "instance-1", "key-1", null, null, null, "trace-1");

        final TokenUsageRecordDTO dto = captured.get();
        assertNotNull(dto, "修复前 !has(\"usage\") 提前 return，不落库不结算");
        assertTrue(dto.getTotalTokens() > 0, "估算 total 必须为正（hello world ≈ 2 tokens）");
        assertEquals(dto.getTotalTokens(), dto.getCompletionTokens(),
                "响应侧无 prompt 信号：prompt=0、completion=估算文本");
        assertEquals(0L, dto.getPromptTokens());
        assertNotNull(dto.getMetadata(), "估算行必须带 metadata 标记");
        assertTrue(dto.getMetadata().contains("estimated"),
                "定案：估算仅配额兜底，账单侧需能识别估算行");
    }

    @Test
    @DisplayName("usage.total=0 → 估算 completion 补差，prompt 保留 usage 实测值")
    void zeroTotalEstimatesCompletionOnly() {
        extractor.extractAndRecordTokenUsage(
                ZERO_TOTAL_BODY, "openai", "instance-1", "key-1", null, null, null, "trace-1");

        final TokenUsageRecordDTO dto = captured.get();
        assertNotNull(dto, "修复前 totalTokens<=0 提前 return");
        assertEquals(10L, dto.getPromptTokens(), "usage 里的实测 prompt 保留");
        assertTrue(dto.getCompletionTokens() > 0, "completion 按响应文本估算（你好世界 ≈ 2 tokens）");
        assertEquals(10L + dto.getCompletionTokens(), dto.getTotalTokens());
        assertTrue(dto.getMetadata() != null && dto.getMetadata().contains("estimated"));
    }

    @Test
    @DisplayName("usage 完整 → 以实测为准，不标 estimated（回归守护）")
    void fullUsageUsesBackendValues() {
        extractor.extractAndRecordTokenUsage(
                FULL_USAGE_BODY, "openai", "instance-1", "key-1", null, null, null, "trace-1");

        final TokenUsageRecordDTO dto = captured.get();
        assertNotNull(dto);
        assertEquals(7L, dto.getTotalTokens());
        assertEquals(4L, dto.getCompletionTokens());
        assertNull(dto.getMetadata(), "实测行不得标 estimated");
    }

    @Test
    @DisplayName("usage 缺失且响应无文本 → 不落库（预留即计费口径保留）")
    void emptyChoicesNoTextSignalsKeepsOldBehavior() {
        extractor.extractAndRecordTokenUsage(
                EMPTY_BODY, "openai", "instance-1", "key-1", null, null, null, "trace-1");

        assertNull(captured.get(), "无文本信号时退回旧行为：不落库不结算");
    }

    @Test
    @DisplayName("估算系数与流式一致：中文按 2 字符/token（你好世界=4 字 → 2 tokens）")
    void estimateCoefficientMatchesStreaming() {
        extractor.extractAndRecordTokenUsage(
                ZERO_TOTAL_BODY, "openai", "instance-1", null, null, null, null, "trace-1");

        final TokenUsageRecordDTO dto = captured.get();
        assertEquals(2L, dto.getCompletionTokens(),
                "HAN/2 系数：你好世界 4 个中文字 → 2 tokens（与流式 estimateTokens 同系数）");
        assertNotNull(dto.getMetadata());
        assertFalse(dto.getMetadata().isBlank());
    }
}
