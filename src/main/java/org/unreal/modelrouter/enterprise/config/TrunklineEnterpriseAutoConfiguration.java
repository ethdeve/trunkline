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
import org.unreal.modelrouter.enterprise.noop.CommunityFeaturesProvider;
import org.unreal.modelrouter.enterprise.noop.CommunityDataResidencyResolver;
import org.unreal.modelrouter.enterprise.noop.CommunityClassificationRoutingPolicy;
import org.unreal.modelrouter.billing.price.CommunityPriceResolver;
import org.unreal.modelrouter.billing.price.PriceResolver;
import org.unreal.modelrouter.enterprise.noop.NoopBillingProvider;
import org.unreal.modelrouter.enterprise.noop.NoopLicenseVerifier;
import org.unreal.modelrouter.enterprise.noop.NoopModerationProvider;
import org.unreal.modelrouter.enterprise.noop.NoopOutboundContentInspector;
import org.unreal.modelrouter.enterprise.noop.NoopToolGovernanceProvider;
import org.unreal.modelrouter.enterprise.noop.NoopSsoConnector;
import org.unreal.modelrouter.enterprise.noop.SingleTenantContext;
import org.unreal.modelrouter.enterprise.spi.BillingProvider;
import org.unreal.modelrouter.enterprise.spi.DataResidencyResolver;
import org.unreal.modelrouter.enterprise.spi.ClassificationRoutingPolicy;
import org.unreal.modelrouter.enterprise.spi.EnterpriseFeaturesProvider;
import org.unreal.modelrouter.enterprise.spi.LicenseVerifier;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider;
import org.unreal.modelrouter.enterprise.spi.OutboundContentInspector;
import org.unreal.modelrouter.enterprise.spi.ToolGovernanceProvider;
import org.unreal.modelrouter.enterprise.spi.SsoConnector;
import org.unreal.modelrouter.enterprise.spi.TenantContext;
import org.unreal.modelrouter.router.capability.ModelCapabilityProfileProvider;
import org.unreal.modelrouter.router.capability.DefaultModelCapabilityProfileProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    /**
     * 出站内容防泄露兜底（B2）：企业版 {@code OutboundDlpInspector} 先注册后本 Bean 退位。
     * 放行语义零开销，社区版 DLP 链路不生效。
     */
    @Bean
    @ConditionalOnMissingBean(OutboundContentInspector.class)
    public NoopOutboundContentInspector noopOutboundContentInspector() {
        return new NoopOutboundContentInspector();
    }

    /**
     * Agent 工具治理兜底（M4）：企业版 {@code AgentToolGovernor} 先注册后本 Bean 退位。
     * 放行语义零开销，社区版工具治理链路不生效。
     */
    @Bean
    @ConditionalOnMissingBean(ToolGovernanceProvider.class)
    public NoopToolGovernanceProvider noopToolGovernanceProvider() {
        return new NoopToolGovernanceProvider();
    }

    /**
     * 价格解析 SPI 兜底（O2「价格可演进」）：社区版不含单价配置，恒返回空价。
     *
     * <p>商业版 {@code EnterpriseAutoConfiguration.flatPriceResolver()} 通过
     * {@code @AutoConfiguration(before = TrunklineEnterpriseAutoConfiguration.class)}
     * 先于本 Bean 注册企业版实现；本 Bean 随即因 {@code @ConditionalOnMissingBean(PriceResolver.class)}
     * 退位，使「classpath 有企业版 jar 且 billing 功能位开启 → 覆盖默认实现」确定生效，
     * 与 {@link #noopBillingProvider()} 完全同构。</p>
     */
    @Bean
    @ConditionalOnMissingBean(PriceResolver.class)
    public CommunityPriceResolver communityPriceResolver() {
        return new CommunityPriceResolver();
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

    /**
     * 企业能力探测兜底（O6）：社区版恒返回空能力集。
     *
     * <p>商业版 jar 注册自己的 {@link EnterpriseFeaturesProvider} 后本 Bean 退位，
     * {@code GET /api/v1/enterprise/features} 即返回企业能力集。</p>
     */
    @Bean
    @ConditionalOnMissingBean(EnterpriseFeaturesProvider.class)
    public CommunityFeaturesProvider communityFeaturesProvider() {
        return new CommunityFeaturesProvider();
    }

    /**
     * 模型能力画像数据源（v0.2.0）：社区版读内置 JSON，企业版/现场可覆盖。
     *
     * <p>商业版 jar 通过 {@code @AutoConfiguration(before=...)} 先注册自己的
     * {@link ModelCapabilityProfileProvider} 后本 Bean 退位，构成确定覆盖链——
     * 与 {@link #communityPriceResolver()} 完全同构。解析失败时默认实现 fail-soft
     * 返回空列表，不阻断启动。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ModelCapabilityProfileProvider.class)
    public DefaultModelCapabilityProfileProvider modelCapabilityProfileProvider(
            final ObjectProvider<ObjectMapper> objectMapperProvider) {
        // 精简测试上下文（ApplicationContextRunner 只加载本配置类）没有 Jackson 自动配置，
        // 缺 Bean 时退化为一个本地 ObjectMapper——生产环境由 Spring Boot 提供共享实例。
        return new DefaultModelCapabilityProfileProvider(objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /**
     * 数据分级出境（#9）：社区版地域解析 + 出境策略兜底。
     *
     * <p>企业侧 {@code EnterpriseDataClassificationConfiguration} 以
     * {@code @AutoConfiguration(before=本类)} 先注册覆盖实现，本组 Bean 随即退位，构成
     * 与价格解析器完全同构的覆盖链。无企业插件时恒为「读 tags + 恒 ALLOW」，
     * 零策略时行为与改造前一致。</p>
     */
    @Bean
    @ConditionalOnMissingBean(DataResidencyResolver.class)
    public CommunityDataResidencyResolver communityDataResidencyResolver() {
        return new CommunityDataResidencyResolver();
    }

    @Bean
    @ConditionalOnMissingBean(ClassificationRoutingPolicy.class)
    public CommunityClassificationRoutingPolicy communityClassificationRoutingPolicy() {
        return new CommunityClassificationRoutingPolicy();
    }
}
