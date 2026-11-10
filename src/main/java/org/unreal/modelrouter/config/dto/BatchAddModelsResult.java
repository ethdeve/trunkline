/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量添加模型结果（v3.5.1）。
 *
 * <p>除创建数量外，携带被跳过模型的原因明细——同名实例在模型分类内全局唯一，
 * 「发现了模型却添加不上」多数情况是同名实例已由其他供应商接入，须让前端能逐条提示。
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchAddModelsResult {

    /** 实际创建成功的数量 */
    private int created;

    /** 被跳过的模型清单（含原因），永不为 null（无跳过时为空列表） */
    @Builder.Default
    private List<SkippedModel> skipped = new java.util.ArrayList<>();

    /**
     * 单条跳过明细
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkippedModel {
        /** 被跳过的模型 ID */
        private String modelId;
        /** 跳过原因（直接展示给用户） */
        private String reason;
        /** 该模型所属的模型分类（v3.5 逐模型分类后，同批次可能有多个分类） */
        private String serviceType;
    }
}
