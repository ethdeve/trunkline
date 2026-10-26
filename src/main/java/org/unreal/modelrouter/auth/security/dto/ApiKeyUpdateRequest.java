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
 * 更新 API Key 请求 DTO
 * 注意：keyValue 不可更新
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiKeyUpdateRequest {

    /**
     * API Key 描述信息
     */
    @Size(max = 128, message = "描述长度不能超过128字符")
    private String description;

    /**
     * 权限列表
     */
    private List<String> permissions;

    /**
     * 可访问的模型/实例白名单（按路由 model 参数值匹配）。
     * 为空表示不限制。
     */
    private List<String> allowedModels;

    /**
     * 可访问的模型分组（资源池 / pool）白名单（按分组名匹配）。
     */
    private List<String> allowedGroups;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime expiresAt;

    /**
     * 允许使用的 IP 白名单
     */
    private List<String> allowedIpAddresses;

    /**
     * 每日请求上限（0 表示无限制）
     */
    private Long dailyRequestLimit;

    /**
     * 每日 Token 使用上限（0 表示无限制）
     */
    private Long dailyTokenLimit;

    /**
     * 每小时请求上限（0 表示无限制，对齐上游 3.4.0 多窗口配额）
     */
    private Long hourlyRequestLimit;

    /**
     * 每小时 Token 使用上限（0 表示无限制）
     */
    private Long hourlyTokenLimit;

    /**
     * 每月请求上限（0 表示无限制）
     */
    private Long monthlyRequestLimit;

    /**
     * 每月 Token 使用上限（0 表示无限制）
     */
    private Long monthlyTokenLimit;

    /**
     * 每分钟请求速率限制（0 表示无限制）
     */
    private Integer rateLimitPerMinute;

    /**
     * 配额告警阈值（0.0-1.0，达到此比例时触发告警）
     */
    private Double quotaAlertThreshold;

    /**
     * 密钥轮换周期（天数），0 表示不自动轮换
     */
    private Integer rotationPeriodDays;
}