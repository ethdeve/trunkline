/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型能力画像单元测试：DTO 别名匹配 + Service 筛选/查找 + fail-soft。
 * @author Trunkline Team
 */
@DisplayName("模型能力画像")
class ModelCapabilityProfileTest {

    private ModelCapabilityProfile profile(String canonical, String modelId, String vendor,
                                            String strength, String useCase, String latency) {
        ModelCapabilityProfile p = new ModelCapabilityProfile();
        p.setCanonicalId(canonical);
        p.setModelId(modelId);
        p.setVendor(vendor);
        p.setServiceType("chat");
        p.setAliases(List.of(canonical + "-alias"));
        p.setUseCases(List.of(useCase));
        p.setLatency(latency);
        ModelCapabilityProfile.Strengths s = new ModelCapabilityProfile.Strengths();
        s.setCode(strength);
        p.setStrengths(s);
        p.setSource(ModelCapabilityProfile.SOURCE_BUILTIN);
        return p;
    }

    @Test
    @DisplayName("别名匹配：modelId / canonicalId / alias 大小写不敏感")
    void aliasMatching() {
        ModelCapabilityProfile p = profile("deepseek-v3", "deepseek-chat", "deepseek", "high", "客服问答", "mid");
        assertTrue(p.matchesName("DEEPSEEK-CHAT"));
        assertTrue(p.matchesName("deepseek-v3"));
        assertTrue(p.matchesName("DeepSeek-V3-alias"));
        assertFalse(p.matchesName("other-model"));
        assertFalse(p.matchesName(null));
    }

    @Test
    @DisplayName("findByModelName 通过别名命中 standard 画像")
    void findByAlias() {
        ModelCapabilityProfileProvider provider = () -> List.of(
                profile("deepseek-v3", "deepseek-chat", "deepseek", "high", "客服问答", "mid"));
        ModelCapabilityProfileService service = new ModelCapabilityProfileService(provider);
        assertTrue(service.findByModelName("deepseek-v3-alias").isPresent());
        assertTrue(service.findByModelName("unseen").isEmpty());
    }

    @Test
    @DisplayName("filter 按 useCase + preferStrength 排序（code high 在前）")
    void filterAndSort() {
        ModelCapabilityProfileProvider provider = () -> List.of(
                profile("weak", "weak-chat", "vendor-a", "low", "客服问答", "mid"),
                profile("strong", "strong-chat", "vendor-b", "high", "客服问答", "high"),
                profile("code", "code-chat", "vendor-c", "high", "代码助手", "low"));
        ModelCapabilityProfileService service = new ModelCapabilityProfileService(provider);
        List<ModelCapabilityProfile> support = service.filter("客服问答", null, null, "code", null);
        assertEquals(2, support.size());
        assertEquals("strong", support.get(0).getCanonicalId(), "code=high 应排在 low 之前");
    }

    @Test
    @DisplayName("filter 的 minLatency=low 只保留 low/unknown 档位")
    void filterByLatency() {
        ModelCapabilityProfileProvider provider = () -> List.of(
                profile("fast", "fast-chat", "vendor-a", "mid", "客服问答", "low"),
                profile("slow", "slow-chat", "vendor-b", "mid", "客服问答", "high"),
                profile("unknown-lat", "unk-chat", "vendor-c", "mid", "客服问答", "unknown"));
        ModelCapabilityProfileService service = new ModelCapabilityProfileService(provider);
        List<ModelCapabilityProfile> result = service.filter("客服问答", null, null, null, "low");
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(p -> "fast".equals(p.getCanonicalId())));
        assertTrue(result.stream().anyMatch(p -> "unknown-lat".equals(p.getCanonicalId())));
    }

    @Test
    @DisplayName("unknown 与 false/NO 严格区分（strength 缺省为 unknown）")
    void unknownNotFalse() {
        ModelCapabilityProfile p = new ModelCapabilityProfile();
        assertEquals(ModelCapabilityProfile.UNKNOWN, p.getFunctionCall());
        assertEquals(ModelCapabilityProfile.UNKNOWN, p.getStrengths().getCode());
        p.setFunctionCall(ModelCapabilityProfile.NO);
        assertEquals(ModelCapabilityProfile.NO, p.getFunctionCall());
    }

    @Test
    @DisplayName("vendors / useCases 去重排序")
    void facets() {
        ModelCapabilityProfileProvider provider = () -> List.of(
                profile("a", "a", "zhipu", "mid", "客服问答", "mid"),
                profile("b", "b", "openai", "mid", "代码助手", "mid"),
                profile("c", "c", "openai", "mid", "客服问答", "mid"));
        ModelCapabilityProfileService service = new ModelCapabilityProfileService(provider);
        assertEquals(List.of("openai", "zhipu"), service.vendors());
        assertEquals(List.of("代码助手", "客服问答"), service.useCases());
    }
}
