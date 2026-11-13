package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Token 使用量统计实体类
 * 记录每次 AI 模型调用的 token 使用情况
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "token_usage", indexes = {
    @Index(name = "idx_token_usage_model", columnList = "model_name"),
    @Index(name = "idx_token_usage_service_type", columnList = "service_type"),
    @Index(name = "idx_token_usage_occurred_at", columnList = "occurred_at"),
    @Index(name = "idx_token_usage_api_key", columnList = "api_key_id"),
    @Index(name = "idx_token_usage_trace_id", columnList = "trace_id"),
    @Index(name = "idx_token_usage_virtual_model", columnList = "virtual_model"),
    @Index(name = "idx_token_usage_tenant", columnList = "tenant_id")
})
@Filter(name = "tenantFilter")
public class TokenUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 追踪 ID
     */
    @Column(name = "trace_id", length = 100)
    private String traceId;

    /**
     * 服务类型 (chat, embedding, rerank, tts, stt, imgGen 等)
     */
    @Column(name = "service_type", nullable = false, length = 50)
    private String serviceType;

    /**
     * 模型名称
     */
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    /**
     * 虚拟模型名（模型分组 / 池名，如「公司主力」），v3.5 Trunkline 治理新增。
     *
     * <p><b>与 modelName / instanceName 的分工</b>：</p>
     * <ul>
     *   <li>{@code virtualModel} —— 请求命中的<b>虚拟模型</b>，是员工填进 AI 工具里的对外名字，
     *       也是「团队用量」的归属维度；一次请求只命中一个虚拟模型，归属无歧义。</li>
     *   <li>{@code modelName} —— 上游响应体回传的模型名（上游原样返回，可能带版本后缀）。</li>
     *   <li>{@code instanceName} —— 实际路由到的<b>厂商模型实例</b>，是计价依据
     *       （对齐 {@code model_price.model_name}）。</li>
     * </ul>
     *
     * <p>历史数据为空（老版本落库时无此字段），聚合时归入「未归类」而不丢数据。</p>
     */
    @Column(name = "virtual_model", length = 255)
    private String virtualModel;

    /**
     * 模型提供商/适配器类型 (gpustack, ollama, vllm 等)
     */
    @Column(name = "provider", length = 100)
    private String provider;

    /**
     * 实例名称
     */
    @Column(name = "instance_name", length = 255)
    private String instanceName;

    /**
     * 实例基础 URL
     */
    @Column(name = "instance_url", length = 500)
    private String instanceUrl;

    /**
     * 输入 token 数 (prompt tokens)
     */
    @Column(name = "prompt_tokens")
    @Builder.Default
    private Long promptTokens = 0L;

    /**
     * 输出 token 数 (completion tokens)
     */
    @Column(name = "completion_tokens")
    @Builder.Default
    private Long completionTokens = 0L;

    /**
     * 总 token 数
     */
    @Column(name = "total_tokens", nullable = false)
    @Builder.Default
    private Long totalTokens = 0L;

    /**
     * API Key ID (如果使用了 API Key 认证)
     */
    @Column(name = "api_key_id", length = 255)
    private String apiKeyId;

    /**
     * 用户 ID (如果已认证)
     */
    @Column(name = "user_id", length = 255)
    private String userId;

    /**
     * 客户端 IP
     */
    @Column(name = "client_ip", length = 50)
    private String clientIp;

    /**
     * 请求是否成功
     */
    @Column(name = "is_success")
    private Boolean isSuccess;

    /**
     * 错误代码（如果失败）
     */
    @Column(name = "error_code", length = 100)
    private String errorCode;

    /**
     * 错误消息（如果失败）
     */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /**
     * 响应时间 (毫秒)
     */
    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    /**
     * 请求时间
     */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 星期 (用于按周统计，0-6，0 表示周日)
     */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    /**
     * 周数 (用于按周统计，ISO-8601 周数)
     */
    @Column(name = "week_of_year")
    private Integer weekOfYear;

    /**
     * 月份 (用于按月统计，1-12)
     */
    @Column(name = "month_num")
    private Integer month;

    /**
     * 年份
     */
    @Column(name = "year_num")
    private Integer year;

    /**
     * 日期 (用于按日统计，格式：YYYY-MM-DD)
     */
    @Column(name = "usage_date", length = 10)
    private String usageDate;

    /**
     * 小时 (0-23，用于按小时统计)
     */
    @Column(name = "hour_num")
    private Integer hour;

    /**
     * 元数据（JSON 格式，存储额外信息）
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    /**
     * 成交价快照·输入单价（¥ / 千 token），O1 价格固化。
     *
     * <p>落库时点由 {@code PriceResolver} 取价写入；账单聚合<b>只读本快照</b>、
     * 不再查当前 {@code model_price}，保证改价不影响历史账单金额。
     * 未定价（{@code priceMissing=true}）或历史迁移行为 NULL。</p>
     */
    @Column(name = "price_input_snapshot", precision = 18, scale = 6)
    private BigDecimal priceInputSnapshot;

    /**
     * 成交价快照·输出单价（¥ / 千 token），O1 价格固化（语义同 {@link #priceInputSnapshot}）。
     */
    @Column(name = "price_output_snapshot", precision = 18, scale = 6)
    private BigDecimal priceOutputSnapshot;

    /**
     * 价格版本标识（如 {@code flat}），标记该笔用量按哪一代计价策略成交。
     */
    @Column(name = "price_version", length = 64)
    private String priceVersion;

    /**
     * 价格缺失标记：{@code true} = 落库时无价可固（未定价模型）或历史迁移无法回填。
     *
     * <p><b>未定价 ≠ ¥0</b>（O2）：缺失价格的用量在账单侧单列「待定价」，
     * 绝不静默按 ¥0 混入总额。</p>
     */
    @Column(name = "price_missing")
    private Boolean priceMissing;

    /**
     * 在保存前自动设置时间相关字段
     */
    @PrePersist
    public void prePersist() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        if (dayOfWeek == null) {
            dayOfWeek = occurredAt.getDayOfWeek().getValue() % 7; // 0=周日
        }
        if (weekOfYear == null) {
            weekOfYear = occurredAt.get(java.time.temporal.WeekFields.of(java.util.Locale.CHINA).weekOfWeekBasedYear());
        }
        if (month == null) {
            month = occurredAt.getMonthValue();
        }
        if (year == null) {
            year = occurredAt.getYear();
        }
        if (usageDate == null) {
            usageDate = occurredAt.toLocalDate().toString();
        }
        if (hour == null) {
            hour = occurredAt.getHour();
        }
    }

    /** 归属租户（多租户预埋，S2 任务 35/36；社区版恒为 default） */
    @Builder.Default
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

}
