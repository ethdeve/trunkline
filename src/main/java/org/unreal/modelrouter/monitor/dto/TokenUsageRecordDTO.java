package org.unreal.modelrouter.monitor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Token 使用量记录 DTO
 * 用于记录单次 Token 使用量
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class TokenUsageRecordDTO {

    /**
     * 追踪 ID
     */
    private String traceId;

    /**
     * 服务类型 (chat, embedding, rerank, tts, stt, imgGen 等)
     */
    private String serviceType;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 虚拟模型名（模型分组 / 池名，如「公司主力」），v3.5 Trunkline 治理新增。
     *
     * <p>请求命中的虚拟模型，是「团队用量」的归属维度；为空表示未命中任何分组
     * （历史数据或规则锁定实例等内部路径），聚合时归入「未归类」。</p>
     */
    private String virtualModel;

    /**
     * 模型提供商/适配器类型
     */
    private String provider;

    /**
     * 实例名称
     */
    private String instanceName;

    /**
     * 实例基础 URL
     */
    private String instanceUrl;

    /**
     * 输入 token 数
     */
    private Long promptTokens;

    /**
     * 输出 token 数
     */
    private Long completionTokens;

    /**
     * 总 token 数
     */
    private Long totalTokens;

    /**
     * API Key ID
     */
    private String apiKeyId;

    /**
     * 归属租户（多租户：调用打标）。
     *
     * <p>在业务链路的 DTO 构建点从 {@code TenantContextHolder} 快照，随 DTO 透传到
     * 异步写入线程（调用历史走独立消费线程、用量走 subscribe 独立订阅，均读不到
     * 实时 ThreadLocal）。为空时实体侧回落 {@code default}。</p>
     */
    private String tenantId;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 客户端 IP
     */
    private String clientIp;

    /**
     * 是否成功
     */
    private Boolean isSuccess;

    /**
     * 错误代码
     */
    private String errorCode;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 响应时间 (毫秒)
     */
    private Long responseTimeMs;

    /**
     * 请求时间
     */
    private LocalDateTime occurredAt;

    /**
     * 元数据
     */
    private String metadata;

    /**
     * 成交价快照·输入单价（¥ / 千 token），O1 价格固化。
     *
     * <p>为 {@code null} 时由 {@code PriceResolver} 在落库时点取价固化；
     * 企业链路可显式携带（如从请求上下文透传议价结果），显式值优先。</p>
     */
    private java.math.BigDecimal priceInputSnapshot;

    /**
     * 成交价快照·输出单价（¥ / 千 token），O1 价格固化（语义同 {@link #priceInputSnapshot}）。
     */
    private java.math.BigDecimal priceOutputSnapshot;

    /**
     * 价格版本标识（如 {@code flat}）；显式快照时由调用方给出。
     */
    private String priceVersion;

    /**
     * 价格缺失标记（{@code null} 视为 {@code false}）：落库时无价可固 → true，
     * 账单侧单列「待定价」（未定价 ≠ ¥0）。
     */
    private Boolean priceMissing;
}
