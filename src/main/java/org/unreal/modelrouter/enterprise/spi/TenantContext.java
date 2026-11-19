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

    /**
     * 在当前线程显式绑定租户（多租户业务链路补绑用）。
     *
     * <p><b>为什么需要</b>：企业侧 {@code TenantContextWebFilter} 在 Security 链下游执行，
     * 而 API Key 认证可能尚未完成，过滤器读不到 {@code ApiKeyAuthentication}，只能回落
     * {@code default}。认证成功后本方法把 Key 归属租户补绑到当前线程，使本条链路上
     * 所有后续读取（配额账本维度、调用历史/用量打标、JPA 行级过滤器）都得到正确租户。</p>
     *
     * <p>社区版（{@code SingleTenantContext}）默认 no-op：单租户恒 {@code default}，
     * 调用方传任何值都不应改变行为，故不覆盖即可。</p>
     *
     * @param tenantId 待绑定的租户 ID；null/空串时实现可选择忽略
     */
    default void resolve(final String tenantId) {
        // 单租户实现默认忽略
    }
}
