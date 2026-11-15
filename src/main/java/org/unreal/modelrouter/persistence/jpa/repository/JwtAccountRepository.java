package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;

import java.util.List;
import java.util.Optional;

/**
 * JWT 账户仓库 (JPA 版本)
 * v1.5.2: 从 R2DBC 迁移到 JPA
 */
@Repository
public interface JwtAccountRepository extends JpaRepository<JwtAccountEntity, Long> {

    /**
     * 根据用户名查找
     */
    Optional<JwtAccountEntity> findByUsername(String username);

    /**
     * 检查用户名是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 根据外部身份标识（IdP 侧 {@code externalId}）查找。
     *
     * <p>A4 最小回收闭环（闸门②）：JIT 建号写入 {@code externalId}，停用/对账据此回向定位本地账号。</p>
     */
    Optional<JwtAccountEntity> findByExternalId(String externalId);

    /**
     * 列出全部带外部身份标识的账号（即 SSO 自动开通的账号）。
     *
     * <p>本地/YAML 静态账户 {@code external_id} 为 null，reconcile 据此区分、避免误伤。</p>
     */
    List<JwtAccountEntity> findAllByExternalIdIsNotNull();
}
