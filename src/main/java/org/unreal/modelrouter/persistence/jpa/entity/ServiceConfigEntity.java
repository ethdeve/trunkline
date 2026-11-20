package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 服务配置实体 (JPA 版本)
 * v1.5.2: 从 R2DBC 迁移到 JPA
 *
 * <p><b>多租户口径：全局共享，不挂 {@code @Filter}</b>。本表记录的是「模型分类」定义
 * （{@code chat / embedding / rerank / tts / stt / imgGen / imgEdit} 各自用哪个适配器、
 * 哪种负载均衡），它是协议事实而非租户偏好——文本、音频这些分类对全平台只有一套。
 * 若按租户隔离，每个租户都要重复维护同一份分类，切租户就会"看不见自己刚配的分类"，
 * 且同一部署内出现互相矛盾的适配器映射。</p>
 *
 * <p>因此本实体标 {@link TenantShared}：{@code tenant_id} 列保留（表结构不动、历史数据不动），
 * 但读取时不追加租户条件，写入时统一落哨兵租户以保持数据来源单一。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "service_config", indexes = @Index(name = "idx_service_config_tenant", columnList = "tenant_id"))
@TenantShared(reason = "模型分类（文本/音频等）是协议事实而非租户偏好，全平台仅一套定义")
public class ServiceConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false)
    private String configKey;

    @Column(name = "service_type", nullable = false)
    private String serviceType; // chat, embedding, rerank, tts, stt, imgGen, imgEdit

    @Column(name = "adapter")
    private String adapter;

    @Column(name = "load_balance_type")
    private String loadBalanceType; // random, round-robin, least-connections, ip-hash

    @Column(name = "version")
    private Integer version;

    @Column(name = "is_latest")
    private Boolean isLatest;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 归属租户（多租户预埋，S2 任务 35/36；社区版恒为 default） */
    @Builder.Default
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

    /**
     * 落库前固定归属哨兵租户（全局共享）。
     *
     * <p>不再跟随当前请求租户：模型分类全平台一份，写入必须落到同一处，
     * 否则多租户下会写出 N 份内容相同、彼此不可见的分类定义。</p>
     */
    @PrePersist
    public void prePersist() {
        tenantId = org.unreal.modelrouter.enterprise.config.TenantJpaFilterDefinition.SHARED_TENANT_ID;
    }

}
