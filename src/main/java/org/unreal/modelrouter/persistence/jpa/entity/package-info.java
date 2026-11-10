/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

/**
 * JPA 实体定义包。
 *
 * <p>包级定义多租户行级过滤器（S2 任务 36 预埋）：
 * 各业务实体通过 {@code @Filter(name = "tenantFilter")} 挂接本定义。
 * 过滤器<b>默认不启用</b>——只有配置
 * {@code trunkline.tenant.filter-enabled=true}（商业版多租户解锁）时，
 * {@link org.unreal.modelrouter.enterprise.config.TenantJpaFilter} 才会在
 * 事务会话上调用 {@code enableFilter("tenantFilter")}，此时所有挂接实体的查询
 * 自动追加 {@code tenant_id = :tenantId} 条件。社区版（默认关闭）行为零变化。
 * @author Trunkline Team
 */
@FilterDef(
    name = TenantJpaFilterDefinition.NAME,
    parameters = @ParamDef(name = "tenantId", type = String.class),
    defaultCondition = "tenant_id = :tenantId"
)
package org.unreal.modelrouter.persistence.jpa.entity;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.unreal.modelrouter.enterprise.config.TenantJpaFilterDefinition;
