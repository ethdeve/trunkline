/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.capability;

import java.util.List;

/**
 * 模型能力画像数据源 SPI（v0.2.0）。
 *
 * <p>社区版默认实现读内置 JSON（{@link DefaultModelCapabilityProfileProvider}）；
 * 企业版或现场可通过 {@code @ConditionalOnMissingBean} 覆盖本接口注入增强画像
 * （如按客户私有模型补充能力），不改 OSS 类——与既有 5 个 SPI 同构。</p>
 *
 * <p>本接口为新增扩展点（全 default 方法），不改变既有 SPI 签名，
 * 按 {@code EnterpriseSpiVersion} 约定属兼容扩展，不推版本号。</p>
 *
 * @author Trunkline Team
 */
public interface ModelCapabilityProfileProvider {

    /**
     * 加载全部画像（唯一抽象方法，使本接口可作为函数式接口使用）。
     * 实现应保证失败时返回空列表而非抛异常（fail-soft，不阻断启动）。
     */
    List<ModelCapabilityProfile> load();

    /**
     * 数据源名称，用于诊断与前端来源标注。
     */
    default String name() {
        return "default";
    }
}
