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
 *       非事务调用不拦截、直接放行（SimpleJpaRepository 的读方法均有事务注解）。</li>
 *   <li>切面 order 与事务拦截器同为 LOWEST_PRECEDENCE；若装配顺序导致本切面
 *       先于事务开启执行，会话尚未绑定则跳过本次过滤（记 DEBUG），
 *       不会报错——严格 fail-closed 语义由 enterprise jar 替换本切面实现。</li>
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
     * <p>异常兜底：预埋层的任何故障只记 WARN 并放行原调用——
     * 过滤是「多租户隔离增强」，不应因它阻断请求。
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
            log.warn("tenant filter enable failed (request proceeds unfiltered): {}", e.getMessage());
        }
        return joinPoint.proceed();
    }
}
