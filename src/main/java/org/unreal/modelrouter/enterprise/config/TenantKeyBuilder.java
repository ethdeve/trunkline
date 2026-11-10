/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import org.unreal.modelrouter.enterprise.noop.SingleTenantContext;

/**
 * 租户感知的 Redis key 构建器（S2 任务 37 预埋）。
 *
 * <p><b>命名空间约定</b>：
 * <pre>
 *   社区版（默认）：  trunkline:{domain}:...            （现状，零迁移）
 *   商业版多租户：    trunkline:{tenantId}:{domain}:... （非 default 租户）
 *   default 租户：    trunkline:{domain}:...            （与社区版同义，缺省段即 default）
 * </pre>
 *
 * <p>即 {@code trunkline:{domain}:} ≡ {@code trunkline:default:{domain}:}：
 * default 租户的 key <b>不带</b>租户段——社区版升级商业版时存量数据无需任何
 * Redis 迁移；只有新增租户才产生带段 key。商业版读取带段 key 未命中时，
 * 可按需回落无段 key（双读），由 enterprise jar 实现严格隔离语义。
 *
 * <p>社区版调用方现状无需改动：{@link #isMultiTenant()} 为 false 时
 * 输出与既有 key 完全一致。
 * @author Trunkline Team
 */
public final class TenantKeyBuilder {

    /** 网关 Redis 命名空间根（与既有 trunkline:quota 等 key 保持兼容） */
    private static final String NAMESPACE = "trunkline";

    private TenantKeyBuilder() {
    }

    /**
     * 构建租户感知的 Redis key。
     *
     * @param domain 领域段（如 {@code quota}、{@code config}、{@code cache}）
     * @param rest   领域内剩余 key 片段（可为空串；非空时自动补 {@code :}）
     * @return 社区版 / default 租户：{@code trunkline:{domain}[:{rest}]}；
     *         多租户：{@code trunkline:{tenantId}:{domain}[:{rest}]}
     */
    public static String domainKey(final String domain, final String rest) {
        final String suffix = (rest == null || rest.isEmpty()) ? "" : ":" + rest;
        if (!TenantContextHolder.isMultiTenant()
            || SingleTenantContext.DEFAULT_TENANT_ID.equals(TenantContextHolder.currentTenantId())) {
            return NAMESPACE + ":" + domain + suffix;
        }
        return NAMESPACE + ":" + TenantContextHolder.currentTenantId() + ":" + domain + suffix;
    }
}
