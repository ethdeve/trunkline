/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.persistence.jpa;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.unreal.modelrouter.enterprise.config.TenantJpaFilterDefinition;
import org.unreal.modelrouter.persistence.jpa.entity.QuotaLedgerEntity;
import org.unreal.modelrouter.persistence.jpa.repository.QuotaLedgerRepository;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸门⑦ 可运行实证：Hibernate {@code @Filter} <b>只</b>作用于 JPA/Hibernate 查询路径，
 * 对 {@code JdbcTemplate} 裸 SQL <b>完全无效</b>。
 *
 * <p>这不是一个"证明系统没问题"的测试，而是一个<b>把盲区摆到台面上</b>的测试：
 * 在同一个事务、同一份数据、同一个已启用 {@code tenantFilter} 的会话里，
 * <ul>
 *   <li>{@code QuotaLedgerRepository.findAll()}（JPQL）只看见当前租户 1 行；</li>
 *   <li>{@code SELECT COUNT(*) FROM quota_ledger}（裸 JDBC）看见两个租户共 2 行；</li>
 *   <li>{@code ... WHERE tenant_id = ?}（手写租户谓词）才重新只看见 1 行。</li>
 * </ul>
 * 三者并排即为「为什么必须有源码级守卫」的证据：裸 SQL 没有任何框架兜底，
 * 唯一防线是显式 {@code tenant_id} 条件 + {@link org.unreal.modelrouter.persistence.jpa.entity.TenantNativeQueryGuardTest}
 * 与企业仓 {@code TenantAwareJdbcSqlPolicyTest} 的静态拦截。</p>
 *
 * <p>私有化单客户部署下 {@code tenant_id} 恒为 {@code default}，盲区不产生实际串扰；
 * 但本测试保证「真正启用多租户前」盲区是可观测、可回归的，而不是靠口头约定。</p>
 *
 * @author Trunkline Team
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("闸门⑦: native SQL 盲区可运行实证")
class NativeSqlTenantIsolationProofTest {

    private static final String TABLE = "quota_ledger";
    private static final LocalDateTime WINDOW = LocalDateTime.of(2026, 11, 1, 0, 0);

    @Autowired
    private QuotaLedgerRepository quotaLedgerRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    /** 刻意用原生 JdbcTemplate（而非 JPA）读同一张表，验证 @Filter 是否生效。 */
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedTwoTenants() {
        jdbc = new JdbcTemplate(dataSource);
        // IDENTITY 主键策略下 saveAndFlush 会立即 INSERT，裸 JDBC 在同一事务/连接上可见
        quotaLedgerRepository.saveAndFlush(ledger("tenant-a", "alice"));
        quotaLedgerRepository.saveAndFlush(ledger("tenant-b", "bob"));
    }

    @AfterEach
    void disableFilter() {
        entityManager.unwrap(Session.class).disableFilter(TenantJpaFilterDefinition.NAME);
    }

    @Test
    @DisplayName("实证：同一会话下 @Filter 拦住 JPA，却拦不住裸 JDBC")
    void filterConstrainsJpaButNotRawJdbc() {
        enableFilter("tenant-a");

        // 1) JPA / JPQL 路径：受 @Filter 约束
        final List<QuotaLedgerEntity> visible = quotaLedgerRepository.findAll();
        assertEquals(1, visible.size(), "JPA 路径必须受 @Filter 约束");
        assertEquals("tenant-a", visible.get(0).getTenantId());

        // 2) 裸 JDBC 路径：@Filter 完全不生效 → 两个租户的行都会漏出来（盲区实证）
        assertEquals(2, countAll(), "裸 SQL 不带 tenant_id 时看见全部租户，这就是盲区");
        assertEquals(List.of("alice", "bob"),
                jdbc.queryForList("SELECT user_id FROM " + TABLE + " ORDER BY user_id", String.class),
                "裸 SQL 确实读到了别的租户的数据行，不是只有计数差异");

        // 3) 手写 tenant_id 谓词后恢复隔离
        assertEquals(1, countFor("tenant-a"), "tenant-aware 裸 SQL 只应看见本租户");
        assertEquals(1, countFor("tenant-b"), "另一租户同样只看见自己");
    }

    @Test
    @DisplayName("实证：切换租户上下文后，手写 tenant_id 的裸 SQL 仍零串扰")
    void explicitTenantPredicateNeverLeaksAcrossContextSwitches() {
        enableFilter("tenant-b");

        assertEquals(1, quotaLedgerRepository.findAll().size());
        assertEquals(List.of("bob"), jdbc.queryForList(
                "SELECT user_id FROM " + TABLE + " WHERE tenant_id = ?", String.class, "tenant-b"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE tenant_id = ? AND user_id = ?",
                Integer.class, "tenant-b", "alice"),
                "tenant-b 下不得读到 tenant-a 的 alice");

        enableFilter("tenant-a");
        assertEquals(List.of("alice"), jdbc.queryForList(
                "SELECT user_id FROM " + TABLE + " WHERE tenant_id = ?", String.class, "tenant-a"));
        assertTrue(quotaLedgerRepository.findAll().stream()
                .noneMatch(e -> "bob".equals(e.getUserId())));
    }

    @Test
    @DisplayName("对照：社区版默认不启用过滤器时，两租户行全可见（行为零变化）")
    void filterDisabledSeesEverything() {
        assertEquals(2, quotaLedgerRepository.findAll().size());
        assertEquals(2, countAll());
    }

    private void enableFilter(final String tenantId) {
        entityManager.unwrap(Session.class)
                .enableFilter(TenantJpaFilterDefinition.NAME)
                .setParameter(TenantJpaFilterDefinition.PARAM_TENANT_ID, tenantId);
    }

    private int countAll() {
        final Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE, Integer.class);
        return n == null ? 0 : n;
    }

    private int countFor(final String tenantId) {
        final Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE tenant_id = ?",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    private static QuotaLedgerEntity ledger(final String tenantId, final String userId) {
        return QuotaLedgerEntity.builder()
                .tenantId(tenantId)
                .apiKeyId("key-1")
                .userId(userId)
                .serviceType("chat")
                .model("gpt-4")
                .groupId("")
                .windowType("DAY")
                .windowStart(WINDOW)
                .requestCount(1L)
                .tokenCount(10L)
                .build();
    }
}
