package org.unreal.modelrouter.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 模型供应商 DTO（v2.11.0）
 *
 * <p>apiKey 永不出现在本 DTO 中（仅创建/更新请求可写）；列表用 {@code hasApiKey} 表达「已设置 Key」。
 * {@code modelCount} 为该供应商下已接入的实例数（卡片「可用 N 个模型」）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ModelProviderDTO {

    private Long id;

    /** 供应商唯一标识（前端引用） */
    private String providerUid;

    private String name;

    private String serviceType;

    private String baseUrl;

    private String path;

    private String adapter;

    /** 发现模型使用的模型列表路径（可空，空则按 path 启发式推导） */
    private String modelsPath;

    /** 状态：ACTIVE / INACTIVE */
    private String status;

    private String description;

    private Integer sortOrder;

    /** 是否已配置上游 API Key（不回传 Key 本身） */
    private boolean hasApiKey;

    /** 该供应商下已接入的模型（实例）数量 */
    private long modelCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
