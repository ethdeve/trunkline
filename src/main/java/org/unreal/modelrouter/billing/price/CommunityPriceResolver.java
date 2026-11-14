/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.price;

import java.util.Optional;

/**
 * 社区版价格解析器（开源默认实现，O2「价格可演进」SPI 兜底）。
 *
 * <p>社区版不含单价配置（单价属商业能力，由企业版 {@code FlatPriceResolver} 覆盖本实现），
 * 故恒返回 {@link Optional#empty()}——<b>未定价 ≠ ¥0</b>：调用方把该笔用量标记为「待定价」
 * （{@code priceMissing=true}）单列，绝不静默按 ¥0 计入账单。</p>
 *
 * <p>本类本身不带 {@code @Component}：其 Bean 由
 * {@code TrunklineEnterpriseAutoConfiguration.communityPriceResolver()} 以
 * {@code @ConditionalOnMissingBean(PriceResolver.class)} 注册——与企业版
 * {@code EnterpriseAutoConfiguration.flatPriceResolver()}（{@code @AutoConfiguration(before=...)}
 * 先注册）构成「classpath 有企业版 jar 且 billing 功能位开启 → 覆盖默认实现」的确定覆盖链，
 * 与 {@code NoopBillingProvider} 完全同构。</p>
 *
 * @author Trunkline Team
 */
public class CommunityPriceResolver implements PriceResolver {

    @Override
    public Optional<PriceQuote> resolve(final String modelName) {
        return Optional.empty();
    }
}
