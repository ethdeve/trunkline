package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 使用量统计仓库接口
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsageEntity, Long> {

    // ========== 基本查询方法 ==========

    /**
     * 根据追踪 ID 查找
     */
    List<TokenUsageEntity> findByTraceId(String traceId);

    /**
     * 根据模型名称查找
     */
    List<TokenUsageEntity> findByModelName(String modelName);

    /**
     * 根据服务类型查找
     */
    List<TokenUsageEntity> findByServiceType(String serviceType);

    /**
     * 根据 API Key ID 查找
     */
    List<TokenUsageEntity> findByApiKeyId(String apiKeyId);

    /**
     * 根据用户 ID 查找
     */
    List<TokenUsageEntity> findByUserId(String userId);

    // ========== 时间范围查询 ==========

    /**
     * 根据时间范围查询
     */
    @Query("SELECT t FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "ORDER BY t.occurredAt DESC")
    List<TokenUsageEntity> findByTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计时间范围内的 token 使用量
     */
    @Query("SELECT COALESCE(SUM(t.totalTokens), 0) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime")
    long countTotalTokensByTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // ========== 统计查询 ==========

    /**
     * 按模型名称统计 token 使用量
     */
    @Query("SELECT t.modelName, SUM(t.totalTokens), SUM(t.promptTokens), SUM(t.completionTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "GROUP BY t.modelName ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> countTokensByModel(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按服务类型统计 token 使用量
     */
    @Query("SELECT t.serviceType, SUM(t.totalTokens), SUM(t.promptTokens), SUM(t.completionTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "GROUP BY t.serviceType ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> countTokensByServiceType(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按提供商统计 token 使用量
     */
    @Query("SELECT t.provider, SUM(t.totalTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "AND t.provider IS NOT NULL "
           + "GROUP BY t.provider ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> countTokensByProvider(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按 API Key 统计 token 使用量
     */
    @Query("SELECT t.apiKeyId, SUM(t.totalTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "AND t.apiKeyId IS NOT NULL "
           + "GROUP BY t.apiKeyId ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> countTokensByApiKey(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按用户统计 token 使用量
     */
    @Query("SELECT t.userId, SUM(t.totalTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "AND t.userId IS NOT NULL "
           + "GROUP BY t.userId ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> countTokensByUser(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // ========== 账单聚合查询（v3.5 Trunkline UI 改造，§4.3） ==========

    /**
     * 按「用户 + 月份 + 厂商模型」聚合成功调用的 token 用量（「我的账单」按厂商模型拆分）。
     *
     * <p><b>计价口径</b>：账单按<b>厂商模型名</b>计价——员工请求的虚拟模型最终落到某个厂商模型实例，
     * 用量落在 {@code token_usage.instance_name}（实例名），按该名查 {@code model_price.model_name} 单价。
     * 因此本查询按 {@code instance_name} 分组，而非响应体里的 {@code model_name}
     * （后者是上游原样回传的模型名，与本网关的价格表键不一致）。</p>
     *
     * <p>{@code instance_name} 为空的历史数据用 {@code COALESCE} 回落到 {@code model_name}，
     * 保证用量不丢（这类行按 ¥0 计价，因其名称不会命中厂商模型价格行）。</p>
     *
     * <p>仅统计 {@code isSuccess = true} 的成功调用（失败调用不产生实际费用）。
     * 返回每行：{@code [modelName, promptTokens, completionTokens, totalTokens, requestCount,
     * successCount, snapshotAmount, pricedCount]}。其中（O1 成交价快照 / O2 未定价 ≠ ¥0）：</p>
     * <ul>
     *   <li>{@code snapshotAmount} —— 按落库时点固化的快照单价在 SQL 内求和的成交金额
     *       （{@code Σ(prompt×priceIn + completion×priceOut)/1000}）；价格缺失的行表达式为 NULL，
     *       SUM 自动忽略，COALESCE 兜 0；</li>
     *   <li>{@code pricedCount} —— 该模型下<b>有快照价</b>的行数；为 0 表示该模型全部用量
     *       无价可固（未定价 / 历史迁移行），服务层单列「待定价」、金额不入总额。</li>
     * </ul>
     *
     * @param userId 用户名（JWT subject，服务端 principal 透传）
     * @param year   年份
     * @param month  月份（1-12）
     * @return 按 totalTokens 降序的聚合行
     */
    @Query("SELECT COALESCE(t.instanceName, t.modelName), "
           + "COALESCE(SUM(t.promptTokens), 0), COALESCE(SUM(t.completionTokens), 0), "
           + "COALESCE(SUM(t.totalTokens), 0), COUNT(t), "
           + "COALESCE(SUM(CASE WHEN t.isSuccess = true THEN 1 ELSE 0 END), 0), "
           + "COALESCE(SUM(CASE WHEN t.priceMissing = false "
           + "AND t.priceInputSnapshot IS NOT NULL AND t.priceOutputSnapshot IS NOT NULL "
           + "THEN (t.promptTokens * t.priceInputSnapshot "
           + "+ t.completionTokens * t.priceOutputSnapshot) / 1000 ELSE 0 END), 0), "
           + "COALESCE(SUM(CASE WHEN t.priceMissing = false AND t.priceInputSnapshot IS NOT NULL "
           + "AND t.priceOutputSnapshot IS NOT NULL THEN 1 ELSE 0 END), 0) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.userId = :userId AND t.year = :year AND t.month = :month "
           + "AND t.isSuccess = true "
           + "GROUP BY COALESCE(t.instanceName, t.modelName) ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> aggregateByUserMonthModel(
            @Param("userId") String userId,
            @Param("year") Integer year,
            @Param("month") Integer month);

    /**
     * 按月汇总「有快照价」用量的成交金额（O1：账单只读快照，改价不影响历史账单）。
     *
     * <p>「我的账单」上月同期对比专用。价格缺失的行不计入（O2：未定价 ≠ ¥0）。</p>
     *
     * @param userId 用户名
     * @param year   年份
     * @param month  月份（1-12）
     * @return 单行单列：成交金额合计（无记录时 0）
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.priceMissing = false "
           + "AND t.priceInputSnapshot IS NOT NULL AND t.priceOutputSnapshot IS NOT NULL "
           + "THEN (t.promptTokens * t.priceInputSnapshot "
           + "+ t.completionTokens * t.priceOutputSnapshot) / 1000 ELSE 0 END), 0) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.userId = :userId AND t.year = :year AND t.month = :month "
           + "AND t.isSuccess = true "
           + "AND t.priceMissing = false AND t.priceInputSnapshot IS NOT NULL "
           + "AND t.priceOutputSnapshot IS NOT NULL")
    List<Object[]> sumPricedAmountByUserMonth(
            @Param("userId") String userId,
            @Param("year") Integer year,
            @Param("month") Integer month);

    /**
     * 按月汇总「待定价」用量的 token 与请求数（O2：未定价 ≠ ¥0、账单页可见）。
     *
     * @param userId 用户名
     * @param year   年份
     * @param month  月份（1-12）
     * @return 单行：{@code [unpricedTokens, unpricedRequests]}（无记录时 0）
     */
    @Query("SELECT COALESCE(SUM(t.totalTokens), 0), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.userId = :userId AND t.year = :year AND t.month = :month "
           + "AND t.isSuccess = true "
           + "AND (t.priceMissing = true OR t.priceInputSnapshot IS NULL)")
    List<Object[]> sumUnpricedByUserMonth(
            @Param("userId") String userId,
            @Param("year") Integer year,
            @Param("month") Integer month);

    /**
     * 按「用户 + 月份」聚合成功调用的总 token 与请求数（「我的账单」月度汇总）。
     *
     * <p>返回单行：{@code [totalTokens, promptTokens, completionTokens, requestCount]}；
     * 无记录时各列返回 0（COALESCE）。输入 / 输出分别汇总，因单价可能不同需分开计价。</p>
     *
     * @param userId 用户名（JWT subject，服务端 principal 透传）
     * @param year   年份
     * @param month  月份（1-12）
     * @return 单行聚合结果
     */
    @Query("SELECT COALESCE(SUM(t.totalTokens), 0), COALESCE(SUM(t.promptTokens), 0), "
           + "COALESCE(SUM(t.completionTokens), 0), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.userId = :userId AND t.year = :year AND t.month = :month "
           + "AND t.isSuccess = true")
    List<Object[]> aggregateByUserMonth(
            @Param("userId") String userId,
            @Param("year") Integer year,
            @Param("month") Integer month);

    /**
     * 按「用户 + 月份 + 日期 + 虚拟模型」聚合成功调用（「我的账单」按天 × 虚拟模型交叉表用）。
     *
     * <p>v3.5 Trunkline UI 改造：原型的「我的账单」是<b>按天汇总</b>的交叉表——
     * 行是日期，列是虚拟模型，单元格是该天该虚拟模型的花费。
     * 员工视角不该关心背后是哪个厂商模型，因此按 {@code virtualModel} 分组。</p>
     *
     * <p><b>计价维度与分组维度分离</b>：分组按虚拟模型（员工视角），
     * 但计价必须按厂商模型（{@code instanceName}，即请求实际落到的模型实例）——
     * 单价表 {@code model_price} 是按厂商模型名配的。因此本查询同时返回
     * 虚拟模型（分组用）与厂商模型（计价用），由服务层按厂商模型匹配单价后再汇总到虚拟模型。</p>
     *
     * <p>{@code virtualModel} 为 {@code null} 表示未命中任何虚拟模型（历史数据 / 内部路径），
     * 服务层归入「未归类」，不丢数据。</p>
     *
     * <p>仅统计 {@code isSuccess = true} 的成功调用（失败调用不产生实际费用）。
     * 返回每行：{@code [usageDate, virtualModel, instanceName, promptTokens, completionTokens,
     * totalTokens, requestCount, snapshotAmount]}。{@code snapshotAmount} 为该组按快照单价
     * 求和的成交金额（O1：价格缺失行为 0，金额不入总额）。</p>
     *
     * @param userId 用户名（JWT subject，服务端 principal 透传）
     * @param year   年份
     * @param month  月份（1-12）
     * @return 按日期、虚拟模型分组的聚合行
     */
    @Query("SELECT t.usageDate, t.virtualModel, COALESCE(t.instanceName, t.modelName), "
           + "COALESCE(SUM(t.promptTokens), 0), COALESCE(SUM(t.completionTokens), 0), "
           + "COALESCE(SUM(t.totalTokens), 0), COUNT(t), "
           + "COALESCE(SUM((t.promptTokens * t.priceInputSnapshot "
           + "+ t.completionTokens * t.priceOutputSnapshot) / 1000), 0) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.userId = :userId AND t.year = :year AND t.month = :month "
           + "AND t.isSuccess = true "
           + "GROUP BY t.usageDate, t.virtualModel, COALESCE(t.instanceName, t.modelName) "
           + "ORDER BY t.usageDate DESC, SUM(t.totalTokens) DESC")
    List<Object[]> aggregateByUserMonthDayVirtualModel(
            @Param("userId") String userId,
            @Param("year") Integer year,
            @Param("month") Integer month);

    /**
     * 按「用户 + 虚拟模型」聚合指定日期区间内的成功调用（「我的额度」页「本周最常用」用）。
     *
     * <p>v3.5 Trunkline UI 改造：额度首页要回答「我最近主要在用哪个虚拟模型」，
     * 需要一个<b>跨自然月</b>的区间聚合——本月的按天聚合（{@code aggregateByUserMonthDayVirtualModel}）
     * 在月初会取不到上月末的数据，故另设本查询。</p>
     *
     * <p>计价维度与分组维度分离（同上）：分组按虚拟模型，金额按厂商模型单价算。
     * 返回每行：{@code [virtualModel, instanceName, promptTokens, completionTokens,
     * totalTokens, requestCount, snapshotAmount]}。{@code snapshotAmount} 为该组按快照
     * 单价求和的成交金额（O1：价格缺失行为 0）。</p>
     *
     * <p>仅统计 {@code isSuccess = true} 的成功调用。</p>
     *
     * @param userId    用户名（JWT subject，服务端 principal 透传）
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     * @return 按虚拟模型分组的聚合行
     */
    @Query("SELECT t.virtualModel, COALESCE(t.instanceName, t.modelName), "
           + "COALESCE(SUM(t.promptTokens), 0), COALESCE(SUM(t.completionTokens), 0), "
           + "COALESCE(SUM(t.totalTokens), 0), COUNT(t), "
           + "COALESCE(SUM((t.promptTokens * t.priceInputSnapshot "
           + "+ t.completionTokens * t.priceOutputSnapshot) / 1000), 0) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.userId = :userId AND t.usageDate BETWEEN :startDate AND :endDate "
           + "AND t.isSuccess = true "
           + "GROUP BY t.virtualModel, COALESCE(t.instanceName, t.modelName) "
           + "ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> aggregateByUserDateRangeVirtualModel(
            @Param("userId") String userId,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate);

    /**
     * 按「用户 + 月份 + 厂商模型」聚合全公司范围内的成功调用（「团队用量」用）。
     *
     * <p>与 {@link #aggregateByUserMonthModel} 的区别：<b>不限定单个用户</b>，
     * 返回该月全部用户的逐人逐厂商模型用量。部门归属在服务层用
     * {@code jwt_accounts.username → department} 内存关联得出——避免跨实体 JOIN
     * 在 H2 / Postgres 上的方言差异。</p>
     *
     * <p>计价口径与「我的账单」一致：按 {@code instance_name}（厂商模型名）分组，
     * 空值回落 {@code model_name}。</p>
     *
     * <p>返回每行：{@code [userId, modelName, promptTokens, completionTokens, totalTokens, requestCount]}。</p>
     *
     * @param year  年份
     * @param month 月份（1-12）
     * @return 按 totalTokens 降序的聚合行
     */
    @Query("SELECT t.userId, COALESCE(t.instanceName, t.modelName), "
           + "COALESCE(SUM(t.promptTokens), 0), COALESCE(SUM(t.completionTokens), 0), "
           + "COALESCE(SUM(t.totalTokens), 0), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.year = :year AND t.month = :month "
           + "AND t.isSuccess = true "
           + "AND t.userId IS NOT NULL "
           + "GROUP BY t.userId, COALESCE(t.instanceName, t.modelName) ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> aggregateByMonthUserModel(
            @Param("year") Integer year,
            @Param("month") Integer month);

    /**
     * 按「用户 + 虚拟模型 + 厂商模型 + 用途类型」聚合某月的<b>全部</b>调用（含失败）。
     *
     * <p>v3.5 Trunkline：「团队用量」页需要同时呈现四个维度——按虚拟模型（员工看到的对外名字）
     * 排行、按厂商模型（实际落到的实例）下钻、按用途类型（chat/embedding/...）分布，
     * 以及成功率。因此一次查询取回这四个维度的明细行，服务层在内存中做多维聚合，
     * 避免为每个维度各发一条 SQL。</p>
     *
     * <p><b>为什么必须按虚拟模型落库、而不是事后用「实例 → 分组」反查</b>：
     * 同一个厂商模型实例可能同时被多个虚拟模型引用（如 OpenAI 实例既挂「公司主力」
     * 又挂「便宜日常」），反查无法确定该笔用量究竟属于哪个虚拟模型；而请求在路由时
     * 只命中一个虚拟模型，落库时的正向取值是无歧义的。</p>
     *
     * <p>计价口径仍按厂商模型（{@code COALESCE(instance_name, model_name)}），
     * 与「我的账单」同源——虚拟模型只是归属维度，不是计价维度。</p>
     *
     * <p>返回每行：
     * {@code [userId, virtualModel, instanceName, serviceType, promptTokens, completionTokens,
     * totalTokens, requestCount, successCount, snapshotAmount, pricedCount]}。其中</p>
     * <ul>
     *   <li>{@code virtualModel} 为 {@code null} 表示未命中任何虚拟模型（历史数据 /
     *       规则锁定实例等内部路径），服务层归入「未归类」，<b>不丢数据</b>；</li>
     *   <li>{@code successCount} 为该行成功调用数，失败数 = requestCount − successCount；</li>
     *   <li>{@code snapshotAmount} / {@code pricedCount} 为 P0-3 追加的两列：仅当
     *       {@code priceMissing=false} 且<b>双列快照同时非空</b>时才累加成交金额并计数，
     *       供「团队用量」与「我的账单」共用同一套快照口径，半价行不会被当成已定价。</li>
     * </ul>
     *
     * @param year  年份
     * @param month 月份（1-12）
     * @return 聚合明细行
     */
    @Query("SELECT t.userId, t.virtualModel, COALESCE(t.instanceName, t.modelName), t.serviceType, "
           + "COALESCE(SUM(t.promptTokens), 0), COALESCE(SUM(t.completionTokens), 0), "
           + "COALESCE(SUM(t.totalTokens), 0), COUNT(t), "
           + "COALESCE(SUM(CASE WHEN t.isSuccess = true THEN 1 ELSE 0 END), 0), "
           + "COALESCE(SUM(CASE WHEN t.priceMissing = false "
           + "  AND t.priceInputSnapshot IS NOT NULL AND t.priceOutputSnapshot IS NOT NULL "
           + "  THEN (t.promptTokens * t.priceInputSnapshot "
           + "      + t.completionTokens * t.priceOutputSnapshot) / 1000 ELSE 0 END), 0), "
           + "COALESCE(SUM(CASE WHEN t.priceMissing = false "
           + "  AND t.priceInputSnapshot IS NOT NULL AND t.priceOutputSnapshot IS NOT NULL "
           + "  THEN 1 ELSE 0 END), 0) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.year = :year AND t.month = :month "
           + "AND t.userId IS NOT NULL "
           + "GROUP BY t.userId, t.virtualModel, COALESCE(t.instanceName, t.modelName), t.serviceType "
           + "ORDER BY SUM(t.totalTokens) DESC")
    List<Object[]> aggregateByMonthUserVirtualModel(
            @Param("year") Integer year,
            @Param("month") Integer month);

    /**
     * 按日期统计 token 使用量（日级别）
     */    @Query("SELECT t.usageDate, SUM(t.totalTokens), SUM(t.promptTokens), SUM(t.completionTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "GROUP BY t.usageDate ORDER BY t.usageDate")
    List<Object[]> countTokensByDay(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按小时统计 token 使用量
     */
    @Query("SELECT t.hour, SUM(t.totalTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "GROUP BY t.hour ORDER BY t.hour")
    List<Object[]> countTokensByHour(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按周统计 token 使用量（周级别）
     */
    @Query("SELECT t.year, t.weekOfYear, SUM(t.totalTokens), SUM(t.promptTokens), SUM(t.completionTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "GROUP BY t.year, t.weekOfYear ORDER BY t.year, t.weekOfYear")
    List<Object[]> countTokensByWeek(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按月统计 token 使用量（月级别）
     */
    @Query("SELECT t.year, t.month, SUM(t.totalTokens), SUM(t.promptTokens), SUM(t.completionTokens), COUNT(t) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "GROUP BY t.year, t.month ORDER BY t.year, t.month")
    List<Object[]> countTokensByMonth(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按模型和日期统计 token 使用量（用于热力图）
     */
    @Query("SELECT t.modelName, t.usageDate, SUM(t.totalTokens) "
           + "FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "GROUP BY t.modelName, t.usageDate ORDER BY t.modelName, t.usageDate")
    List<Object[]> countTokensByModelAndDay(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计成功率
     */
    @Query("SELECT COUNT(t) FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "AND t.isSuccess = true")
    long countSuccessByTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计时间范围内的总请求次数（成功率分母用，issue #714）
     *
     * <p>与 {@link #countSuccessByTimeRange} / {@link #countFailedByTimeRange} 同窗口：
     * 成功率 = 窗口内成功数 ÷ 窗口内总数。修复前分母用 {@link #countAllUsage()}
     * 统计全表，窗口远小于全表时成功率被严重稀释（如全表 1000 条、窗口 10 条、
     * 成功 7 条会得出 0.7% 而非 70%）。</p>
     *
     * <p>{@code isSuccess} 为 {@code null} 的记录计入分母但不计入成功数，
     * 与成功/失败两条查询的口径一致（成功 + 失败 ≤ 总数）。</p>
     */
    @Query("SELECT COUNT(t) FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime")
    long countByTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计失败数
     */
    @Query("SELECT COUNT(t) FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "AND t.isSuccess = false")
    long countFailedByTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 统计平均响应时间
     */
    @Query("SELECT COALESCE(AVG(t.responseTimeMs), 0) FROM TokenUsageEntity t "
           + "WHERE t.occurredAt BETWEEN :startTime AND :endTime "
           + "AND t.responseTimeMs IS NOT NULL")
    double avgResponseTimeByTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // ========== 最近使用记录查询 ==========

    /**
     * 获取最近的使用记录
     */
    @Query("SELECT t FROM TokenUsageEntity t ORDER BY t.occurredAt DESC LIMIT :limit")
    List<TokenUsageEntity> findRecentUsage(@Param("limit") int limit);

    /**
     * 获取指定模型的最近使用记录
     */
    @Query("SELECT t FROM TokenUsageEntity t WHERE t.modelName = :modelName ORDER BY t.occurredAt DESC LIMIT :limit")
    List<TokenUsageEntity> findRecentUsageByModel(
            @Param("modelName") String modelName,
            @Param("limit") int limit);

    // ========== 清理过期数据 ==========

    /**
     * 删除指定时间之前的使用记录
     */
    @Modifying
    @Query("DELETE FROM TokenUsageEntity t WHERE t.occurredAt < :cutoffTime")
    int deleteByOccurredAtBefore(@Param("cutoffTime") LocalDateTime cutoffTime);

    // ========== #10 被遗忘权精确擦除 ==========
    // 财务硬约束：金额/token/trace_id/价格快照一律不动，仅身份字段假名化（方案 3.3 / DoD）。

    /**
     * 按用户假名化用量明细的身份字段（userId / apiKeyId / clientIp）。
     *
     * <p>仅改身份列；amount/token/trace_id/priceSnapshot 原样保留，保证 B4 对账口径一致。
     * 假名由企业侧执行器传入（如 "erased:{tenantId}:{userId}" 的不可逆占位），主干不猜算法。</p>
     *
     * @param userId     数据主体
     * @param tenantId   租户隔离条件，必填
     * @param pseudonym  假名占位值
     */
    @Modifying
    @Query("UPDATE TokenUsageEntity t SET t.userId = :pseudonym, t.apiKeyId = :pseudonym, "
            + "t.clientIp = :pseudonym WHERE t.userId = :userId AND t.tenantId = :tenantId")
    int pseudonymizeByUserIdAndTenantId(@Param("userId") String userId,
                                        @Param("tenantId") String tenantId,
                                        @Param("pseudonym") String pseudonym);

    @Query("SELECT COUNT(t) FROM TokenUsageEntity t WHERE t.userId = :userId AND t.tenantId = :tenantId")
    long countByUserIdAndTenantId(@Param("userId") String userId, @Param("tenantId") String tenantId);

    /**
     * 统计总记录数
     */
    @Query("SELECT COUNT(t) FROM TokenUsageEntity t")
    long countAllUsage();

    /**
     * 统计总 token 使用量
     */
    @Query("SELECT COALESCE(SUM(t.totalTokens), 0) FROM TokenUsageEntity t")
    long sumAllTokens();
}