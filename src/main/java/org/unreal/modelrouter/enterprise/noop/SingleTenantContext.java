/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.TenantContext;

/**
 * 单租户默认实现（社区版，S2 任务 33）。
 *
 * <p>社区版 = 商业版在 {@code maxTenants=1} 时的特例：
 * 所有租户相关读写都落到 {@code default} 租户。
 * @author Trunkline Team
 */
public class SingleTenantContext implements TenantContext {

    /** 社区版固定租户 ID，与 Flyway 预埋列的 DEFAULT 值一致 */
    public static final String DEFAULT_TENANT_ID = "default";

    private static final SingleTenantContext INSTANCE = new SingleTenantContext();

    public static SingleTenantContext getInstance() {
        return INSTANCE;
    }

    @Override
    public String getTenantId() {
        return DEFAULT_TENANT_ID;
    }

    @Override
    public boolean isMultiTenant() {
        return false;
    }
}
