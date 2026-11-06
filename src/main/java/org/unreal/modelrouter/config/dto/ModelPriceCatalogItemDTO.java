package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 模型价格与额度目录条目（v3.5 Trunkline UI 改造，接入管理「价格与额度」tab）。
 *
 * <p><b>一行 = 一个厂商模型</b>。需求：「价格与额度」与「额度与限速」复用一张表，
 * 且额度与限速是<b>针对厂商模型</b>的配置——故本条目同时承载「价格」与「额度/限速」两组字段，
 * 前端渲染一张表即可完成两类配置。</p>
 *
 * <p>计价口径：账单按<b>厂商模型名</b>计价（{@code token_usage.instance_name}），
 * 本条目的 {@code modelName} 即厂商模型名，与 {@code model_price} 表主键一致。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ModelPriceCatalogItemDTO {

    /** 条目种类：PROVIDER_MODEL（厂商模型） */
    public static final String KIND_PROVIDER_MODEL = "PROVIDER_MODEL";

    /** 厂商模型名（= model_price 表主键 = service_instance.instance_name） */
    private String modelName;

    /** 条目种类 */
    private String kind;

    /** 所属供应商名 */
    private String providerName;

    /** 服务分类 */
    private String serviceType;

    /**
     * 代表实例主键（Long PK，= service_instance.id）。
     * 熔断/限流配置按实例粒度存储（instance_circuit_breaker / instance_rate_limit 的 instance_id），
     * 前端据此跳转实例级配置，并在「价格与额度」表格直接渲染「熔断」「限流」两列开关。
     * 同名模型合并多实例时取代表实例（详见 {@code ModelPriceService.catalog}）。
     */
    private Long instanceId;

    /**
     * 代表实例的熔断器是否已启用（来自 instance_circuit_breaker.enabled）。
     * 表格「熔断」列开关的当前状态；无配置时默认 false（关闭）。
     */
    private Boolean circuitBreakerEnabled;

    /**
     * 代表实例的限流器是否已启用（来自 instance_rate_limit.enabled）。
     * 表格「限流」列开关的当前状态；无配置时默认 false（关闭）。
     */
    private Boolean rateLimitEnabled;

    /** 是否已配置价格 */
    private Boolean hasPrice;

    /** 输入单价（¥ / 千 token）；未配置为 0 */
    private BigDecimal priceInput;

    /** 输出单价（¥ / 千 token）；未配置为 0 */
    private BigDecimal priceOutput;

    /** 币种（默认 CNY） */
    private String currency;

    /** 是否启用 */
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
