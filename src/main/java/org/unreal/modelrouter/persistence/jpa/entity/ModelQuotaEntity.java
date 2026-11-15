/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 厂商模型额度与限速实体（开源基础能力，v3.5 Trunkline UI 改造「额度与限速」tab）。
 *
 * <p><b>一行 = 一个厂商模型</b>（主键 {@code model_name} = 厂商模型名 =
 * {@code service_instance.instance_name}）。本实体只承载<b>额度与限速</b>配置——
 * 单价属商业能力，移至企业版 {@code model_price} 表（见企业仓）。</p>
 *
 * <p>额度与限速是<b>针对厂商模型</b>的配置（虚拟模型 / 模型分组的额度限速落在 pool 定义上）。
 * 语义与 {@code PoolDefinition} 同构（0 = 不限制）。</p>
 *
 * <p>全部列由合并基线 {@code V1__baseline.sql} 建成（见 {@code db/migration/<方言>/}）。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TenantShared(reason = "厂商模型额度与限速为全局配置，uk_model_quota_model_name 全局唯一，按租户拆分会破坏共享语义")
@Entity
@Table(name = "model_quota", uniqueConstraints = {
        @UniqueConstraint(name = "uk_model_quota_model_name", columnNames = {"model_name"})
}, indexes = {
        @Index(name = "idx_model_quota_service_type", columnList = "service_type")
})
public class ModelQuotaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 厂商模型名（= service_instance.instance_name，如 gpt-4o / claude-3-5-sonnet） */
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    /** 服务分类（chat/embedding/imgGen…，可选，便于按分类筛选） */
    @Column(name = "service_type", length = 64)
    private String serviceType;

    /** 是否启用（停用后该厂商模型的额度配置不生效） */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** 说明（可选） */
    @Column(name = "description", length = 512)
    private String description;

    // ==================== 额度与限速（与 PoolDefinition 同构）====================
    // 语义一致：0 = 不限制。

    /** 每分钟请求速率上限（0 = 不限制） */
    @Column(name = "rate_limit_per_minute")
    @Builder.Default
    private Integer rateLimitPerMinute = 0;

    /** 每小时请求上限（0 = 不限制） */
    @Column(name = "hourly_request_limit")
    @Builder.Default
    private Long hourlyRequestLimit = 0L;

    /** 每小时 Token 上限（0 = 不限制） */
    @Column(name = "hourly_token_limit")
    @Builder.Default
    private Long hourlyTokenLimit = 0L;

    /** 每日请求上限（0 = 不限制） */
    @Column(name = "daily_request_limit")
    @Builder.Default
    private Long dailyRequestLimit = 0L;

    /** 每日 Token 上限（0 = 不限制） */
    @Column(name = "daily_token_limit")
    @Builder.Default
    private Long dailyTokenLimit = 0L;

    /** 每月请求上限（0 = 不限制） */
    @Column(name = "monthly_request_limit")
    @Builder.Default
    private Long monthlyRequestLimit = 0L;

    /** 每月 Token 上限（0 = 不限制） */
    @Column(name = "monthly_token_limit")
    @Builder.Default
    private Long monthlyTokenLimit = 0L;

    /** 配额告警阈值（0.0-1.0），达到此比例时触发告警，默认 0.8 */
    @Column(name = "quota_alert_threshold")
    @Builder.Default
    private Double quotaAlertThreshold = 0.8;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 把本行的额度与限速配置打包为限额集合（供配额执行层的「模型维度」判定使用）。
     *
     * <p>与 {@code PoolDefinition.toQuotaLimitSet()} 完全同构：各窗口取值 {@code <= 0}
     * 表示不限制；{@code MINUTE} 窗口只有请求数上限。</p>
     *
     * @return 限额集合（各窗口 {@code <= 0} 表示不限制）
     */
    public org.unreal.modelrouter.auth.security.quota.QuotaLimitSet toQuotaLimitSet() {
        return new org.unreal.modelrouter.auth.security.quota.QuotaLimitSet(
                rateLimitPerMinute == null ? 0L : rateLimitPerMinute.longValue(),
                hourlyRequestLimit == null ? 0L : hourlyRequestLimit,
                hourlyTokenLimit == null ? 0L : hourlyTokenLimit,
                dailyRequestLimit == null ? 0L : dailyRequestLimit,
                dailyTokenLimit == null ? 0L : dailyTokenLimit,
                monthlyRequestLimit == null ? 0L : monthlyRequestLimit,
                monthlyTokenLimit == null ? 0L : monthlyTokenLimit);
    }
}
