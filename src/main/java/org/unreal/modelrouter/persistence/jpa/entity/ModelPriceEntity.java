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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型价格与额度实体（v3.5 Trunkline UI 改造，接入管理「价格与额度」tab）。
 *
 * <p><b>一行 = 一个厂商模型</b>（主键 {@code model_name} = 厂商模型名 =
 * {@code service_instance.instance_name}，接入厂商时实例名直接取上游模型 ID）。
 * 该行同时承载两类配置：</p>
 * <ul>
 *   <li><b>价格</b>：输入 / 输出 / 缓存三档单价（¥ / 千 token），供账单聚合
 *       {@code Σ(tokens × price)}。缓存单价用于命中缓存的 token 单独计价场景
 *       （如 Anthropic Prompt Caching）。注意：{@code token_usage} 目前只记录输入/输出
 *       token，尚无缓存 token 计量维度，故缓存单价本次仅做「可配置 / 可回显」，
 *       账单计算暂不使用本列。价格来源一期手工配置（用户决策 #2）。</li>
 *   <li><b>额度与限速</b>：分钟 / 小时 / 日 / 月 四级窗口的请求数与 Token 上限，
 *       以及配额告警阈值。语义与 {@code PoolDefinition} 同构（0 = 不限制）。
 *       这一段是<b>针对厂商模型</b>的配置——虚拟模型（原模型分组）有自己的额度与限速配置，
 *       落在 pool 定义上（{@code PoolDefinition} + group 维度限额解析器）。</li>
 * </ul>
 *
 * <p>单价默认 {@code 0}：未配置价格的模型按 ¥0 计入，前端账单页给空态提示。</p>
 *
 * <p>全部列（含缓存单价 {@code price_cache}）均由合并基线 {@code V1__baseline.sql} 建成
 * （见 {@code src/main/resources/db/migration/<方言>/}）；缓存单价列曾短暂以
 * {@code V2__model_price_cache.sql} 增量脚本存在，因项目未发版已于 2026-11-11 并入 V1，不留增量。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "model_price", uniqueConstraints = {
        @UniqueConstraint(name = "uk_model_price_model_name", columnNames = {"model_name"})
}, indexes = {
        @Index(name = "idx_model_price_service_type", columnList = "service_type")
})
public class ModelPriceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 厂商模型名（= service_instance.instance_name，如 gpt-4o / claude-3-5-sonnet） */
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    /** 服务分类（chat/embedding/imgGen…，可选，便于按分类筛选） */
    @Column(name = "service_type", length = 64)
    private String serviceType;

    /** 输入单价（¥ / 千 token） */
    @Column(name = "price_input", nullable = false, columnDefinition = "DECIMAL(12,6)")
    @Builder.Default
    private BigDecimal priceInput = BigDecimal.ZERO;

    /** 输出单价（¥ / 千 token） */
    @Column(name = "price_output", nullable = false, columnDefinition = "DECIMAL(12,6)")
    @Builder.Default
    private BigDecimal priceOutput = BigDecimal.ZERO;

    /** 缓存单价（¥ / 千 token），命中缓存的 token 单独计价时使用；未配置为 0 */
    @Column(name = "price_cache", nullable = false, columnDefinition = "DECIMAL(12,6)")
    @Builder.Default
    private BigDecimal priceCache = BigDecimal.ZERO;

    /** 币种（默认 CNY） */
    @Column(name = "currency", nullable = false, length = 8)
    @Builder.Default
    private String currency = "CNY";

    /** 是否启用（停用后不参与账单计价） */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** 说明（可选） */
    @Column(name = "description", length = 512)
    private String description;

    // ==================== 额度与限速（与 PoolDefinition 同构）====================
    // 需求：接入管理「价格与额度」与「额度与限速」复用一张表，且额度与限速是
    // <针对厂商模型>的配置（虚拟模型/模型分组的额度限速落在 pool 定义上）。
    // 语义与 PoolDefinition 完全一致：0 = 不限制。

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
