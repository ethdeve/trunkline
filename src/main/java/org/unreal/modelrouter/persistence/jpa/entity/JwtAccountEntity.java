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
                @Index(name = "idx_jwt_accounts_username", columnList = "username")
        })
@TenantShared(reason = "认证与 RBAC 身份存储（JWT 登录账号 + API Key holder 用户目录），属平台基础设施而非租户业务数据；私有化单租户下即唯一客户的用户目录")
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

    /**
     * 用户真实姓名（展示用，便于识别账号归属人；可为空）。
     *
     * <p>与 username（登录名）区分：name 是给人看的中文/英文姓名，不参与任何鉴权或聚合逻辑。</p>
     */
    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "enabled")
    private Boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

}
