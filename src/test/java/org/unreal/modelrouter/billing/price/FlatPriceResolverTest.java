/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.price;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.persistence.jpa.entity.ModelPriceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelPriceRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * FlatPriceResolver（开源默认价格实现）单元测试。
 *
 * @author Trunkline Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlatPriceResolver：开源平面单价实现")
class FlatPriceResolverTest {

    @Mock
    private ModelPriceRepository modelPriceRepository;

    @InjectMocks
    private FlatPriceResolver resolver;

    private ModelPriceEntity price(final BigDecimal in, final BigDecimal out, final boolean enabled) {
        final ModelPriceEntity e = new ModelPriceEntity();
        e.setModelName("gpt-4o");
        e.setPriceInput(in);
        e.setPriceOutput(out);
        e.setEnabled(enabled);
        return e;
    }

    @Test
    @DisplayName("命中已启用模型 → 返回报价，版本 flat")
    void resolvesEnabledPrice() {
        when(modelPriceRepository.findByModelName("gpt-4o"))
                .thenReturn(Optional.of(price(new BigDecimal("0.01"), new BigDecimal("0.03"), true)));

        final Optional<PriceQuote> quote = resolver.resolve("gpt-4o");

        assertTrue(quote.isPresent());
        assertEquals(new BigDecimal("0.01"), quote.get().priceInput());
        assertEquals(new BigDecimal("0.03"), quote.get().priceOutput());
        assertEquals("flat", quote.get().version());
    }

    @Test
    @DisplayName("停用模型 → empty（未定价，不得 ¥0 计入）")
    void disabledPriceIsEmpty() {
        when(modelPriceRepository.findByModelName("gpt-4o"))
                .thenReturn(Optional.of(price(new BigDecimal("0.01"), new BigDecimal("0.03"), false)));
        assertTrue(resolver.resolve("gpt-4o").isEmpty());
    }

    @Test
    @DisplayName("无价模型 / 空白键 → empty")
    void missingModelOrBlankKeyIsEmpty() {
        when(modelPriceRepository.findByModelName("unknown")).thenReturn(Optional.empty());
        assertTrue(resolver.resolve("unknown").isEmpty());
        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(resolver.resolve("  ").isEmpty());
    }
}
