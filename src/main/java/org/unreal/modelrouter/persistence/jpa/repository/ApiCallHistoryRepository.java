package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.ApiCallHistoryEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API 调用历史仓库接口
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Repository
public interface ApiCallHistoryRepository extends JpaRepository<ApiCallHistoryEntity, Long> {

    // ========== 基础查询 ==========

    /**
     * 根据追踪 ID 查找
     */
    List<ApiCallHistoryEntity> findByTraceId(String traceId);

    /**
     * 根据请求 ID 查找
     */
    List<ApiCallHistoryEntity> findByRequestId(String requestId);

    /**
     * 根据模型名称查找
     */
    List<ApiCallHistoryEntity> findByModelName(String modelName);

    /**
     * 根据服务类型查找
     */
    List<ApiCallHistoryEntity> findByServiceType(String serviceType);

    /**
     * 根据 API Key ID 查找
     */
    List<ApiCallHistoryEntity> findByApiKeyId(String apiKeyId);

    /**
     * 根据用户 ID 查找
     */
    List<ApiCallHistoryEntity> findByUserId(String userId);

    // ========== 时间范围查询 ==========

    /**
     * 根据时间范围查询
     */
    @Query("SELECT c FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime "
           + "ORDER BY c.createdAt DESC")
    List<ApiCallHistoryEntity> findByTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 分页查询（按时间范围）
     */
    Page<ApiCallHistoryEntity> findByCreatedAtBetween(
            LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    /**
     * 分页查询（带过滤条件）
     *
     * <p>v3.5 Trunkline：新增 {@code virtualModel}（虚拟模型 / 模型分组）、{@code userId}
     * （员工）、{@code keyword}（多字段模糊 OR 匹配）、{@code rateLimited}（限流标记）
     * 四个维度，供「调用观测」按「谁调了哪个虚拟模型」过滤。</p>
     *
     * <p>{@code keyword} 的大小写不敏感（{@code lower(...)} 双侧包裹），
     * 命中员工 / 虚拟模型 / Trace ID / 模型名 / 实例名任一即返回。</p>
     */
    // ⚠️ 可空参数必须 cast 显式定型（PG 42P18，见 SecurityAuditEventRepository 同注）。
    @Query("SELECT c FROM ApiCallHistoryEntity c WHERE "
           + "(cast(:startTime as timestamp) IS NULL OR c.createdAt >= :startTime) AND "
           + "(cast(:endTime as timestamp) IS NULL OR c.createdAt <= :endTime) AND "
           + "(cast(:modelName as string) IS NULL OR c.modelName = :modelName) AND "
           + "(cast(:virtualModel as string) IS NULL OR c.virtualModel = :virtualModel) AND "
           + "(cast(:userId as string) IS NULL OR c.userId = :userId) AND "
           + "(cast(:serviceType as string) IS NULL OR c.serviceType = :serviceType) AND "
           + "(cast(:apiKeyId as string) IS NULL OR c.apiKeyId = :apiKeyId) AND "
           + "(cast(:isSuccess as boolean) IS NULL OR c.isSuccess = :isSuccess) AND "
           + "(cast(:rateLimited as boolean) IS NULL OR c.rateLimited = :rateLimited) AND "
           + "(cast(:httpStatusCode as integer) IS NULL OR c.httpStatusCode = :httpStatusCode) AND "
           + "(cast(:keyword as string) IS NULL OR "
           + "  lower(c.userId) LIKE lower(concat('%', :keyword, '%')) OR "
           + "  lower(c.virtualModel) LIKE lower(concat('%', :keyword, '%')) OR "
           + "  lower(c.traceId) LIKE lower(concat('%', :keyword, '%')) OR "
           + "  lower(c.modelName) LIKE lower(concat('%', :keyword, '%')) OR "
           + "  lower(c.instanceName) LIKE lower(concat('%', :keyword, '%'))) "
           + "ORDER BY c.createdAt DESC")
    Page<ApiCallHistoryEntity> findWithFilters(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("modelName") String modelName,
            @Param("virtualModel") String virtualModel,
            @Param("userId") String userId,
            @Param("serviceType") String serviceType,
            @Param("apiKeyId") String apiKeyId,
            @Param("isSuccess") Boolean isSuccess,
            @Param("rateLimited") Boolean rateLimited,
            @Param("httpStatusCode") Integer httpStatusCode,
            @Param("keyword") String keyword,
            Pageable pageable);

    // ========== 错误和慢调用查询 ==========

    /**
     * 查询错误调用（失败的请求）
     */
    @Query("SELECT c FROM ApiCallHistoryEntity c WHERE c.isSuccess = false "
           + "AND (cast(:startTime as timestamp) IS NULL OR c.createdAt >= :startTime) "
           + "AND (cast(:endTime as timestamp) IS NULL OR c.createdAt <= :endTime) "
           + "ORDER BY c.createdAt DESC")
    List<ApiCallHistoryEntity> findErrors(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    /**
     * 查询慢调用（响应时间超过阈值）
     */
    @Query("SELECT c FROM ApiCallHistoryEntity c WHERE c.responseTimeMs > :threshold "
           + "AND (cast(:startTime as timestamp) IS NULL OR c.createdAt >= :startTime) "
           + "AND (cast(:endTime as timestamp) IS NULL OR c.createdAt <= :endTime) "
           + "ORDER BY c.responseTimeMs DESC")
    List<ApiCallHistoryEntity> findSlowCalls(
            @Param("threshold") Long threshold,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    // ========== 统计查询 ==========

    /**
     * 按模型统计调用次数和 token 使用量
     */
    @Query("SELECT c.modelName, COUNT(c), COALESCE(SUM(c.totalTokens), 0), "
           + "COALESCE(AVG(c.responseTimeMs), 0), "
           + "SUM(CASE WHEN c.isSuccess = true THEN 1 ELSE 0 END) "
           + "FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime "
           + "GROUP BY c.modelName ORDER BY COUNT(c) DESC")
    List<Object[]> countByModel(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按服务类型统计
     */
    @Query("SELECT c.serviceType, COUNT(c), COALESCE(SUM(c.totalTokens), 0), "
           + "COALESCE(AVG(c.responseTimeMs), 0) "
           + "FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime "
           + "GROUP BY c.serviceType ORDER BY COUNT(c) DESC")
    List<Object[]> countByServiceType(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按小时统计（指定日期）
     */
    @Query("SELECT c.requestHour, COUNT(c) "
           + "FROM ApiCallHistoryEntity c "
           + "WHERE c.requestDate = :date "
           + "GROUP BY c.requestHour ORDER BY c.requestHour")
    List<Object[]> countByHour(@Param("date") String date);

    /**
     * 按日期统计趋势
     */
    @Query("SELECT c.requestDate, COUNT(c), COALESCE(SUM(c.totalTokens), 0) "
           + "FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime "
           + "GROUP BY c.requestDate ORDER BY c.requestDate")
    List<Object[]> countByDay(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * HTTP 状态码分布
     */
    @Query("SELECT c.httpStatusCode, COUNT(c) "
           + "FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime "
           + "GROUP BY c.httpStatusCode ORDER BY c.httpStatusCode")
    List<Object[]> countByStatusCode(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 获取汇总统计
     */
    @Query("SELECT COUNT(c), COALESCE(SUM(c.totalTokens), 0), "
           + "COALESCE(AVG(c.responseTimeMs), 0), "
           + "SUM(CASE WHEN c.isSuccess = true THEN 1 ELSE 0 END) "
           + "FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime")
    Object[] getSummary(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 获取错误类型分布
     */
    @Query("SELECT c.errorCode, COUNT(c) "
           + "FROM ApiCallHistoryEntity c "
           + "WHERE c.isSuccess = false "
           + "AND c.createdAt BETWEEN :startTime AND :endTime "
           + "AND c.errorCode IS NOT NULL "
           + "GROUP BY c.errorCode ORDER BY COUNT(c) DESC")
    List<Object[]> countByErrorCode(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // ========== 独立聚合查询（避免 H2 Object[] 映射问题） ==========

    /**
     * 统计时间范围内的调用总数
     */
    @Query("SELECT COUNT(c) FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime")
    long countAllInRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计时间范围内的 Token 总量
     */
    @Query("SELECT COALESCE(SUM(c.totalTokens), 0) FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime")
    long sumTokensInRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计时间范围内的平均响应时间
     */
    @Query("SELECT COALESCE(AVG(c.responseTimeMs), 0) FROM ApiCallHistoryEntity c "
           + "WHERE c.createdAt BETWEEN :startTime AND :endTime")
    double avgResponseTimeInRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计时间范围内的成功调用数
     */
    @Query("SELECT COUNT(c) FROM ApiCallHistoryEntity c "
           + "WHERE c.isSuccess = true "
           + "AND c.createdAt BETWEEN :startTime AND :endTime")
    long countSuccessInRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // ========== 数据清理 ==========

    /**
     * 删除指定时间之前的调用记录
     */
    @Modifying
    @Query("DELETE FROM ApiCallHistoryEntity c WHERE c.createdAt < :cutoffTime")
    int deleteByCreatedAtBefore(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * 统计总记录数
     */
    @Query("SELECT COUNT(c) FROM ApiCallHistoryEntity c")
    long countAll();

    /**
     * 统计总 token 使用量
     */
    @Query("SELECT COALESCE(SUM(c.totalTokens), 0) FROM ApiCallHistoryEntity c")
    long sumAllTokens();
}
