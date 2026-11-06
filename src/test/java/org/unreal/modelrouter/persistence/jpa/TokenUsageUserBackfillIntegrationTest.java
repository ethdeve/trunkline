package org.unreal.modelrouter.persistence.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.unreal.modelrouter.persistence.jpa.entity.ApiCallHistoryEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ApiCallHistoryRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * V15 回填迁移的效果验证（v3.5 Trunkline 团队用量出数修复）
 *
 * <p>验证 {@code token_usage.user_id} 的回填逻辑：存量用量行（userId 为 null）
 * 能从 {@code api_call_history} 里已有的正确归属回填出来，从而被团队用量的
 * 聚合查询（{@code WHERE userId IS NOT NULL}）命中。</p>
 *
 * <p>本测试直接执行 V15 的两条回填 SQL（与迁移脚本保持一致），
 * 验证「先按 trace_id 精确匹配、再按 api_key_id 兜底」的策略。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("token_usage.user_id 回填验证")
class TokenUsageUserBackfillIntegrationTest {

    @Autowired
    private TokenUsageRepository tokenUsageRepository;

    @Autowired
    private ApiCallHistoryRepository callHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private TokenUsageEntity usage(final String traceId, final String apiKeyId,
                                    final String userId, final long tokens) {
        final TokenUsageEntity e = new TokenUsageEntity();
        e.setTraceId(traceId);
        e.setApiKeyId(apiKeyId);
        e.setServiceType("CHAT");
        e.setModelName("deepseek-v4.1-flash");
        e.setVirtualModel("test");
        e.setProvider("normal");
        e.setInstanceName("deepseek-v4.1-flash");
        e.setPromptTokens(tokens - 100L);
        e.setCompletionTokens(100L);
        e.setTotalTokens(tokens);
        e.setUserId(userId);
        e.setIsSuccess(true);
        e.setOccurredAt(LocalDateTime.of(2026, 10, 30, 13, 31, 48));
        return e;
    }

    private ApiCallHistoryEntity history(final String traceId, final String apiKeyId,
                                         final String userId, final long tokens) {
        final ApiCallHistoryEntity e = new ApiCallHistoryEntity();
        e.setTraceId(traceId);
        e.setApiKeyId(apiKeyId);
        e.setRequestId(traceId + "-req");
        e.setRequestMethod("POST");
        e.setRequestPath("/v1/chat/completions");
        e.setServiceType("chat");
        e.setModelName("deepseek-v4.1-flash");
        e.setVirtualModel("test");
        e.setUserId(userId);
        e.setInstanceName("deepseek-v4.1-flash");
        e.setProvider("normal");
        e.setPromptTokens(tokens - 100L);
        e.setCompletionTokens(100L);
        e.setTotalTokens(tokens);
        e.setResponseTimeMs(4400L);
        e.setIsSuccess(true);
        e.setRateLimited(false);
        e.setCircuitBroken(false);
        e.setCreatedAt(LocalDateTime.of(2026, 10, 30, 13, 31, 48));
        return e;
    }

    /** 与 V15 迁移脚本一致的两条回填 SQL（用 MIN() 聚合保证标量子查询恰好一行） */
    private void runBackfillSql() {
        jdbcTemplate.update("UPDATE token_usage SET user_id = ("
                + "  SELECT MIN(h.user_id) FROM api_call_history h"
                + "   WHERE h.trace_id = token_usage.trace_id AND h.user_id IS NOT NULL) "
                + "WHERE user_id IS NULL AND EXISTS ("
                + "  SELECT 1 FROM api_call_history h"
                + "   WHERE h.trace_id = token_usage.trace_id AND h.user_id IS NOT NULL)");

        jdbcTemplate.update("UPDATE token_usage SET user_id = ("
                + "  SELECT MIN(h.user_id) FROM api_call_history h"
                + "   WHERE h.api_key_id = token_usage.api_key_id AND h.user_id IS NOT NULL) "
                + "WHERE user_id IS NULL AND api_key_id IS NOT NULL AND EXISTS ("
                + "  SELECT 1 FROM api_call_history h"
                + "   WHERE h.api_key_id = token_usage.api_key_id AND h.user_id IS NOT NULL)");
    }

    @Test
    @DisplayName("按 trace_id 回填存量用量行的 user_id")
    void backfillByTraceId() {
        // 存量用量行：userId 为 null（修复前落库的样子）
        tokenUsageRepository.saveAndFlush(usage("trace-1", "key-0088a3b7", null, 12830L));
        // 调用历史行：userId 已正确写入
        callHistoryRepository.saveAndFlush(history("trace-1", "key-0088a3b7", "admin", 12830L));

        System.out.println("DIAG history rows=" + jdbcTemplate.queryForList(
                "select trace_id, api_key_id, user_id from api_call_history"));
        System.out.println("DIAG usage rows=" + jdbcTemplate.queryForList(
                "select trace_id, api_key_id, user_id from token_usage"));

        runBackfillSql();

        // 回填是 JDBC 直接改的库，Hibernate 一级缓存里还是旧值——必须先清缓存再读实体
        entityManager.clear();

        final TokenUsageEntity filled = tokenUsageRepository.findAll().get(0);
        assertEquals("admin", filled.getUserId(), "应从 api_call_history 回填出持有人");
        assertEquals(12830L, filled.getTotalTokens());

        // 关键：回填后能被团队用量的聚合查到
        final List<Object[]> rows = tokenUsageRepository.aggregateByMonthUserVirtualModel(2026, 10);
        assertEquals(1, rows.size(), "回填后应出现在团队用量聚合里");
        assertEquals("admin", rows.get(0)[0]);
    }

    @Test
    @DisplayName("trace_id 对不上时按 api_key_id 兜底回填")
    void backfillFallbackByApiKeyId() {
        // trace_id 不同，但 api_key_id 相同 → 走兜底
        tokenUsageRepository.saveAndFlush(usage("trace-old", "key-0088a3b7", null, 5000L));
        callHistoryRepository.saveAndFlush(history("trace-new", "key-0088a3b7", "admin", 12830L));

        runBackfillSql();
        entityManager.clear();

        assertEquals("admin", tokenUsageRepository.findAll().get(0).getUserId());
        assertEquals(1, tokenUsageRepository.aggregateByMonthUserVirtualModel(2026, 10).size());
    }

    @Test
    @DisplayName("已有归属的行不被回填覆盖；无来源的行保持 null")
    void doesNotOverwriteExistingAndKeepsUnknown() {
        tokenUsageRepository.saveAndFlush(usage("trace-a", "key-1", "someone", 100L));
        tokenUsageRepository.saveAndFlush(usage("trace-b", "key-2", null, 200L));
        callHistoryRepository.saveAndFlush(history("trace-a", "key-1", "admin", 100L));

        runBackfillSql();
        entityManager.clear();

        final List<TokenUsageEntity> all = tokenUsageRepository.findAll();
        assertEquals("someone", all.get(0).getUserId(), "已有归属不被覆盖");
        assertEquals(null, all.get(1).getUserId(), "无来源的行保持 null，不凭空造数据");
    }
}
