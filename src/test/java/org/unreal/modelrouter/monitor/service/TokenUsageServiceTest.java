package org.unreal.modelrouter.monitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.billing.price.PriceResolver;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.monitor.dto.TokenUsageStatisticsDTO;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * TokenUsageService 单元测试
 *
 * @author JAiRouter Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenUsageService 测试")
class TokenUsageServiceTest {

    @Mock
    private TokenUsageRepository tokenUsageRepository;

    @Mock
    private PriceResolver priceResolver;

    @Mock
    private org.unreal.modelrouter.enterprise.spi.BillingProvider billingProvider;

    @InjectMocks
    private TokenUsageService tokenUsageService;

    private TokenUsageRecordDTO createTestRecord() {
        return TokenUsageRecordDTO.builder()
                .traceId("trace-001")
                .serviceType("chat")
                .modelName("gpt-4")
                .provider("openai")
                .instanceName("instance-1")
                .instanceUrl("http://localhost:8080")
                .promptTokens(100L)
                .completionTokens(50L)
                .totalTokens(150L)
                .apiKeyId("key-001")
                .userId("user-001")
                .clientIp("127.0.0.1")
                .isSuccess(true)
                .responseTimeMs(500L)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("SVC-TOKEN-001: 记录使用量测试")
    class RecordUsageTests {

        @Test
        @DisplayName("记录单条Token使用量")
        void testRecordTokenUsage() {
            TokenUsageRecordDTO record = createTestRecord();

            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenReturn(new TokenUsageEntity());

            tokenUsageService.recordTokenUsage(record);

            verify(tokenUsageRepository).save(any(TokenUsageEntity.class));
        }

        @Test
        @DisplayName("记录使用量时异常不应抛出")
        void testRecordTokenUsageException() {
            TokenUsageRecordDTO record = createTestRecord();

            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenThrow(new RuntimeException("Database error"));

            // 不应该抛出异常
            assertDoesNotThrow(() -> tokenUsageService.recordTokenUsage(record));
        }

        @Test
        @DisplayName("批量记录Token使用量")
        void testRecordTokenUsageBatch() {
            TokenUsageRecordDTO record1 = createTestRecord();
            TokenUsageRecordDTO record2 = TokenUsageRecordDTO.builder()
                    .traceId("trace-002")
                    .serviceType("chat")
                    .modelName("gpt-4")
                    .provider("openai")
                    .promptTokens(200L)
                    .completionTokens(100L)
                    .totalTokens(300L)
                    .isSuccess(true)
                    .build();

            List<TokenUsageRecordDTO> records = List.of(record1, record2);

            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenReturn(new TokenUsageEntity());

            tokenUsageService.recordTokenUsageBatch(records);

            verify(tokenUsageRepository, times(2)).save(any(TokenUsageEntity.class));
        }
    }

    @Nested
    @DisplayName("SVC-TOKEN-002: 查询使用记录测试")
    class QueryUsageTests {

        @Test
        @DisplayName("获取最近使用记录")
        void testGetRecentUsage() {
            when(tokenUsageRepository.findRecentUsage(anyInt()))
                    .thenReturn(Collections.emptyList());

            List<TokenUsageEntity> result = tokenUsageService.getRecentUsage(10);

            assertNotNull(result);
            verify(tokenUsageRepository).findRecentUsage(10);
        }

        @Test
        @DisplayName("获取最近使用记录限制最大数量为100")
        void testGetRecentUsageLimitMax() {
            when(tokenUsageRepository.findRecentUsage(anyInt()))
                    .thenReturn(Collections.emptyList());

            tokenUsageService.getRecentUsage(1000);

            verify(tokenUsageRepository).findRecentUsage(100);
        }

        @Test
        @DisplayName("按模型获取最近使用记录")
        void testGetRecentUsageByModel() {
            when(tokenUsageRepository.findRecentUsageByModel(anyString(), anyInt()))
                    .thenReturn(Collections.emptyList());

            List<TokenUsageEntity> result = tokenUsageService.getRecentUsageByModel("gpt-4", 10);

            assertNotNull(result);
            verify(tokenUsageRepository).findRecentUsageByModel("gpt-4", 10);
        }
    }

    @Nested
    @DisplayName("SVC-TOKEN-003: 删除过期记录测试")
    class DeleteOldRecordsTests {

        @Test
        @DisplayName("删除过期使用记录")
        void testDeleteOldUsageRecords() {
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(30);
            when(tokenUsageRepository.deleteByOccurredAtBefore(any(LocalDateTime.class)))
                    .thenReturn(100);

            int deleted = tokenUsageService.deleteOldUsageRecords(cutoffTime);

            assertEquals(100, deleted);
            verify(tokenUsageRepository).deleteByOccurredAtBefore(cutoffTime);
        }
    }

    @Nested
    @DisplayName("SVC-TOKEN-004: 获取排名测试")
    class TopRankingTests {

        @Test
        @DisplayName("获取模型使用量排名")
        void testGetTopModels() {
            LocalDateTime startTime = LocalDateTime.now().minusDays(7);
            LocalDateTime endTime = LocalDateTime.now();

            when(tokenUsageRepository.countTokensByModel(any(), any()))
                    .thenReturn(Collections.emptyList());

            List<?> result = tokenUsageService.getTopModels(startTime, endTime, 10);

            assertNotNull(result);
            verify(tokenUsageRepository).countTokensByModel(startTime, endTime);
        }

        @Test
        @DisplayName("获取服务类型使用量排名")
        void testGetTopServiceTypes() {
            LocalDateTime startTime = LocalDateTime.now().minusDays(7);
            LocalDateTime endTime = LocalDateTime.now();

            when(tokenUsageRepository.countTokensByServiceType(any(), any()))
                    .thenReturn(Collections.emptyList());

            List<?> result = tokenUsageService.getTopServiceTypes(startTime, endTime, 10);

            assertNotNull(result);
            verify(tokenUsageRepository).countTokensByServiceType(startTime, endTime);
        }
    }

    @Nested
    @DisplayName("SVC-TOKEN-005: 获取统计信息测试")
    class StatisticsTests {

        @Test
        @DisplayName("获取Token使用量统计信息")
        void testGetTokenUsageStatistics() {
            LocalDateTime startTime = LocalDateTime.now().minusDays(7);
            LocalDateTime endTime = LocalDateTime.now();

            // Mock 所有统计查询（issue #714：成功率分母改为同窗口 countByTimeRange）
            when(tokenUsageRepository.countByTimeRange(any(), any())).thenReturn(100L);
            when(tokenUsageRepository.countTotalTokensByTimeRange(any(), any())).thenReturn(1000L);
            when(tokenUsageRepository.countSuccessByTimeRange(any(), any())).thenReturn(95L);
            when(tokenUsageRepository.countFailedByTimeRange(any(), any())).thenReturn(5L);
            when(tokenUsageRepository.avgResponseTimeByTimeRange(any(), any())).thenReturn(250.0);
            when(tokenUsageRepository.countTokensByServiceType(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByModel(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByProvider(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByDay(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByWeek(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByMonth(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByHour(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByApiKey(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByUser(any(), any())).thenReturn(Collections.emptyList());

            TokenUsageStatisticsDTO result = tokenUsageService.getTokenUsageStatistics(startTime, endTime);

            assertNotNull(result);
            assertEquals(100L, result.getTotalRequests());
            assertEquals(1000L, result.getTotalTokens());
            assertEquals(95L, result.getSuccessfulRequests());
            assertEquals(5L, result.getFailedRequests());
            assertEquals(95.0, result.getSuccessRate(), 0.1);
        }

        @Test
        @DisplayName("获取统计信息使用默认时间范围")
        void testGetTokenUsageStatisticsDefaultTimeRange() {
            when(tokenUsageRepository.countByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.countTotalTokensByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.countSuccessByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.countFailedByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.avgResponseTimeByTimeRange(any(), any())).thenReturn(0.0);
            when(tokenUsageRepository.countTokensByServiceType(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByModel(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByProvider(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByDay(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByWeek(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByMonth(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByHour(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByApiKey(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByUser(any(), any())).thenReturn(Collections.emptyList());

            // 不传时间参数，使用默认值
            TokenUsageStatisticsDTO result = tokenUsageService.getTokenUsageStatistics(null, null);

            assertNotNull(result);
            assertNotNull(result.getStartTime());
            assertNotNull(result.getEndTime());
        }

        @Test
        @DisplayName("issue #714：成功率分母用同窗口计数（全表1000/窗口10/成功7 → 70%，反证旧 0.7%）")
        void testSuccessRateUsesWindowDenominator() {
            LocalDateTime startTime = LocalDateTime.now().minusDays(1);
            LocalDateTime endTime = LocalDateTime.now();

            // 分母 = 窗口内 10 条（修复前是全表 1000 条 → 成功率被稀释为 0.7%）
            when(tokenUsageRepository.countByTimeRange(any(), any())).thenReturn(10L);
            when(tokenUsageRepository.countTotalTokensByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.countSuccessByTimeRange(any(), any())).thenReturn(7L);
            when(tokenUsageRepository.countFailedByTimeRange(any(), any())).thenReturn(3L);
            when(tokenUsageRepository.avgResponseTimeByTimeRange(any(), any())).thenReturn(0.0);
            when(tokenUsageRepository.countTokensByServiceType(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByModel(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByProvider(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByDay(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByWeek(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByMonth(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByHour(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByApiKey(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByUser(any(), any())).thenReturn(Collections.emptyList());

            TokenUsageStatisticsDTO result = tokenUsageService.getTokenUsageStatistics(startTime, endTime);

            assertEquals(10L, result.getTotalRequests(), "分母必须是窗口内计数而非全表");
            assertEquals(7L, result.getSuccessfulRequests());
            assertEquals(70.0, result.getSuccessRate(), 0.01,
                    "修复前 7/1000=0.7%（分母全表）；修复后 7/10=70%（分母同窗口）");
        }

        @Test
        @DisplayName("issue #714：窗口 0 条 → 成功率 0.0 不抛异常")
        void testSuccessRateZeroWindowDoesNotThrow() {
            when(tokenUsageRepository.countByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.countTotalTokensByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.countSuccessByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.countFailedByTimeRange(any(), any())).thenReturn(0L);
            when(tokenUsageRepository.avgResponseTimeByTimeRange(any(), any())).thenReturn(0.0);
            when(tokenUsageRepository.countTokensByServiceType(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByModel(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByProvider(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByDay(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByWeek(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByMonth(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByHour(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByApiKey(any(), any())).thenReturn(Collections.emptyList());
            when(tokenUsageRepository.countTokensByUser(any(), any())).thenReturn(Collections.emptyList());

            TokenUsageStatisticsDTO result = assertDoesNotThrow(
                    () -> tokenUsageService.getTokenUsageStatistics(LocalDateTime.now().minusDays(1), LocalDateTime.now()));

            assertEquals(0L, result.getTotalRequests());
            assertEquals(0.0, result.getSuccessRate(), 0.0);
        }
    }

    @Nested
    @DisplayName("O1 成交价快照 / O2 未定价 ≠ ¥0")
    class PriceSnapshotTests {

        @Test
        @DisplayName("PriceResolver 命中 → 快照固化（input/output/version，missing=false）")
        void resolverHitSnapshotsPrice() {
            when(priceResolver.resolve(any(PriceResolver.PricingContext.class)))
                    .thenReturn(java.util.Optional.of(
                            new org.unreal.modelrouter.billing.price.PriceQuote(
                                    new java.math.BigDecimal("0.01"),
                                    new java.math.BigDecimal("0.03"), "flat")));
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            tokenUsageService.recordTokenUsage(createTestRecord());

            org.mockito.ArgumentCaptor<TokenUsageEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(TokenUsageEntity.class);
            verify(tokenUsageRepository).save(captor.capture());
            TokenUsageEntity e = captor.getValue();
            assertEquals(new java.math.BigDecimal("0.01"), e.getPriceInputSnapshot());
            assertEquals(new java.math.BigDecimal("0.03"), e.getPriceOutputSnapshot());
            assertEquals("flat", e.getPriceVersion());
            assertEquals(Boolean.FALSE, e.getPriceMissing());
        }

        @Test
        @DisplayName("计价键取 instance_name 优先，instance 为空回落 model_name")
        void resolverKeyPrefersInstanceName() {
            when(priceResolver.resolve(any(PriceResolver.PricingContext.class)))
                    .thenReturn(java.util.Optional.empty());
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TokenUsageRecordDTO noInstance = createTestRecord();
            noInstance.setInstanceName(null);
            tokenUsageService.recordTokenUsage(noInstance);

            org.mockito.ArgumentCaptor<PriceResolver.PricingContext> ctxCaptor =
                    org.mockito.ArgumentCaptor.forClass(PriceResolver.PricingContext.class);
            verify(priceResolver).resolve(ctxCaptor.capture());
            assertEquals("gpt-4", ctxCaptor.getValue().modelName());
            // B1 / P0-6：租户上下文确已进入取价链路
            assertEquals(org.unreal.modelrouter.enterprise.config.TenantContextHolder.currentTenantId(),
                    ctxCaptor.getValue().tenantId());
        }

        @Test
        @DisplayName("P0-6：非默认租户 ID 透传进取价上下文（议价能力前置）")
        void nonDefaultTenantIsPassedToPricingContext() {
            final org.unreal.modelrouter.enterprise.spi.TenantContext tenantContext =
                    mock(org.unreal.modelrouter.enterprise.spi.TenantContext.class);
            when(tenantContext.currentTenantId()).thenReturn("acme");
            org.unreal.modelrouter.enterprise.config.TenantContextHolder.setDelegate(tenantContext);
            try {
                when(priceResolver.resolve(any(PriceResolver.PricingContext.class)))
                        .thenReturn(java.util.Optional.empty());
                when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                        .thenAnswer(inv -> inv.getArgument(0));

                tokenUsageService.recordTokenUsage(createTestRecord());

                org.mockito.ArgumentCaptor<PriceResolver.PricingContext> captor =
                        org.mockito.ArgumentCaptor.forClass(PriceResolver.PricingContext.class);
                verify(priceResolver).resolve(captor.capture());
                assertEquals("acme", captor.getValue().tenantId());
                assertEquals("instance-1", captor.getValue().modelName());
                assertEquals(0L, captor.getValue().cachedTokens());
                assertEquals("CNY", captor.getValue().currency());
            } finally {
                // 静态持有器必须复位，避免污染全量测试的其他用例
                org.unreal.modelrouter.enterprise.config.TenantContextHolder.setDelegate(null);
            }
        }

        @Test
        @DisplayName("PriceResolver 未命中 → priceMissing=true，不虚构 ¥0 快照")
        void resolverMissMarksMissing() {
            when(priceResolver.resolve(any(PriceResolver.PricingContext.class)))
                    .thenReturn(java.util.Optional.empty());
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            tokenUsageService.recordTokenUsage(createTestRecord());

            org.mockito.ArgumentCaptor<TokenUsageEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(TokenUsageEntity.class);
            verify(tokenUsageRepository).save(captor.capture());
            TokenUsageEntity e = captor.getValue();
            assertEquals(Boolean.TRUE, e.getPriceMissing());
            assertNull(e.getPriceInputSnapshot());
            assertNull(e.getPriceOutputSnapshot());
            assertNull(e.getPriceVersion());
        }

        @Test
        @DisplayName("DTO 显式快照优先，不再调 PriceResolver")
        void explicitSnapshotWins() {
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TokenUsageRecordDTO record = createTestRecord();
            record.setPriceInputSnapshot(new java.math.BigDecimal("0.5"));
            record.setPriceOutputSnapshot(new java.math.BigDecimal("1.5"));
            record.setPriceVersion("negotiated");
            tokenUsageService.recordTokenUsage(record);

            verify(priceResolver, never()).resolve(org.mockito.ArgumentMatchers.anyString());
            verify(priceResolver, never()).resolve(any(PriceResolver.PricingContext.class));
            org.mockito.ArgumentCaptor<TokenUsageEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(TokenUsageEntity.class);
            verify(tokenUsageRepository).save(captor.capture());
            assertEquals("negotiated", captor.getValue().getPriceVersion());
            assertEquals(Boolean.FALSE, captor.getValue().getPriceMissing());
        }

        @Test
        @DisplayName("PriceResolver 抛异常 → 按缺失处理，计量永不阻断")
        void resolverExceptionDoesNotBlockMetering() {
            when(priceResolver.resolve(any(PriceResolver.PricingContext.class)))
                    .thenThrow(new RuntimeException("price db down"));
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            assertDoesNotThrow(() -> tokenUsageService.recordTokenUsage(createTestRecord()));

            org.mockito.ArgumentCaptor<TokenUsageEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(TokenUsageEntity.class);
            verify(tokenUsageRepository).save(captor.capture());
            assertEquals(Boolean.TRUE, captor.getValue().getPriceMissing());
        }
    }

    @Nested
    @DisplayName("O7-b: 计费接线（提交后扣费）")
    class BillingChargeTests {

        private org.unreal.modelrouter.enterprise.spi.BillingProvider.BillingEntry chargeCaptor() {
            org.mockito.ArgumentCaptor<org.unreal.modelrouter.enterprise.spi.BillingProvider.BillingEntry> c =
                    org.mockito.ArgumentCaptor.forClass(
                            org.unreal.modelrouter.enterprise.spi.BillingProvider.BillingEntry.class);
            verify(billingProvider).charge(c.capture());
            return c.getValue();
        }

        @Test
        @DisplayName("事务激活时：提交前不扣费，afterCommit 后按快照价扣费")
        void chargesOnlyAfterCommit() {
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                TokenUsageRecordDTO record = createTestRecord();
                record.setPriceInputSnapshot(new java.math.BigDecimal("0.01"));
                record.setPriceOutputSnapshot(new java.math.BigDecimal("0.03"));

                tokenUsageService.recordTokenUsage(record);

                // 主事务未提交：不扣费
                verify(billingProvider, never()).charge(any());

                // 模拟提交：触发 afterCommit
                for (org.springframework.transaction.support.TransactionSynchronization s
                        : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                    s.afterCommit();
                }

                // cost = (100×0.01 + 50×0.03)/1000 = 0.0025
                var entry = chargeCaptor();
                assertEquals("trace-001", entry.traceId(),
                        "B2：traceId 必须透传到 BillingEntry，作为真实账本的幂等键");
                assertEquals(new java.math.BigDecimal("0.002500"), entry.cost());
                assertEquals("user-001", entry.username());
                assertEquals("instance-1", entry.model());
                assertEquals(150L, entry.tokens());
                assertEquals("default", entry.tenantId());
            } finally {
                if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                    org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
                }
            }
        }

        @Test
        @DisplayName("B2：不同 traceId 生成不同幂等键（与落库用量同源）")
        void traceIdIsPropagatedAsIdempotencyKey() {
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            final TokenUsageRecordDTO record = createTestRecord();
            record.setTraceId("trace-unique-42");
            record.setPriceInputSnapshot(new java.math.BigDecimal("0.01"));
            record.setPriceOutputSnapshot(new java.math.BigDecimal("0.03"));

            tokenUsageService.recordTokenUsage(record);

            assertEquals("trace-unique-42", chargeCaptor().traceId());
        }

        @Test
        @DisplayName("无事务上下文：直接同步扣费（单测/非事务调用点兜底）")
        void chargesImmediatelyWithoutTransaction() {
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TokenUsageRecordDTO record = createTestRecord();
            record.setPriceInputSnapshot(new java.math.BigDecimal("0.01"));
            record.setPriceOutputSnapshot(new java.math.BigDecimal("0.03"));

            tokenUsageService.recordTokenUsage(record);

            assertEquals(new java.math.BigDecimal("0.002500"), chargeCaptor().cost());
        }

        @Test
        @DisplayName("未定价（priceMissing）→ 不扣费，绝不按 ¥0 计")
        void unpricedNeverChargedAsZero() {
            when(priceResolver.resolve(any(PriceResolver.PricingContext.class)))
                    .thenReturn(java.util.Optional.empty());
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            tokenUsageService.recordTokenUsage(createTestRecord());

            verify(billingProvider, never()).charge(any());
        }

        @Test
        @DisplayName("企业实现抛异常 → 记录不失败、请求不受影响（降级放行）")
        void chargeFailureDoesNotBreakRecording() {
            when(tokenUsageRepository.save(any(TokenUsageEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(billingProvider.charge(any()))
                    .thenThrow(new RuntimeException("billing backend down"));

            TokenUsageRecordDTO record = createTestRecord();
            record.setPriceInputSnapshot(new java.math.BigDecimal("0.01"));
            record.setPriceOutputSnapshot(new java.math.BigDecimal("0.03"));

            assertDoesNotThrow(() -> tokenUsageService.recordTokenUsage(record));
            verify(billingProvider).charge(any());
        }
    }
}
