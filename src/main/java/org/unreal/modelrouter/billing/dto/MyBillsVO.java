package org.unreal.modelrouter.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「我的账单」月度聚合响应 VO（v3.5 Trunkline UI 改造，§4.3）。
 *
 * <p>月度汇总 + 按厂商模型明细。金额 = {@code Σ(promptTokens × priceInput + completionTokens × priceOutput)}
 * （单价按 ¥ / 千 token，故除以 1000）。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MyBillsVO {

    /** 账单月份（YYYY-MM） */
    private String month;

    /** 币种 */
    private String currency;

    /** 本月总花费（¥） */
    private BigDecimal totalAmount;

    /** 本月总 token */
    private Long totalTokens;

    /** 本月输入 token */
    private Long promptTokens;

    /** 本月输出 token */
    private Long completionTokens;

    /** 本月成功调用次数 */
    private Long requestCount;

    /**
     * 上月同期花费（¥）（v3.5 Trunkline UI 改造：账单页 KPI 卡「上月同期」）。
     *
     * <p>用于给员工一个「比上月多花/少花」的参照。取上一个自然月的总花费；
     * 上月无记录时为 0。</p>
     */
    private BigDecimal previousMonthAmount;

    /**
     * 上月同期调用次数（v3.5 Trunkline UI 改造）。
     */
    private Long previousMonthRequestCount;

    /** 按厂商模型明细（金额降序） */
    private List<ModelBill> byModel;

    /**
     * 按天 × 虚拟模型的交叉表明细（v3.5 Trunkline UI 改造：账单页按原型改为「按天汇总」）。
     *
     * <p>员工视角不该关心背后是哪个厂商模型，因此按<b>虚拟模型</b>分组；
     * 计价仍按厂商模型单价算好后汇总到虚拟模型。</p>
     *
     * <p>按日期倒序（最近的一天在前），与原型一致。</p>
     */
    private List<DailyBill> byDay;

    /**
     * 某一天按虚拟模型分组的花费明细（交叉表的一行）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DailyBill {

        /** 日期（YYYY-MM-DD） */
        private String usageDate;

        /** 当日总花费（¥） */
        private BigDecimal amount;

        /** 当日调用次数 */
        private Long requestCount;

        /** 当日总 token */
        private Long totalTokens;

        /** 当日各虚拟模型的花费（键 = 虚拟模型名，值 = ¥） */
        private java.util.Map<String, BigDecimal> byVirtualModel;
    }

    /**
     * 按厂商模型的账单明细。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelBill {

        /** 厂商模型名（账单按厂商模型计价，即实际落到的已接入模型实例名） */
        private String modelName;

        /** 输入 token */
        private Long promptTokens;

        /** 输出 token */
        private Long completionTokens;

        /** 总 token */
        private Long totalTokens;

        /** 调用次数 */
        private Long requestCount;

        /** 输入单价（¥ / 千 token） */
        private BigDecimal priceInput;

        /** 输出单价（¥ / 千 token） */
        private BigDecimal priceOutput;

        /** 该项花费（¥） */
        private BigDecimal amount;
    }
}
