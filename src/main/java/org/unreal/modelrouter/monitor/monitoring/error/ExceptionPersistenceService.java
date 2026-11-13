package org.unreal.modelrouter.monitor.monitoring.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.unreal.modelrouter.persistence.jpa.entity.ExceptionEventEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ExceptionStatsHourlyEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ExceptionEventRepository;
import org.unreal.modelrouter.monitor.tracing.TracingContext;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异常持久化服务
 * 
 * 负责将异常事件持久化到数据库，并提供小时级别的聚合统计功能。
 * 
 * @author JAiRouter Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionPersistenceService {

    private final ExceptionEventRepository exceptionEventRepository;
    private final ObjectMapper objectMapper;
    private final ErrorCodeResolver errorCodeResolver;

    /**
     * 事务管理器：{@code this::} 定时任务与类内自调用不走 AOP 代理，
     * 必须用它构造 TransactionTemplate 显式开启事务（issue #857）。
     */
    private final PlatformTransactionManager transactionManager;

    /** 由 init() 初始化；TransactionTemplate 无状态，未初始化时按需创建 */
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    // 内存中的小时统计缓存
    private final ConcurrentHashMap<String, ExceptionStatsHourlyEntity> hourlyStatsCache = new ConcurrentHashMap<>();

    // 异常聚合缓存（用于批量更新）
    private final ConcurrentHashMap<String, AtomicLong> aggregationCounters = new ConcurrentHashMap<>();

    // 定时任务调度器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * 初始化定时任务
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);

        // 每 5 分钟将缓存的小时统计数据写入数据库（自调用，走显式事务入口）
        scheduler.scheduleAtFixedRate(this::flushHourlyStatsSafely, 5, 5, TimeUnit.MINUTES);

        // 每小时清理一次聚合缓存（纯内存清理，不涉及 DB 写，无需事务）
        scheduler.scheduleAtFixedRate(this::cleanupAggregationCache, 1, 1, TimeUnit.HOURS);

        log.info("ExceptionPersistenceService 定时任务已启动");
    }

    /**
     * 持久化异常事件
     * 
     * @param throwable 异常对象
     * @param operation 操作名称
     * @param context 追踪上下文
     * @param additionalInfo 额外信息
     * @param sanitizedMessage 脱敏后的消息
     * @param sanitizedStackTrace 脱敏后的堆栈跟踪
     * @return 保存的异常事件实体
     */
    @Transactional
    public ExceptionEventEntity persistException(
            final Throwable throwable,
            final String operation,
            final TracingContext context,
            final Map<String, Object> additionalInfo,
            final String sanitizedMessage,
            final String sanitizedStackTrace) {

        try {
            // 创建异常事件实体
            ExceptionEventEntity entity = buildExceptionEventEntity(
                    throwable, operation, context, additionalInfo, sanitizedMessage, sanitizedStackTrace);
            
            // 保存事件
            ExceptionEventEntity savedEntity = exceptionEventRepository.save(entity);
            
            // 更新小时统计
            updateHourlyStats(throwable, operation, context, additionalInfo);
            
            return savedEntity;
            
        } catch (Exception e) {
            log.error("持久化异常事件失败：{}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构建异常事件实体
     */
    private ExceptionEventEntity buildExceptionEventEntity(
            final Throwable throwable,
            final String operation,
            final TracingContext context,
            final Map<String, Object> additionalInfo,
            final String sanitizedMessage,
            final String sanitizedStackTrace) {

        LocalDateTime now = LocalDateTime.now();
        
        // 解析错误代码和分类
        String errorCode = errorCodeResolver.resolveErrorCode(throwable);
        ErrorCodeResolver.ErrorCategory errorCategory = errorCodeResolver.resolveErrorCategory(throwable);
        String httpStatus = errorCodeResolver.resolveHttpStatus(throwable);

        // 构建实体
        ExceptionEventEntity.ExceptionEventEntityBuilder builder = ExceptionEventEntity.builder()
                .eventId(UUID.randomUUID().toString())
                .exceptionType(throwable.getClass().getName())
                .exceptionMessage(truncate(throwable.getMessage(), 1000))
                .sanitizedMessage(truncate(sanitizedMessage, 1000))
                .sanitizedStackTrace(sanitizedStackTrace)
                .operation(operation)
                .errorCode(errorCode)
                .errorCategory(errorCategory.name())
                .httpStatus(httpStatus)
                .occurredAt(now)
                .firstOccurrence(now)
                .lastOccurrence(now);

        // 从追踪上下文填充信息
        if (context != null && context.isActive()) {
            builder.traceId(context.getTraceId())
                   .spanId(context.getSpanId());
        }

        // 从额外信息填充
        if (additionalInfo != null) {
            builder.clientIp(getStringValue(additionalInfo, "clientIp"))
                   .userAgent(getStringValue(additionalInfo, "userAgent"))
                   .serviceName(getStringValue(additionalInfo, "serviceName"))
                   .methodName(getStringValue(additionalInfo, "methodName"))
                   .className(getStringValue(additionalInfo, "className"))
                   .serviceType(getStringValue(additionalInfo, "serviceType"))
                   .modelName(getStringValue(additionalInfo, "modelName"))
                   .provider(getStringValue(additionalInfo, "provider"))
                   .instanceName(getStringValue(additionalInfo, "instanceName"))
                   .responseTimeMs(getLongValue(additionalInfo, "responseTimeMs"));

            // 尝试解析元数据为 JSON
            try {
                builder.metadata(objectMapper.writeValueAsString(additionalInfo));
            } catch (JsonProcessingException e) {
                log.warn("序列化元数据失败：{}", e.getMessage());
            }
        }

        return builder.build();
    }

    /**
     * 更新小时统计
     */
    private void updateHourlyStats(
            final Throwable throwable,
            final String operation,
            final TracingContext context,
            final Map<String, Object> additionalInfo) {

        try {
            // 截断到小时
            LocalDateTime hourTimestamp = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
            
            // 解析错误信息
            String exceptionType = throwable.getClass().getName();
            String errorCode = errorCodeResolver.resolveErrorCode(throwable);
            ErrorCodeResolver.ErrorCategory errorCategory = errorCodeResolver.resolveErrorCategory(throwable);
            String serviceName = additionalInfo != null ? getStringValue(additionalInfo, "serviceName") : null;

            // 生成缓存键
            String cacheKey = generateHourlyStatsKey(hourTimestamp, exceptionType, errorCode, operation);

            // 获取或创建统计实体
            ExceptionStatsHourlyEntity stats = hourlyStatsCache.computeIfAbsent(cacheKey, k -> {
                ExceptionStatsHourlyEntity entity = new ExceptionStatsHourlyEntity();
                entity.setHourTimestamp(hourTimestamp);
                entity.setExceptionType(exceptionType);
                entity.setErrorCode(errorCode);
                entity.setErrorCategory(errorCategory.name());
                entity.setOperation(operation);
                entity.setServiceName(serviceName);
                entity.setTotalCount(0L);
                entity.setSuccessCount(0L);
                entity.setFailureCount(0L);
                entity.setUniqueTraceIds(0L);
                entity.setUniqueClientIps(0L);
                return entity;
            });

            // 更新计数
            stats.incrementTotal();
            
            // 根据 HTTP 状态判断成功/失败
            String httpStatus = errorCodeResolver.resolveHttpStatus(throwable);
            if (httpStatus.startsWith("2")) {
                stats.incrementSuccess();
            } else {
                stats.incrementFailure();
            }

            // 统计唯一追踪 ID 和客户端 IP（简化实现，实际可能需要 Set）
            if (context != null && context.getTraceId() != null) {
                stats.setUniqueTraceIds(stats.getUniqueTraceIds() + 1);
            }
            if (additionalInfo != null && additionalInfo.get("clientIp") != null) {
                stats.setUniqueClientIps(stats.getUniqueClientIps() + 1);
            }

        } catch (Exception e) {
            log.warn("更新小时统计失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 将小时统计数据刷新到数据库（供跨 Bean 代理调用；类内自调用请用 {@link #flushHourlyStatsSafely()}）。
     */
    @Transactional
    public void flushHourlyStatsToDatabase() {
        doFlushHourlyStats();
    }

    /**
     * 定时任务与关闭钩子的安全入口（issue #857）。
     *
     * <p>{@code this::} 与类内自调用绑定的是原始对象而非 AOP 代理，注解式事务不会生效，
     * 无事务时 {@code entityManager.merge} 要么抛 TransactionRequiredException，要么静默不落库。
     * 这里用 TransactionTemplate 显式开启事务；外层兜底日志位于事务之外，
     * 既让异常触发整体回滚，也避免异常终止 scheduleAtFixedRate 的后续调度。</p>
     */
    public void flushHourlyStatsSafely() {
        // 空缓存直接返回，避免每 5 分钟空转一个事务（此刻并发新增的条目留到下一轮，无数据丢失）
        if (hourlyStatsCache.isEmpty()) {
            return;
        }
        try {
            transactionTemplate().executeWithoutResult(status -> doFlushHourlyStats());
        } catch (Exception e) {
            // 事务已回滚、缓存仍保留，下一轮定时刷新会重试；@PreDestroy 阶段 tm 可能已关闭，同样在此兜底
            log.error("刷新小时统计数据失败（已回滚，缓存保留待重试）：{}", e.getMessage(), e);
        }
    }

    private TransactionTemplate transactionTemplate() {
        // init() 未执行时（如直接单测调用）也能拿到模板；模板无状态，重复创建无副作用
        return transactionTemplate != null ? transactionTemplate : new TransactionTemplate(transactionManager);
    }

    /**
     * 实际刷新逻辑：不吞异常，保证失败时事务整体回滚。
     * 缓存清理注册在 afterCommit，回滚时保留内存数据；且按快照逐条比对移除，
     * 不会清掉刷新期间由 persistException 并发新增的统计。
     */
    private void doFlushHourlyStats() {
        if (hourlyStatsCache.isEmpty()) {
            return;
        }

        Map<String, ExceptionStatsHourlyEntity> snapshot = new HashMap<>(hourlyStatsCache);
        log.info("开始刷新小时统计数据到数据库，缓存大小：{}", snapshot.size());

        // 这里使用简单的保存策略，查询时按小时聚合；不再逐条 catch，任一条失败即整体回滚
        for (ExceptionStatsHourlyEntity stats : snapshot.values()) {
            entityManager.merge(stats);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshot.forEach((key, value) -> hourlyStatsCache.remove(key, value));
                    log.info("小时统计数据刷新完成，提交后清理 {} 条缓存", snapshot.size());
                }
            });
        } else {
            // 正常链路不可达；保守起见不清缓存，宁可下次重复 merge 也不丢数据
            log.warn("无事务同步上下文，小时统计缓存保留待下次刷新重试");
        }
    }

    /**
     * 清理聚合缓存
     */
    public void cleanupAggregationCache() {
        try {
            int sizeBefore = aggregationCounters.size();
            aggregationCounters.clear();
            log.info("清理聚合缓存，清理前大小：{}", sizeBefore);
        } catch (Exception e) {
            log.warn("清理聚合缓存失败：{}", e.getMessage());
        }
    }

    /**
     * 生成小时统计缓存键
     */
    private String generateHourlyStatsKey(
            final LocalDateTime hourTimestamp,
            final String exceptionType, final String errorCode,
            final String operation) {
        return hourTimestamp.toString() + "|" + exceptionType + "|" + errorCode + "|" + operation;
    }

    /**
     * 从 Map 中安全获取字符串值
     */
    private String getStringValue(final Map<String, Object> map, final String key) {
        if (map == null || !map.containsKey(key)) {
            return null;
        }
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 从 Map 中安全获取 Long 值
     */
    private Long getLongValue(final Map<String, Object> map, final String key) {
        if (map == null || !map.containsKey(key)) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 截断字符串到指定长度
     */
    private String truncate(final String str, final int maxLength) {
        if (str == null) {
            return null;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...";
    }

    /**
     * 获取最近的异常事件
     */
    public List<ExceptionEventEntity> findRecentEvents(final int limit) {
        return exceptionEventRepository.findRecentEvents(limit);
    }

    /**
     * 根据条件查询异常事件
     */
    public org.springframework.data.domain.Page<ExceptionEventEntity> findByConditions(
            final LocalDateTime startTime,
            final LocalDateTime endTime,
            final String exceptionType,
            final String operation,
            final String errorCode,
            final String errorCategory,
            final String traceId,
            final String clientIp,
            final Boolean isAggregated,
            final String serviceType,
            final String modelName,
            final org.springframework.data.domain.Pageable pageable) {

        return exceptionEventRepository.findByConditions(
                startTime, endTime, exceptionType, operation, errorCode, errorCategory,
                traceId, clientIp, isAggregated, serviceType, modelName, pageable);
    }

    /**
     * 获取异常统计信息
     */
    public Map<String, Object> getExceptionStatistics(final LocalDateTime startTime, final LocalDateTime endTime) {
        Map<String, Object> result = new HashMap<>();
        
        // 按类型统计
        List<Object[]> byType = exceptionEventRepository.countByExceptionType(startTime, endTime);
        Map<String, Long> typeStats = new HashMap<>();
        byType.forEach(row -> typeStats.put((String) row[0], (Long) row[1]));
        result.put("byType", typeStats);
        
        // 按分类统计
        List<Object[]> byCategory = exceptionEventRepository.countByErrorCategory(startTime, endTime);
        Map<String, Long> categoryStats = new HashMap<>();
        byCategory.forEach(row -> categoryStats.put((String) row[0], (Long) row[1]));
        result.put("byCategory", categoryStats);
        
        // 总数
        long total = exceptionEventRepository.countByTimeRange(startTime, endTime);
        result.put("total", total);
        
        return result;
    }

    /**
     * 关闭服务
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        try {
            // 刷新剩余数据：自调用不走代理，必须走显式事务入口
            flushHourlyStatsSafely();
            
            // 关闭调度器
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            log.info("ExceptionPersistenceService 已关闭");
        } catch (Exception e) {
            log.error("关闭 ExceptionPersistenceService 失败：{}", e.getMessage(), e);
        }
    }
}
