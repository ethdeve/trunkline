/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.math.BigDecimal;

/**
 * 商业计费扩展点（商业版功能，S2 任务 32；B2 扩展预算前置熔断）。
 *
 * <p>注意与 {@code org.unreal.modelrouter.billing.service.BillingService}
 * （社区版账单聚合，读 token_usage 快照出报表）区分：本接口是「预算前置拦截 + 扣费/账本」
 * 的商业实现入口，社区版由 {@code NoopBillingProvider} 提供 default 放行 + 空扣费回执，
 * 配额仍走既有 quota 体系。</p>
 *
 * <p><b>两个时点，职责不可混用</b>（方案 3.7）：</p>
 * <ul>
 *   <li>{@link #precheck(BillingPrecheck)}：在路由与下游调用<b>之前</b>执行，预算耗尽时
 *       真正拒绝请求（402）。这是「钱不够就别发请求」唯一物理上可行的位置；</li>
 *   <li>{@link #charge(BillingEntry)}：由 {@code TokenUsageService} 在请求成功、用量事务
 *       <b>提交之后</b>上报，只能记账。事后扣费无法拒绝已发生的调用，实现失败只允许记日志
 *       降级，<b>不得阻断调用链</b>。</li>
 * </ul>
 *
 * @author Trunkline Team
 */
public interface BillingProvider {

    /**
     * 对一次成功调用记账/扣费（事务提交后）。
     *
     * @param entry 计费条目，{@code traceId} 为账本幂等键
     * @return 扣费结果；实现失败只允许记日志降级，不得阻断调用链
     */
    BillingAck charge(BillingEntry entry);

    /**
     * 预算前置校验（B2）。默认放行 —— 社区版与存量商业实现零行为变化；
     * 企业实现 override 本方法查当期预算，耗尽时返回 {@link PrecheckResult#deny(String)}。
     *
     * <p>新增的是带默认实现的方法，本身<b>不</b>触发 SPI 版本 +1；本轮版本推进来自
     * {@link BillingEntry} record 组件变化（见 {@code [BREAKING-SPI] v3}）。</p>
     *
     * @param context 租户 / 用户 / 模型上下文，可为 {@code null}
     * @return 校验结果，永不为 {@code null}
     */
    default PrecheckResult precheck(BillingPrecheck context) {
        return PrecheckResult.allow();
    }

    /** 预算前置校验入参。 */
    record BillingPrecheck(String tenantId, String username, String model) {
    }

    /** 预算前置校验结果：{@code allowed()==false} 时 reason 会透出到 402 响应体。 */
    record PrecheckResult(boolean allowed, String reason) {

        /**
         * 放行工厂。<b>不能叫 {@code allowed()}</b>：record 组件 {@code allowed} 会自动生成
         * 无参实例访问器 {@code allowed()}，同名静态工厂在 Java 里编译不过。
         */
        public static PrecheckResult allow() {
            return new PrecheckResult(true, null);
        }

        /** 拒绝工厂（方案 3.7 写作 {@code denied}，因上述冲突改名为 {@code deny}）。 */
        public static PrecheckResult deny(final String reason) {
            return new PrecheckResult(false, reason);
        }
    }

    /**
     * 计费条目。B2 起首组件为 {@code traceId}：一次调用一条账变，重放不重复扣费。
     *
     * <p>五参兼容构造器保证存量调用方<b>源码</b>不改也能编译；但 record 组件数量变化属
     * 二进制破坏性变更，故 {@link EnterpriseSpiVersion} 推到 3（{@code [BREAKING-SPI] v3}），
     * 企业仓必须同步发版，否则 {@code spiVersionGate()} 会 fail-fast 拒绝启动。</p>
     */
    record BillingEntry(String traceId, String tenantId, String username, String model,
                        BigDecimal cost, long tokens) {

        /** 兼容构造器：存量调用方（无 traceId）源码不改也能编译，但账本幂等会降级。 */
        public BillingEntry(final String tenantId, final String username, final String model,
                            final BigDecimal cost, final long tokens) {
            this(null, tenantId, username, model, cost, tokens);
        }
    }

    /** 计费回执 */
    record BillingAck(boolean accepted, BigDecimal balanceAfter, String message) {

        public static BillingAck ok() {
            return new BillingAck(true, null, null);
        }
    }
}
