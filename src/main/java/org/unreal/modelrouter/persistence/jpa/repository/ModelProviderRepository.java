package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;

import java.util.List;
import java.util.Optional;

/**
 * 模型供应商仓库（v2.11.0 新增）
 */
@Repository
public interface ModelProviderRepository extends JpaRepository<ModelProviderEntity, Long> {

    /**
     * 按 providerUid 查找（前端选择器按 uid 引用，避免泄露自增主键语义）
     */
    Optional<ModelProviderEntity> findByProviderUid(String providerUid);

    /**
     * 按名称查找（创建时唯一性校验）
     */
    Optional<ModelProviderEntity> findByName(String name);

    /**
     * 按状态列出供应商（按 sortOrder 升序，null 视为 0，再按 id 稳定排序）
     */
    List<ModelProviderEntity> findByStatusOrderBySortOrderAscIdAsc(String status);

    /**
     * 全部供应商（卡片列表用）
     */
    List<ModelProviderEntity> findAllByOrderBySortOrderAscIdAsc();
}
