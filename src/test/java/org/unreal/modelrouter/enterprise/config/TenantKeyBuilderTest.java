/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.enterprise.noop.SingleTenantContext;
import org.unreal.modelrouter.enterprise.spi.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantKeyBuilder 单测（S2 任务 37 预埋）：
 * 社区版 key 零变化、多租户自动分段、default 租户不产生带段 key。
 * @author Trunkline Team
 */
class TenantKeyBuilderTest {

    @AfterEach
    void resetDelegate() {
        TenantContextHolder.setDelegate(null);
    }

    @Test
    @DisplayName("未绑定上下文（启动早期 / 单测）：输出与既有 key 完全一致")
    void shouldKeepLegacyKeyWithoutContext() {
        assertThat(TenantKeyBuilder.domainKey("quota", "user1:model1:DAY:123"))
            .isEqualTo("trunkline:quota:user1:model1:DAY:123");
        assertThat(TenantKeyBuilder.domainKey("config", null)).isEqualTo("trunkline:config");
    }

    @Test
    @DisplayName("社区版上下文（default 租户）：key 不带租户段")
    void shouldKeepLegacyKeyForDefaultTenant() {
        TenantContextHolder.setDelegate(SingleTenantContext.getInstance());
        assertThat(TenantKeyBuilder.domainKey("quota", "abc")).isEqualTo("trunkline:quota:abc");
    }

    @Test
    @DisplayName("多租户上下文：非 default 租户自动分段，default 租户不分段")
    void shouldPrefixNonDefaultTenant() {
        TenantContextHolder.setDelegate(new TenantContext() {
            @Override
            public String getTenantId() {
                return "acme";
            }

            @Override
            public boolean isMultiTenant() {
                return true;
            }
        });
        assertThat(TenantKeyBuilder.domainKey("quota", "abc")).isEqualTo("trunkline:acme:quota:abc");

        TenantContextHolder.setDelegate(new TenantContext() {
            @Override
            public String getTenantId() {
                return "default";
            }

            @Override
            public boolean isMultiTenant() {
                return true;
            }
        });
        assertThat(TenantKeyBuilder.domainKey("quota", "abc")).isEqualTo("trunkline:quota:abc");
    }
}
