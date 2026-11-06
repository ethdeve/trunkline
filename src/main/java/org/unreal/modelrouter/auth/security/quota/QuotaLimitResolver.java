package org.unreal.modelrouter.auth.security.quota;

import java.util.Optional;

/**
 * 配额限额解析器（按维度标识解析限额集合）。
 *
 * <p>执行层本身只认识 API Key 限额（来自 {@code ApiKey} 配置）；分组 / 模型维度的限额
 * 各自落在 {@code PoolDefinition} / 已接入模型配置上，由对应模块以本接口实现并注入，
 * 避免执行层直接依赖具体配置服务（保持包边界）。</p>
 *
 * @author JAiRouter Team
 * @since 3.4.0
 * @modified Trunkline Team
 */
public interface QuotaLimitResolver {

    /**
     * 按维度标识解析限额集合。
     *
     * @param scopeId 维度标识（分组名为池名；模型名为实例名）
     * @return 限额集合；标识未知或配置缺失时返回 {@link Optional#empty()}（按无限制放行）
     */
    Optional<QuotaLimitSet> resolve(String scopeId);
}
