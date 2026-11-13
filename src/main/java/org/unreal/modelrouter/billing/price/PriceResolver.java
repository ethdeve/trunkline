/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.price;

import java.util.Optional;

/**
 * 价格解析 SPI（O2「价格可演进」扩展点）。
 *
 * <p><b>定位</b>：用量落库时点的一次性取价——把「当时的价格」固化进
 * {@code token_usage} 快照列（O1 成交价快照），账单聚合只读快照、不再查当前价格表，
 * 从根上消除「今天调一次单价，上月账单跟着变」的历史漂移。</p>
 *
 * <p><b>开源实现</b>：{@link FlatPriceResolver}（读 {@code model_price} 平面单价，
 * 行为等价现状）。企业版可覆盖为阶梯 / 时段 / 议价 / 多币种等策略
 * （{@code TieredPriceResolver} / {@code TimeBasedPriceResolver} /
 * {@code NegotiatedRateResolver}），本接口是它们的落点。</p>
 *
 * <p><b>语义约定</b>：</p>
 * <ul>
 *   <li>取不到价返回 {@link Optional#empty()}——<b>未定价 ≠ ¥0</b>（O2）：
 *       调用方必须把该笔用量标记为「待定价」（{@code price_missing=true}）单列，
 *       绝不允许静默按 ¥0 计入账单；</li>
 *   <li>本 SPI 只影响<b>计价</b>，永不阻断计量（{@code token_usage} 照记）与路由——
 *       真正的 fail-closed 焊在预算耗尽拒绝路由（{@code BillingProvider.charge}），
 *       不焊在缺价。</li>
 * </ul>
 *
 * @author Trunkline Team
 */
public interface PriceResolver {

    /**
     * 解析某模型的当前有效单价。
     *
     * @param modelName 计价键（与账单口径一致：厂商模型名 {@code instance_name}，
     *                  为空时由调用方回落 {@code model_name}）
     * @return 有效报价；无价可取时返回 empty
     */
    Optional<PriceQuote> resolve(String modelName);
}
