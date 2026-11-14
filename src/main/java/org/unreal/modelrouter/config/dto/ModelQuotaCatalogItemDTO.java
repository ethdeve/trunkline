/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 厂商模型额度目录条目（开源基础能力，接入管理「额度与限速」tab）。
 *
 * <p><b>一行 = 一个厂商模型</b>。单价属商业能力，不在本条目内（企业版另行提供）。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ModelQuotaCatalogItemDTO {

    /** 条目种类：PROVIDER_MODEL（厂商模型） */
    public static final String KIND_PROVIDER_MODEL = "PROVIDER_MODEL";

    /** 厂商模型名（= model_quota 表主键 = service_instance.instance_name） */
    private String modelName;

    /** 条目种类 */
    private String kind;

    /** 所属供应商名 */
    private String providerName;

    /** 服务分类 */
    private String serviceType;

    /**
     * 代表实例主键（Long PK，= service_instance.id）。
     * 熔断/限流配置按实例粒度存储，前端据此跳转实例级配置。
     */
    private Long instanceId;

    /** 代表实例的熔断器是否已启用 */
    private Boolean circuitBreakerEnabled;

    /** 代表实例的限流器是否已启用 */
    private Boolean rateLimitEnabled;

    /** 是否启用额度配置 */
    private Boolean enabled;

    /** 说明 */
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
}
