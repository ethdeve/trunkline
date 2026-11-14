/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.ModelQuotaEntity;

import java.util.List;
import java.util.Optional;

/**
 * 厂商模型额度仓库（开源基础能力，「额度与限速」tab 列表用）。
 *
 * @author Trunkline Team
 */
@Repository
public interface ModelQuotaRepository extends JpaRepository<ModelQuotaEntity, Long> {

    /** 按模型名精确查找。 */
    Optional<ModelQuotaEntity> findByModelName(String modelName);

    /** 全部已启用的额度行（密钥模型范围 / 配额判定用）。 */
    List<ModelQuotaEntity> findAllByEnabledTrue();

    /** 全部额度行（含停用，管理列表用）。 */
    List<ModelQuotaEntity> findAll();
}
