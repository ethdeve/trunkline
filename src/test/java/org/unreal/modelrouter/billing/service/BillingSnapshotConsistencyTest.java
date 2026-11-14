/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.billing.dto.MyBillsVO;
import org.unreal.modelrouter.billing.dto.TeamUsageVO;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

/**
 * 「我的账单」与「团队用量」同口径回归（方案 4.1 / P0-3）。
 *
 * <p>B0 之前团队用量按<b>当前价</b>在内存里算，改单价会连带改动历史月份金额，
 * 与只读快照的「我的账单」形成两套数字；现在两者共用聚合 SQL 的快照列。</p>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("账单快照口径一致性（P0-3）")
class BillingSnapshotConsistencyTest {

    @Mock
    private TokenUsageRepository repo;

    @Mock
    private JwtAccountRepository accountRepo;

    private BillingService service;

    @BeforeEach
    void setUp() {
        service = new BillingService(repo, accountRepo);
    }

    /** 我的账单聚合行：8 列，末两列为快照金额与有价行数。 */
    private static Object[] myRow(final String model, final String snapshotAmount,
                                  final long pricedCount) {
        return new Object[]{model, 1000L, 1000L, 2000L, 1L, 1L,
                new BigDecimal(snapshotAmount), pricedCount};
    }

    /** 团队用量聚合行：11 列，末两列为快照金额与有价行数（P0-3 追加）。 */
    private static Object[] teamRow(final String user, final String snapshotAmount,
                                    final long pricedCount) {
        return new Object[]{user, "主力模型", "gpt-4o", "chat",
                1000L, 1000L, 2000L, 1L, 1L,
                new BigDecimal(snapshotAmount), pricedCount};
    }

    private void stubMyBillsDefaults(final int year, final int month) {
        lenient().when(repo.aggregateByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L}));
        lenient().when(repo.sumUnpricedByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L}));
        lenient().when(repo.sumPricedAmountByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{BigDecimal.ZERO}));
        lenient().when(repo.aggregateByUserMonthDayVirtualModel(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("同一批用量：我的账单与团队用量的 totalAmount 完全相等，且都来自快照")
    void myBillsAndTeamUsageShareSnapshotAmount() {
        stubMyBillsDefaults(2026, 10);
        when(repo.aggregateByUserMonthModel("admin", 2026, 10))
                .thenReturn(List.<Object[]>of(myRow("gpt-4o", "0.04", 1L)));
        when(repo.aggregateByMonthUserVirtualModel(2026, 10))
                .thenReturn(List.<Object[]>of(teamRow("admin", "0.04", 1L)));
        when(accountRepo.findAll()).thenReturn(List.of());

        final MyBillsVO my = service.getMyBills("admin", 2026, 10);
        final TeamUsageVO team = service.getTeamUsage("COMPANY", 2026, 10, null);

        assertEquals(new BigDecimal("0.0400"), my.getTotalAmount());
        assertEquals(my.getTotalAmount(), team.getTotalAmount(),
                "两个账单页面必须共用同一套快照口径，不得出现两套数字");
        assertEquals(0L, team.getUnpricedTokenTotal());
    }

    @Test
    @DisplayName("改价不影响历史月份：服务层已无 PriceResolver，重复查询金额恒定")
    void currentPriceChangeCannotAlterHistoricalAmount() {
        stubMyBillsDefaults(2026, 10);
        when(repo.aggregateByUserMonthModel("admin", 2026, 10))
                .thenReturn(List.<Object[]>of(myRow("gpt-4o", "0.04", 1L)));
        when(repo.aggregateByMonthUserVirtualModel(2026, 10))
                .thenReturn(List.<Object[]>of(teamRow("admin", "0.04", 1L)));
        when(accountRepo.findAll()).thenReturn(List.of());

        // 结构上彻底移除「当前价」入口：单价只在落库时点被读取一次并固化
        assertTrue(Arrays.stream(BillingService.class.getDeclaredFields())
                        .noneMatch(f -> f.getType().getName()
                                .equals("org.unreal.modelrouter.billing.price.PriceResolver")),
                "BillingService 不得再持有 PriceResolver 字段");

        assertEquals(new BigDecimal("0.0400"),
                service.getMyBills("admin", 2026, 10).getTotalAmount());
        assertEquals(new BigDecimal("0.0400"),
                service.getTeamUsage("COMPANY", 2026, 10, null).getTotalAmount());
        // 模拟「管理员之后改了单价、再查上月账单」：聚合行不变，金额也不变
        assertEquals(new BigDecimal("0.0400"),
                service.getMyBills("admin", 2026, 10).getTotalAmount());
        assertEquals(new BigDecimal("0.0400"),
                service.getTeamUsage("COMPANY", 2026, 10, null).getTotalAmount());
    }
}
