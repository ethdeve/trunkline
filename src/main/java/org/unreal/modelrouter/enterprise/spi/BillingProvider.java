package org.unreal.modelrouter.enterprise.spi;

import java.math.BigDecimal;

/**
 * 商业计费扩展点（商业版功能，S2 任务 32）。
 *
 * <p>注意与 {@code org.unreal.modelrouter.billing.service.BillingService}
 * （社区版账单聚合，读 token_usage 出报表）区分：本接口是「扣费/限额」的商业实现入口，
 * 社区版由 {@code NoopBillingService} 提供——只记账不扣费，配额仍走既有 quota 体系。
 */
public interface BillingProvider {

    /**
     * 对一次成功调用记账/扣费。
     *
     * @param entry 计费条目
     * @return 扣费结果；实现失败只允许记日志降级，不得阻断调用链
     */
    BillingAck charge(BillingEntry entry);

    /** 计费条目 */
    record BillingEntry(String tenantId, String username, String model,
                        BigDecimal cost, long tokens) {
    }

    /** 计费回执 */
    record BillingAck(boolean accepted, BigDecimal balanceAfter, String message) {

        public static BillingAck ok() {
            return new BillingAck(true, null, null);
        }
    }
}
