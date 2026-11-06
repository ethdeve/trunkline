package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建/更新模型供应商请求（v2.11.0）
 *
 * <p>更新语义：字段为 null 表示不修改；{@code apiKey} 为 null/空白表示保持原 Key 不变
 * （换 Key 是显式动作，见专用接口）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class CreateModelProviderRequest {

    private String name;

    /**
     * 预设唯一标识（预设目录一键启用时传入，如 "deepseek"）。
     * 留空则自动生成 UUID；重复传入已存在的 uid 会报 PROVIDER_UID_DUPLICATED（前端转「更新 Key」流程）。
     */
    private String providerUid;

    /** 服务类型（chat / embedding / rerank ...），该供应商下添加的模型默认落入此类型 */
    private String serviceType;

    private String baseUrl;

    private String path;

    private String adapter;

    /** 上游 API Key（创建必填；更新留空=保持原值） */
    private String apiKey;

    /** 发现模型使用的模型列表路径（可选） */
    private String modelsPath;

    /** 状态：ACTIVE / INACTIVE（创建可空，默认 ACTIVE） */
    private String status;

    private String description;

    private Integer sortOrder;

    /**
     * 批量添加模型请求（v2.11.0）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class BatchAddModelsRequest {
        /** 目标服务类型（可空，默认取供应商的 serviceType） */
        private String serviceType;

        /** 待添加模型列表 */
        private List<BatchModelItem> items;
    }

    /**
     * 批量添加的单项（v2.11.0）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class BatchModelItem {
        /** 上游模型 ID（调用时填写的 model 参数，须与上游真实模型名一致） */
        private String modelId;

        /** 显示名称（选填，写入实例 tags.alias） */
        private String displayName;

        /**
         * 该模型自己的模型分类（可空）。
         *
         * <p>v3.5：接入向导把「模型分类」下拉从供应商级下沉到模型级——同一个厂商可能同时提供
         * 聊天 / 向量 / 图片模型，因此分类由每个模型各自决定。留空表示沿用批次级
         * {@link BatchAddModelsRequest#serviceType}。</p>
         */
        private String serviceType;
    }
}
