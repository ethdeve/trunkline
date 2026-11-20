package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.PrePersist;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 配置实体类 (JPA 版本)
 * v1.5.1: 替代 R2DBC 的 ConfigEntity
 *
 * <p>v3.6 (E1) 行语义按 version 分派：</p>
 * <ul>
 *   <li>version = 0：当前生效配置行（isLatest = true），由 saveConfig upsert</li>
 *   <li>version &ge; 1：版本快照行（isLatest = false），只追加、永不改写</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "config_data",
        // 多租户：同一 (config_key, version) 在不同租户下必须各存一份，
        // 否则租户 A 写入会撞租户 B 的唯一键。租户 ID 纳入唯一键后，
        // 查询侧由实体上的 @Filter(tenantFilter) 自动附加 tenant_id 条件，
        // 各租户 upsert 互不干扰（社区版恒 default，行为不变）。
        uniqueConstraints = @UniqueConstraint(
                name = "uk_config_data_key_version",
                columnNames = {"config_key", "version", "tenant_id"}),
        // X4：与 V6__config_current_version_separation.sql 的 idx_config_data_key 对齐
        // （H2BaselineMatchesEntityModelTest 要求实体模型与迁移侧 schema 逐项一致）
        indexes = {
                @Index(name = "idx_config_data_key", columnList = "config_key"),
                @Index(name = "idx_config_data_tenant", columnList = "tenant_id")
        })
@Filter(name = "tenantFilter")
public class ConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 255)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "version", nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_latest", nullable = false)
    private Boolean isLatest;

    /** 归属租户（多租户预埋，S2 任务 35/36；社区版恒为 default） */
    @Builder.Default
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

    /**
     * 落库前回填归属租户（多租户）。配置写入经管理 API 在请求线程同步 save，
     * 此处 ThreadLocal 可读；社区版 currentTenantId 恒为 default，行为不变。
     *
     * <p><b>全局配置强制归入哨兵租户</b>：{@code config_data} 里混放两类数据——
     * 按租户隔离的业务配置（虚拟模型池 / 路由规则）与全部署唯一的字典配置
     * （模型分类 / 提示词模板 / Key 审批 / 组-角色映射 / 适配器生命周期）。
     * 后者必须对所有租户一致可见，否则每个租户都要重复维护一份、切租户就"看不见"。
     * 判定见 {@link GlobalConfigKeys}。</p>
     */
    @PrePersist
    public void prePersist() {
        if (GlobalConfigKeys.isGlobal(configKey)) {
            tenantId = org.unreal.modelrouter.enterprise.config.TenantJpaFilterDefinition.SHARED_TENANT_ID;
            return;
        }
        if (tenantId == null || tenantId.isBlank()
                || org.unreal.modelrouter.enterprise.noop.SingleTenantContext.DEFAULT_TENANT_ID.equals(tenantId)) {
            tenantId = org.unreal.modelrouter.enterprise.config.TenantContextHolder.currentTenantId();
        }
    }

}
