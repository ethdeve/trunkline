/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.price;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.persistence.jpa.entity.ModelPriceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelPriceRepository;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 平面单价解析器（开源默认实现）。
 *
 * <p>读 {@code model_price} 的 {@code priceInput / priceOutput} 平面单价，
 * 行为与既有账单计价等价。价格版本标识恒为 {@code flat}。</p>
 *
 * <p>企业版阶梯 / 时段 / 议价等策略按 {@link PriceResolver} SPI 覆盖本 Bean
 * （企业侧 {@code @AutoConfiguration(before=...)} + {@code @ConditionalOnMissingBean}）。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlatPriceResolver implements PriceResolver {

    /** 平面单价的价格版本标识 */
    public static final String VERSION_FLAT = "flat";

    private final ModelPriceRepository modelPriceRepository;

    @Override
    public Optional<PriceQuote> resolve(final String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        return modelPriceRepository.findByModelName(modelName)
                .filter(ModelPriceEntity::getEnabled)
                .filter(price -> price.getPriceInput() != null || price.getPriceOutput() != null)
                .map(price -> new PriceQuote(
                        price.getPriceInput() != null ? price.getPriceInput() : BigDecimal.ZERO,
                        price.getPriceOutput() != null ? price.getPriceOutput() : BigDecimal.ZERO,
                        VERSION_FLAT));
    }
}
