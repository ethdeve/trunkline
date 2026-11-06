package org.unreal.modelrouter.auth.security.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API Key 信息 VO（不包含敏感的 keyValue）
 * 用于 API Key 列表和详情查询返回
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKeyVO {

    /**
     * API Key 唯一标识符
     */
    private String keyId;

    /**
     * API Key 描述信息
     */
    private String description;

    /**
     * 创建者用户名
     */
    private String createdBy;

    /**
     * 密钥持有人（分配给谁）。为空表示未指定，回落显示 {@link #createdBy}。
     */
    private String holder;

    /**
     * 创建者 IP 地址
     */
    private String creatorIpAddress;

    /**
     * 权限列表
     */
    private List<String> permissions;

    /**
     * 可访问的模型分组（资源池 / pool）白名单（按分组名匹配）。
     */
    private List<String> allowedGroups;

    /**
     * 该密钥可见分组的扩展信息（P0-L：员工侧不再需要拉全公司资源池即可展示友好名与均价）。
     *
     * <p>由「我的密钥」接口下发，元素含分组名 / 友好名 / 服务类型 / 加权均价，
     * <b>不含</b>成员明细与实例地址等内部字段。白名单为空（= 不限制模型）的密钥，
     * 此处给出全部<b>启用中</b>分组的展示信息 —— 该密钥本就可以调用全部分组，
     * 因此不构成越权；白名单非空时只含白名单内的分组。</p>
     */
    private List<ApiKeyModelScopeVO> modelScope;

    /**
     * 该密钥的<b>密钥级合计均价</b>（¥ / 千 token）—— 应修8 口径统一。
     *
     * <p><b>为什么需要它</b>：此前均价口径四处不一致（分组内成员加权 / 跨分组算术平均 /
     * 前端再平均 / 全部密钥页加权）。员工侧「我的密钥」页拿这个数字当金额估算分母，
     * 口径分叉会让员工看到的金额与真实计价对不上。现改为<b>服务端唯一下发</b>，
     * 前端只显示不计算。</p>
     *
     * <p><b>口径</b>：对白名单内<b>已配置价格</b>的分组，把所有可达成员的单价
     * {@code (输入价 + 输出价) / 2} 按成员权重加权平均（与 {@code BillingService}
     * 计价口径一致）。白名单为空（= 不限制模型）时返回 {@code null} —— 此时聚合的是
     * 全公司所有分组，属于应修7 界定的「全公司成本水平」，不下发。</p>
     *
     * <p>组内成员均未配价、或无任何可达成员时返回 {@code null}。</p>
     */
    private Double mixedPrice;

    /**
     * 允许使用的 IP 白名单（为空表示不限制）。
     * 2026-10-23 补充：此前 VO 漏出该字段，导致前端编辑密钥时拿不到现值、保存即清空。
     */
    private List<String> allowedIpAddresses;

    /**
     * 脱敏后的密钥展示串（v3.5 Trunkline UI 改造：「我的密钥」页要显示「密钥前缀」）。
     *
     * <p>形如 {@code sk-9fK2••••••••4aQz}——保留开头 {@code sk-} 与首 4 位、末 4 位，
     * 中间以圆点遮蔽。<b>不是完整密钥</b>，无法用于认证；仅供员工辨认「我名下有几把钥匙」。</p>
     *
     * <p>完整密钥只在创建时一次性返回（{@code ApiKeyCreationVO.keyValue}），此后任何接口都不再暴露。</p>
     */
    private String keyMasked;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 是否已过期
     */
    private boolean expired;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime createdAt;

    /**
     * 密钥轮换周期（天数），0 表示不自动轮换
     */
    private int rotationPeriodDays;

    /**
     * 上次轮换时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime lastRotatedAt;

    /**
     * 是否需要轮换
     */
    private boolean needsRotation;

    /**
     * 过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime expiresAt;

    /**
     * 最后使用时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime lastUsedAt;

    /**
     * 总请求次数
     */
    private Long totalRequests;

    /**
     * 成功请求次数
     */
    private Long successfulRequests;

    /**
     * 失败请求次数
     */
    private Long failedRequests;

    // ===== 配额配置 =====

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
     * 配额告警阈值（0.0-1.0）
     */
    private Double quotaAlertThreshold;

    // ===== 配额使用量 =====

    /**
     * 今日请求次数
     */
    private Long todayRequestCount;

    /**
     * 今日 Token 使用量
     */
    private Long todayTokenUsage;

    /**
     * 是否触发配额告警
     */
    private boolean quotaAlertTriggered;

    /**
     * 剩余有效天数（-1 表示已过期，null 表示永不过期）
     */
    private Integer remainingDays;

    /**
     * 计算剩余天数
     */
    public void calculateRemainingDays() {
        if (expiresAt == null) {
            remainingDays = null;
        } else {
            long diffDays = java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), expiresAt);
            remainingDays = diffDays < 0 ? -1 : (int) diffDays;
        }
    }
}