package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 员工自助实例视图对象（P0-L 凭据泄漏防护）。
 *
 * <p><b>用途</b>：员工端点 {@code GET /api/instance/mine}（{@code config:instances:self}）
 * 专用出参。仅保留前端「试验场」真正读取的字段，
 * <b>刻意剔除</b>所有运维/成本/上游敏感信息：</p>
 *
 * <ul>
 *   <li><b>剔除</b> {@code headers} —— 含上游厂商 {@code Authorization: Bearer <key>}，
 *       员工拿到后可直调上游厂商 API（凭据泄漏）。</li>
 *   <li><b>剔除</b> {@code errorMessage} —— 含上游真实报错（可能暴露内部拓扑）。</li>
 *   <li><b>剔除</b> {@code baseUrl} / {@code path} —— 上游厂商地址（SSRF 面 / 商业信息）。</li>
 *   <li><b>剔除</b> {@code rateLimit} / {@code circuitBreaker} —— 运维阈值（成本容量信息）。</li>
 *   <li><b>剔除</b> {@code providerId} / {@code providerName} —— 供应商信息。</li>
 *   <li><b>剔除</b> {@code serviceConfigId} / {@code id} / {@code createdAt} / {@code updatedAt} ——
 *       内部数据库主键与时间戳，员工无需感知。</li>
 * </ul>
 *
 * <p><b>与 ServiceInstanceDTO 的区别</b>：{@code ServiceInstanceDTO} 是管理面全量出参，
 * 含明文 headers 与全部运维字段；本 VO 是员工面裁剪出参，不含任何可用于覆盖真实 Key 的字段
 * （前端无法把 {@code "***"} 回填，因为根本没有该字段）。</p>
 *
 * @since P0-L
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeServiceInstanceVO {

    /**
     * 运行时实例唯一标识（UUID）。
     * 前端试验场用于关联熔断运行态。
     */
    private String instanceId;

    /**
     * 实例名称（模型名）。
     */
    private String name;

    /**
     * 服务类型（模型分类）：chat / embedding / rerank / tts / stt / imgGen / imgEdit。
     */
    private String serviceType;

    /**
     * 实例状态（小写）。
     */
    private String status;

    /**
     * 健康状态。
     */
    private String healthStatus;

    /**
     * 适配器类型。
     */
    private String adapter;

    /**
     * 权重。
     */
    private Integer weight;

    /**
     * 标签配置。
     */
    private Map<String, String> tags;
}
