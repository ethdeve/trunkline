/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.unreal.modelrouter.enterprise.noop.NoopBillingProvider;
import org.unreal.modelrouter.enterprise.noop.NoopLicenseVerifier;
import org.unreal.modelrouter.enterprise.noop.NoopModerationProvider;
import org.unreal.modelrouter.enterprise.noop.NoopSsoConnector;
import org.unreal.modelrouter.enterprise.noop.SingleTenantContext;
import org.unreal.modelrouter.enterprise.spi.BillingProvider;
import org.unreal.modelrouter.enterprise.spi.LicenseVerifier;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider;
import org.unreal.modelrouter.enterprise.spi.SsoConnector;
import org.unreal.modelrouter.enterprise.spi.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 企业扩展点装配测试（S2 任务 34）：
 * 验证 Noop 兜底注册、商业版实现覆盖、TenantContextHolder 绑定。
 * @author Trunkline Team
 */
class TrunklineEnterpriseAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TrunklineEnterpriseAutoConfiguration.class));

    @Test
    @DisplayName("默认装配：五个扩展点全部 Noop 兜底，租户恒为 default")
    void shouldRegisterAllNoopDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TenantContext.class);
            assertThat(context.getBean(TenantContext.class)).isInstanceOf(SingleTenantContext.class);
            assertThat(context).hasSingleBean(ModerationProvider.class)
                .getBean(ModerationProvider.class).isInstanceOf(NoopModerationProvider.class);
            assertThat(context).hasSingleBean(BillingProvider.class)
                .getBean(BillingProvider.class).isInstanceOf(NoopBillingProvider.class);
            assertThat(context).hasSingleBean(SsoConnector.class)
                .getBean(SsoConnector.class).isInstanceOf(NoopSsoConnector.class);
            assertThat(context).hasSingleBean(LicenseVerifier.class)
                .getBean(LicenseVerifier.class).isInstanceOf(NoopLicenseVerifier.class);

            final TenantContext tenantContext = context.getBean(TenantContext.class);
            assertThat(tenantContext.getTenantId()).isEqualTo("default");
            assertThat(tenantContext.isMultiTenant()).isFalse();
        });
    }

    @Test
    @DisplayName("Noop 行为：审核放行、计费回执、SSO 不支持、license 社区版合法")
    void shouldExposeNoopSemantics() {
        runner.run(context -> {
            final ModerationProvider moderation = context.getBean(ModerationProvider.class);
            assertThat(moderation.check(new ModerationProvider.ModerationInput(
                "gpt-4o", "任意内容", "emp01")).allowed()).isTrue();

            final BillingProvider billing = context.getBean(BillingProvider.class);
            assertThat(billing.charge(new BillingProvider.BillingEntry(
                "default", "emp01", "gpt-4o", java.math.BigDecimal.ONE, 100L)).accepted()).isTrue();

            final SsoConnector sso = context.getBean(SsoConnector.class);
            assertThat(sso.exchange("any-code")).isEmpty();

            final LicenseVerifier license = context.getBean(LicenseVerifier.class);
            final LicenseVerifier.LicenseStatus status = license.verify();
            assertThat(status.valid()).isTrue();
            assertThat(status.edition()).isEqualTo("community");
            assertThat(status.maxTenants()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("商业版覆盖：用户提供的实现令全部 Noop 退位")
    void shouldBackOffToUserProvidedImplementations() {
        runner.withUserConfiguration(EnterpriseSimulatorConfig.class).run(context -> {
            assertThat(context.getBean(TenantContext.class))
                .isInstanceOf(EnterpriseSimulatorConfig.MultiTenantContext.class);
            assertThat(context).doesNotHaveBean(SingleTenantContext.class);
            assertThat(context).doesNotHaveBean(NoopModerationProvider.class);
            assertThat(context).doesNotHaveBean(NoopBillingProvider.class);
            assertThat(context).doesNotHaveBean(NoopSsoConnector.class);
            assertThat(context).doesNotHaveBean(NoopLicenseVerifier.class);
        });
    }

    @Test
    @DisplayName("TenantContextHolder 绑定：容器刷新后可静态取值")
    void shouldBindTenantContextHolderAfterRefresh() {
        runner.run(context ->
            assertThat(TenantContextHolder.currentTenantId()).isEqualTo("default"));
    }

    /** 模拟商业版 jar 注入的实现 */
    @Configuration(proxyBeanMethods = false)
    static class EnterpriseSimulatorConfig {

        @Bean
        public TenantContext enterpriseTenantContext() {
            return new MultiTenantContext();
        }

        @Bean
        public ModerationProvider enterpriseModeration() {
            return input -> ModerationProvider.ModerationDecision.block("enterprise");
        }

        @Bean
        public BillingProvider enterpriseBilling() {
            return entry -> BillingProvider.BillingAck.ok();
        }

        @Bean
        public SsoConnector enterpriseSso() {
            return new SsoConnector() {
                @Override
                public String providerName() {
                    return "oidc";
                }

                @Override
                public java.util.Optional<SsoConnector.EnterpriseUser> exchange(final String authorizationCode) {
                    return java.util.Optional.empty();
                }
            };
        }

        @Bean
        public LicenseVerifier enterpriseLicense() {
            return () -> new LicenseVerifier.LicenseStatus(true, "enterprise", 50, "ok");
        }

        static class MultiTenantContext implements TenantContext {
            @Override
            public String getTenantId() {
                return "acme";
            }

            @Override
            public boolean isMultiTenant() {
                return true;
            }
        }
    }
}
