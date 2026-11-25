/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;

/**
 * 上游模型实例地域解析扩展点（#9 数据分级出境）。
 *
 * <p>把「这个模型通道把数据送到哪个地区」解析成结构化视图。判定的对象是
 * <b>上游模型通道</b>（{@link ModelInstance}），不是网关自身服务器——网关跑在国内但
 * 转发到境外端点，数据照样出境。</p>
 *
 * <p><b>信任模型（9-D 已确认口径：自动 + 人工确认）</b>：自动判据（厂商模板 category /
 * 实例 tags / baseUrl 启发式）只产出 {@code suggested} 候选，<b>不参与路由</b>；
 * 只有管理员确认后的 {@code confirmed} 值才生效。社区版默认实现读 tags 的历史
 * {@code region} 键，行为与改动前一致；企业版覆盖实现从
 * {@code enterprise_instance_residency} 读取 confirmed 值。</p>
 *
 * <p><b>失败语义</b>：返回 {@code null} 或抛出任何异常由主干按 <b>fail-closed</b> 处理
 * （视为地域未知，交给 {@link ClassificationRoutingPolicy} 拒绝）。绝不能因解析失败
 * 而"当作境内放行"。</p>
 *
 * <p><b>SPI 兼容性</b>：新接口不改变既有接口签名，{@link EnterpriseSpiVersion#VERSION}
 * 保持 3。社区版由 {@code CommunityDataResidencyResolver} 兜底，零行为影响。</p>
 *
 * @author Trunkline Team
 */
public interface DataResidencyResolver {

    /**
     * 解析一个上游实例的地域视图。
     *
     * @param instance 上游模型实例
     * @return 地域视图；返回 {@code null} 由主干按 fail-closed 处理
     */
    InstanceResidencyView resolve(ModelInstance instance);

    /** 部署形态：私有部署 / 公有云端点 */
    enum DeploymentType {
        /** 私有 / 自部署 / 专有云 */
        PRIVATE,
        /** 公有云 API 端点 */
        PUBLIC,
        /** 未知（fail-closed 由策略决定） */
        UNKNOWN
    }

    /** 判定状态：只有 CONFIRMED 参与路由决策 */
    enum Status {
        /** 自动预填候选，不参与路由 */
        SUGGESTED,
        /** 人工确认后的生效值 */
        CONFIRMED,
        /** 被人工驳回 */
        REJECTED
    }

    /**
     * 地域视图。
     *
     * @param instanceId     实例 ID
     * @param region         地域：{@code domestic} / {@code international} / {@code local}
     * @param deploymentType 部署形态
     * @param status         判定状态（仅 CONFIRMED 生效）
     * @param source         判定来源：template / tags / baseUrl-heuristic / manual
     * @param confidence     置信度：high / medium / low（现场核对用，不参与判定）
     */
    record InstanceResidencyView(String instanceId, String region, DeploymentType deploymentType,
                                 Status status, String source, String confidence) {

        /** 是否已被人工确认、可作为路由生效值 */
        public boolean isEffective() {
            return status == Status.CONFIRMED && region != null && !region.isBlank();
        }

        /** 是否为境内/私有（domestic 或 local 或 PRIVATE 部署） */
        public boolean isDomesticOrPrivate() {
            if (deploymentType == DeploymentType.PRIVATE) {
                return true;
            }
            return "domestic".equalsIgnoreCase(region) || "local".equalsIgnoreCase(region);
        }
    }
}
