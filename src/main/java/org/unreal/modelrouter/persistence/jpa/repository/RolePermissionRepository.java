package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.RolePermissionEntity;

import java.util.Collection;
import java.util.List;

/**
 * 角色-权限映射仓库（v2.9.8 RBAC 数据驱动权限体系）
 *
 * @author JAiRouter Team
 * @since 2.9.8
 */
@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, Long> {

    /**
     * 按角色名查询权限映射
     *
     * @param roleName 角色名
     * @return 权限映射列表
     */
    List<RolePermissionEntity> findByRoleName(String roleName);

    /**
     * 按多个角色名批量查询权限映射
     *
     * @param roleNames 角色名集合
     * @return 权限映射列表
     */
    List<RolePermissionEntity> findByRoleNameIn(Collection<String> roleNames);

    /**
     * 删除某角色的全部权限映射（权限管理 API 更新角色时使用）
     *
     * <p>注意：这是派生删除（先查后逐条 remove），删除动作延迟到 flush 才执行；
     * 「先删后插同唯一键」场景必须改用 {@link #deleteByRoleNameBulk(String)}
     * （Hibernate 同事务 flush 顺序为 insert 先于 delete，派生删除会触发
     * uk_role_permission 唯一键冲突，2026-10-21）。
     *
     * @param roleName 角色名
     */
    void deleteByRoleName(String roleName);

    /**
     * 批量 JPQL 删除某角色的全部权限映射（立即执行 SQL）
     *
     * <p>「整体替换角色权限」专用：SQL 在调用点立即下发，先于后续 insert 到达数据库，
     * 不会与同唯一键的新行冲突；必须在事务内调用（@Modifying 要求）。
     *
     * @param roleName 角色名
     * @return 删除的行数
     */
    @Modifying
    @Query("delete from RolePermissionEntity rp where rp.roleName = :roleName")
    int deleteByRoleNameBulk(@Param("roleName") String roleName);
}
