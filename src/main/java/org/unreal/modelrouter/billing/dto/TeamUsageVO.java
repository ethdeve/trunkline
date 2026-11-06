package org.unreal.modelrouter.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 团队用量 VO（v3.5 Trunkline UI 改造，「团队用量」）。
 *
 * <p>两个维度：</p>
 * <ul>
 *   <li>{@code COMPANY} —— 全公司：按人汇总（每人一组，附其部门）；</li>
 *   <li>{@code DEPARTMENT} —— 按部门：{@code jwt_accounts.department} 分组汇总。</li>
 * </ul>
 *
 * <p>费用口径与「我的账单」一致：{@code prompt/1000 × priceInput + completion/1000 × priceOutput}，
 * 单价取自 {@code model_price}（¥ / 千 token），未配置价格按 ¥0 计。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class TeamUsageVO {

    /** 维度：COMPANY（全公司，按人）/ DEPARTMENT（按部门） */
    public static final String DIMENSION_COMPANY = "COMPANY";
    public static final String DIMENSION_DEPARTMENT = "DEPARTMENT";

    /** 未分配部门的归口名（账号未填部门 / 非账号发起的调用） */
    public static final String UNASSIGNED_DEPARTMENT = "未分配部门";

    /** 未命中虚拟模型的归口名（历史数据 / 规则锁定实例等内部路径） */
    public static final String UNASSIGNED_VIRTUAL_MODEL = "未归类";

    /** 「全公司」汇总卡的标题 */
    public static final String COMPANY_LABEL = "全公司";

    /** 月份（yyyy-MM） */
    private String month;

    /** 币种（默认 CNY） */
    private String currency;

    /** 统计维度 */
    private String dimension;

    /** 筛选的部门（按部门维度时可能有值） */
    private String departmentFilter;

    /** 总花费（¥） */
    private BigDecimal totalAmount;

    /** 总 token */
    private long totalTokens;

    /** 输入 token */
    private long promptTokens;

    /** 输出 token */
    private long completionTokens;

    /** 总请求数 */
    private long requestCount;

    /** 参与统计的人数 */
    private int userCount;

    /** 成功调用数（用于算成功率） */
    private long successCount;

    /** 失败调用数（用于算成功率） */
    private long failedCount;

    /** 成功率（0~1，无调用时为 0） */
    private double successRate;

    /** 分组明细（按部门 或 按人） */
    private List<GroupUsage> groups;

    /** 按模型明细 */
    private List<ModelUsage> byModel;

    /**
     * 按<b>虚拟模型</b>（模型分组）明细——员工看到的对外名字（「公司主力」「便宜日常」）。
     *
     * <p>这是「团队用量」的主展示维度；{@link #byModel} 是其下钻明细（实际落到的厂商模型）。</p>
     */
    private List<VirtualModelUsage> byVirtualModel;

    /** 按用途类型分布（chat / embedding / rerank / tts / stt / imgGen） */
    private List<ServiceTypeUsage> byServiceType;

    /**
     * 部门汇总卡（每个部门一张，含全公司汇总）。
     *
     * <p>与 {@link #groups} 的区别：{@code groups} 受「维度 / 部门筛选」影响，
     * 而本列表始终给出<b>全量</b>部门概览，供页面顶部卡片直接渲染。</p>
     */
    private List<DepartmentSummary> departments;

    /**
     * 分组用量（部门维度 / 人员维度通用）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupUsage {

        /** 分组键：部门名 或 用户名 */
        private String key;

        /** 展示名 */
        private String label;

        /** 该组人数（部门维度=部门人数；人员维度=1） */
        private int userCount;

        /** 请求数 */
        private long requestCount;

        /** 输入 token */
        private long promptTokens;

        /** 输出 token */
        private long completionTokens;

        /** 总 token */
        private long totalTokens;

        /** 花费（¥） */
        private BigDecimal amount;
    }

    /**
     * 按虚拟模型（模型分组）的用量明细。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VirtualModelUsage {

        /** 虚拟模型名（模型分组 / 池名，如「公司主力」）；未归类时为「未归类」 */
        private String virtualModel;

        /** 展示名（虚拟模型名本身即展示名，保留字段便于后续做别名映射） */
        private String label;

        /** 覆盖的厂商模型数（该虚拟模型背后挂了几个真实模型） */
        private int modelCount;

        /** 请求数 */
        private long requestCount;

        /** 输入 token */
        private long promptTokens;

        /** 输出 token */
        private long completionTokens;

        /** 总 token */
        private long totalTokens;

        /** 花费（¥） */
        private BigDecimal amount;

        /** 占比（0~1，相对当月总花费） */
        private double amountRatio;

        /** 下钻：该虚拟模型实际落到的厂商模型明细 */
        private List<ModelUsage> models;
    }

    /**
     * 按用途类型（服务类型）的用量分布。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceTypeUsage {

        /** 服务类型（chat / embedding / rerank / tts / stt / imgGen） */
        private String serviceType;

        /** 人话化展示名（聊天对话 / 知识库嵌入 / 文档排序 / 语音 / 图片生成） */
        private String label;

        /** 请求数 */
        private long requestCount;

        /** 总 token */
        private long totalTokens;

        /** 花费（¥） */
        private BigDecimal amount;

        /** 占比（0~1，相对当月总花费） */
        private double amountRatio;
    }

    /**
     * 部门汇总卡。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepartmentSummary {

        /** 部门名（「未分配部门」表示账号未填部门） */
        private String department;

        /** 该部门人数 */
        private int userCount;

        /** 请求数 */
        private long requestCount;

        /** 总 token */
        private long totalTokens;

        /** 花费（¥） */
        private BigDecimal amount;

        /** 占比（0~1，相对全公司总花费） */
        private double amountRatio;

        /** 是否为「全公司」汇总卡（不是真实部门） */
        private boolean company;
    }

    /**
     * 按模型的用量明细。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelUsage {

        /** 厂商模型名（账单按厂商模型计价，即实际落到的已接入模型实例名） */
        private String modelName;

        /** 请求数 */
        private long requestCount;

        /** 输入 token */
        private long promptTokens;

        /** 输出 token */
        private long completionTokens;

        /** 总 token */
        private long totalTokens;

        /** 花费（¥） */
        private BigDecimal amount;
    }
}
