/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 网关运行形态信息（{@code GET /api/gateway/info} 的响应体）。
 *
 * <p>用途：让控制台能展示<b>真实</b>的接入地址与部署形态，而不是像旧版「快速上手」页那样
 * 写死 {@code https://ai.yourcompany.com/v1} 之类的示例值——员工照着做必然失败。</p>
 *
 * <p>全部字段均为<b>只读、非敏感</b>：不含密钥、不含上游地址、不含内部主机名。
 * {@code baseUrl} 由请求上下文推导（见 {@code GatewayInfoService}），因此无论单机直连
 * 还是挂在 Ingress/反代之后都能给出用户实际可达的地址。</p>
 *
 * @param baseUrl          OpenAI 兼容接入地址（含 {@code /v1} 后缀），前端直接展示与复制
 * @param deploymentMode   部署形态：{@code SINGLE} 单机 / {@code CLUSTER} 多副本
 * @param replicas         副本数；{@code <=0} 表示未知（未显式配置且无法从环境探测）
 * @param sharedStateReady 跨副本共享态是否齐备（单机恒为 true，无共享态概念）
 * @param sharedStateMissing 未开启的共享态开关配置键；多副本下用于运维排障提示
 * @author Trunkline Team
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GatewayInfo(
        String baseUrl,
        String deploymentMode,
        int replicas,
        boolean sharedStateReady,
        List<String> sharedStateMissing) {

    /** 单机部署 */
    public static final String MODE_SINGLE = "SINGLE";

    /** 多副本部署 */
    public static final String MODE_CLUSTER = "CLUSTER";
}
