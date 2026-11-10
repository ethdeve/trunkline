/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
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

/**
 * Trunkline 企业扩展点装配（社区版兜底，S2 任务 34）。
 *
 * <p>策略：每个扩展点用 {@code @ConditionalOnMissingBean} 注册 Noop 兜底。
 * 商业版 {@code trunkline-enterprise.jar} 自带的 AutoConfiguration 通过
 * {@code @AutoConfiguration(before = TrunklineEnterpriseAutoConfiguration.class)}
 * 先注册真实实现，本类的 Noop 随即全部退位——
 * 即「classpath 有 enterprise jar 且 license 校验通过 → 覆盖默认实现」。
 *
 * <p>全部 Bean 无状态、零 IO，对社区版运行时无可观测影响。
 * @author Trunkline Team
 */
@AutoConfiguration
public class TrunklineEnterpriseAutoConfiguration {

    /**
     * 容器刷新完成后把「实际生效」的 TenantContext（Noop 或商业版实现）
     * 绑定到 {@link TenantContextHolder}，供非 Spring 上下文取值。
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> tenantContextHolderBinder(
        final ObjectProvider<TenantContext> tenantContextProvider) {
        return event -> TenantContextHolder.setDelegate(tenantContextProvider.getObject());
    }

    @Bean
    @ConditionalOnMissingBean(TenantContext.class)
    public SingleTenantContext singleTenantContext() {
        return SingleTenantContext.getInstance();
    }

    @Bean
    @ConditionalOnMissingBean(ModerationProvider.class)
    public NoopModerationProvider noopModerationProvider() {
        return new NoopModerationProvider();
    }

    @Bean
    @ConditionalOnMissingBean(BillingProvider.class)
    public NoopBillingProvider noopBillingProvider() {
        return new NoopBillingProvider();
    }

    @Bean
    @ConditionalOnMissingBean(SsoConnector.class)
    public NoopSsoConnector noopSsoConnector() {
        return new NoopSsoConnector();
    }

    @Bean
    @ConditionalOnMissingBean(LicenseVerifier.class)
    public NoopLicenseVerifier noopLicenseVerifier() {
        return new NoopLicenseVerifier();
    }
}
