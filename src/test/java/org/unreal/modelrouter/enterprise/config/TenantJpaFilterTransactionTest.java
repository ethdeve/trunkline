/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.unreal.modelrouter.enterprise.spi.TenantContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A3 验收硬化①：把 {@link TenantJpaFilter} 在事务内的行为用断言钉死。
 *
 * <p>《企业版待开发功能清单》A3 的第 2 层要求「事务内 {@code TenantJpaFilter} 读到租户」。
 * 既有 {@code TenantAspectOrderingConfigurationTest} 只校验注解上的 order 声明，
 * {@code QuotaLedgerTenantFilterIntegrationTest} 则是手工 {@code enableFilter}，
 * 两者都没有证明<b>切面本身</b>在真实事务绑定下把当前租户写进 Hibernate 过滤器参数。</p>
 *
 * <p>本测试用真实的 {@link TransactionSynchronizationManager}（不是 mock）构造事务状态与
 * {@link EntityManagerHolder} 资源绑定，逐分支锁定：
 * <ol>
 *   <li>事务内 + 会话已绑定 → enableFilter + setParameter(当前租户) + proceed；</li>
 *   <li>非事务调用 → 不碰过滤器，直接放行（社区版零影响）；</li>
 *   <li>事务内但会话尚未绑定（防御性兜底）→ 不打异常、直接放行；</li>
 *   <li>enableFilter 抛异常 → fail-closed 抛 {@link IllegalStateException} 且<b>不执行</b>查询；</li>
 *   <li>切面由 {@code trunkline.tenant.filter-enabled=true} 门控，社区版默认不装配。</li>
 * </ol>
 *
 * @author Trunkline Team
 */
@DisplayName("A3 硬化①：事务内 TenantJpaFilter 行为")
class TenantJpaFilterTransactionTest {

    private final EntityManagerFactory emf = mock(EntityManagerFactory.class);
    private final EntityManagerHolder holder = mock(EntityManagerHolder.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Session session = mock(Session.class);
    private final Filter hibernateFilter = mock(Filter.class);
    private final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final TenantJpaFilter aspect = new TenantJpaFilter(tenantContext, emf);

    @AfterEach
    void clearTransactionState() {
        // 静态事务状态必须复位，否则会污染同一 JVM 内后续测试
        if (TransactionSynchronizationManager.hasResource(emf)) {
            TransactionSynchronizationManager.unbindResource(emf);
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.clear();
    }

    private void bindActiveSession() {
        when(holder.getEntityManager()).thenReturn(entityManager);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.enableFilter(TenantJpaFilterDefinition.NAME)).thenReturn(hibernateFilter);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(emf, holder);
    }

    @Test
    @DisplayName("事务内：切面读到当前租户并写入 Hibernate 过滤器参数")
    void insideTransactionEnablesFilterWithCurrentTenant() throws Throwable {
        when(tenantContext.currentTenantId()).thenReturn("acme-corp");
        when(joinPoint.proceed()).thenReturn("ok");
        bindActiveSession();

        assertEquals("ok", aspect.applyTenantFilter(joinPoint));

        verify(session, times(1)).enableFilter(TenantJpaFilterDefinition.NAME);
        verify(hibernateFilter, times(1))
                .setParameter(TenantJpaFilterDefinition.PARAM_TENANT_ID, "acme-corp");
        verify(joinPoint, times(1)).proceed();
    }

    @Test
    @DisplayName("非事务调用：不触碰过滤器，直接放行（社区版行为零变化）")
    void outsideTransactionSkipsFilter() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        // 刻意不设置 actualTransactionActive / 不绑定会话

        assertEquals("ok", aspect.applyTenantFilter(joinPoint));

        verify(session, never()).enableFilter(anyString());
        verifyNoInteractions(hibernateFilter);
        verify(joinPoint, times(1)).proceed();
    }

    @Test
    @DisplayName("事务内但会话未绑定：走防御性兜底，放行且不抛")
    void transactionWithoutBoundSessionStillProceeds() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        // 刻意不 bindResource

        assertEquals("ok", aspect.applyTenantFilter(joinPoint));

        verify(session, never()).enableFilter(anyString());
        verify(joinPoint, times(1)).proceed();
    }

    @Test
    @DisplayName("enableFilter 失败：fail-closed 拒绝未过滤查询，绝不 proceed")
    void filterFailureRefusesUnfilteredQuery() throws Throwable {
        when(tenantContext.currentTenantId()).thenReturn("acme-corp");
        when(holder.getEntityManager()).thenReturn(entityManager);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.enableFilter(TenantJpaFilterDefinition.NAME))
                .thenThrow(new IllegalStateException("session already closed"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(emf, holder);

        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.applyTenantFilter(joinPoint));

        assertTrue(ex.getMessage().contains("refusing to run unfiltered query"),
                "异常须明确表达「拒绝未过滤查询」语义: " + ex.getMessage());
        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("切面由 filter-enabled=true 门控，社区版默认不装配")
    void aspectIsGatedBehindFeatureSwitch() {
        final ConditionalOnProperty annotation =
                TenantJpaFilter.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(annotation, "TenantJpaFilter 必须带 @ConditionalOnProperty");
        assertEquals(TenantJpaFilterDefinition.ENABLED_PROPERTY, annotation.name()[0]);
        assertEquals("true", annotation.havingValue(),
                "havingValue 必须为 true（缺省 false 时切面不装配，社区版零影响）");
    }
}
