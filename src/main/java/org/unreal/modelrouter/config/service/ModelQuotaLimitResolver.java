/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitResolver;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitSet;

import java.util.Optional;

/**
 * 厂商模型维度配额限额解析器（开源基础能力，v3.5 Trunkline UI 改造）。
 *
 * <p>把 {@code model_quota} 表挂在<b>厂商模型</b>上的额度与限速配置
 * 打包为 {@link QuotaLimitSet}，供配额执行层在请求路由到具体厂商模型后按该维度独立判定。</p>
 *
 * <p><b>与分组维度的分工</b>：</p>
 * <ul>
 *   <li>分组（虚拟模型 / 模型分组）维度的限额落在 {@code PoolDefinition} 上，
 *       由 {@code PoolQuotaLimitResolver}（{@code @Qualifier("group")}）解析；</li>
 *   <li>厂商模型维度的限额落在 {@code model_quota} 行上（本解析器，
 *       {@code @Qualifier("model")}）——同一厂商模型被多个虚拟模型引用时<b>共享</b>这一份额度。</li>
 * </ul>
 *
 * @author Trunkline Team
 */
@Component
@Qualifier("model")
public class ModelQuotaLimitResolver implements QuotaLimitResolver {

    private final ModelQuotaService modelQuotaService;

    @Autowired
    public ModelQuotaLimitResolver(final ModelQuotaService modelQuotaService) {
        this.modelQuotaService = modelQuotaService;
    }

    /**
     * 按厂商模型名解析限额集合。
     *
     * @param scopeId 厂商模型名（= {@code service_instance.instance_name}）
     * @return 限额集合；模型名缺失时返回 {@link Optional#empty()}（按无限制放行），
     *         未配置额度时返回 {@link QuotaLimitSet#UNLIMITED}（同样不判定，但维度照常记账）
     */
    @Override
    public Optional<QuotaLimitSet> resolve(final String scopeId) {
        if (scopeId == null || scopeId.isEmpty() || modelQuotaService == null) {
            return Optional.empty();
        }
        return Optional.of(modelQuotaService.resolveQuotaLimit(scopeId));
    }
}
