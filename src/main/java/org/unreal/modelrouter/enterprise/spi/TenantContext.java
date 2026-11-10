/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

/**
 * 租户上下文扩展点（商业版多租户入口，S2 任务 32）。
 *
 * <p>社区版由 {@code SingleTenantContext} 提供，永远返回 {@code default}；
 * 商业版由 {@code trunkline-enterprise.jar} 提供基于 JWT claim / 请求头的动态实现。
 *
 * <p>约定：租户 ID 只允许小写字母、数字、连字符（{@code [a-z0-9-]}），
 * 因为它会被拼进 Redis key（{@code trunkline:{tenantId}:{domain}:}）。
 * @author Trunkline Team
 */
public interface TenantContext {

    /** 当前请求归属的租户 ID；社区版恒为 {@code default} */
    String getTenantId();

    /** 是否多租户模式（用于控制管理界面是否展示租户切换等）；社区版恒为 false */
    default boolean isMultiTenant() {
        return false;
    }

    /** 不做线程上下文传播的静态取值（调度线程 / 定时任务用） */
    default String currentTenantId() {
        return getTenantId();
    }
}
