/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户切面与事务拦截器排序前置确认（O8-3）。
 *
 * <p>背景：{@code TenantJpaFilter} 异常路径已改 throw（fail-closed），若租户切面
 * 先于事务开启执行（会话未绑定 + enableFilter 失败）会演变成全站 Repository 调用失败。
 * {@link TenantAspectOrderingConfiguration} 用
 * {@code @EnableTransactionManagement(order = LOWEST_PRECEDENCE - 10)} 把事务拦截器
 * 钉到切面外侧。本测试锁定该声明不被静默改动。</p>
 *
 * <p>判定规则：事务 order 必须<b>严格小于</b>切面 order（LOWEST_PRECEDENCE）——
 * 值小者在外层（先进后出），保证切面恒运行在事务之内。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("租户切面/事务拦截器确定性排序")
class TenantAspectOrderingConfigurationTest {

    /** TenantJpaFilter 上标注的切面 order（与其保持同步断言） */
    private static final int TENANT_ASPECT_ORDER = Ordered.LOWEST_PRECEDENCE;

    @Test
    @DisplayName("事务拦截器 order 严格小于切面 order（事务在外层先开启）")
    void transactionInterceptorWrapsTenantAspect() {
        final EnableTransactionManagement annotation = TenantAspectOrderingConfiguration.class
                .getAnnotation(EnableTransactionManagement.class);
        assertTrue(annotation != null,
                "TenantAspectOrderingConfiguration 必须显式声明 @EnableTransactionManagement"
                        + "（否则回退 Boot 自动配置的无序注册，切面与事务顺序再次未定义）");
        assertTrue(annotation.order() < TENANT_ASPECT_ORDER,
                "事务 order=" + annotation.order() + " 必须小于切面 order=" + TENANT_ASPECT_ORDER
                        + "（值小者在外层，切面恒在事务之内）");
        assertEquals(Ordered.LOWEST_PRECEDENCE - 10, annotation.order(),
                "排序值为评审定案值，改动须同步更新本断言与 TenantJpaFilter 注释");
    }

    @Test
    @DisplayName("TenantJpaFilter 切面 order 保持 LOWEST_PRECEDENCE（与排序配置的假设一致）")
    void tenantAspectOrderAssumptionHolds() {
        // 切面 order 由 TenantJpaFilter 自身 @Order 声明，此处直接反射校验
        final org.springframework.core.annotation.Order orderAnnotation =
                TenantJpaFilter.class.getAnnotation(org.springframework.core.annotation.Order.class);
        assertTrue(orderAnnotation != null, "TenantJpaFilter 必须显式声明 @Order");
        assertEquals(TENANT_ASPECT_ORDER, orderAnnotation.value(),
                "切面 order 若改动，事务 order（LOWEST_PRECEDENCE - 10）必须同步重排");
    }
}
