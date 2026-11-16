/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.OutboundContentInspector;

/**
 * 出站内容防泄露放行实现（社区版兜底，B2 配套）。
 *
 * <p>不做任何检查，全部放行——社区版不部署 DLP 链路，请求延迟不受本实现影响
 * （无 IO、无拷贝、共享不可变单例）。企业版
 * {@code OutboundDlpInspector} 通过 {@code @ConditionalOnMissingBean} 覆盖本实现。
 * @author Trunkline Team
 */
public class NoopOutboundContentInspector implements OutboundContentInspector {

    /** 共享放行单例：决策对象不可变，跨请求复用安全 */
    private static final InspectionDecision ALLOW = InspectionDecision.allow();

    @Override
    public InspectionDecision inspect(final InspectionInput input) {
        return ALLOW;
    }
}
