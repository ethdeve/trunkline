/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.service;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.enterprise.config.TenantContextHolder;
import org.unreal.modelrouter.enterprise.spi.BillingProvider;
import org.unreal.modelrouter.enterprise.spi.BillingProvider.BillingPrecheck;
import org.unreal.modelrouter.enterprise.spi.BillingProvider.PrecheckResult;

/**
 * 预算前置校验（B2 / 方案 3.7）。
 *
 * <p><b>为什么必须有这一层</b>：{@code BillingProvider.charge()} 由
 * {@code TokenUsageService} 在请求成功、用量事务提交之后调用，物理上无法拒绝已发生的调用。
 * 「预算耗尽 → 不放行」只能发生在路由之前。本服务把该判定收口到一个可独立测试的类，
 * 由 {@code ServiceRequestHandler} 在实例选择之前调用。</p>
 *
 * <p><b>零行为变化</b>：社区版 {@code NoopBillingProvider} 走 SPI default 方法恒放行；
 * 未装配 {@link BillingProvider} 的精简上下文直接跳过。任何异常按 <b>fail-open</b> 放行并告警，
 * 商业账本故障绝不把网关拖成 5xx（与 {@code QuotaEnforcementService} 同策略）。</p>
 *
 * <p>返回 {@code Optional.empty()} = 放行；返回 {@link PrecheckResult} = 拒绝，
 * 调用方应渲染 402 并透出 {@code reason()}。</p>
 *
 * @author Trunkline Team
 */
@Service
public class BillingPrecheckService {

    private static final Logger log = LoggerFactory.getLogger(BillingPrecheckService.class);

    private final ObjectProvider<BillingProvider> provider;

    public BillingPrecheckService(final ObjectProvider<BillingProvider> provider) {
        this.provider = provider;
    }

    /**
     * 从当前租户上下文取 tenantId 后判定；租户解析失败（如企业侧 enforce 模式未解析到租户）
     * 也按 fail-open 放行，避免在计费前置链引入新的拒绝原因。
     */
    public Optional<PrecheckResult> evaluate(final String username, final String model) {
        final String tenantId;
        try {
            tenantId = TenantContextHolder.currentTenantId();
        } catch (Exception e) {
            log.warn("预算前置校验取租户上下文失败，按 fail-open 放行: model={}, cause={}",
                    model, e.toString());
            return Optional.empty();
        }
        return evaluate(tenantId, username, model);
    }

    /**
     * 执行 SPI 前置校验。
     *
     * @param tenantId 租户 ID
     * @param username 调用主体
     * @param model    请求的虚拟模型名
     * @return 拒绝结果；放行（含降级放行）时为 {@link Optional#empty()}
     */
    public Optional<PrecheckResult> evaluate(final String tenantId,
                                             final String username,
                                             final String model) {
        final BillingProvider billing = provider.getIfAvailable();
        if (billing == null) {
            return Optional.empty();
        }
        try {
            final PrecheckResult result = billing.precheck(new BillingPrecheck(tenantId, username, model));
            return result != null && !result.allowed() ? Optional.of(result) : Optional.empty();
        } catch (Exception e) {
            log.warn("预算前置校验异常，按 fail-open 放行: tenantId={}, model={}, cause={}",
                    tenantId, model, e.toString());
            return Optional.empty();
        }
    }
}
