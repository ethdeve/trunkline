/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa.entity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「有意全局共享表」：<b>不做</b> {@code @Filter(name = "tenantFilter")} 行级隔离的实体。
 *
 * <p>用于让多租户数据层的覆盖情况可验证、可审计。两类实体可以标注：
 * <ul>
 *   <li>含 {@code tenantId} 字段但有意跨租户可见的字典 / 模板数据；</li>
 *   <li>完全不含租户字段的<b>全局配置与运行时状态</b>（如厂商模型额度、全局熔断配置、
 *       实例级熔断/限速状态、错误跟踪聚合）。这类表按业务语义本就不归属某个租户，
 *       强行加 {@code tenant_id} 会破坏全局唯一键与共享语义。</li>
 * </ul>
 *
 * <p><b>启动期约束</b>：{@code trunkline.tenant.filter-enabled=true} 时，
 * {@code TenantIsolationStartupGuard} 会反射扫描 {@code persistence.jpa.entity} 包，
 * 任何 {@code @Entity} 若既未挂 {@code @Filter} 也未标本注解 → <b>fail-fast 拒绝启动</b>，
 * 杜绝「打开多租户开关却静默漏掉一部分表」的假隔离。已标注本注解的实体会在启动日志中
 * 以 WARN 清单形式列出，供部署方确认业务可接受；社区版（开关默认 false）本守卫不装配，
 * 行为零变化。
 *
 * <p>配套审计见 {@code TenantIsolationAuditTest}（全实体覆盖断言）与
 * 《Trunkline-多租户与多实例架构体检报告》的「有意全局共享」登记名单。
 *
 * @author Trunkline Team
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TenantShared {

    /** 有意共享的原因说明（必填语义，供审计名单引用） */
    String reason() default "";
}
