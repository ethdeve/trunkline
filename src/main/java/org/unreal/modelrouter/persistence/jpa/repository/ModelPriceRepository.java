package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.ModelPriceEntity;

import java.util.List;
import java.util.Optional;

/**
 * 模型价格仓库（v3.5 Trunkline UI 改造，「我的账单」计费）。
 *
 * <p>查询已启用的价格行，按 model_name 合并；未配置价格的模型按 ¥0 计入。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@Repository
public interface ModelPriceRepository extends JpaRepository<ModelPriceEntity, Long> {

    /** 按模型名精确查找（账单计价用，未命中返回空 → 按 ¥0 计）。 */
    Optional<ModelPriceEntity> findByModelName(String modelName);

    /** 全部已启用的价格行（接入管理「价格与额度」tab 列表用）。 */
    List<ModelPriceEntity> findAllByEnabledTrue();

    /** 全部价格行（含停用，管理列表用）。 */
    List<ModelPriceEntity> findAll();
}
