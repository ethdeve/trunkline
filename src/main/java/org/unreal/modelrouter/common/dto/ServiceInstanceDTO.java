package org.unreal.modelrouter.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 服务实例 DTO
 * v1.5.2: 用于替代 Map 传递数据
 * v1.7.1: 添加 adapter 和 headers 字段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ServiceInstanceDTO {

    private Long id;

    /**
     * 获取实例ID（兼容前端）
     *
     * @deprecated 此字段与 {@link #id} 字段功能重复，仅为兼容前端而保留。
     *             前端应迁移使用 id 字段，通过 {@link #getId()} 获取实例ID。
     *             <p>迁移说明：</p>
     *             <ul>
     *               <li>instanceId 返回 String 类型（id 的字符串形式）</li>
     *               <li>id 返回 Long 类型，是数据库主键</li>
     *               <li>前端应使用 id 字段，统一数据类型</li>
     *             </ul>
     *             <p>此字段将在 v3.0 版本中移除，前端需同步更新。</p>
     * @return 实例ID字符串
     * @since v2.5.1 标注废弃
     */
    @Deprecated(since = "2.5.1", forRemoval = true)
    @JsonProperty("instanceId")
    public String getInstanceId() {
        return id != null ? String.valueOf(id) : null;
    }

    private Long serviceConfigId;

    /**
     * 服务类型（模型分类）：chat / embedding / rerank / tts / stt / imgGen / imgEdit。
     * <p>实例表本身没有此列——服务类型由 {@code serviceConfigId} 指向的 service_config 行决定
     * （见 {@code ServiceInstanceEntity#getServiceConfigId()}）。本字段是<b>后端权威的派生值</b>，
     * 由 {@code ServiceInstanceManager#convertToDTO} 从 service_config 反查带出，
     * 避免前端靠「当前在哪个 Tab」推断而与真实归属脱节。</p>
     * <p>前端据此展示「模型分类」，并在编辑时提交目标分类触发跨分类迁移。</p>
     * @since 2026-10-22
     */
    private String serviceType;

    /**
     * 运行时实例唯一标识（UUID）。
     * <p>与数据库主键 {@code id} 不同：该值同时是网关路由层与熔断器（{@code CircuitBreakerManager}）使用的 key，
     * 前端需要它才能把「已接入模型」列表行与「熔断运行态」关联起来。</p>
     * <p>老数据可能为空（此时运行时退化为使用数据库 ID），前端按空值处理即可。</p>
     */
    private String runtimeInstanceId;

    /**
     * v2.11.0: 关联的模型供应商 ID（可空，老实例为 null）
     */
    private Long providerId;

    /**
     * v2.11.0: 关联的模型供应商名称（展示用，随 providerId 一起带出）
     */
    private String providerName;

    private String name;
    private String baseUrl;
    private String path;
    private Integer weight;

    // 内部存储原始状态值
    private String status;

    // 兼容前端，返回小写的状态值
    @JsonProperty("status")
    public String getStatusForJson() {
        return status != null ? status.toLowerCase() : null;
    }

    private String healthStatus;
    private String errorMessage;

    /**
     * 实例级别适配器配置（可选，覆盖服务级别配置）
     */
    private String adapter;

    /**
     * 自定义请求头配置
     */
    private Map<String, String> headers;

    /**
     * v2.9.7: 实例标签配置(标签路由按键值对圈选)
     */
    private Map<String, String> tags;

    // 限流器配置
    private InstanceRateLimitDTO rateLimit;

    // 熔断器配置
    private InstanceCircuitBreakerDTO circuitBreaker;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}