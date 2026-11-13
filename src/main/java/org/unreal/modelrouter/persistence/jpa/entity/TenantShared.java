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
 * 标记「全局共享表」：含租户语义字段，但<b>有意</b>不挂
 * {@code @Filter(name = "tenantFilter")} 行级隔离的实体（字典、模板等跨租户共享数据）。
 *
 * <p>仅作文档与审计用途，不影响运行时行为。配套审计见
 * {@code TenantIsolationAuditTest}：反射扫描 {@code persistence.jpa.entity} 包，
 * 任何含 {@code tenantId} 字段的实体若既未挂 {@code @Filter} 也未标 {@code @TenantShared}，
 * 测试失败——防止新实体静默漏挂行级隔离。</p>
 *
 * <p>标了本注解的实体必须登记进 A3 验收的「有意不隔离」名单（见
 * 《商业版骨架改造方案》A3），说明为何该表可以跨租户可见。</p>
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
