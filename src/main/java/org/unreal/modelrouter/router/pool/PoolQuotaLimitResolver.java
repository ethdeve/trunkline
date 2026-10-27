package org.unreal.modelrouter.router.pool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitResolver;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitSet;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 分组（模型分组 / 池）维度配额限额解析器。
 *
 * <p>把 {@link PoolDefinition} 上配置的限额字段打包为 {@link QuotaLimitSet}，供配额执行层
 * 在请求预留时按池维度独立判定。属于“分组”维度，故以 {@code @Qualifier("group")} 注入执行层。</p>
 *
 * @author JAiRouter Team
 * @since 3.4.0
 */
@Component
@Qualifier("group")
public class PoolQuotaLimitResolver implements QuotaLimitResolver {

    private final PoolSelector poolSelector;

    @Autowired
    public PoolQuotaLimitResolver(final PoolSelector poolSelector) {
        this.poolSelector = poolSelector;
    }

    @Override
    public Optional<QuotaLimitSet> resolve(final String scopeId) {
        if (scopeId == null || scopeId.isEmpty() || poolSelector == null) {
            return Optional.empty();
        }
        final List<PoolDefinition> pools = poolSelector.getAllPools();
        if (pools == null) {
            return Optional.empty();
        }
        return pools.stream()
                .filter(p -> p != null && scopeId.equals(p.getPoolName()))
                .findFirst()
                .map(PoolDefinition::toQuotaLimitSet);
    }
}
