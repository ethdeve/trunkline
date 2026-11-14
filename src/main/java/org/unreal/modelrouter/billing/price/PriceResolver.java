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
 * <p><b>开源实现</b>：{@link CommunityPriceResolver}（社区版默认返回空价，单价属商业能力）。
 * 企业版可覆盖为平面单价 / 阶梯 / 时段 / 议价 / 多币种等策略
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
 * <p><b>计价上下文（B1 / P0-6）</b>：新增带 {@link PricingContext} 的重载，让租户 ID
 * 进入取价链路，企业版可据此实现租户级议价 / 部门价 / 客户专属价。为保持存量实现
 * 二进制兼容，该重载是 <b>default 方法</b>（委托给 {@link #resolve(String)}）——按
 * {@code EnterpriseSpiVersion} 注释规范，仅新增带默认实现的方法不算破坏性变更，
 * <b>本处不触发 SPI 版本 +1</b>（VERSION 保持 2）。</p>
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

    /**
     * 带上下文取价：企业版 override 本重载，实现租户级 / 时段级 / 阶梯 / 议价策略。
     *
     * <p>默认委托旧方法，保证只实现 {@link #resolve(String)} 的存量商业插件无需改动即可工作。
     * 传入 {@code null} 上下文时不抛异常，按「无价可取」处理。</p>
     *
     * @param context 计价上下文
     * @return 有效报价；无价可取时返回 empty
     */
    default Optional<PriceQuote> resolve(PricingContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return resolve(context.modelName());
    }

    /**
     * 计价上下文（预留字段用于阶梯 / 缓存 token / 多币种，v1 不参与计价）。
     *
     * <p><b>不要顺手给 {@link PriceQuote} 加组件</b>：那属于破坏性变更，需把
     * {@code EnterpriseSpiVersion.VERSION} 推到 3 并两仓同批发版；本版
     * {@code cachedTokens} / {@code currency} 仅作配置与展示字段。</p>
     *
     * @param tenantId     租户 ID（单租户 / 未解析时为 {@code default}）
     * @param modelName    计价键（与账单口径一致）
     * @param cachedTokens 命中缓存的 token 数（预留，v1 恒 0）
     * @param currency     币种（预留，v1 恒 CNY）
     */
    record PricingContext(String tenantId, String modelName,
                          long cachedTokens, String currency) {
        /** 常规取价的简写：缓存 token 0、币种 CNY。 */
        public static PricingContext of(String tenantId, String modelName) {
            return new PricingContext(tenantId, modelName, 0L, "CNY");
        }
    }
}
