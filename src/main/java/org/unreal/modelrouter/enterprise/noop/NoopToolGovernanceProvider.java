/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.ToolGovernanceProvider;

/**
 * Agent 工具治理放行实现（社区版兜底，M4 配套）。
 *
 * <p>不做任何检查，全部放行——社区版不部署工具治理链路，请求延迟不受本实现影响
 * （无 IO、无拷贝、共享不可变单例）。企业版 {@code AgentToolGovernor} 通过
 * {@code @ConditionalOnMissingBean} 覆盖本实现。</p>
 *
 * @author Trunkline Team
 */
public class NoopToolGovernanceProvider implements ToolGovernanceProvider {

    /** 共享放行单例：决策对象不可变，跨请求复用安全 */
    private static final GovernanceDecision ALLOW = GovernanceDecision.allow();

    @Override
    public GovernanceDecision govern(final GovernanceInput input) {
        return ALLOW;
    }
}
