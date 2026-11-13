package org.unreal.modelrouter.auth.security.config;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O5 出站 DLP 开源开关（第一步）：脱敏排除集可配置 + AI 实时路径可强制纳入脱敏。
 *
 * <p>只覆盖 {@link ExcludedPathsConfig#isDataMaskExcluded(String)} 分支；认证排除
 * （{@code isAuthExcluded}）不在 O5 范围内，其静态行为不受本测试影响。</p>
 */
@DisplayName("O5 出站 DLP 开源开关：脱敏排除集可配置")
class DataMaskingConfigurableTest {

    @AfterEach
    void reset() {
        // O5 配置为静态注入，必须重置以免污染同 JVM 其他用例
        ExcludedPathsConfig.resetDataMaskingConfig();
    }

    @Test
    @DisplayName("默认 maskAiPaths=false：AI 实时路径仍被排除（不脱敏，保持现状）")
    void defaultAiPathExcluded() {
        assertTrue(ExcludedPathsConfig.isDataMaskExcluded("/v1/chat"));
        assertTrue(ExcludedPathsConfig.isDataMaskExcluded("/api/v1/chat"));
    }

    @Test
    @DisplayName("maskAiPaths=true：AI 实时路径强制纳入脱敏（不再被排除）")
    void maskAiPathsForcesMasking() {
        ExcludedPathsConfig.configureDataMasking(true, Set.of(), List.of());

        // AI 实时推理路径：从排除集中移除 → 脱敏过滤器生效
        assertFalse(ExcludedPathsConfig.isDataMaskExcluded("/v1/chat"));
        assertFalse(ExcludedPathsConfig.isDataMaskExcluded("/api/v1/chat"));

        // 非 AI 路径不受影响：管理台 / 静态资源仍按默认排除
        assertTrue(ExcludedPathsConfig.isDataMaskExcluded("/admin"));
        assertTrue(ExcludedPathsConfig.isDataMaskExcluded("/css/app.css"));
        assertTrue(ExcludedPathsConfig.isDataMaskExcluded("/api/config/instance/chat"));
    }

    @Test
    @DisplayName("extraMaskExclusions：追加精确路径额外免于脱敏，且不影响 AI 默认排除")
    void extraExclusionsAppend() {
        ExcludedPathsConfig.configureDataMasking(false, Set.of("/custom/internal"), List.of());

        assertTrue(ExcludedPathsConfig.isDataMaskExcluded("/custom/internal"));
        // 未列出的仍走默认集：/v1/chat 默认排除（仍不脱敏，因 maskAiPaths=false）
        assertTrue(ExcludedPathsConfig.isDataMaskExcluded("/v1/chat"));
    }

    @Test
    @DisplayName("extraMaskExclusionPatterns：追加 Ant 模式额外免于脱敏")
    void extraExclusionPatternsAppend() {
        ExcludedPathsConfig.configureDataMasking(false, Set.of(), List.of("/internal/**"));

        assertTrue(ExcludedPathsConfig.isDataMaskExcluded("/internal/report"));
    }
}
