/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额超限 429 文案维度化测试（2026-11-22 整改）。
 *
 * <p>修复前 describe() 硬编码 "API Key quota exceeded"，池超限也误报 API Key。
 * 现按 scopeLabel 渲染，且旧 5 参构造回退为中性文案。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("配额超限文案按维度渲染")
class QuotaLimitViolationDescriptionTest {

    @Test
    @DisplayName("池（模型分组）超限时文案带出池名，不再误报 API Key")
    void poolViolationNamesThePool() {
        QuotaLimitViolation v = new QuotaLimitViolation(
                QuotaLimitViolation.METRIC_DAILY_REQUESTS, QuotaWindow.DAY,
                100L, 100L, 3600L, "模型分组 my-pool");

        String described = v.describe();
        assertTrue(described.startsWith("模型分组 my-pool quota exceeded"), described);
        assertTrue(!described.contains("API Key quota exceeded"), described);
    }

    @Test
    @DisplayName("厂商模型超限时文案带出模型名")
    void modelViolationNamesTheModel() {
        QuotaLimitViolation v = new QuotaLimitViolation(
                QuotaLimitViolation.METRIC_DAILY_REQUESTS, QuotaWindow.DAY,
                100L, 100L, 3600L, "厂商模型 qwen3:4b");
        assertTrue(v.describe().startsWith("厂商模型 qwen3:4b quota exceeded"), v.describe());
    }

    @Test
    @DisplayName("旧 5 参构造回退为中性文案，不再默认甩锅 API Key")
    void legacyConstructorUsesNeutralSubject() {
        QuotaLimitViolation v = new QuotaLimitViolation(
                QuotaLimitViolation.METRIC_DAILY_REQUESTS, QuotaWindow.DAY, 100L, 100L, 3600L);
        assertEquals("Quota exceeded: metric=dailyRequestLimit, window=DAY, limit=100, used=100",
                v.describe());
    }
}
