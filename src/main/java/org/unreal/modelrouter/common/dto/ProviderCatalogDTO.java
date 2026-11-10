/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 供应商目录条目 DTO（v3.5 Trunkline UI 改造，接入管理「接入厂商」tab）。
 *
 * <p>把「供应商」与「其下已接入的模型名」合并成一条，供表格直接渲染——
 * 避免前端为每个供应商再发一次模型列表请求（N+1）。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ProviderCatalogDTO {

    /** 供应商 ID */
    private Long id;

    /** 幂等标识（= 模板 uid；可与供应商模板目录比对得出「国内 / 国外」标签） */
    private String providerUid;

    /** 供应商名称 */
    private String name;

    /** 上游地址 */
    private String baseUrl;

    /** 服务分类 */
    private String serviceType;

    /** 状态（ACTIVE / INACTIVE） */
    private String status;

    /** 是否已配置上游 Key */
    private Boolean hasApiKey;

    /** 已接入模型数 */
    private int modelCount;

    /** 已接入的模型名列表 */
    private List<String> models;

    /** 已接入模型中处于不健康状态的数量（用于「限流中 / 异常」提示） */
    private int unhealthyCount;
}
