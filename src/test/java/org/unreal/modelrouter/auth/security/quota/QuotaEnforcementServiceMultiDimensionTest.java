/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link QuotaEnforcementService} 多维度（Key 总闸 / 分组 / 模型）预留与回滚测试（v3.4 一期）。
 *
 * <p>被测对象是真实 {@link QuotaEnforcementService} + 真实 {@link QuotaLedgerService} +
 * 手写内存仓库（{@link InMemoryQuotaLedgerRepository}），时钟固定；{@link ApiKeyService}
 * 用 Mockito 桩（它由 JPA 存储支撑），返回真实 {@link HashMap} 作为缓存与索引。
 * 分组 / 模型限额解析器用真实实现（lambda）经<b>反射注入</b>执行层的可选 Bean 字段，
 * 不依赖 Spring 容器。</p>
 *
 * <p>覆盖的核心不变量：</p>
 * <ul>
 *   <li>三个维度各自独立预算：任一维度超限即拒绝，其余维度不得留下残量；</li>
 *   <li>维度之间不串账：A 分组的用量不得计入 B 分组；</li>
 *   <li>无有效限额的维度只记账、不判定；</li>
 *   <li>预留凭据同时挂载多个维度，结算按维度逐条冲正。</li>
 * </ul>
 *
 * @author Trunkline Team
 * @modified Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QuotaEnforcementService 多维度配额测试")
class QuotaEnforcementServiceMultiDimensionTest {

    private static final String KEY_ID = "key-1";
    private static final String KEY_HASH = "hash-key-1";
    private static final String GROUP_A = "group-a";
    private static final String GROUP_B = "group-b";
    private static final String MODEL_A = "model-a";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定时刻 2026-03-14 13:45:30 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 14, 13, 45, 30);

    @Mock
    private ApiKeyService apiKeyService;

    private InMemoryQuotaLedgerRepository repository;
    private QuotaProperties properties;
    private QuotaLedgerService ledgerService;
    private QuotaEnforcementService enforcementService;
    private Map<String, ApiKey> apiKeyCache;
    private Map<String, String> keyIdIndex;
    private ServerHttpRequest request;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        final Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

        repository = InMemoryQuotaLedgerRepository.create();
        properties = new QuotaProperties();
        properties.setEnabled(true);

        apiKeyCache = new HashMap<>();
        keyIdIndex = new HashMap<>();
        when(apiKeyService.getApiKeyCache()).thenReturn(apiKeyCache);
        when(apiKeyService.getKeyIdIndex()).thenReturn(keyIdIndex);

        ledgerService = new QuotaLedgerService(repository.proxy(), properties, clock);
        enforcementService = new QuotaEnforcementService(ledgerService, properties, apiKeyService, clock);
        request = MockServerWebExchange.from(MockServerHttpRequest
            .post("/api/v1/chat/completions").build()).getRequest();
    }

    // ==================== 分组维度判定 ====================

    @Test
    @DisplayName("分组维度超限：拒绝请求，且 Key 维度本笔增量被回滚（不多扣）")
    void groupLimitExceeded_shouldRejectAndRollbackKeyDimension() {
        registerApiKey(0L, 0L, 0);
        installGroupResolver(GROUP_A, new QuotaLimitSet(0L, 0L, 0L, 1L, 0L, 0L, 0L));

        // 第 1 次：分组额度充足，放行
        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isEmpty());
        assertEquals(1L, requestCount(QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY));
        assertEquals(1L, requestCount(QuotaDimension.ofGroup(GROUP_A), QuotaWindow.DAY));

        // 第 2 次：分组日请求额度已用满 → 拒绝
        final Optional<QuotaLimitViolation> violation =
            enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L);
        assertTrue(violation.isPresent(), "分组额度用满后应拒绝");
        assertEquals(QuotaLimitViolation.METRIC_DAILY_REQUESTS, violation.get().metric());
        assertEquals(QuotaWindow.DAY, violation.get().window());

        // 关键断言：被拒的请求不得在 Key 维度留下残量
        assertEquals(1L, requestCount(QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY),
            "被拒请求的 Key 维度增量必须回滚");
        assertEquals(10L, tokenCount(QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY),
            "被拒请求的 Key 维度 token 增量必须回滚（应只剩第 1 次放行的 10）");
        assertEquals(1L, requestCount(QuotaDimension.ofGroup(GROUP_A), QuotaWindow.DAY),
            "分组维度只保留放行的那一笔");
    }

    @Test
    @DisplayName("Key 维度超限：拒绝请求，分组维度增量同样回滚（对称性）")
    void keyLimitExceeded_shouldRejectAndRollbackGroupDimension() {
        registerApiKey(1L, 0L, 0);
        installGroupResolver(GROUP_A, new QuotaLimitSet(0L, 0L, 0L, 100L, 0L, 0L, 0L));

        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isEmpty());
        assertEquals(1L, requestCount(QuotaDimension.ofGroup(GROUP_A), QuotaWindow.DAY));

        final Optional<QuotaLimitViolation> violation =
            enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L);
        assertTrue(violation.isPresent(), "Key 额度用满后应拒绝");

        assertEquals(1L, requestCount(QuotaDimension.ofGroup(GROUP_A), QuotaWindow.DAY),
            "被拒请求的分组维度增量必须回滚");
    }

    @Test
    @DisplayName("分组间隔离：A 分组额度用满不影响 B 分组")
    void groupIsolation_shouldNotLeakUsageBetweenGroups() {
        registerApiKey(0L, 0L, 0);
        installGroupResolver(GROUP_A, new QuotaLimitSet(0L, 0L, 0L, 1L, 0L, 0L, 0L));
        installGroupResolver(GROUP_B, new QuotaLimitSet(0L, 0L, 0L, 1L, 0L, 0L, 0L));

        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isEmpty());
        assertFalse(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isPresent(),
            "A 分组第二次应被拒绝");

        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_B, "", 10L).isEmpty(),
            "B 分组有独立额度，不应受 A 分组影响");
        assertEquals(1L, requestCount(QuotaDimension.ofGroup(GROUP_B), QuotaWindow.DAY));
    }

    @Test
    @DisplayName("未配置限额的分组：只记账不判定（放行但计数连续）")
    void groupWithoutLimits_shouldOnlyRecordUsage() {
        registerApiKey(0L, 0L, 0);
        installGroupResolver(GROUP_A, QuotaLimitSet.UNLIMITED);

        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isEmpty());
        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isEmpty());

        assertEquals(2L, requestCount(QuotaDimension.ofGroup(GROUP_A), QuotaWindow.DAY),
            "无限额分组仍应完整记账");
    }

    @Test
    @DisplayName("未知分组（解析器返回空）：按无限制放行，不影响其它维度")
    void unknownGroup_shouldBeTreatedAsUnlimited() {
        registerApiKey(0L, 0L, 0);
        installGroupResolver(GROUP_A, new QuotaLimitSet(0L, 0L, 0L, 1L, 0L, 0L, 0L));

        assertTrue(enforcementService.tryReserve(request, KEY_ID, "group-unknown", "", 10L).isEmpty());
        assertEquals(1L, requestCount(QuotaDimension.ofGroup("group-unknown"), QuotaWindow.DAY));
    }

    // ==================== 模型维度 ====================

    @Test
    @DisplayName("模型维度超限：拒绝请求，Key 维度增量回滚")
    void modelLimitExceeded_shouldRejectAndRollbackKeyDimension() {
        registerApiKey(0L, 0L, 0);
        installModelResolver(MODEL_A, new QuotaLimitSet(0L, 0L, 0L, 1L, 0L, 0L, 0L));

        assertTrue(enforcementService.tryReserve(request, KEY_ID, "", MODEL_A, 10L).isEmpty());
        assertEquals(1L, requestCount(QuotaDimension.ofModel(MODEL_A), QuotaWindow.DAY));

        final Optional<QuotaLimitViolation> violation =
            enforcementService.tryReserve(request, KEY_ID, "", MODEL_A, 10L);
        assertTrue(violation.isPresent(), "模型额度用满后应拒绝");
        assertEquals(1L, requestCount(QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY),
            "被拒请求的 Key 维度增量必须回滚");
    }

    @Test
    @DisplayName("分组与模型互斥：只传分组时不产生模型维度计数")
    void groupAndModel_areMutuallyExclusive() {
        registerApiKey(0L, 0L, 0);
        installGroupResolver(GROUP_A, QuotaLimitSet.UNLIMITED);
        installModelResolver(MODEL_A, QuotaLimitSet.UNLIMITED);

        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isEmpty());

        assertEquals(1L, requestCount(QuotaDimension.ofGroup(GROUP_A), QuotaWindow.DAY));
        assertEquals(0L, requestCount(QuotaDimension.ofModel(MODEL_A), QuotaWindow.DAY),
            "走分组维度时不得同时计入模型维度");
    }

    // ==================== 预留凭据 ====================

    @Test
    @DisplayName("预留凭据同时挂载 Key 与分组两个维度")
    void reservation_shouldCarryMultipleDimensions() {
        registerApiKey(0L, 0L, 0);
        installGroupResolver(GROUP_A, QuotaLimitSet.UNLIMITED);

        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isEmpty());

        final QuotaReservation reservation = QuotaReservation.from(request);
        assertNotNull(reservation);
        assertEquals(2, reservation.dimensions().size(), "凭据应同时携带 Key 与分组两个维度");
        assertEquals(KEY_ID, reservation.dimensions().get(0).apiKeyId());
        assertEquals(GROUP_A, reservation.dimensions().get(1).groupId());
    }

    @Test
    @DisplayName("结算按维度逐条冲正：Key 与分组维度的请求数都归零")
    void settlement_shouldRefundEveryDimension() {
        registerApiKey(0L, 0L, 0);
        installGroupResolver(GROUP_A, QuotaLimitSet.UNLIMITED);

        assertTrue(enforcementService.tryReserve(request, KEY_ID, GROUP_A, "", 10L).isEmpty());
        assertEquals(1L, requestCount(QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY));

        QuotaReservation.settleFailure(QuotaReservation.from(request));

        assertEquals(0L, requestCount(QuotaDimension.ofApiKey(KEY_ID), QuotaWindow.DAY));
        assertEquals(0L, requestCount(QuotaDimension.ofGroup(GROUP_A), QuotaWindow.DAY));
    }

    // ==================== 辅助方法 ====================

    /**
     * 注入分组维度限额解析器（执行层的可选 Bean 字段，测试环境无 Spring 容器）。
     */
    private void installGroupResolver(final String groupId, final QuotaLimitSet limits) {
        injectResolver("groupLimitResolver", groupId, limits);
    }

    /**
     * 注入模型维度限额解析器。
     */
    private void installModelResolver(final String model, final QuotaLimitSet limits) {
        injectResolver("modelLimitResolver", model, limits);
    }

    private void injectResolver(final String fieldName, final String scopeId, final QuotaLimitSet limits) {
        try {
            final Field field = QuotaEnforcementService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(enforcementService, (QuotaLimitResolver) id -> scopeId.equals(id)
                ? Optional.of(limits) : Optional.empty());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入配额限额解析器失败: " + fieldName, e);
        }
    }

    private long requestCount(final QuotaDimension dimension, final QuotaWindow window) {
        return ledgerService.usageStrict(dimension, window).map(QuotaUsage::requestCount).orElse(0L);
    }

    private long tokenCount(final QuotaDimension dimension, final QuotaWindow window) {
        return ledgerService.usageStrict(dimension, window).map(QuotaUsage::tokenCount).orElse(0L);
    }

    /**
     * 注册一个带限额的 API Key（写入 mock 返回的真实缓存与索引 Map）。
     *
     * @param dailyRequestLimit  日请求限额（0 = 不限）
     * @param dailyTokenLimit    日 token 限额（0 = 不限）
     * @param rateLimitPerMinute 每分钟请求限额（0 = 不限）
     */
    private void registerApiKey(final long dailyRequestLimit,
                                final long dailyTokenLimit,
                                final int rateLimitPerMinute) {
        apiKeyCache.put(KEY_HASH, ApiKey.builder()
            .keyId(KEY_ID)
            .keyHash(KEY_HASH)
            .dailyRequestLimit(dailyRequestLimit)
            .dailyTokenLimit(dailyTokenLimit)
            .rateLimitPerMinute(rateLimitPerMinute)
            .enabled(true)
            .build());
        keyIdIndex.put(KEY_ID, KEY_HASH);
    }
}
