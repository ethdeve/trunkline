package org.unreal.modelrouter.monitor.callhistory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API 调用历史查询请求 DTO
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class CallHistoryQueryDTO {

    /**
     * 开始时间 (ISO-8601)
     */
    private String startTime;

    /**
     * 结束时间 (ISO-8601)
     */
    private String endTime;

    /**
     * 模型名称（精确匹配，上游响应体回传的厂商模型名）
     */
    private String modelName;

    /**
     * 虚拟模型名（精确匹配，模型分组 / 池名，如「公司主力」），v3.5 Trunkline 新增。
     *
     * <p>「调用观测」按虚拟模型筛选用；与 {@link #modelName}（上游模型名）语义不同。</p>
     */
    private String virtualModel;

    /**
     * 员工（精确匹配），v3.5 Trunkline 新增。
     *
     * <p>「调用观测」按员工筛选用：JWT 调用为控制台用户名，
     * API Key 调用为密钥持有人（{@code ApiKey.createdBy}）。</p>
     */
    private String userId;

    /**
     * 关键字（模糊匹配，多字段 OR），v3.5 Trunkline 新增。
     *
     * <p>对应原型的「一个搜索框搜人名、虚拟模型、Trace ID…」：命中下列任一字段即返回——
     * {@code userId}（员工）、{@code virtualModel}（虚拟模型）、{@code traceId}、
     * {@code modelName}（上游模型名）、{@code instanceName}（实际路由到的实例）。</p>
     */
    private String keyword;

    /**
     * 是否被限流（true/false/null 表示全部），v3.5 Trunkline 新增。
     *
     * <p>「调用观测」状态筛选的「限流」项。<b>注意</b>：限流的调用
     * {@code isSuccess} 仍为 true（网关自己拒的，请求没到上游），
     * 故此项与 {@link #isSuccess} 是正交关系，不可互相替代。</p>
     */
    private Boolean rateLimited;

    /**
     * 服务类型
     */
    private String serviceType;

    /**
     * API Key ID
     */
    private String apiKeyId;

    /**
     * 是否成功（true/false/null 表示全部）
     */
    private Boolean isSuccess;

    /**
     * HTTP 状态码
     */
    private Integer httpStatusCode;

    /**
     * 页码（从 0 开始）
     */
    @Builder.Default
    private Integer page = 0;

    /**
     * 每页大小
     */
    @Builder.Default
    private Integer size = 20;

    /**
     * 排序字段
     */
    @Builder.Default
    private String sortField = "createdAt";

    /**
     * 排序方向（asc/desc）
     */
    @Builder.Default
    private String sortDirection = "desc";
}
