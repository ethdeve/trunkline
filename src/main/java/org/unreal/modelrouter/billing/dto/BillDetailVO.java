/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 逐笔账单明细（「我的账单 → 账单明细」下钻）。
 *
 * <p>金额口径与 {@link MyBillsVO} 完全一致：只读落库时点固化的成交价快照，
 * {@code amount = (promptTokens × priceInputSnapshot + completionTokens × priceOutputSnapshot) / 1000}。
 * 未定价行（{@code priced=false}）金额恒为 0，但仍在明细里可见，绝不静默丢弃。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillDetailVO {

    /** 追踪 ID：与 token_usage / enterprise_billing_ledger 关联的唯一键 */
    private String traceId;

    /** 请求发生时间 */
    private LocalDateTime occurredAt;

    /** 调用者（JWT 用户名 / 密钥持有人） */
    private String userId;

    /** 虚拟模型（员工看到的对外名字） */
    private String virtualModel;

    /** 厂商模型实例（计价键，对齐 model_price.model_name） */
    private String instanceName;

    /** 上游回传模型名 */
    private String modelName;

    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;

    /** 缓存命中 token 数（从落库 metadata 解析；无缓存命中为 0） */
    private Long cacheTokens;

    /** 调用者真实姓名（JWT 账号 name；未配置时为空，前端回落显示 userId） */
    private String userName;

    /** 成交金额（¥）；未计价行为 0 */
    private BigDecimal amount;

    /** 是否有可计价的成交价快照（false = 未计价，O2：未计价 ≠ ¥0） */
    private Boolean priced;

    private BigDecimal priceInputSnapshot;
    private BigDecimal priceOutputSnapshot;
    private String priceVersion;

    /** 响应耗时（毫秒） */
    private Long responseTimeMs;
}
