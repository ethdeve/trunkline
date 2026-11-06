package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-A 补充验证：月/小时额度的<b>窗口边界语义</b>与<b>真实运行期链路</b>。
 *
 * <p>本类是 {@code ApiKeyMultiWindowQuotaTest} 的补充，覆盖其未覆盖的两类关键语义：
 * <ol>
 *   <li><b>窗口边界</b>：桶里只有上月历史 key 时本月不得超限；跨月后新桶必须从 0 开始
 *       （旧月累计不得串到新月的判定里）；</li>
 *   <li><b>真实运行期链路</b>：走完整
 *       {@link ApiKeyService#validateApiKey(String, String, String)} 认证链，
 *       证明「成功请求累加月桶 → 第 N+1 次请求被拒 → HTTP 429」这条链路<b>真的通</b>，
 *       且用量确实累加在<b>认证时读到的同一个 ApiKey 实例</b>上（不是副本）。</li>
 * </ol>
 *
 * <p>纯逻辑测试，不使用 Mockito（避开本机 inline mock maker 故障）。
 * 事件发布器用手写空实现，避免引入 Spring 上下文。</p>
 *
 * @author JAiRouter Team
 * @since P0-A
 */
@DisplayName("P0-A 补充验证：月/小时额度窗口边界 + 真实运行期链路")
class ApiKeyMonthlyWindowBoundaryTest {

    private static final String RAW_KEY = "sk-p0a-boundary-key-0001";
    private static final String KEY_ID = "key-p0a-boundary-1";

    // ==================== 窗口边界语义 ====================

    @Test
    @DisplayName("桶里只有上月历史 key → 本月不超限（历史累计不得串到本月）")
    void lastMonthOnlyKeyDoesNotTripCurrentMonth() {
        final String lastMonthKey = QuotaWindow.MONTH
                .windowStart(LocalDateTime.now().minusMonths(1)).toString();
        assertFalse(lastMonthKey.equals(UsageStatistics.currentMonthKey()),
                "前置条件：上月 key 与本月 key 必须不同");

        final ApiKey key = ApiKey.builder()
                .monthlyRequestLimit(100L)
                .monthlyTokenLimit(100L)
                .usage(UsageStatistics.builder()
                        // 只放上个月的 key，本月完全没有记录
                        .monthlyUsage(new HashMap<>(Map.of(lastMonthKey, 9999L)))
                        .monthlyTokenUsage(new HashMap<>(Map.of(lastMonthKey, 9999L)))
                        .build())
                .build();

        assertFalse(key.isMonthlyRequestLimitExceeded(),
                "上月累计再高也不应触发本月限额");
        assertFalse(key.isMonthlyTokenLimitExceeded(),
                "上月累计再高也不应触发本月 Token 限额");
        assertEquals(0L, key.getCurrentMonthRequestCount(), "本月请求数应为 0");
        assertEquals(0L, key.getCurrentMonthTokenUsage(), "本月 Token 应为 0");
    }

    @Test
    @DisplayName("跨月后新桶从 0 开始：上月累计不串入本月，新月首次累加即为 1")
    void newMonthBucketStartsFromZero() {
        final String lastMonthKey = QuotaWindow.MONTH
                .windowStart(LocalDateTime.now().minusMonths(1)).toString();
        final UsageStatistics usage = UsageStatistics.builder()
                .monthlyUsage(new HashMap<>(Map.of(lastMonthKey, 500L)))
                .build();

        // 模拟「跨月后的第一次成功请求」
        usage.incrementRequest(true);

        assertEquals(1L, usage.getMonthlyUsage().get(UsageStatistics.currentMonthKey()),
                "新月份桶应从 1 开始累加，而不是接着上月继续");
        assertEquals(500L, usage.getMonthlyUsage().get(lastMonthKey),
                "上月桶的历史值应原样保留（供查询），但不参与本月判定");
    }

    @Test
    @DisplayName("跨月后新桶从 0 开始：Token 维度同样独立")
    void newMonthTokenBucketStartsFromZero() {
        final String lastMonthKey = QuotaWindow.MONTH
                .windowStart(LocalDateTime.now().minusMonths(1)).toString();
        final UsageStatistics usage = UsageStatistics.builder()
                .monthlyTokenUsage(new HashMap<>(Map.of(lastMonthKey, 8000L)))
                .build();

        usage.incrementTokens(120L);

        assertEquals(120L, usage.getMonthlyTokenUsage().get(UsageStatistics.currentMonthKey()),
                "新月份 Token 桶应只含本次用量");
        assertEquals(8000L, usage.getMonthlyTokenUsage().get(lastMonthKey));
    }

    @Test
    @DisplayName("跨小时后新桶从 0 开始：新小时首次累加即为 1")
    void newHourBucketStartsFromZero() {
        final String lastHourKey = QuotaWindow.HOUR
                .windowStart(LocalDateTime.now().minusHours(1)).toString();
        final UsageStatistics usage = UsageStatistics.builder()
                .hourlyUsage(new HashMap<>(Map.of(lastHourKey, 42L)))
                .build();

        usage.incrementRequest(true);

        assertEquals(1L, usage.getHourlyUsage().get(UsageStatistics.currentHourKey()),
                "新小时桶应从 1 开始累加");
        assertEquals(42L, usage.getHourlyUsage().get(lastHourKey));
    }

    @Test
    @DisplayName("resetDaily() 不清月/小时桶（否则可借重置日配额绕过月/小时配额）")
    void resetDailyKeepsMonthlyAndHourlyBuckets() {
        final UsageStatistics usage = UsageStatistics.builder().build();
        usage.incrementRequest(true);
        usage.incrementTokens(100L);

        usage.resetDaily();

        assertNotNull(usage.getMonthlyUsage());
        assertFalse(usage.getMonthlyUsage().isEmpty(), "月桶不得被 resetDaily 清空");
        assertFalse(usage.getMonthlyTokenUsage().isEmpty(), "月 Token 桶不得被 resetDaily 清空");
        assertFalse(usage.getHourlyUsage().isEmpty(), "小时桶不得被 resetDaily 清空");
        assertFalse(usage.getHourlyTokenUsage().isEmpty(), "小时 Token 桶不得被 resetDaily 清空");
    }

    // ==================== 真实运行期链路 ====================

    /**
     * 手写存根：只吞掉事件发布，避免引入 Spring 上下文。
     */
    private static final class NoopEventPublisher implements ApplicationEventPublisher {
        @Override
        public void publishEvent(final Object event) {
            // 忽略：测试只关心配额链路
        }
    }

    /**
     * 构造一个「只差认证链」的 ApiKeyService：
     * 真实 {@link ApiKeyValidator} + 真实 {@link ApiKeyService#validateApiKey}，
     * 仅把存储写入替换为内存存根（避免文件/DB 依赖）。
     */
    private static ApiKeyService newService(final ApiKey key) {
        final ApiKeyService service = new ApiKeyService(null, null, null);
        ReflectionTestUtils.setField(service, "apiKeyValidator", new ApiKeyValidator());
        ReflectionTestUtils.setField(service, "eventPublisher", new NoopEventPublisher());
        ReflectionTestUtils.setField(service, "apiKeyPersistenceService", new ApiKeyPersistenceService(null, null) {
            @Override
            public void saveApiKeysToStore(final Map<String, ApiKey> apiKeyCache) {
                // 内存存根：不做真实落盘
            }
        });

        @SuppressWarnings("unchecked")
        final Map<String, ApiKey> cache =
                (Map<String, ApiKey>) ReflectionTestUtils.getField(service, "apiKeyCache");
        cache.put(key.getKeyHash(), key);
        @SuppressWarnings("unchecked")
        final Map<String, String> keyIdIndex =
                (Map<String, String>) ReflectionTestUtils.getField(service, "keyIdIndex");
        keyIdIndex.put(KEY_ID, key.getKeyHash());
        return service;
    }

    @Test
    @DisplayName("真实链路：连续成功请求累加本月用量 → 超过月限额后被拒并返回 429")
    void realChainMonthlyQuotaBlocksAfterThreshold() {
        final ApiKey key = ApiKey.builder()
                .keyId(KEY_ID)
                .keyHash(ApiKeyHashUtil.hashApiKey(RAW_KEY))
                .enabled(true)
                .monthlyRequestLimit(2L)
                .usage(UsageStatistics.builder()
                        .dailyUsage(new HashMap<>())
                        .dailyTokenUsage(new HashMap<>())
                        .hourlyUsage(new HashMap<>())
                        .hourlyTokenUsage(new HashMap<>())
                        .monthlyUsage(new HashMap<>())
                        .monthlyTokenUsage(new HashMap<>())
                        .build())
                .build();
        final ApiKeyService service = newService(key);

        // 前 2 次：未超限，正常放行
        for (int i = 1; i <= 2; i++) {
            final ApiKey ok = service.validateApiKey(RAW_KEY, "/v1/chat", "10.0.0.1").block();
            assertNotNull(ok, "第 " + i + " 次请求应放行");
            assertEquals((long) i, key.getCurrentMonthRequestCount(),
                    "成功请求应累加到<b>认证时读的同一个 ApiKey 实例</b>的本月桶上");
            System.out.println("[P0-A-EVIDENCE] 第 " + i + " 次认证通过 | monthlyRequestLimit="
                    + key.getMonthlyRequestLimit() + " | 本月桶累计=" + key.getCurrentMonthRequestCount()
                    + " | 桶key=" + UsageStatistics.currentMonthKey());
        }

        // 第 3 次：已超本月限额 → 被拒
        final AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> service.validateApiKey(RAW_KEY, "/v1/chat", "10.0.0.1").block(),
                "本月累计超限必须被拒");
        assertEquals(AuthenticationException.MONTHLY_QUOTA_EXCEEDED, ex.getErrorCode(),
                "错误码应能区分是「本月」窗口超限");
        assertEquals(429, ex.getHttpStatus().value(),
                "配额超限属于服务端拒绝，必须透出 429 而非 401");
        System.out.println("[P0-A-EVIDENCE] 第 3 次认证被拒 | errorCode=" + ex.getErrorCode()
                + " | httpStatus=" + ex.getHttpStatus().value()
                + " | message=" + ex.getMessage()
                + " | 本月桶累计=" + key.getCurrentMonthRequestCount()
                + "（未因被拒而增加）");
    }

    @Test
    @DisplayName("真实链路：本小时 Token 累计超限 → 被拒并返回 429")
    void realChainHourlyTokenQuotaBlocks() {
        final ApiKey key = ApiKey.builder()
                .keyId(KEY_ID)
                .keyHash(ApiKeyHashUtil.hashApiKey(RAW_KEY))
                .enabled(true)
                .hourlyTokenLimit(1000L)
                .usage(UsageStatistics.builder()
                        .dailyUsage(new HashMap<>())
                        .dailyTokenUsage(new HashMap<>())
                        .hourlyUsage(new HashMap<>())
                        .hourlyTokenUsage(new HashMap<>())
                        .monthlyUsage(new HashMap<>())
                        .monthlyTokenUsage(new HashMap<>())
                        .build())
                .build();
        final ApiKeyService service = newService(key);

        // 先放行一次，随后模拟令牌结算把本小时 Token 用量顶到限额之上
        service.validateApiKey(RAW_KEY, "/v1/chat", "10.0.0.1").block();
        key.getUsage().incrementTokens(1500L);
        assertEquals(1500L, key.getCurrentHourTokenUsage());

        final AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> service.validateApiKey(RAW_KEY, "/v1/chat", "10.0.0.1").block(),
                "本小时 Token 超限必须被拒");
        assertEquals(AuthenticationException.HOURLY_TOKEN_QUOTA_EXCEEDED, ex.getErrorCode());
        assertEquals(429, ex.getHttpStatus().value());
        System.out.println("[P0-A-EVIDENCE] 小时Token 超限被拒 | errorCode=" + ex.getErrorCode()
                + " | httpStatus=" + ex.getHttpStatus().value()
                + " | 本小时Token累计=" + key.getCurrentHourTokenUsage()
                + " | 桶key=" + UsageStatistics.currentHourKey());
    }

    @Test
    @DisplayName("真实链路：被配额拒绝的请求不占用月额度（不会把用量继续顶高）")
    void rejectedRequestDoesNotConsumeMonthlyQuota() {
        final ApiKey key = ApiKey.builder()
                .keyId(KEY_ID)
                .keyHash(ApiKeyHashUtil.hashApiKey(RAW_KEY))
                .enabled(true)
                .monthlyRequestLimit(1L)
                .usage(UsageStatistics.builder()
                        .dailyUsage(new HashMap<>())
                        .dailyTokenUsage(new HashMap<>())
                        .hourlyUsage(new HashMap<>())
                        .hourlyTokenUsage(new HashMap<>())
                        .monthlyUsage(new HashMap<>())
                        .monthlyTokenUsage(new HashMap<>())
                        .build())
                .build();
        final ApiKeyService service = newService(key);

        service.validateApiKey(RAW_KEY, "/v1/chat", "10.0.0.1").block();
        assertEquals(1L, key.getCurrentMonthRequestCount());

        // 第 2 次被拒
        assertThrows(AuthenticationException.class,
                () -> service.validateApiKey(RAW_KEY, "/v1/chat", "10.0.0.1").block());

        assertEquals(1L, key.getCurrentMonthRequestCount(),
                "被拒请求不得累加月额度（否则 limit=N 实际只能成功不足 N 次）");
    }
}
