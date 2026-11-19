/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import java.util.Locale;
import java.util.regex.Pattern;

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
 * @author Trunkline Team
 */
public final class TenantContextHolder {

    /** 租户 ID 约定：小写字母、数字、连字符，长度 1~63（与 Redis key 拼接口径一致） */
    public static final Pattern TENANT_ID_PATTERN = Pattern.compile("[a-z0-9-]{1,63}");

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

    /**
     * 归一化租户 ID：小写 + 去首尾空白；{@code null}/空串等价单租户 {@code default}。
     *
     * <p>供写入侧（如 API Key 归属）统一口径——存量数据可能没有该字段，读取时按 default 处理。</p>
     */
    public static String normalize(final String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return org.unreal.modelrouter.enterprise.noop.SingleTenantContext.DEFAULT_TENANT_ID;
        }
        return tenantId.trim().toLowerCase(Locale.ROOT);
    }

    /** 校验租户 ID 格式是否合法（小写字母 / 数字 / 连字符，1~63 位） */
    public static boolean isValidTenantId(final String tenantId) {
        return tenantId != null && TENANT_ID_PATTERN.matcher(tenantId).matches();
    }
}
