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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我的账单」按天 × 虚拟模型聚合的集成测试（v3.5 Trunkline UI 改造）
 *
 * <p>验证两个新聚合查询的 JPQL 语法与行为——
 * Spring Data JPA 在<b>启动时</b>校验 {@code @Query}，语法错误会直接导致上下文启动失败，
 * 因此这两个查询必须用真实 H2 跑一次才算验证过（只编译过不算）。</p>
 *
 * <ul>
 *   <li>{@code aggregateByUserMonthDayVirtualModel} —— 账单页交叉表（行=日期，列=虚拟模型）；</li>
 *   <li>{@code aggregateByUserDateRangeVirtualModel} —— 额度页「本周最常用」（跨自然月的区间聚合）。</li>
 * </ul>
 *
 * <p>同时验证「分组维度（虚拟模型）与计价维度（厂商模型）分离」这一关键设计：
 * 同一虚拟模型背后挂着两个厂商模型时，应聚合到<b>同一个虚拟模型</b>下但保留各自的厂商模型行。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("按天×虚拟模型聚合集成测试")
class TokenUsageDayVirtualModelAggregationIntegrationTest {

    @Autowired
    private TokenUsageRepository repository;

    private TokenUsageEntity usage(final String traceId, final String userId,
                                    final String usageDate, final String virtualModel,
                                    final String instanceName, final long tokens) {
        final TokenUsageEntity e = new TokenUsageEntity();
        e.setTraceId(traceId);
        e.setUserId(userId);
        e.setServiceType("CHAT");
        e.setModelName(instanceName);
        e.setVirtualModel(virtualModel);
        e.setProvider("normal");
        e.setInstanceName(instanceName);
        e.setPromptTokens(tokens - 100L);
        e.setCompletionTokens(100L);
        e.setTotalTokens(tokens);
        e.setIsSuccess(true);
        // occurredAt 必须与 usageDate 对齐——usageDate / year / month 三个字段
        // 都由 @PrePersist 从 occurredAt 推导，测试里只 setUsageDate 是不够的
        e.setOccurredAt(LocalDateTime.parse(usageDate + "T10:24:18"));
        return e;
    }

    @Test
    @DisplayName("按天×虚拟模型聚合：同一天同一虚拟模型的多条记录合并成一行")
    void aggregateByDayAndVirtualModel() {
        repository.saveAndFlush(usage("t1", "admin", "2026-10-30", "公司主力", "gpt-4o", 1000L));
        repository.saveAndFlush(usage("t2", "admin", "2026-10-30", "公司主力", "gpt-4o", 500L));
        repository.saveAndFlush(usage("t3", "admin", "2026-10-30", "便宜日常", "deepseek-chat", 200L));
        repository.saveAndFlush(usage("t4", "admin", "2026-10-29", "公司主力", "gpt-4o", 300L));
        // 别人的数据不应混进来
        repository.saveAndFlush(usage("t5", "someone-else", "2026-10-30", "公司主力", "gpt-4o", 999L));

        final List<Object[]> rows =
                repository.aggregateByUserMonthDayVirtualModel("admin", 2026, 10);

        assertEquals(3, rows.size(), "应聚合出 3 行（10-30 公司主力 / 10-30 便宜日常 / 10-29 公司主力）");
        // 日期倒序：10-30 在前
        assertEquals("2026-10-30", rows.get(0)[0]);
        // 同日同虚拟模型的两条已合并
        final long totalTokens = ((Number) rows.get(0)[5]).longValue();
        assertEquals(1500L, totalTokens, "同日同虚拟模型的 token 应合并");
        assertEquals(2L, ((Number) rows.get(0)[6]).longValue(), "调用次数应合并");
        assertEquals("公司主力", rows.get(0)[1], "分组维度是虚拟模型");
        assertEquals("gpt-4o", rows.get(0)[2], "计价维度是厂商模型");
    }

    @Test
    @DisplayName("同一虚拟模型背后有多个厂商模型时，分组按虚拟模型、计价保留厂商模型行")
    void separatesGroupingAndPricingDimensions() {
        repository.saveAndFlush(usage("t1", "admin", "2026-10-30", "公司主力", "gpt-4o", 1000L));
        repository.saveAndFlush(usage("t2", "admin", "2026-10-30", "公司主力", "claude-3.5", 800L));

        final List<Object[]> rows =
                repository.aggregateByUserMonthDayVirtualModel("admin", 2026, 10);

        // 同一 (日期, 虚拟模型) 下因厂商模型不同而拆成两行——服务层据此按厂商模型计价后汇总
        assertEquals(2, rows.size(), "厂商模型不同应分行计价");
        for (final Object[] row : rows) {
            assertEquals("2026-10-30", row[0]);
            assertEquals("公司主力", row[1], "两行都归在同一个虚拟模型下");
        }
    }

    @Test
    @DisplayName("区间聚合（额度页本周最常用）：跨自然月也能查到")
    void aggregateByDateRange() {
        repository.saveAndFlush(usage("t1", "admin", "2026-10-30", "公司主力", "gpt-4o", 1000L));
        repository.saveAndFlush(usage("t2", "admin", "2026-10-28", "便宜日常", "deepseek-chat", 200L));
        repository.saveAndFlush(usage("t3", "admin", "2026-09-28", "公司主力", "gpt-4o", 500L));
        repository.saveAndFlush(usage("t4", "admin", "2026-08-01", "公司主力", "gpt-4o", 700L));

        // 区间 [2026-09-28, 2026-10-30]：含上月末，不含 8 月
        final List<Object[]> rows = repository.aggregateByUserDateRangeVirtualModel(
                "admin", "2026-09-28", "2026-10-30");

        // 区间查询按「虚拟模型 × 厂商模型」分组（不是按日期）——
        // 10-30 与 09-28 两条都是「公司主力 × gpt-4o」，合并成一行
        assertEquals(2, rows.size(), "区间内应聚合出 2 行（公司主力 / 便宜日常）");

        // 区间按虚拟模型分组
        long companyMain = rows.stream()
                .filter(r -> "公司主力".equals(r[0]))
                .mapToLong(r -> ((Number) r[4]).longValue())
                .sum();
        // 1500 = 10-30 的 1000 + 09-28 的 500；08-01 的 700 未计入，
        // 即证明区间过滤生效（该日期落在 [09-28, 10-30] 之外）
        assertEquals(1500L, companyMain, "公司主力在区间内共 1000 + 500 tokens");
    }

    @Test
    @DisplayName("失败调用不计入聚合（失败不产生费用）")
    void excludesFailedCalls() {
        final TokenUsageEntity failed = usage("tf", "admin", "2026-10-30", "公司主力", "gpt-4o", 900L);
        failed.setIsSuccess(false);
        repository.saveAndFlush(failed);
        repository.saveAndFlush(usage("t1", "admin", "2026-10-30", "公司主力", "gpt-4o", 100L));

        final List<Object[]> rows =
                repository.aggregateByUserMonthDayVirtualModel("admin", 2026, 10);

        assertEquals(1, rows.size());
        assertEquals(100L, ((Number) rows.get(0)[5]).longValue(), "失败调用不应计入");
    }
}
