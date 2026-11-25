package org.unreal.modelrouter.auth.security.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建 API Key 请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiKeyCreateRequest {

    /**
     * API Key ID（可选，如果不提供则自动生成）
     */
    @Size(max = 64, message = "密钥ID长度不能超过64字符")
    private String keyId;

    /**
     * API Key 描述信息
     */
    @Size(max = 128, message = "描述长度不能超过128字符")
    private String description;

    /**
     * 密钥持有人（分配给谁）。
     *
     * <p>为 null / 空时归属当前登录用户（服务端从 {@code Authentication#getName()} 解析，
     * 不信任前端传参，避免越权）。ADMIN 显式指定时，服务端校验该账户存在后以其为归属，
     * 使「我的密钥」视图（{@code createdBy == principal.name}）能查到该密钥。</p>
     */
    @Size(max = 64, message = "持有人长度不能超过64字符")
    private String holder;

    /**
     * 权限列表
     * 可选值：READ, WRITE, DELETE, ADMIN
     */
    private List<String> permissions;

    /**
     * 数据密级（#9 数据分级出境）：PUBLIC / INTERNAL / CONFIDENTIAL（大小写不敏感）。
     *
     * <p>由服务端写入 {@code ApiKey.metadata.classification}，作为出境策略的信任锚；
     * 请求头自报一律不采信。为 null / 空 / 非法值时按 {@code PUBLIC} 兜底
     * （与 {@code DataClassification.fromCode()} 口径一致），不改变首版默认 PUBLIC 的行为。</p>
     */
    @Size(max = 32, message = "密级长度不能超过32字符")
    private String classification;

    /**
     * 可访问的模型分组（资源池 / pool）白名单（按分组名匹配）。
     * 为空表示不限制。
     */
    private List<String> allowedGroups;

    /**
     * 是否启用（默认启用）
     */
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 过期时间（可选，不设置则永不过期）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime expiresAt;

    /**
     * 允许使用的 IP 白名单（可选）
     */
    private List<String> allowedIpAddresses;

    /**
     * 每日请求上限（可选，0 表示无限制）
     */
    @Builder.Default
    private Long dailyRequestLimit = 0L;

    /**
     * 每日 Token 使用上限（可选，0 表示无限制）
     */
    @Builder.Default
    private Long dailyTokenLimit = 0L;

    /**
     * 每小时请求上限（0 表示无限制，对齐上游 3.4.0 多窗口配额）
     */
    @Builder.Default
    private Long hourlyRequestLimit = 0L;

    /**
     * 每小时 Token 使用上限（0 表示无限制）
     */
    @Builder.Default
    private Long hourlyTokenLimit = 0L;

    /**
     * 每月请求上限（0 表示无限制）
     */
    @Builder.Default
    private Long monthlyRequestLimit = 0L;

    /**
     * 每月 Token 使用上限（0 表示无限制）
     */
    @Builder.Default
    private Long monthlyTokenLimit = 0L;

    /**
     * 每分钟请求速率限制（可选，0 表示无限制）
     */
    @Builder.Default
    private Integer rateLimitPerMinute = 0;

    /**
     * 配额告警阈值（0.0-1.0，达到此比例时触发告警，默认 0.8 即 80%）
     */
    @Builder.Default
    private Double quotaAlertThreshold = 0.8;

    /**
     * 密钥轮换周期（天数），0 表示不自动轮换
     */
    @Builder.Default
    private Integer rotationPeriodDays = 0;
}