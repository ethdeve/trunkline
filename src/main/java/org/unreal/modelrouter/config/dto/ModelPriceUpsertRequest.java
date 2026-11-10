/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 模型价格写入请求（v3.5 Trunkline UI 改造，接入管理「价格与额度」tab）。
 *
 * <p>按 {@code modelName} upsert：存在则更新，不存在则新建。
 * 单价口径 ¥ / 千 token；null 视为「不修改该字段」。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ModelPriceUpsertRequest {

    /** 服务分类（可选，便于按分类筛选） */
    private String serviceType;

    /** 输入单价（¥ / 千 token） */
    private BigDecimal priceInput;

    /** 输出单价（¥ / 千 token） */
    private BigDecimal priceOutput;

    /** 币种（缺省 CNY） */
    private String currency;

    /** 是否启用（缺省 true） */
    private Boolean enabled;

    /** 说明（可选） */
    private String description;

    // ==================== 额度与限速（厂商模型维度）====================
    // 语义与 PoolDefinition 一致：0 = 不限制；null = 不修改该字段。

    /** 每分钟请求速率上限（0 = 不限制） */
    private Integer rateLimitPerMinute;

    /** 每小时请求上限（0 = 不限制） */
    private Long hourlyRequestLimit;

    /** 每小时 Token 上限（0 = 不限制） */
    private Long hourlyTokenLimit;

    /** 每日请求上限（0 = 不限制） */
    private Long dailyRequestLimit;

    /** 每日 Token 上限（0 = 不限制） */
    private Long dailyTokenLimit;

    /** 每月请求上限（0 = 不限制） */
    private Long monthlyRequestLimit;

    /** 每月 Token 上限（0 = 不限制） */
    private Long monthlyTokenLimit;

    /** 配额告警阈值（0.0-1.0） */
    private Double quotaAlertThreshold;
}
