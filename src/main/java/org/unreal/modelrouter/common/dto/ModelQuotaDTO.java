/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 厂商模型额度 DTO（开源基础能力，接入管理「额度与限速」tab）。
 *
 * <p>仅包含额度与限速字段；单价属商业能力，由企业版 {@code ModelPriceDTO} 承载。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ModelQuotaDTO {

    private Long id;

    /** 厂商模型名（= service_instance.instance_name） */
    private String modelName;

    /** 服务分类（chat/embedding/imgGen…，可选） */
    private String serviceType;

    /** 是否启用 */
    private Boolean enabled;

    /** 说明（可选） */
    private String description;

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
