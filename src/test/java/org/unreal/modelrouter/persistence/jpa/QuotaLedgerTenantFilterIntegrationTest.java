/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.unreal.modelrouter.enterprise.config.TenantJpaFilterDefinition;
import org.unreal.modelrouter.persistence.jpa.entity.QuotaLedgerEntity;
import org.unreal.modelrouter.persistence.jpa.repository.QuotaLedgerRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O9 验收：{@code QuotaLedgerEntity} 挂上 {@code tenantFilter} 后，
 * 两租户账本行互不可见（行级隔离在真实 H2 上生效验证）。
 *
 * <p>社区版默认过滤器不启用（行为零变化）；本测试显式
 * {@code enableFilter("tenantFilter")} 模拟商业版多租户会话。</p>
 *
 * @author Trunkline Team
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("O9: quota_ledger 行级租户隔离")
class QuotaLedgerTenantFilterIntegrationTest {

    @Autowired
    private QuotaLedgerRepository quotaLedgerRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private final LocalDateTime windowStart = LocalDateTime.of(2026, 11, 1, 0, 0);

    private QuotaLedgerEntity ledger(final String tenantId, final String userId) {
        return QuotaLedgerEntity.builder()
                .tenantId(tenantId)
                .apiKeyId("key-1")
                .userId(userId)
                .serviceType("chat")
                .model("gpt-4")
                .groupId("")
                .windowType("DAY")
                .windowStart(windowStart)
                .requestCount(1L)
                .tokenCount(10L)
                .build();
    }

    @BeforeEach
    void seedTwoTenants() {
        quotaLedgerRepository.save(ledger("tenant-a", "alice"));
        quotaLedgerRepository.save(ledger("tenant-b", "bob"));
    }

    @AfterEach
    void disableFilter() {
        entityManager.unwrap(Session.class).disableFilter(TenantJpaFilterDefinition.NAME);
    }

    @Test
    @DisplayName("过滤器未启用（社区版默认）：两租户行全可见，行为零变化")
    void filterDisabledSeesEverything() {
        final List<QuotaLedgerEntity> all = quotaLedgerRepository.findAll();
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("tenant-a 会话：只见 alice 的账本行")
    void tenantASeesOnlyItsRows() {
        final Session session = entityManager.unwrap(Session.class);
        session.enableFilter(TenantJpaFilterDefinition.NAME)
                .setParameter(TenantJpaFilterDefinition.PARAM_TENANT_ID, "tenant-a");

        final List<QuotaLedgerEntity> visible = quotaLedgerRepository.findAll();
        assertEquals(1, visible.size());
        assertEquals("tenant-a", visible.get(0).getTenantId());
        assertEquals("alice", visible.get(0).getUserId());
    }

    @Test
    @DisplayName("tenant-b 会话：只见 bob 的账本行（互不可见）")
    void tenantBSeesOnlyItsRows() {
        final Session session = entityManager.unwrap(Session.class);
        session.enableFilter(TenantJpaFilterDefinition.NAME)
                .setParameter(TenantJpaFilterDefinition.PARAM_TENANT_ID, "tenant-b");

        final List<QuotaLedgerEntity> visible = quotaLedgerRepository.findAll();
        assertEquals(1, visible.size());
        assertEquals("tenant-b", visible.get(0).getTenantId());
        assertTrue(visible.stream().noneMatch(e -> "alice".equals(e.getUserId())));
    }
}
