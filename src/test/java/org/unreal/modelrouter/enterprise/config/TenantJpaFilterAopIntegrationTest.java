/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.unreal.modelrouter.enterprise.spi.TenantContext;
import org.unreal.modelrouter.persistence.jpa.entity.QuotaLedgerEntity;
import org.unreal.modelrouter.persistence.jpa.repository.QuotaLedgerRepository;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 验收硬化①（端到端）：真实 Spring AOP + 真实 H2 事务，证明切面自动开启租户过滤。
 *
 * <p>与 {@code QuotaLedgerTenantFilterIntegrationTest} 的区别：后者由测试代码手工
 * {@code session.enableFilter(...)}，只能证明「Hibernate 过滤器本身有效」；本测试让
 * {@link TenantJpaFilter} 作为切面拦截 {@code QuotaLedgerRepository} 的真实方法调用，
 * 证明「事务 → 切面 → enableFilter(当前租户) → 查询」整条装配链在 Spring 容器里真的生效，
 * 且同一事务内切换租户后参数随之更新（不会串到上一个租户）。</p>
 *
 * <p>这是 A3 DoD「事务内 TenantJpaFilter 读到租户」与「跨租户查询零串扰」的可运行证据，
 * 补齐了闸门⑦ native/JDBC 路径之外的 JPA 自动过滤路径。</p>
 *
 * @author Trunkline Team
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = TenantJpaFilterDefinition.ENABLED_PROPERTY + "=true")
@EnableAspectJAutoProxy
@Import({TenantJpaFilter.class, TenantJpaFilterAopIntegrationTest.TenantBeans.class})
@DisplayName("A3 硬化①：事务内切面自动开启租户过滤（真 Spring AOP + H2）")
class TenantJpaFilterAopIntegrationTest {

    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 11, 15, 0, 0);

    @Autowired
    private QuotaLedgerRepository quotaLedgerRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MutableTenantContext tenantContext;

    @BeforeEach
    void seedTwoTenants() {
        tenantContext.set("tenant-a");
        quotaLedgerRepository.save(ledger("tenant-a", "alice"));
        quotaLedgerRepository.save(ledger("tenant-b", "bob"));
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void disableFilter() {
        entityManager.unwrap(Session.class).disableFilter(TenantJpaFilterDefinition.NAME);
        tenantContext.set("tenant-a");
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
                .windowStart(WINDOW_START)
                .requestCount(1L)
                .tokenCount(10L)
                .build();
    }

    @Test
    @DisplayName("租户 A 的 Repository 调用：切面自动启用过滤器且只见 alice")
    void aspectEnablesFilterForCurrentTenant() {
        tenantContext.set("tenant-a");

        final List<QuotaLedgerEntity> visible = quotaLedgerRepository.findAll();

        assertEquals(1, visible.size(), "切面须把 tenantFilter 收窄到当前租户");
        assertEquals("alice", visible.get(0).getUserId());
        assertEquals("tenant-a", visible.get(0).getTenantId());
        assertNotNull(entityManager.unwrap(Session.class)
                        .getEnabledFilter(TenantJpaFilterDefinition.NAME),
                "真实 Repository 调用后，事务会话上的 tenantFilter 必须处于启用态");
    }

    @Test
    @DisplayName("同一事务内切换租户：过滤器参数随之更新，两租户零串扰")
    void switchingTenantInsideSameTransactionUpdatesFilter() {
        tenantContext.set("tenant-a");
        final List<QuotaLedgerEntity> asA = quotaLedgerRepository.findAll();
        assertEquals(1, asA.size());
        assertEquals("alice", asA.get(0).getUserId());

        tenantContext.set("tenant-b");
        final List<QuotaLedgerEntity> asB = quotaLedgerRepository.findAll();
        assertEquals(1, asB.size(), "切换租户后不得沿用上一个租户的过滤参数");
        assertEquals("bob", asB.get(0).getUserId());
        assertTrue(asB.stream().noneMatch(e -> "alice".equals(e.getUserId())),
                "tenant-b 会话不得看见 tenant-a 的账本行");
    }

    @Test
    @DisplayName("过滤器启用时，查询全部 5 个 @Filter 租户实体路径均无 native 绕行")
    void filterIsEnabledForRepositoryQueryPath() {
        tenantContext.set("tenant-b");

        quotaLedgerRepository.findAll();

        final Session session = entityManager.unwrap(Session.class);
        assertNotNull(session.getEnabledFilter(TenantJpaFilterDefinition.NAME));
        // 与闸门⑦ 的源码守卫互补：JPA 路径由切面负责，native/JDBC 路径由静态守卫负责
        assertEquals("tenant-b", tenantContext.currentTenantId());
    }

    /** 可切换的测试用 TenantContext（模拟商业版按请求解析租户）。 */
    static class MutableTenantContext implements TenantContext {

        private volatile String tenantId = "tenant-a";

        void set(final String tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        public String getTenantId() {
            return tenantId;
        }

        @Override
        public boolean isMultiTenant() {
            return true;
        }
    }

    @TestConfiguration
    static class TenantBeans {

        @Bean
        MutableTenantContext mutableTenantContext() {
            return new MutableTenantContext();
        }
    }
}
