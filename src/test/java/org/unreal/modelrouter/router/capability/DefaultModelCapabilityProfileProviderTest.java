/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置画像数据源测试：验证随包 JSON 能正确解析、字段归一、别名归一。
 * @author Trunkline Team
 */
@DisplayName("内置画像数据源")
class DefaultModelCapabilityProfileProviderTest {

    private final DefaultModelCapabilityProfileProvider provider =
            new DefaultModelCapabilityProfileProvider(new ObjectMapper());

    @Test
    @DisplayName("内置 JSON 解析出非空画像列表")
    void loadsBuiltinProfiles() {
        List<ModelCapabilityProfile> profiles = provider.load();
        assertFalse(profiles.isEmpty(), "随包画像 JSON 不应为空");
        assertTrue(profiles.stream().anyMatch(p -> "gpt-4o".equals(p.getCanonicalId())));
        assertTrue(profiles.stream().anyMatch(p -> "deepseek-v3".equals(p.getCanonicalId())));
        assertEquals("builtin-json", provider.name());
    }

    @Test
    @DisplayName("字段归一：unknown 保留、大小写折叠、别名从 JSON 加载")
    void fieldNormalization() {
        ModelCapabilityProfileService service = new ModelCapabilityProfileService(provider);
        ModelCapabilityProfile deepseek = service.findByModelName("deepseek-ai/DeepSeek-V3").orElseThrow();
        assertEquals("deepseek-v3", deepseek.getCanonicalId());
        assertEquals(ModelCapabilityProfile.HIGH, deepseek.getStrengths().getCode());
        // 中转站别名命中同一画像
        assertTrue(service.findByModelName("DeepSeek-V3-0324").isPresent());
    }

    @Test
    @DisplayName("R1 推理模型 reasoning=high 且 latency=high（慢）")
    void reasoningProfile() {
        ModelCapabilityProfileService service = new ModelCapabilityProfileService(provider);
        ModelCapabilityProfile r1 = service.findByModelName("deepseek-reasoner").orElseThrow();
        assertEquals(ModelCapabilityProfile.HIGH, r1.getStrengths().getReasoning());
        assertEquals(ModelCapabilityProfile.HIGH, r1.getLatency());
    }
}
