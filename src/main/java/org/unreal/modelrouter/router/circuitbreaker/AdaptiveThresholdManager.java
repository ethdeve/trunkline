package org.unreal.modelrouter.router.circuitbreaker;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.config.service.CircuitBreakerConfigService;
import org.unreal.modelrouter.persistence.jpa.entity.CircuitBreakerMetricsEntity;
import org.unreal.modelrouter.persistence.jpa.repository.CircuitBreakerMetricsRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 自适应熔断阈值管理器
 * 
 * 根据历史统计数据动态调整每个实例的熔断阈值：
 * - 低失败率：适当放宽阈值
 * - 高失败率：收紧阈值
 * 
 * @author JAiRouter Team
 */
@Service
@RequiredArgsConstructor
public class AdaptiveThresholdManager {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveThresholdManager.class);

    // 阈值边界
    private static final int MIN_FAILURE_THRESHOLD = 2;
    private static final int MAX_FAILURE_THRESHOLD = 20;
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;

    // 失败率阈值
    private static final double LOW_FAILURE_RATE = 0.01;      // 1%
    private static final double MEDIUM_FAILURE_RATE = 0.05;   // 5%
    private static final double HIGH_FAILURE_RATE = 0.10;     // 10%

    // 调整系数
    private static final double RELAX_FACTOR = 1.1;    // 放宽 10%
    private static final double TIGHTEN_FACTOR = 0.8;  // 收紧 20%
    private static final double SEVERE_TIGHTEN_FACTOR = 0.6;  // 严重收紧 40%

    private final CircuitBreakerMetricsRepository metricsRepository;
    private final CircuitBreakerConfigService configService;
    private final CircuitBreakerManager circuitBreakerManager;

    // 实时统计数据缓存（内存）
    private final Map<String, InstanceMetrics> metricsCache = new ConcurrentHashMap<>();

    // 是否启用自适应调整
    private volatile boolean adaptiveEnabled = false;

    /**
     * 记录调用成功
     */
    public void recordSuccess(final String instanceId, final String instanceName, final String serviceType) {
        InstanceMetrics metrics = metricsCache.computeIfAbsent(instanceId, 
                k -> new InstanceMetrics(instanceId, instanceName, serviceType));
        metrics.recordSuccess();
    }

    /**
     * 记录调用失败
     */
    public void recordFailure(final String instanceId, final String instanceName, final String serviceType) {
        InstanceMetrics metrics = metricsCache.computeIfAbsent(instanceId,
                k -> new InstanceMetrics(instanceId, instanceName, serviceType));
        metrics.recordFailure();
    }

    /**
     * 设置是否启用自适应调整
     */
    public void setAdaptiveEnabled(final boolean enabled) {
        this.adaptiveEnabled = enabled;
        log.info("自适应阈值调整已{}", enabled ? "启用" : "禁用");
    }

    /**
     * 获取是否启用自适应调整
     */
    public boolean isAdaptiveEnabled() {
        return adaptiveEnabled;
    }

    /**
     * 定时执行阈值评估（每5分钟）
     */
    @Scheduled(fixedRate = 300000, initialDelay = 60000)
    @Transactional
    public void evaluateAndAdjustThresholds() {
        if (!adaptiveEnabled) {
            return;
        }

        log.debug("开始执行自适应阈值评估...");
        
        try {
            // 1. 持久化当前统计数据
            persistCurrentMetrics();

            // 2. 分析历史数据并调整阈值
            analyzeAndAdjust();

            // 3. 重置统计数据（开始新的时间窗口）
            resetMetricsCache();

            log.debug("自适应阈值评估完成");
        } catch (Exception e) {
            log.error("自适应阈值评估失败", e);
        }
    }

    /**
     * 持久化当前统计数据到数据库
     */
    private void persistCurrentMetrics() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes(5);

        for (InstanceMetrics metrics : metricsCache.values()) {
            if (metrics.getTotalCalls() == 0) {
                continue;
            }

            CircuitBreakerMetricsEntity entity = CircuitBreakerMetricsEntity.builder()
                    .instanceId(metrics.instanceId)
                    .instanceName(metrics.instanceName)
                    .serviceType(metrics.serviceType)
                    .windowStart(windowStart)
                    .windowEnd(now)
                    .totalCalls(metrics.getTotalCalls())
                    .failureCalls(metrics.getFailureCalls())
                    .successCalls(metrics.getSuccessCalls())
                    .failureRate(metrics.getFailureRate())
                    .currentFailureThreshold(getCurrentThreshold(metrics.instanceId))
                    .build();

            metricsRepository.save(entity);
        }
    }

    /**
     * 分析历史数据并调整阈值
     */
    private void analyzeAndAdjust() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime analysisStart = now.minusMinutes(30); // 分析最近30分钟的数据

        List<CircuitBreakerMetricsEntity> recentMetrics = metricsRepository.findByWindowEndBetween(analysisStart, now);

        // 按实例分组统计
        Map<String, AggregatedMetrics> aggregated = aggregateMetrics(recentMetrics);

        // 对每个实例进行阈值调整
        for (Map.Entry<String, AggregatedMetrics> entry : aggregated.entrySet()) {
            String instanceId = entry.getKey();
            AggregatedMetrics agg = entry.getValue();

            adjustThresholdForInstance(instanceId, agg);
        }
    }

    /**
     * 聚合统计数据
     */
    private Map<String, AggregatedMetrics> aggregateMetrics(final List<CircuitBreakerMetricsEntity> metricsList) {
        Map<String, AggregatedMetrics> result = new HashMap<>();

        for (CircuitBreakerMetricsEntity entity : metricsList) {
            AggregatedMetrics agg = result.computeIfAbsent(entity.getInstanceId(), 
                    k -> new AggregatedMetrics());

            agg.totalCalls += entity.getTotalCalls() != null ? entity.getTotalCalls() : 0;
            agg.failureCalls += entity.getFailureCalls() != null ? entity.getFailureCalls() : 0;
            agg.successCalls += entity.getSuccessCalls() != null ? entity.getSuccessCalls() : 0;
            agg.sampleCount++;
        }

        return result;
    }

    /**
     * 调整单个实例的阈值
     */
    private void adjustThresholdForInstance(final String instanceId, final AggregatedMetrics agg) {
        if (agg.totalCalls < 10) {
            // 调用次数太少，不调整
            log.debug("实例 {} 调用次数不足 ({})，跳过阈值调整", instanceId, agg.totalCalls);
            return;
        }

        double failureRate = (double) agg.failureCalls / agg.totalCalls;
        int currentThreshold = getCurrentThreshold(instanceId);
        int newThreshold = calculateNewThreshold(currentThreshold, failureRate);

        if (newThreshold != currentThreshold) {
            applyThresholdAdjustment(instanceId, currentThreshold, newThreshold, failureRate);
        }
    }

    /**
     * 计算新的阈值
     */
    private int calculateNewThreshold(final int currentThreshold, final double failureRate) {
        int newThreshold = currentThreshold;

        if (failureRate < LOW_FAILURE_RATE) {
            // 失败率 < 1%：放宽阈值
            newThreshold = (int) Math.ceil(currentThreshold * RELAX_FACTOR);
        } else if (failureRate < MEDIUM_FAILURE_RATE) {
            // 失败率 1-5%：保持当前阈值
            newThreshold = currentThreshold;
        } else if (failureRate < HIGH_FAILURE_RATE) {
            // 失败率 5-10%：收紧阈值
            newThreshold = (int) Math.ceil(currentThreshold * TIGHTEN_FACTOR);
        } else {
            // 失败率 > 10%：严重收紧阈值
            newThreshold = (int) Math.ceil(currentThreshold * SEVERE_TIGHTEN_FACTOR);
        }

        // 确保在边界范围内
        return Math.max(MIN_FAILURE_THRESHOLD, Math.min(MAX_FAILURE_THRESHOLD, newThreshold));
    }

    /**
     * 应用阈值调整（#653 修复）
     *
     * <p>此前只写 DB 且 {@code adjustmentApplied} 恒为 false，运行时熔断器走「裸 reset」
     * 重建后仍取全局/实例级默认阈值——自适应结果从未真正生效。现在：
     * <ol>
     *   <li>调 {@link CircuitBreakerManager#applyAdaptiveThreshold} 写入覆盖层并重建熔断器；</li>
     *   <li>应用成功才落 DB 并置 {@code adjustmentApplied=true}；</li>
     *   <li>手工配置优先时跳过运行时写入，也不产生待补偿记录。</li>
     * </ol></p>
     */
    private void applyThresholdAdjustment(final String instanceId, final int oldThreshold,
            final int newThreshold, final double failureRate) {

        String reason = String.format("自适应调整: 失败率 %.2f%%, 阈值 %d -> %d",
                failureRate * 100, oldThreshold, newThreshold);

        log.info("实例 {} 阈值调整: {} -> {} (失败率: {}%)", instanceId,
                oldThreshold, newThreshold, String.format("%.2f", failureRate * 100));

        // 更新运行时熔断器阈值（手工配置优先，命中即跳过）
        boolean applied = circuitBreakerManager.applyAdaptiveThreshold(instanceId, newThreshold);
        if (!applied) {
            log.info("实例 {} 存在手工熔断配置，自适应调整跳过（手工层优先，两层分离）", instanceId);
            return;
        }

        // 更新数据库记录并标记已应用（供重启后启动补偿核账）
        metricsRepository.findFirstByInstanceIdOrderByWindowEndDesc(instanceId)
                .ifPresent(entity -> {
                    entity.setAdjustedFailureThreshold(newThreshold);
                    entity.setAdjustmentReason(reason);
                    entity.setAdjustmentApplied(true);
                    metricsRepository.save(entity);
                });
    }

    /**
     * 获取实例当前阈值
     */
    private int getCurrentThreshold(final String instanceId) {
        Map<String, Object> detail = circuitBreakerManager.getCircuitBreakerDetail(instanceId);
        if (detail != null && detail.containsKey("failureThreshold")) {
            return (Integer) detail.get("failureThreshold");
        }
        return DEFAULT_FAILURE_THRESHOLD;
    }

    /**
     * 启动补偿（#653）：应用 DB 中「待生效」的自适应阈值记录。
     *
     * <p>{@link CircuitBreakerMetricsRepository#findPendingAdjustments} 此前无任何调用方——
     * 运行时调整虽然写 DB，但重启后内存覆盖层丢失、记录永远停在 {@code adjustmentApplied=false}，
     * 自适应结果静默失效。启动时逐条补偿：写覆盖层 + 标记已应用。</p>
     *
     * <p>包在 try/catch 中：Flyway 尚未完成迁移或 DB 暂不可用时仅告警，不阻断启动。</p>
     */
    @PostConstruct
    public void applyPendingAdjustments() {
        try {
            final List<CircuitBreakerMetricsEntity> pending = metricsRepository.findPendingAdjustments();
            if (pending.isEmpty()) {
                log.debug("启动补偿: 无待生效的自适应阈值记录");
                return;
            }

            int appliedCount = 0;
            for (CircuitBreakerMetricsEntity entity : pending) {
                final Integer threshold = entity.getAdjustedFailureThreshold();
                final String instanceId = entity.getInstanceId();
                if (threshold == null || instanceId == null || instanceId.isBlank()) {
                    continue;
                }

                final boolean applied = circuitBreakerManager.applyAdaptiveThreshold(instanceId, threshold);
                entity.setAdjustmentApplied(true);
                metricsRepository.save(entity);
                if (applied) {
                    appliedCount++;
                } else {
                    log.info("启动补偿: 实例 {} 存在手工熔断配置，记录标记已应用但未覆盖运行时（手工层优先）",
                            instanceId);
                }
            }
            log.info("启动补偿: 应用 {} 条待生效自适应阈值（共 {} 条记录）", appliedCount, pending.size());
        } catch (Exception e) {
            log.warn("启动补偿应用待生效自适应阈值失败", e);
        }
    }

    /**
     * 重置统计数据缓存
     */
    private void resetMetricsCache() {
        for (InstanceMetrics metrics : metricsCache.values()) {
            metrics.reset();
        }
    }

    /**
     * 获取所有实例的当前统计数据
     */
    public Map<String, InstanceMetrics> getAllMetrics() {
        return new HashMap<>(metricsCache);
    }

    /**
     * 获取单个实例的统计数据
     */
    public InstanceMetrics getMetrics(final String instanceId) {
        return metricsCache.get(instanceId);
    }

    // ==================== 内部类 ====================

    /**
     * 实例统计数据（内存）
     */
    public static class InstanceMetrics {
        private final String instanceId;
        private final String instanceName;
        private final String serviceType;
        private final AtomicLong totalCalls = new AtomicLong(0);
        private final AtomicLong failureCalls = new AtomicLong(0);
        private final AtomicLong successCalls = new AtomicLong(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);

        public InstanceMetrics(final String instanceId, final String instanceName, final String serviceType) {
            this.instanceId = instanceId;
            this.instanceName = instanceName;
            this.serviceType = serviceType;
        }

        public void recordSuccess() {
            totalCalls.incrementAndGet();
            successCalls.incrementAndGet();
        }

        public void recordFailure() {
            totalCalls.incrementAndGet();
            failureCalls.incrementAndGet();
        }

        public void reset() {
            totalCalls.set(0);
            failureCalls.set(0);
            successCalls.set(0);
            totalResponseTime.set(0);
        }

        public long getTotalCalls() {
            return totalCalls.get();
        }

        public long getFailureCalls() {
            return failureCalls.get();
        }

        public long getSuccessCalls() {
            return successCalls.get();
        }

        public double getFailureRate() {
            long total = totalCalls.get();
            return total > 0 ? (double) failureCalls.get() / total : 0.0;
        }
    }

    /**
     * 聚合统计数据
     */
    private static class AggregatedMetrics {
        long totalCalls = 0;
        long failureCalls = 0;
        long successCalls = 0;
        int sampleCount = 0;
    }
}
