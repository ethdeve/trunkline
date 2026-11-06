package org.unreal.modelrouter.config.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitResolver;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitSet;

import java.util.Optional;

/**
 * 厂商模型维度配额限额解析器（v3.5 Trunkline UI 改造）。
 *
 * <p>把「价格与额度」表（{@code model_price}）中挂在<b>厂商模型</b>上的额度与限速配置
 * 打包为 {@link QuotaLimitSet}，供配额执行层在请求路由到具体厂商模型后按该维度独立判定。</p>
 *
 * <p><b>与分组维度的分工</b>：</p>
 * <ul>
 *   <li>分组（虚拟模型 / 模型分组）维度的限额落在 {@code PoolDefinition} 上，
 *       由 {@code PoolQuotaLimitResolver}（{@code @Qualifier("group")}）解析；</li>
 *   <li>厂商模型维度的限额落在 {@code model_price} 行上（本解析器，
 *       {@code @Qualifier("model")}）——同一厂商模型被多个虚拟模型引用时<b>共享</b>这一份额度。</li>
 * </ul>
 *
 * <p>放在 {@code config.service} 包下：本解析器的数据源就是同包的
 * {@link ModelPriceService}，而 {@code auth.security.quota} 包不反向依赖
 * {@code config.service}，因此不会形成包依赖环。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@Component
@Qualifier("model")
public class ModelPriceQuotaLimitResolver implements QuotaLimitResolver {

    private final ModelPriceService modelPriceService;

    @Autowired
    public ModelPriceQuotaLimitResolver(final ModelPriceService modelPriceService) {
        this.modelPriceService = modelPriceService;
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
        if (scopeId == null || scopeId.isEmpty() || modelPriceService == null) {
            return Optional.empty();
        }
        return Optional.of(modelPriceService.resolveQuotaLimit(scopeId));
    }
}
