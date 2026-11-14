/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.enterprise.spi.EnterpriseSpiVersion;

/**
 * 计价上下文 SPI 契约测试（B1 / P0-6）。
 *
 * <p>核心红线：新增 {@code resolve(PricingContext)} 必须是 <b>default 方法</b>且委托旧签名，
 * 否则存量商业插件（只实现 {@code resolve(String)}）会二进制不兼容，且按
 * {@code EnterpriseSpiVersion} 规范必须单独推一次 SPI 版本。本测试把
 * 「default 委托 + PriceQuote 仍是三组件」钉死，防止后续有人顺手改成抽象方法或改 record 组件。</p>
 *
 * <p><b>v3 说明（B2）</b>：B1 的 default 重载本身不推版本；当前
 * {@code EnterpriseSpiVersion.VERSION == 3} 来自 B2 给
 * {@code BillingProvider.BillingEntry} 加 {@code traceId} 幂等键（record 组件变化 =
 * 二进制破坏性变更），与 {@code PriceResolver} 无关，两仓同批发版即可。</p>
 *
 * @author Trunkline Team
 */
class PriceResolverPricingContextTest {

    /** 只实现旧签名的「存量插件」：default 重载必须委托回来。 */
    private static final class LegacyPlugin implements PriceResolver {
        private final AtomicReference<String> seen = new AtomicReference<>();

        @Override
        public Optional<PriceQuote> resolve(final String modelName) {
            seen.set(modelName);
            return Optional.of(new PriceQuote(new BigDecimal("0.01"),
                    new BigDecimal("0.03"), "legacy"));
        }
    }

    @Test
    void defaultOverloadDelegatesToLegacySignature() {
        final LegacyPlugin plugin = new LegacyPlugin();
        final Optional<PriceQuote> quote = plugin.resolve(
                PriceResolver.PricingContext.of("acme", "gpt-4o"));

        assertTrue(quote.isPresent());
        assertEquals("legacy", quote.get().version());
        assertEquals("gpt-4o", plugin.seen.get(), "上下文重载必须把 modelName 委托给旧签名");
    }

    @Test
    void nullContextIsTreatedAsNoPriceWithoutCallingLegacy() {
        final PriceResolver plugin = modelName -> {
            throw new AssertionError("null 上下文不应触发旧签名");
        };

        assertFalse(plugin.resolve((PriceResolver.PricingContext) null).isPresent());
    }

    @Test
    void contextFactoryDefaults() {
        final PriceResolver.PricingContext ctx = PriceResolver.PricingContext.of("acme", "gpt-4o");
        assertEquals("acme", ctx.tenantId());
        assertEquals("gpt-4o", ctx.modelName());
        assertEquals(0L, ctx.cachedTokens());
        assertEquals("CNY", ctx.currency());
    }

    @Test
    void contextOverloadIsDefaultAndSpiVersionIsThree() throws NoSuchMethodException {
        final Method method = PriceResolver.class.getMethod(
                "resolve", PriceResolver.PricingContext.class);
        assertTrue(method.isDefault(), "新增重载必须是 default 方法，保持存量插件二进制兼容");
        assertEquals(3, EnterpriseSpiVersion.VERSION,
                "B1 的 default 重载不推版本；v3 来自 B2 的 BillingEntry.traceId（[BREAKING-SPI]）");
        assertEquals(3, PriceQuote.class.getRecordComponents().length,
                "PriceQuote 仍为三组件 record（input/output/version），不得顺手加组件");
    }

    @Test
    void communityResolverStaysUnpricedEvenWithContext() {
        assertFalse(new CommunityPriceResolver()
                        .resolve(PriceResolver.PricingContext.of("acme", "gpt-4o")).isPresent(),
                "社区版无单价源：带租户上下文也仍是「未定价」，绝不退化成 ¥0");
        assertFalse(new CommunityPriceResolver().resolve("gpt-4o").isPresent());
    }

    @Test
    void enterpriseCanOverrideContextOverload() {
        // 模拟商业插件：override 上下文重载，按租户给不同价
        final PriceResolver negotiated = new PriceResolver() {
            @Override
            public Optional<PriceQuote> resolve(final String modelName) {
                return Optional.empty();
            }

            @Override
            public Optional<PriceQuote> resolve(final PricingContext context) {
                return "acme".equals(context.tenantId())
                        ? Optional.of(new PriceQuote(new BigDecimal("0.99"),
                                new BigDecimal("0.99"), "negotiated"))
                        : resolve(context.modelName());
            }
        };

        assertEquals("negotiated", negotiated.resolve(
                PriceResolver.PricingContext.of("acme", "gpt-4o")).orElseThrow().version());
        assertFalse(negotiated.resolve(PriceResolver.PricingContext.of("other", "gpt-4o"))
                .isPresent());
    }
}
