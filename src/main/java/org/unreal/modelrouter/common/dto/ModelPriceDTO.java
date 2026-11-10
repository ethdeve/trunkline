/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型价格 DTO（v3.5 Trunkline UI 改造，接入管理「价格与额度」tab）。
 *
 * <p>单价口径：¥ / 千 token。价格来源一期手工配置（用户决策 #2）。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ModelPriceDTO {

    private Long id;

    /** 模型名 / 虚拟模型名（对外路由名） */
    private String modelName;

    /** 服务分类（chat/embedding/imgGen…，可选） */
    private String serviceType;

    /** 输入单价（¥ / 千 token） */
    private BigDecimal priceInput;

    /** 输出单价（¥ / 千 token） */
    private BigDecimal priceOutput;

    /** 币种（默认 CNY） */
    private String currency;

    /** 是否启用（停用后不参与账单计价） */
    private Boolean enabled;

    /** 说明（可选） */
    private String description;

    // ==================== 额度与限速（厂商模型维度）====================

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

    /** 配额告警阈值（0.0-1.0），默认 0.8 */
    private Double quotaAlertThreshold;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
