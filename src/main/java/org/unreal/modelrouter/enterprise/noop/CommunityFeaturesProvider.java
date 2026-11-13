/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.EnterpriseFeaturesProvider;

import java.util.Map;

/**
 * 社区版企业能力兜底（O6）：恒返回空能力集。
 *
 * <p>开源版调用 {@code GET /api/v1/enterprise/features} 得到 {@code {}}，
 * 前端据此把所有企业专属 UI 渲染为「升级企业版」占位而非报错。
 * 商业版 jar 在场时本类随 {@code @ConditionalOnMissingBean} 退位。</p>
 *
 * @author Trunkline Team
 */
public class CommunityFeaturesProvider implements EnterpriseFeaturesProvider {

    /** 空能力集（不可变，共享实例防每请求重建） */
    private static final Map<String, Object> EMPTY_FEATURES = Map.of();

    @Override
    public Map<String, Object> getFeatures() {
        return EMPTY_FEATURES;
    }
}
