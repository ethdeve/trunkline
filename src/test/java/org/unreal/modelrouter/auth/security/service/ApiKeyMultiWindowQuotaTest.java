/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.model.UsageStatistics;
import org.unreal.modelrouter.auth.security.quota.QuotaWindow;
import org.unreal.modelrouter.auth.security.util.ApiKeyHashUtil;
import org.unreal.modelrouter.common.exception.AuthenticationException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-A：小时 / 月窗口配额在认证链路真正生效的单元测试。
 *
 * <p><b>被测缺口</b>：{@code ApiKey} 上的 {@code hourlyRequestLimit} /
 * {@code hourlyTokenLimit} / {@code monthlyRequestLimit} / {@code monthlyTokenLimit}
 * 四个限额此前「可配置、可展示、但不生效」——认证链
 * （{@link ApiKeyValidator#validateFully}）只校验日配额。</p>
 *
 * <p><b>本类锁死</b>：</p>
 * <ol>
 *   <li>窗口桶（小时 / 月）随用量正确累加，键与 {@link QuotaWindow} 自然窗口对齐；</li>
 *   <li>「限额为 0 = 不限制」与日配额口径一致；</li>
 *   <li>认证链在超限时<b>真的拒绝</b>（{@code validateFully} 返回失败），并给出可区分的
 *       错误码与 HTTP 429。</li>
 * </ol>
 *
 * <p>纯逻辑测试，不使用 Mockito（避免依赖 inline mock maker）。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("P0-A 多窗口配额（小时 / 月）生效测试")
class ApiKeyMultiWindowQuotaTest {

    private static final String RAW_KEY = "sk-p0a-test-key-0001";

    // ==================== 窗口桶累加 ====================

    @Nested
    @DisplayName("UsageStatistics 窗口桶")
    class UsageBucketTests {

        @Test
        @DisplayName("成功请求同时累加日 / 小时 / 月三个窗口的请求桶")
        void successIncrementsAllWindows() {
            final UsageStatistics usage = UsageStatistics.builder().build();

            usage.incrementRequest(true);

            assertEquals(1L, usage.getDailyUsage().get(
                    LocalDateTime.now().toLocalDate().toString()));
            assertEquals(1L, usage.getHourlyUsage().get(UsageStatistics.currentHourKey()));
            assertEquals(1L, usage.getMonthlyUsage().get(UsageStatistics.currentMonthKey()));
        }

        @Test
        @DisplayName("失败请求不占窗口额度（与日桶同口径：被拒请求不计入用量）")
        void failureDoesNotConsumeQuota() {
            final UsageStatistics usage = UsageStatistics.builder().build();

            usage.incrementRequest(false);

            assertTrue(usage.getHourlyUsage() == null || usage.getHourlyUsage().isEmpty(),
                    "失败请求不得累加小时桶");
            assertTrue(usage.getMonthlyUsage() == null || usage.getMonthlyUsage().isEmpty(),
                    "失败请求不得累加月桶");
            assertEquals(1L, usage.getTotalRequests(), "审计口径：总请求数仍应记录");
            assertEquals(1L, usage.getFailedRequests());
        }

        @Test
        @DisplayName("Token 用量同时累加日 / 小时 / 月三个窗口的 Token 桶")
        void tokensIncrementAllWindows() {
            final UsageStatistics usage = UsageStatistics.builder().build();

            usage.incrementTokens(120L);
            usage.incrementTokens(80L);

            assertEquals(200L, usage.getHourlyTokenUsage().get(UsageStatistics.currentHourKey()));
            assertEquals(200L, usage.getMonthlyTokenUsage().get(UsageStatistics.currentMonthKey()));
            assertEquals(200L, usage.getDailyTokenUsage().get(
                    LocalDateTime.now().toLocalDate().toString()));
        }

        @Test
        @DisplayName("非正 Token 数被忽略")
        void nonPositiveTokensIgnored() {
            final UsageStatistics usage = UsageStatistics.builder().build();

            usage.incrementTokens(0L);
            usage.incrementTokens(-5L);

            assertTrue(usage.getHourlyTokenUsage() == null || usage.getHourlyTokenUsage().isEmpty());
            assertTrue(usage.getMonthlyTokenUsage() == null || usage.getMonthlyTokenUsage().isEmpty());
        }

        @Test
        @DisplayName("窗口键与配额账本 QuotaWindow 自然窗口对齐")
        void windowKeysAlignWithQuotaWindow() {
            // 小时桶键 == QuotaWindow.HOUR 的窗口起点
            assertEquals(QuotaWindow.HOUR.windowStart(LocalDateTime.now()).toString(),
                    UsageStatistics.currentHourKey());
            // 月桶键 == QuotaWindow.MONTH 的窗口起点（当月 1 日 0 点）
            assertEquals(QuotaWindow.MONTH.windowStart(LocalDateTime.now()).toString(),
                    UsageStatistics.currentMonthKey());
            // 月桶键必须是当月 1 日
            assertTrue(UsageStatistics.currentMonthKey().startsWith(
                    LocalDateTime.now().toLocalDate().withDayOfMonth(1).toString()));
        }

        @Test
        @DisplayName("小时桶有界：写入时裁剪到最近 48 个窗口（防持久化 JSON 无限增长）")
        void hourlyBucketsAreBounded() {
            final UsageStatistics usage = UsageStatistics.builder()
                    .hourlyUsage(new HashMap<>())
                    .build();
            // 预置 100 个历史小时桶
            for (int i = 0; i < 100; i++) {
                usage.getHourlyUsage().put("2026-01-01T00:" + String.format("%02d", i % 60), 1L);
            }
            usage.getHourlyUsage().put(UsageStatistics.currentHourKey(), 1L);

            usage.incrementRequest(true);

            assertTrue(usage.getHourlyUsage().size() <= 48,
                    "小时桶必须被裁剪，实际 " + usage.getHourlyUsage().size());
            assertTrue(usage.getHourlyUsage().containsKey(UsageStatistics.currentHourKey()),
                    "当前窗口必须保留");
        }

        @Test
        @DisplayName("reset() 清空全部窗口；resetDaily() 只清日桶（不能借重置日配额绕过小时/月配额）")
        void resetSemantics() {
            final UsageStatistics usage = UsageStatistics.builder().build();
            usage.incrementRequest(true);
            usage.incrementTokens(100L);

            usage.resetDaily();
            assertTrue(usage.getDailyUsage().isEmpty(), "日桶应被清空");
            assertFalse(usage.getHourlyUsage().isEmpty(), "小时桶不得被 resetDaily 清空");
            assertFalse(usage.getMonthlyUsage().isEmpty(), "月桶不得被 resetDaily 清空");

            usage.reset();
            assertTrue(usage.getHourlyUsage().isEmpty(), "reset 应清空小时桶");
            assertTrue(usage.getMonthlyUsage().isEmpty(), "reset 应清空月桶");
            assertTrue(usage.getMonthlyTokenUsage().isEmpty());
        }
    }

    // ==================== 限额判定 ====================

    @Nested
    @DisplayName("ApiKey 限额判定")
    class LimitPredicateTests {

        @Test
        @DisplayName("限额为 0 = 不限制")
        void zeroLimitMeansUnlimited() {
            final ApiKey key = ApiKey.builder()
                    .usage(UsageStatistics.builder()
                            .hourlyUsage(new HashMap<>(Map.of(UsageStatistics.currentHourKey(), 9999L)))
                            .monthlyUsage(new HashMap<>(Map.of(UsageStatistics.currentMonthKey(), 9999L)))
                            .build())
                    .build();

            assertFalse(key.isHourlyRequestLimitExceeded());
            assertFalse(key.isHourlyTokenLimitExceeded());
            assertFalse(key.isMonthlyRequestLimitExceeded());
            assertFalse(key.isMonthlyTokenLimitExceeded());
        }

        @Test
        @DisplayName("用量 >= 限额即视为超限")
        void reachedLimitIsExceeded() {
            final ApiKey key = ApiKey.builder()
                    .hourlyRequestLimit(10L)
                    .monthlyRequestLimit(100L)
                    .usage(UsageStatistics.builder()
                            .hourlyUsage(new HashMap<>(Map.of(UsageStatistics.currentHourKey(), 10L)))
                            .monthlyUsage(new HashMap<>(Map.of(UsageStatistics.currentMonthKey(), 100L)))
                            .build())
                    .build();

            assertTrue(key.isHourlyRequestLimitExceeded());
            assertTrue(key.isMonthlyRequestLimitExceeded());
            assertEquals(10L, key.getCurrentHourRequestCount());
            assertEquals(100L, key.getCurrentMonthRequestCount());
        }

        @Test
        @DisplayName("未达限额不超限；usage 为 null 时不超限")
        void belowLimitAndNullUsage() {
            final ApiKey below = ApiKey.builder()
                    .monthlyRequestLimit(100L)
                    .usage(UsageStatistics.builder()
                            .monthlyUsage(new HashMap<>(Map.of(UsageStatistics.currentMonthKey(), 99L)))
                            .build())
                    .build();
            assertFalse(below.isMonthlyRequestLimitExceeded());

            final ApiKey noUsage = ApiKey.builder().monthlyRequestLimit(100L).build();
            assertFalse(noUsage.isMonthlyRequestLimitExceeded());
            assertEquals(0L, noUsage.getCurrentMonthRequestCount());
        }
    }

    // ==================== 认证链路端到端 ====================

    @Nested
    @DisplayName("认证链路端到端")
    class AuthChainTests {

        private ApiKeyValidator newValidator() {
            return new ApiKeyValidator();
        }

        private Map<String, ApiKey> cacheOf(final ApiKey key) {
            final Map<String, ApiKey> cache = new HashMap<>();
            cache.put(key.getKeyHash(), key);
            return cache;
        }

        private ApiKey keyWith(final UsageStatistics usage) {
            return ApiKey.builder()
                    .keyId("key-p0a-1")
                    .keyHash(ApiKeyHashUtil.hashApiKey(RAW_KEY))
                    .enabled(true)
                    .usage(usage)
                    .build();
        }

        @Test
        @DisplayName("本月累计超限 → 认证链拒绝（MONTHLY_QUOTA_EXCEEDED，HTTP 429）")
        void monthlyExceededIsRejected() {
            final ApiKey key = keyWith(UsageStatistics.builder()
                    .monthlyUsage(new HashMap<>(Map.of(UsageStatistics.currentMonthKey(), 500L)))
                    .build());
            key.setMonthlyRequestLimit(100L);

            final ApiKeyValidator.FullValidationResult result =
                    newValidator().validateFully(RAW_KEY, cacheOf(key), "10.0.0.1");

            assertFalse(result.isSuccess(), "本月累计超限必须被拒绝");
            assertEquals(AuthenticationException.MONTHLY_QUOTA_EXCEEDED, result.getErrorCode());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, result.toException().getHttpStatus(),
                    "配额超限属于服务端拒绝，应返回 429 而非 401");
        }

        @Test
        @DisplayName("本月 Token 累计超限 → 认证链拒绝（MONTHLY_TOKEN_QUOTA_EXCEEDED）")
        void monthlyTokenExceededIsRejected() {
            final ApiKey key = keyWith(UsageStatistics.builder()
                    .monthlyTokenUsage(new HashMap<>(Map.of(UsageStatistics.currentMonthKey(), 99_999L)))
                    .build());
            key.setMonthlyTokenLimit(50_000L);

            final ApiKeyValidator.FullValidationResult result =
                    newValidator().validateFully(RAW_KEY, cacheOf(key), "10.0.0.1");

            assertFalse(result.isSuccess());
            assertEquals(AuthenticationException.MONTHLY_TOKEN_QUOTA_EXCEEDED, result.getErrorCode());
        }

        @Test
        @DisplayName("本小时累计超限 → 认证链拒绝（HOURLY_QUOTA_EXCEEDED）")
        void hourlyExceededIsRejected() {
            final ApiKey key = keyWith(UsageStatistics.builder()
                    .hourlyUsage(new HashMap<>(Map.of(UsageStatistics.currentHourKey(), 50L)))
                    .build());
            key.setHourlyRequestLimit(10L);

            final ApiKeyValidator.FullValidationResult result =
                    newValidator().validateFully(RAW_KEY, cacheOf(key), "10.0.0.1");

            assertFalse(result.isSuccess());
            assertEquals(AuthenticationException.HOURLY_QUOTA_EXCEEDED, result.getErrorCode());
        }

        @Test
        @DisplayName("本小时 Token 累计超限 → 认证链拒绝（HOURLY_TOKEN_QUOTA_EXCEEDED）")
        void hourlyTokenExceededIsRejected() {
            final ApiKey key = keyWith(UsageStatistics.builder()
                    .hourlyTokenUsage(new HashMap<>(Map.of(UsageStatistics.currentHourKey(), 9_999L)))
                    .build());
            key.setHourlyTokenLimit(1_000L);

            final ApiKeyValidator.FullValidationResult result =
                    newValidator().validateFully(RAW_KEY, cacheOf(key), "10.0.0.1");

            assertFalse(result.isSuccess());
            assertEquals(AuthenticationException.HOURLY_TOKEN_QUOTA_EXCEEDED, result.getErrorCode());
        }

        @Test
        @DisplayName("未超限 → 认证链放行（且返回的副本仍带限额字段）")
        void withinLimitPasses() {
            final ApiKey key = keyWith(UsageStatistics.builder()
                    .monthlyUsage(new HashMap<>(Map.of(UsageStatistics.currentMonthKey(), 10L)))
                    .hourlyUsage(new HashMap<>(Map.of(UsageStatistics.currentHourKey(), 2L)))
                    .build());
            key.setMonthlyRequestLimit(100L);
            key.setHourlyRequestLimit(50L);

            final ApiKeyValidator.FullValidationResult result =
                    newValidator().validateFully(RAW_KEY, cacheOf(key), "10.0.0.1");

            assertTrue(result.isSuccess(), "未超限必须放行");
            assertNotNull(result.getApiKey());
            assertEquals(100L, result.getApiKey().getMonthlyRequestLimit());
        }

        @Test
        @DisplayName("超限优先于限流：月配额先于速率限制短路（不白扣限流令牌）")
        void monthlyCheckedBeforeRateLimit() {
            final ApiKey key = keyWith(UsageStatistics.builder()
                    .monthlyUsage(new HashMap<>(Map.of(UsageStatistics.currentMonthKey(), 500L)))
                    .build());
            key.setMonthlyRequestLimit(100L);
            key.setRateLimitPerMinute(1);

            final ApiKeyValidator.FullValidationResult result =
                    newValidator().validateFully(RAW_KEY, cacheOf(key), "10.0.0.1");

            assertFalse(result.isSuccess());
            assertEquals(AuthenticationException.MONTHLY_QUOTA_EXCEEDED, result.getErrorCode(),
                    "月配额超限应先于速率限制命中");
        }
    }
}
