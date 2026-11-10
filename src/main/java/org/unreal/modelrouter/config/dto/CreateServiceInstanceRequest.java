package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 创建服务实例请求 DTO
 * v1.5.2: 用于替代 Map 传递数据
 * v1.7.1: 添加 adapter、headers 和 status 字段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class CreateServiceInstanceRequest {

    private String name;
    private String baseUrl;
    private String path;
    private Integer weight;

    /**
     * v2.11.0: 关联的模型供应商 ID（可选）。
     * 非空时，baseUrl / path / adapter / Authorization 头按供应商带出
     * （请求中对应的显式值仍优先，便于个别实例覆盖）。
     */
    private Long providerId;

    /**
     * 实例状态：active 或 inactive
     */
    private String status;

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

    /**
     * 目标服务类型（模型分类）：chat / embedding / rerank / tts / stt / imgGen / imgEdit。
     * <p>仅在<b>更新</b>时有意义，值为空表示不改变分类；非空表示把实例迁移到该分类。</p>
     * <p>迁移不是单纯改一个字段——实例的服务类型由 {@code service_config_id} 决定，
     * 且健康状态、实例限流、池成员引用都按「serviceType:instanceId」组织，
     * 因此由 {@code ServiceInstanceManager#updateInstance} 统一做级联迁移与前置校验。</p>
     */
    private String serviceType;
}
