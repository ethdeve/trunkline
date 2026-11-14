/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.billing.dto.MyBillsVO;
import org.unreal.modelrouter.billing.dto.TeamUsageVO;
import org.unreal.modelrouter.billing.price.CommunityPriceResolver;
import org.unreal.modelrouter.enterprise.spi.BillingProvider;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.monitor.service.TokenUsageService;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

/**
 * 红线验收：「未定价 ≠ ¥0」回归（方案 4.1 / 4.2 / P0-4 / P0-5）。
 *
 * <p>把一个模型价格行建成默认空行（不填价），发 100 次请求，账单 {@code totalAmount}
 * 必须保持 0 且 {@code unpricedTokenTotal} = 100 次的 token 总量；<b>不得出现「已计入 ¥0」</b>。
 * 这条一次性覆盖 P0-4（空价语义）/ P0-5（半价快照）/ P0-3（团队用量快照口径）。</p>
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("未定价不等于 ¥0 红线回归")
class UnpricedNotZeroRegressionTest {

    @Mock
    private TokenUsageRepository repo;

    @Mock
    private JwtAccountRepository accountRepo;

    @Mock
    private BillingProvider billingProvider;

    private BillingService billingService;
    private TokenUsageService usageService;

    @BeforeEach
    void setUp() {
        billingService = new BillingService(repo, accountRepo);
        usageService = new TokenUsageService(repo, new CommunityPriceResolver(), billingProvider);
    }

    private void stubMyBillsDefaults(final int year, final int month,
                                     final long unpricedTokens, final long unpricedRequests) {
        lenient().when(repo.aggregateByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L}));
        lenient().when(repo.sumUnpricedByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{unpricedTokens, unpricedRequests}));
        lenient().when(repo.sumPricedAmountByUserMonth(anyString(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{BigDecimal.ZERO}));
        lenient().when(repo.aggregateByUserMonthDayVirtualModel(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("空价行 100 次请求：金额恒 0，token 全量单列待定价，charge 零调用")
    void hundredUnpricedRequestsAreNotBilledAsZero() {
        final int requests = 100;
        final long tokensPerRequest = 1000L;
        final List<Object[]> teamRows = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            teamRows.add(new Object[]{"user" + i, "主力模型", "gpt-4o", "chat",
                    500L, 500L, tokensPerRequest, 1L, 1L,
                    BigDecimal.ZERO, 0L});
        }
        when(repo.aggregateByMonthUserVirtualModel(2026, 11)).thenReturn(teamRows);
        when(accountRepo.findAll()).thenReturn(List.of());

        stubMyBillsDefaults(2026, 11, 100_000L, 100L);
        when(repo.aggregateByUserMonthModel("admin", 2026, 11))
                .thenReturn(List.<Object[]>of(new Object[]{"gpt-4o", 50_000L, 50_000L,
                        100_000L, 100L, 100L, BigDecimal.ZERO, 0L}));

        final TeamUsageVO team = billingService.getTeamUsage("COMPANY", 2026, 11, null);
        assertEquals(0, team.getTotalAmount().compareTo(BigDecimal.ZERO));
        assertEquals(100_000L, team.getUnpricedTokenTotal());
        assertEquals(100_000L, team.getTotalTokens(), "token 照常计入总量，不丢数据");
        assertTrue(team.isHasUnpriced());

        final MyBillsVO my = billingService.getMyBills("admin", 2026, 11);
        assertEquals(0, my.getTotalAmount().compareTo(BigDecimal.ZERO));
        assertEquals(100_000L, my.getUnpricedTokenTotal());
        assertEquals(Boolean.TRUE, my.getHasUnpriced());

        // 社区空价下，用量落库必须是 priceMissing=true，且完全不上报扣费
        for (int i = 0; i < requests; i++) {
            usageService.recordTokenUsage(record("trace-" + i, null, null));
        }
        final ArgumentCaptor<TokenUsageEntity> captor =
                ArgumentCaptor.forClass(TokenUsageEntity.class);
        verify(repo, org.mockito.Mockito.times(requests)).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(e -> Boolean.TRUE.equals(e.getPriceMissing())));
        assertTrue(captor.getAllValues().stream()
                .allMatch(e -> e.getPriceInputSnapshot() == null
                        && e.getPriceOutputSnapshot() == null));
        verify(billingProvider, never()).charge(any());
    }

    @Test
    @DisplayName("半价显式快照：两侧不齐 → 按未定价落库，charge 零调用（P0-5）")
    void halfExplicitSnapshotIsNotPriced() {
        usageService.recordTokenUsage(record("trace-half", new BigDecimal("0.01"), null));

        final ArgumentCaptor<TokenUsageEntity> captor =
                ArgumentCaptor.forClass(TokenUsageEntity.class);
        verify(repo).save(captor.capture());
        final TokenUsageEntity saved = captor.getValue();
        assertTrue(Boolean.TRUE.equals(saved.getPriceMissing()));
        assertNull(saved.getPriceInputSnapshot(), "半价不得按 0 补齐后写库");
        assertNull(saved.getPriceOutputSnapshot());
        verify(billingProvider, never()).charge(any());
    }

    private static TokenUsageRecordDTO record(final String traceId,
                                              final BigDecimal inputSnapshot,
                                              final BigDecimal outputSnapshot) {
        return TokenUsageRecordDTO.builder()
                .traceId(traceId)
                .modelName("gpt-4o")
                .instanceName("gpt-4o")
                .userId("admin")
                .serviceType("chat")
                .promptTokens(500L)
                .completionTokens(500L)
                .totalTokens(1000L)
                .priceInputSnapshot(inputSnapshot)
                .priceOutputSnapshot(outputSnapshot)
                .build();
    }
}
