/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.noop;

import java.util.List;
import org.unreal.modelrouter.enterprise.spi.ClassificationRoutingPolicy;
import org.unreal.modelrouter.enterprise.spi.DataClassification;
import org.unreal.modelrouter.enterprise.spi.DataResidencyResolver;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;

/**
 * 出境策略社区版兜底：恒 ALLOW（#9 数据分级出境）。
 *
 * <p>社区版不装配数据分级功能——任何密级的请求都放行全部候选实例，
 * 行为与改造前完全一致。企业版
 * {@code EnterpriseClassificationRoutingPolicy} 通过 {@code @ConditionalOnMissingBean}
 * 覆盖本实现。</p>
 *
 * @author Trunkline Team
 */
public class CommunityClassificationRoutingPolicy implements ClassificationRoutingPolicy {

    /** 共享放行单例，跨请求复用安全 */
    private static final PolicyDecision ALLOW = PolicyDecision.allow();

    @Override
    public final PolicyDecision decide(final DataClassification classification,
                                 final List<ModelInstance> candidates,
                                 final DataResidencyResolver resolver) {
        return ALLOW;
    }
}
