/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.billing.dto.MyBillsVO;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 「我的账单」成交价快照计价单元测试（O1 / O2）。
 *
 * <p>核心断言：<b>改价不影响历史账单金额</b>——金额完全来自
 * {@code token_usage} 聚合行的快照列，当前价格表根本不参与；
 * 以及<b>未定价 ≠ ¥0</b>——无价用量单列，不混入总额。</p>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("getMyBills 快照计价（O1 成交价快照 / O2 未定价 ≠ ¥0）")
class BillingServicePriceSnapshotTest {

    @Mock
    private TokenUsageRepository tokenUsageRepository;

    @Mock
    private JwtAccountRepository jwtAccountRepository;

    private BillingService billingService;

    @BeforeEach
    void setUp() {
        billingService = new BillingService(tokenUsageRepository, jwtAccountRepository);
    }

    /**
     * 聚合行：[modelName, prompt, completion, total, requests, successCount, snapshotAmount, pricedCount]
     */
    private static Object[] row(final String model, final long prompt, final long completion,
                                final long total, final long requests,
                                final String snapshotAmount, final long pricedCount) {
        return new Object[]{model, prompt, completion, total, requests,
                requests, new BigDecimal(snapshotAmount), pricedCount};
    }

    private void stubDefaults(final int year, final int month) {
        // 月度 token 汇总（countMonthRequests 用）：[totalTokens, promptTokens, completionTokens, requestCount]
        lenient().when(tokenUsageRepository.aggregateByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L}));
        // 待定价汇总：默认无
        lenient().when(tokenUsageRepository.sumUnpricedByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L}));
        // 上月快照金额
        lenient().when(tokenUsageRepository.sumPricedAmountByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{BigDecimal.ZERO}));
        // 按天交叉表：默认空
        lenient().when(tokenUsageRepository.aggregateByUserMonthDayVirtualModel(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("金额只读快照列：当前价格表完全不参与（改价不影响历史账单）")
    void amountComesFromSnapshotOnly() {
        stubDefaults(2026, 10);
        // 1000 prompt × snapshot 0.01/千 + 1000 completion × 0.03/千 = 0.04
        when(tokenUsageRepository.aggregateByUserMonthModel("admin", 2026, 10))
                .thenReturn(List.<Object[]>of(row("gpt-4o", 1000L, 1000L, 2000L, 1L, "0.04", 1L)));

        final MyBillsVO vo = billingService.getMyBills("admin", 2026, 10);

        // P0-3：服务层连 PriceResolver 字段都已删除，当前价在结构上就不可能参与历史金额
        assertTrue(java.util.Arrays.stream(BillingService.class.getDeclaredFields())
                .noneMatch(f -> f.getType().getName()
                        .equals("org.unreal.modelrouter.billing.price.PriceResolver")));
        assertEquals(new BigDecimal("0.0400"), vo.getTotalAmount());
        assertEquals(1, vo.getByModel().size());
        assertEquals(new BigDecimal("0.0400"), vo.getByModel().get(0).getAmount());
        assertEquals(Boolean.FALSE, vo.getByModel().get(0).getUnpriced());
    }

    @Test
    @DisplayName("未定价模型：单列待定价，金额不入总额（未定价 ≠ ¥0）")
    void unpricedModelListedSeparately() {
        stubDefaults(2026, 10);
        when(tokenUsageRepository.aggregateByUserMonthModel("admin", 2026, 10))
                .thenReturn(List.<Object[]>of(
                        row("gpt-4o", 1000L, 1000L, 2000L, 2L, "0.08", 2L),
                        row("new-model", 500L, 500L, 1000L, 1L, "0", 0L)));
        // 待定价汇总：1 次 1000 tokens
        when(tokenUsageRepository.sumUnpricedByUserMonth("admin", 2026, 10))
                .thenReturn(List.<Object[]>of(new Object[]{1000L, 1L}));

        final MyBillsVO vo = billingService.getMyBills("admin", 2026, 10);

        assertEquals(new BigDecimal("0.0800"), vo.getTotalAmount(), "待定价行不得计入总额");
        assertEquals(Boolean.TRUE, vo.getHasUnpriced());
        assertEquals(1000L, vo.getUnpricedTokenTotal());
        assertEquals(1L, vo.getUnpricedRequestCount());

        final MyBillsVO.ModelBill unpriced = vo.getByModel().stream()
                .filter(b -> "new-model".equals(b.getModelName())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, unpriced.getUnpriced());
        assertEquals(0, BigDecimal.ZERO.compareTo(unpriced.getAmount()), "金额为 0 但 unpriced 标记区分语义");
        assertEquals(Boolean.FALSE, vo.getByModel().stream()
                .filter(b -> "gpt-4o".equals(b.getModelName())).findFirst().orElseThrow().getUnpriced());
    }

    @Test
    @DisplayName("上月同期对比同样只读快照金额")
    void previousMonthUsesSnapshotQuery() {
        stubDefaults(2026, 10);
        when(tokenUsageRepository.aggregateByUserMonthModel("admin", 2026, 10))
                .thenReturn(List.<Object[]>of(row("gpt-4o", 1000L, 1000L, 2000L, 1L, "0.04", 1L)));
        when(tokenUsageRepository.sumPricedAmountByUserMonth("admin", 2026, 9))
                .thenReturn(List.<Object[]>of(new Object[]{new BigDecimal("0.55")}));

        final MyBillsVO vo = billingService.getMyBills("admin", 2026, 10);

        assertEquals(new BigDecimal("0.5500"), vo.getPreviousMonthAmount());
    }

    @Test
    @DisplayName("混合 priced/unpriced 行：pricedCount=0 判据驱动单列，聚合安全")
    void pricedCountZeroMeansUnpriced() {
        stubDefaults(2026, 10);
        when(tokenUsageRepository.aggregateByUserMonthModel("admin", 2026, 10))
                .thenReturn(List.<Object[]>of(row("m", 10L, 10L, 20L, 1L, "0", 0L)));
        when(tokenUsageRepository.sumUnpricedByUserMonth("admin", 2026, 10))
                .thenReturn(List.<Object[]>of(new Object[]{20L, 1L}));

        final MyBillsVO vo = billingService.getMyBills("admin", 2026, 10);

        assertTrue(vo.getHasUnpriced());
        assertEquals(Boolean.TRUE, vo.getByModel().get(0).getUnpriced());
    }
}
