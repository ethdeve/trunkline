package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.persistence.jpa.entity.QuotaLedgerEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 配额账本仓库接口（v3.1 PR-1）。
 *
 * <p>账本写入只有两种形态：</p>
 * <ul>
 *   <li>{@link #accumulate} —— 行已存在时按增量原子累加（upsert 的 update 分支）；</li>
 *   <li>{@link JpaRepository#save} —— 行不存在时插入绝对值（upsert 的 insert 分支）。</li>
 * </ul>
 *
 * <p>所有写方法显式声明事务：调用方（配额账本服务）不持有长事务，每个窗口的落库都是独立短事务，
 * 单个 key 落库失败不会污染其它 key。</p>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Repository
public interface QuotaLedgerRepository extends JpaRepository<QuotaLedgerEntity, Long> {

    /**
     * 按「六维 + 窗口类型 + 窗口起点」精确查询账本行（八列唯一键）。
     *
     * @param tenantId    租户 ID
     * @param apiKeyId    API Key ID
     * @param userId      用户 ID
     * @param serviceType 服务类型
     * @param model       模型名称
     * @param groupId     分组（池）名称
     * @param windowType  窗口类型
     * @param windowStart 窗口起点
     * @return 命中的账本行，不存在时返回 {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    Optional<QuotaLedgerEntity> findByTenantIdAndApiKeyIdAndUserIdAndServiceTypeAndModelAndGroupIdAndWindowTypeAndWindowStart(
            String tenantId,
            String apiKeyId,
            String userId,
            String serviceType,
            String model,
            String groupId,
            String windowType,
            LocalDateTime windowStart);

    /**
     * 查询某个分组（模型分组 / 池）的全部账本行（所有维度与所有窗口）。
     *
     * @param groupId 分组（池）名称
     * @return 账本行列表，无数据时返回空列表
     */
    @Transactional(readOnly = true)
    List<QuotaLedgerEntity> findByGroupId(String groupId);

    /**
     * 查询某个模型（已接入模型 / 实例）的全部账本行（所有维度与所有窗口）。
     *
     * @param model 模型（实例）名称
     * @return 账本行列表，无数据时返回空列表
     */
    @Transactional(readOnly = true)
    List<QuotaLedgerEntity> findByModel(String model);

    /**
     * 查询某个 API Key 的全部账本行（所有维度与所有窗口）。
     *
     * @param apiKeyId API Key ID
     * @return 账本行列表，无数据时返回空列表
     */
    @Transactional(readOnly = true)
    List<QuotaLedgerEntity> findByApiKeyId(String apiKeyId);

    /**
     * 原子累加指定账本行的请求数与 token 数（行不存在时返回 0，由调用方改为插入）。
     *
     * @param tenantId    租户 ID
     * @param apiKeyId    API Key ID
     * @param userId      用户 ID
     * @param serviceType 服务类型
     * @param model       模型名称
     * @param windowType  窗口类型
     * @param windowStart 窗口起点
     * @param requests    请求数增量（可为负，用于回滚）
     * @param tokens      token 数增量（可为负，用于回滚）
     * @param updatedAt   更新时间
     * @return 受影响行数（0 表示账本行不存在）
     */
    @Modifying
    @Transactional
    @Query("UPDATE QuotaLedgerEntity q SET q.requestCount = q.requestCount + :requests, "
           + "q.tokenCount = q.tokenCount + :tokens, q.updatedAt = :updatedAt "
           + "WHERE q.tenantId = :tenantId AND q.apiKeyId = :apiKeyId AND q.userId = :userId "
           + "AND q.serviceType = :serviceType AND q.model = :model AND q.groupId = :groupId "
           + "AND q.windowType = :windowType AND q.windowStart = :windowStart")
    int accumulate(@Param("tenantId") String tenantId,
                   @Param("apiKeyId") String apiKeyId,
                   @Param("userId") String userId,
                   @Param("serviceType") String serviceType,
                   @Param("model") String model,
                   @Param("groupId") String groupId,
                   @Param("windowType") String windowType,
                   @Param("windowStart") LocalDateTime windowStart,
                   @Param("requests") long requests,
                   @Param("tokens") long tokens,
                   @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 删除某个 API Key 的全部账本行（配额重置）。
     *
     * @param apiKeyId API Key ID
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM QuotaLedgerEntity q WHERE q.apiKeyId = :apiKeyId")
    int deleteByApiKeyId(@Param("apiKeyId") String apiKeyId);

    // ========== #10 被遗忘权精确擦除 ==========

    /**
     * 按租户 + 用户擦除配额账本行（显式带 tenantId，方案 3.5 硬约束）。
     * Redis 计数槽的清理由企业侧执行器调 QuotaLedgerService.purgeCounters 完成。
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM QuotaLedgerEntity q WHERE q.userId = :userId AND q.tenantId = :tenantId")
    int deleteByUserIdAndTenantId(@Param("userId") String userId, @Param("tenantId") String tenantId);

    @Query("SELECT COUNT(q) FROM QuotaLedgerEntity q WHERE q.userId = :userId AND q.tenantId = :tenantId")
    long countByUserIdAndTenantId(@Param("userId") String userId, @Param("tenantId") String tenantId);

    /**
     * 删除某个分组（模型分组 / 池）的全部账本行（分组配额重置）。
     *
     * @param groupId 分组（池）名称
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM QuotaLedgerEntity q WHERE q.groupId = :groupId")
    int deleteByGroupId(@Param("groupId") String groupId);

    /**
     * 删除某个模型（已接入模型 / 实例）的全部账本行（模型配额重置）。
     *
     * @param model 模型（实例）名称
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM QuotaLedgerEntity q WHERE q.model = :model")
    int deleteByModel(@Param("model") String model);

    /**
     * 删除指定窗口类型中早于截止时间的账本行（保留期清理）。
     *
     * @param windowType 窗口类型
     * @param cutoff     保留期截止时间
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM QuotaLedgerEntity q WHERE q.windowType = :windowType AND q.windowStart < :cutoff")
    int deleteExpired(@Param("windowType") String windowType, @Param("cutoff") LocalDateTime cutoff);
}
