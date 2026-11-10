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
 * 「我的额度」页的近期用量视图（v3.5 Trunkline UI 改造，§4.1）。
 *
 * <p>回答两个问题：「我最近主要在用哪个虚拟模型」与「每天花了多少钱」。</p>
 *
 * <p>金额按<b>厂商模型</b>单价算好后汇总到<b>虚拟模型</b>——员工看到的是虚拟模型名，
 * 不必关心背后是哪个厂商。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MyRecentUsageVO {

    /** 统计区间的起始日期（含，YYYY-MM-DD） */
    private String startDate;

    /** 统计区间的结束日期（含，YYYY-MM-DD） */
    private String endDate;

    /** 区间内的总花费（¥） */
    private BigDecimal totalAmount;

    /** 区间内的总 token */
    private Long totalTokens;

    /** 区间内的调用次数 */
    private Long requestCount;

    /** 按虚拟模型聚合的用量（按花费降序） */
    private List<VirtualModelUsage> byVirtualModel;

    /**
     * 按虚拟模型的用量（额度页「本周最常用」卡片用）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VirtualModelUsage {

        /** 虚拟模型名（未命中任何虚拟模型的历史数据归入「未归类」） */
        private String virtualModel;

        /** 调用次数 */
        private Long requestCount;

        /** 总 token */
        private Long totalTokens;

        /** 花费（¥） */
        private BigDecimal amount;

        /** 占比（0~1，相对区间总花费） */
        private Double amountRatio;
    }
}
