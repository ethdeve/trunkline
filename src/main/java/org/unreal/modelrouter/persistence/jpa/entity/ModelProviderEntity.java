package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 模型供应商实体（v2.11.0 新增）
 *
 * <p>「供应商层」改造的核心表：把原先每次新增模型实例都要重填的 baseUrl / path / adapter /
 * 上游 API Key 收敛到供应商一行。实例（{@link ServiceInstanceEntity}）通过 {@code provider_id}
 * 关联供应商，创建时自动带出地址与鉴权头；供应商更换 Key 时级联更新其下所有实例。
 *
 * <p>与实例的既有约定保持一致：状态值大写（ACTIVE / INACTIVE）；apiKey 明文存储与
 * {@code service_instance.headers} 中 Authorization 同级（既有方案即明文 JSON，不引入新的加密面）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "model_provider")
public class ModelProviderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 供应商唯一标识（前端引用 / 日志排查用） */
    @Column(name = "provider_uid", unique = true, length = 64)
    private String providerUid;

    /** 供应商名称（如 DeepSeek / 智谱 GLM），列表与选择器展示 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 服务类型（chat / embedding / rerank ...），该供应商下添加的模型默认落入此类型 */
    @Column(name = "service_type", nullable = false, length = 64)
    private String serviceType;

    /** 上游基础地址，如 https://api.deepseek.com */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /** 上游调用路径，如 /v1/chat/completions */
    @Column(name = "path", length = 512)
    private String path;

    /** 适配器名称（留空则用全局适配器） */
    @Column(name = "adapter", length = 128)
    private String adapter;

    /**
     * 上游 API Key（明文）。
     * 列表接口永不返回该字段；仅创建/更新时可写，更新留空表示保持原值不变。
     */
    @Column(name = "api_key", length = 1024)
    private String apiKey;

    /**
     * 发现模型时使用的模型列表路径（可选）。
     * 留空时由 path 启发式推导（取 path 中的版本前缀，如 /v1/chat/completions → /v1/models）。
     */
    @Column(name = "models_path", length = 512)
    private String modelsPath;

    /** 状态：ACTIVE / INACTIVE */
    @Column(name = "status", length = 32)
    private String status;

    /** 备注/描述 */
    @Column(name = "description", length = 512)
    private String description;

    /** 排序权重（小的靠前） */
    @Column(name = "sort_order")
    private Integer sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
