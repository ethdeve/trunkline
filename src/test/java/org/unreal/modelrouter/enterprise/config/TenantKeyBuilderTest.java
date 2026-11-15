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

    @Test
    @DisplayName("A3 硬化：社区版 ↔ 多租户 default 租户，全 domain/rest 组合逐字节一致且不带 default 段")
    void defaultTenantKeepsLegacyKeysAcrossAllDomains() {
        final String[] domains = {"quota", "config", "cache", "ratelimit", "license", "billing", "audit"};
        final String[] rests = {null, "", "abc", "user1:model1:DAY:123", "a:b:c", "含中文"};

        for (final String domain : domains) {
            for (final String rest : rests) {
                TenantContextHolder.setDelegate(null);
                final String community = TenantKeyBuilder.domainKey(domain, rest);

                TenantContextHolder.setDelegate(defaultTenantContext());
                final String multiDefault = TenantKeyBuilder.domainKey(domain, rest);

                assertThat(multiDefault)
                        .as("domain=%s rest=%s：商业版 default 租户必须复用社区版 key（存量数据零迁移）", domain, rest)
                        .isEqualTo(community)
                        .doesNotContain(":default:");
                assertThat(community)
                        .as("社区版 key 不得出现任何租户段")
                        .startsWith("trunkline:");
            }
        }
    }

    @Test
    @DisplayName("A3 硬化：不同非 default 租户的 key 互相隔离，且同一租户内 domain 边界清晰")
    void nonDefaultTenantKeysAreIsolatedAcrossTenants() {
        TenantContextHolder.setDelegate(tenant("acme-corp"));
        final String acmeQuota = TenantKeyBuilder.domainKey("quota", "abc");
        final String acmeConfig = TenantKeyBuilder.domainKey("config", "abc");

        TenantContextHolder.setDelegate(tenant("globex"));
        final String globexQuota = TenantKeyBuilder.domainKey("quota", "abc");

        assertThat(acmeQuota).isEqualTo("trunkline:acme-corp:quota:abc");
        assertThat(globexQuota).isEqualTo("trunkline:globex:quota:abc");
        assertThat(acmeQuota).isNotEqualTo(globexQuota).isNotEqualTo(acmeConfig);
        assertThat(acmeConfig).startsWith("trunkline:acme-corp:");
    }

    private static TenantContext defaultTenantContext() {
        return tenant("default");
    }

    private static TenantContext tenant(final String tenantId) {
        return new TenantContext() {
            @Override
            public String getTenantId() {
                return tenantId;
            }

            @Override
            public boolean isMultiTenant() {
                return true;
            }
        };
    }
}
