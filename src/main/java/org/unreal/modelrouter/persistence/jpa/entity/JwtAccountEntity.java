package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * JWT 账户实体 (JPA 版本)
 * v1.5.2: 从 R2DBC 迁移到 JPA
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "jwt_accounts",
        indexes = {
                @Index(name = "idx_jwt_accounts_username", columnList = "username"),
                @Index(name = "idx_jwt_accounts_tenant", columnList = "tenant_id")
        })
@Filter(name = "tenantFilter")
public class JwtAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "roles")
    private String roles; // JSON 格式存储 ["ADMIN", "USER"]

    /**
     * 所属部门（v3.5 Trunkline UI 改造）。
     *
     * <p>用于「团队用量」按部门维度统计：token_usage.user_id 关联本表 username
     * 取 department 后聚合。历史数据为 null，聚合时归入「未分配部门」。</p>
     */
    @Column(name = "department", length = 128)
    private String department;

    @Column(name = "enabled")
    private Boolean enabled;

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

}
