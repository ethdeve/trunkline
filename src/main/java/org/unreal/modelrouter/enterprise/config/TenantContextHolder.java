package org.unreal.modelrouter.enterprise.config;

/**
 * 租户静态持有器（S2 任务 36 预埋）。
 *
 * <p>为 Redis key（{@code trunkline:{tenantId}:{domain}:}）与
 * Hibernate tenant filter 提供统一取值入口：
 * <ul>
 *   <li>Spring 环境优先取容器内 {@link org.unreal.modelrouter.enterprise.spi.TenantContext}</li>
 *   <li>非 Spring 上下文（单测 / 启动早期）回落 {@code default}</li>
 * </ul>
 *
 * <p>商业版通过 enterprise jar 注入多租户 TenantContext Bean 后，
 * 此处自动跟随，无需改动任何调用方。
 */
public final class TenantContextHolder {

    private static volatile org.unreal.modelrouter.enterprise.spi.TenantContext delegate;

    private TenantContextHolder() {
    }

    /** 由 AutoConfiguration 在装配时调用；业务代码不要调用 */
    public static void setDelegate(final org.unreal.modelrouter.enterprise.spi.TenantContext tenantContext) {
        delegate = tenantContext;
    }

    /** 当前租户 ID；无 Spring 上下文时返回 {@code default} */
    public static String currentTenantId() {
        final org.unreal.modelrouter.enterprise.spi.TenantContext ctx = delegate;
        return ctx != null ? ctx.currentTenantId()
            : org.unreal.modelrouter.enterprise.noop.SingleTenantContext.DEFAULT_TENANT_ID;
    }

    /** 是否多租户模式 */
    public static boolean isMultiTenant() {
        final org.unreal.modelrouter.enterprise.spi.TenantContext ctx = delegate;
        return ctx != null && ctx.isMultiTenant();
    }
}
