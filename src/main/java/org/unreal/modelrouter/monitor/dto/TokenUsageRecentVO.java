package org.unreal.modelrouter.monitor.dto;

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
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;

import java.time.LocalDateTime;

/**
 * Token 用量「最近记录」对外 VO（P0-L 越权泄漏收敛）。
 *
 * <p><b>为什么不能再直接返回 JPA 实体</b>：{@link TokenUsageEntity} 是全字段落库的领域实体，
 * 其中 {@code clientIp}（调用方 IP）、{@code instanceUrl}（实例基础 URL）、
 * {@code errorMessage}（上游错误原文）、{@code metadata}（JSON 元数据）属于
 * <b>内部排障 / 安全审计</b>信息，一旦随列表接口整包外泄，等于把全公司调用明细
 * （含每个人的来源 IP 与上游报错细节）暴露给任一登录用户。</p>
 *
 * <p>本 VO 是「白名单投影」：只保留<b>业务展示必需</b>的用量维度，
 * 刻意<b>不包含</b> clientIp / instanceUrl / errorMessage / metadata。
 * 排障所需的完整明细仍可通过运维侧接口（{@code /api/token-usage/**} 的
 * {@code monitoring:tokenusage:read} 权限码）获取，权限收敛不影响运维排障。</p>
 *
 * <p>字段与 {@link TokenUsageEntity} 一一对应但做了裁剪；{@code isSuccess} 沿用实体侧的
 * {@code Boolean} 包装类型，Lombok 生成的访问器为 {@code getIsSuccess()}，
 * 序列化后的 JSON 键为 {@code isSuccess}（与前端 {@code TokenUsageRecord.isSuccess} 对齐）。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenUsageRecentVO {

    /** 记录主键 */
    private Long id;

    /** 追踪 ID */
    private String traceId;

    /** 服务类型（chat / embedding / rerank …） */
    private String serviceType;

    /** 上游响应体回传的模型名 */
    private String modelName;

    /** 虚拟模型名（模型分组 / 池名） */
    private String virtualModel;

    /** 实际路由到的厂商模型实例名（计价依据） */
    private String instanceName;

    /** 输入 token 数 */
    private Long promptTokens;

    /** 输出 token 数 */
    private Long completionTokens;

    /** 总 token 数 */
    private Long totalTokens;

    /** API Key ID */
    private String apiKeyId;

    /** 用户 ID */
    private String userId;

    /** 请求是否成功 */
    private Boolean isSuccess;

    /** 错误代码（失败时） */
    private String errorCode;

    /** 响应耗时（毫秒） */
    private Long responseTimeMs;

    /** 请求发生时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime occurredAt;

    /**
     * 由实体构造对外 VO（白名单投影）。
     *
     * <p>刻意<b>不</b>拷贝 clientIp / instanceUrl / errorMessage / metadata
     * —— 见类注释的收敛说明。实体为 {@code null} 时返回 {@code null}。</p>
     *
     * @param entity Token 用量实体，可为 {@code null}
     * @return 裁剪后的对外 VO；入参为 {@code null} 时返回 {@code null}
     */
    public static TokenUsageRecentVO fromEntity(final TokenUsageEntity entity) {
        if (entity == null) {
            return null;
        }
        return TokenUsageRecentVO.builder()
                .id(entity.getId())
                .traceId(entity.getTraceId())
                .serviceType(entity.getServiceType())
                .modelName(entity.getModelName())
                .virtualModel(entity.getVirtualModel())
                .instanceName(entity.getInstanceName())
                .promptTokens(entity.getPromptTokens())
                .completionTokens(entity.getCompletionTokens())
                .totalTokens(entity.getTotalTokens())
                .apiKeyId(entity.getApiKeyId())
                .userId(entity.getUserId())
                .isSuccess(entity.getIsSuccess())
                .errorCode(entity.getErrorCode())
                .responseTimeMs(entity.getResponseTimeMs())
                .occurredAt(entity.getOccurredAt())
                .build();
    }
}
