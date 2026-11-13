/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.unreal.modelrouter.enterprise.spi.TenantContext;

import jakarta.persistence.EntityManagerFactory;

/**
 * 租户行级过滤器启用器（S2 任务 36 预埋，默认关闭）。
 *
 * <p>当 {@code trunkline.tenant.filter-enabled=true}（商业版多租户解锁）时装配本组件，
 * 在 Repository 方法执行前，对当前事务绑定的 Hibernate 会话开启
 * {@code tenantFilter}（定义见 persistence.jpa.entity 包级 {@code @FilterDef}），
 * 使挂接实体（带 {@code @Filter} 的实体）的查询自动追加
 * {@code tenant_id = :tenantId} 条件。
 *
 * <p><b>社区版（默认）</b>：开关为 false，本组件不装配，任何查询行为零变化。
 *
 * <p>实现约束：
 * <ul>
 *   <li>取的是 {@link TransactionSynchronizationManager} 上绑定在
 *       EntityManagerFactory 的当前事务会话（与 Repository 实际使用一致）；
 *       非事务调用不拦截、直接放行（SimpleJpaRepository 的读方法均有事务注解）。
 *       <b>O8-3 定序后</b>（{@link TenantAspectOrderingConfiguration} 把事务拦截器排到
 *       本切面外侧），本切面总是运行在事务之内，"切面先于事务"分支只是防御性兜底。</li>
 *   <li>切面 order 与事务拦截器同为 LOWEST_PRECEDENCE 时顺序未定义——
 *       已由 {@link TenantAspectOrderingConfiguration} 显式定序（事务外层、本切面内层）。</li>
 *   <li>enableFilter 对同名过滤器重复调用只更新参数，幂等无副作用。</li>
 * </ul>
 * @author Trunkline Team
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = TenantJpaFilterDefinition.ENABLED_PROPERTY, havingValue = "true")
public class TenantJpaFilter {

    private final TenantContext tenantContext;

    private final EntityManagerFactory entityManagerFactory;

    public TenantJpaFilter(final TenantContext tenantContext, final EntityManagerFactory entityManagerFactory) {
        this.tenantContext = tenantContext;
        this.entityManagerFactory = entityManagerFactory;
    }

    /**
     * Repository 方法执行前开启租户过滤。
     *
     * <p>异常兜底（2026-11-13 更新，fail-closed）：过滤器开启却未能生效 =
     * 行级隔离被绕过，必须中止查询而非放行（多租户模式 {@code filter-enabled=true}
     * 下隔离正确性优先于可用性）；未开启多租户时本切面根本不装配，社区版零影响。
     */
    @Around("execution(* org.unreal.modelrouter.persistence.jpa.repository..*(..))")
    public Object applyTenantFilter(final ProceedingJoinPoint joinPoint) throws Throwable {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return joinPoint.proceed();
        }
        try {
            if (TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
                final Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
                if (resource instanceof final EntityManagerHolder holder) {
                    final org.hibernate.Session session =
                        holder.getEntityManager().unwrap(org.hibernate.Session.class);
                    final org.hibernate.Filter filter = session.enableFilter(TenantJpaFilterDefinition.NAME);
                    filter.setParameter(TenantJpaFilterDefinition.PARAM_TENANT_ID,
                        tenantContext.currentTenantId());
                }
            } else {
                log.debug("tenant filter skipped: no session bound yet (aspect ran before transaction)");
            }
        } catch (final RuntimeException e) {
            // 多租户模式下，过滤器开启却未能生效 = 行级隔离被绕过，必须中止查询而非放行。
            throw new IllegalStateException(
                    "tenant filter could not be enabled; refusing to run unfiltered query "
                            + "(tenant isolation would be bypassed): " + e.getMessage(), e);
        }
        return joinPoint.proceed();
    }
}
