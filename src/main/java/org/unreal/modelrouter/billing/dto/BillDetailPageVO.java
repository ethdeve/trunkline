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
import java.util.List;

/**
 * 逐笔账单明细分页响应。
 *
 * <p>{@code totalAmount} 为当前筛选条件下的全量已定价金额（不受分页影响），
 * 与「我的账单」月总额同口径，前端可据此断言「月总额 = 逐笔之和」。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillDetailPageVO {

    private List<BillDetailVO> items;
    private long total;
    private int page;
    private int size;

    /** 当前筛选条件下的已定价金额合计（¥，全量非本页） */
    private BigDecimal totalAmount;

    /** 当前筛选条件下的成功调用次数（全量非本页） */
    private long requestCount;

    /** 待定价笔数（全量非本页） */
    private long unpricedCount;
}
