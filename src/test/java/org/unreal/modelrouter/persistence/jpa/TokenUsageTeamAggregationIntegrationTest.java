/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 「团队用量」聚合可出数的集成测试（v3.5 Trunkline）
 *
 * <p><b>背景（真实缺陷）</b>：{@code aggregateByMonthUserVirtualModel} 的 WHERE 含
 * {@code userId IS NOT NULL}，而用量落库链路（{@code TokenUsageRecorder.recordTokenUsageNoAuth}）
 * 此前把 userId 硬编码为 {@code null} —— 所有用量行都被那条 WHERE 过滤掉，
 * 团队用量页面恒为 0（调用观测读的是 {@code api_call_history}，不受影响，故两页数据对不上）。</p>
 *
 * <p>本测试用真实 H2 验证：</p>
 * <ul>
 *   <li>带 userId 的用量行能被月度聚合查到（团队用量的出数前提）；</li>
 *   <li>userId 为 null 的行确实被聚合排除（与 SQL 语义一致，明确边界）；</li>
 *   <li>year / month 由 {@code occurredAt} 推导正确（聚合按这两个字段过滤）。</li>
 * </ul>
 *
 * @author Trunkline Team
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("团队用量聚合集成测试")
class TokenUsageTeamAggregationIntegrationTest {

    @Autowired
    private TokenUsageRepository repository;

    private TokenUsageEntity usage(final String traceId, final String userId,
                                    final String virtualModel, final String instanceName,
                                    final long totalTokens, final boolean success) {
        final TokenUsageEntity e = new TokenUsageEntity();
        e.setTraceId(traceId);
        e.setServiceType("CHAT");
        e.setModelName(instanceName);
        e.setVirtualModel(virtualModel);
        e.setProvider("normal");
        e.setInstanceName(instanceName);
        e.setPromptTokens(totalTokens - 100L);
        e.setCompletionTokens(100L);
        e.setTotalTokens(totalTokens);
        e.setUserId(userId);
        e.setIsSuccess(success);
        e.setResponseTimeMs(1200L);
        e.setOccurredAt(LocalDateTime.of(2026, 10, 30, 13, 31, 48));
        return e;
    }

    @Test
    @DisplayName("带 userId 的用量能被月度聚合查到——团队用量出数的前提")
    void aggregatedWhenUserIdPresent() {
        repository.saveAndFlush(usage("t-1", "admin", "test", "deepseek-v4.1-flash", 12830L, true));

        final List<Object[]> rows = repository.aggregateByMonthUserVirtualModel(2026, 10);

        assertEquals(1, rows.size(), "带 userId 的用量行应被聚合查到");
        final Object[] row = rows.get(0);
        assertEquals("admin", row[0], "聚合行的 userId");
        assertEquals("test", row[1], "聚合行的虚拟模型");
        assertEquals("deepseek-v4.1-flash", row[2], "聚合行的厂商模型（计价口径）");
        assertEquals(12830L, ((Number) row[6]).longValue(), "聚合出的总 token");
        assertEquals(1L, ((Number) row[7]).longValue(), "聚合出的请求数");
        assertEquals(1L, ((Number) row[8]).longValue(), "聚合出的成功数");
    }

    @Test
    @DisplayName("userId 为空的用量行被聚合排除（与 SQL 语义一致）")
    void excludedWhenUserIdNull() {
        repository.saveAndFlush(usage("t-null", null, "test", "deepseek-v4.1-flash", 999L, true));

        final List<Object[]> rows = repository.aggregateByMonthUserVirtualModel(2026, 10);

        assertEquals(0, rows.size(), "userId 为空的行不应出现在团队用量聚合里");
    }

    @Test
    @DisplayName("年月由 occurredAt 推导，跨月数据不串月")
    void yearAndMonthDerivedFromOccurredAt() {
        final TokenUsageEntity e = new TokenUsageEntity();
        e.setTraceId("t-oct");
        e.setServiceType("CHAT");
        e.setModelName("m");
        e.setProvider("normal");
        e.setTotalTokens(100L);
        e.setPromptTokens(60L);
        e.setCompletionTokens(40L);
        e.setUserId("admin");
        e.setIsSuccess(true);
        e.setOccurredAt(LocalDateTime.of(2026, 10, 15, 9, 0, 0));
        final TokenUsageEntity saved = repository.saveAndFlush(e);

        assertNotNull(saved.getYear());
        assertEquals(2026, saved.getYear());
        assertEquals(10, saved.getMonth());

        // 10 月的聚合能查到
        assertEquals(1, repository.aggregateByMonthUserVirtualModel(2026, 10).size());
        // 11 月的聚合查不到
        assertEquals(0, repository.aggregateByMonthUserVirtualModel(2026, 11).size());
    }
}
