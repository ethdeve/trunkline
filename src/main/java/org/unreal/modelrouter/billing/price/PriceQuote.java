/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.price;

import java.math.BigDecimal;

/**
 * 一次计价的结果快照（O2 价格可演进：落库时固化到 {@code token_usage} 价格快照列）。
 *
 * @param priceInput  输入单价（¥ / 千 token）
 * @param priceOutput 输出单价（¥ / 千 token）
 * @param version     价格版本标识（如 {@code flat}），写入 {@code token_usage.price_version}，
 *                    用于区分计价策略演进后的历史数据口径
 * @author Trunkline Team
 */
public record PriceQuote(BigDecimal priceInput, BigDecimal priceOutput, String version) {
}
