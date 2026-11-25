/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.DataResidencyResolver;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;

/**
 * 地域解析社区版兜底（#9 数据分级出境）。
 *
 * <p>只读实例 {@code tags} 里的历史 {@code region} / {@code deployment} 键（v2.9.7 标签
 * 路由既有字段），没有则视为未知。社区版无企业表、无自动预填、无人工确认——
 * 但读 tags 的行为与改动前一致，配合恒 ALLOW 的{@link CommunityClassificationRoutingPolicy}
 * 使社区版零策略时行为完全不变。</p>
 *
 * <p>企业版 {@code EnterpriseDataResidencyResolver} 通过 {@code @ConditionalOnMissingBean}
 * 覆盖本实现，改从 {@code enterprise_instance_residency} 读 confirmed 值。</p>
 *
 * @author Trunkline Team
 */
public class CommunityDataResidencyResolver implements DataResidencyResolver {

    private static final String TAG_REGION = "region";

    @Override
    public final InstanceResidencyView resolve(final ModelInstance instance) {
        final String region = instance != null && instance.getTags() != null
                ? instance.getTags().get(TAG_REGION) : null;
        // 社区版读 tags 视为人工已确认（管理员手填），有 region 即生效；无则未知（fail-closed 由策略决定）
        final Status status = region != null && !region.isBlank()
                ? Status.CONFIRMED : Status.REJECTED;
        return new InstanceResidencyView(
                instance != null ? instance.getInstanceId() : null,
                region,
                DeploymentType.UNKNOWN,
                status,
                "tags",
                region != null ? "high" : "low");
    }
}
