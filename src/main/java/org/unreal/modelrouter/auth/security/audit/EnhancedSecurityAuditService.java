package org.unreal.modelrouter.auth.security.audit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.model.SecurityAuditEvent;
import org.unreal.modelrouter.common.dto.AuditEventType;
import org.unreal.modelrouter.persistence.jpa.entity.SecurityAuditEventEntity;
import org.unreal.modelrouter.persistence.jpa.repository.SecurityAuditEventRepository;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 增强型审计日志服务
 * 提供错误处理和备用日志通道，确保审计日志不丢失
 *
 * 特性：
 * 1. 主存储写入失败时自动切换到备用文件日志，失败事件进入有界重试队列
 * 2. 异步写入（boundedElastic），避免阻塞主流程
 * 3. 失败计数器 + 死信兜底，监控审计系统健康状态
 * 4. 定期刷新失败重试队列，防止数据丢失
 * 5. Prometheus 指标导出
 *
 * <p><b>M-09 修复</b>：原实现的 writeToPrimaryStorage 恒返回 true（从不落库）、flushBuffer poll 后
 * 仅 log.debug（事件静默丢弃）、刷新订阅句柄为局部变量从未 dispose、失败队列无上限且从不重试。
 * 由于本服务默认不装配（{@code matchIfMissing=false}），这些缺陷平时不触发；一旦开启
 * {@code trunkline.security.audit.enhanced=true}，注入攻击审计事件将 100% 静默丢失。
 * 本修复：接入真实 {@link SecurityAuditEventRepository} 落库；失败事件有界重试 + 超限/耗尽死信兜底；
 * 刷新订阅由 {@code @PostConstruct}/{@code @PreDestroy} 管理。默认仍不接线，不改变运行时行为。</p>
 */
@Slf4j
@Service("enhancedSecurityAuditService")
@Primary
@ConditionalOnProperty(name = "trunkline.security.audit.enhanced", havingValue = "true", matchIfMissing = false)
public class EnhancedSecurityAuditService implements SecurityAuditService {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 内存查询缓存上限（落库已在 recordEvent 同步完成，淘汰仅影响内存查询、不影响持久化） */
    private static final int MAX_BUFFER_SIZE = 1000;
    /** 落库失败待重试队列上限，超限淘汰的条目走死信兜底 */
    private static final int MAX_FAILED_BUFFER_SIZE = 500;
    /** 单条失败事件最大重试次数，耗尽后走死信兜底 */
    private static final int MAX_RETRY_ATTEMPTS = 3;
    /** 生产失败重试基础间隔（第 n 次失败后为 interval * n） */
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(60);

    /** 实例重试间隔（默认取 {@link #RETRY_INTERVAL}，单测可注入更短值） */
    private final Duration retryInterval;
    /** 定期刷新间隔（秒） */
    private static final int FLUSH_INTERVAL_SECONDS = 30;

    /** 整个 JVM 只注册一次关闭钩子（生产仅一个增强审计 bean） */
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    /** 审计指标服务 */
    private final AuditMetricsService auditMetricsService;
    /** 主存储（可选）：缺席时以文件日志为唯一通道，保证事件仍有痕迹 */
    private final SecurityAuditEventRepository repository;
    private final AuditEntityMapper entityMapper;
    private final boolean primaryStorageConfigured;

    /** 近期事件内存查询缓存（有界） */
    private final ConcurrentLinkedQueue<SecurityAuditEvent> eventBuffer = new ConcurrentLinkedQueue<>();
    /** 落库失败待重试队列（有界） */
    private final ConcurrentLinkedQueue<FailedEventEntry> failedEventBuffer = new ConcurrentLinkedQueue<>();

    /** 主存储失败计数器 */
    private final AtomicLong primaryStorageFailureCount = new AtomicLong(0);
    /** 备用存储成功计数器 */
    private final AtomicLong fallbackStorageSuccessCount = new AtomicLong(0);
    /** 总事件计数器 */
    private final AtomicLong totalEventCount = new AtomicLong(0);
    /** 查询缓存淘汰计数（非审计丢失） */
    private final AtomicLong evictedFromBufferCount = new AtomicLong(0);
    /** 失败队列超限淘汰计数（已走死信） */
    private final AtomicLong evictedFromFailedCount = new AtomicLong(0);
    /** 死信（所有存储通道失败）计数 */
    private final AtomicLong deadLetterCount = new AtomicLong(0);

    /** 是否启用备用日志 */
    private final boolean fallbackEnabled;
    /** 缓冲区大小阈值（保留：供触发一次失败队列刷新） */
    private final int bufferThreshold;

    /** 定期刷新任务的订阅句柄（M-09：保存并可在容器关闭时 dispose） */
    private volatile Disposable flushDisposable;

    /**
     * 兼容构造器（单测 / 无存储环境）：未配置主存储，文件日志作为唯一通道。
     * 生产装配走下方 {@link #EnhancedSecurityAuditService(AuditMetricsService, ObjectProvider, ObjectProvider)}。
     */
    public EnhancedSecurityAuditService(final AuditMetricsService auditMetricsService) {
        this(auditMetricsService, null, null, RETRY_INTERVAL);
    }

    /** Spring 装配构造器：主存储与映射器均为可选依赖，缺席时降级为文件日志通道。 */
    @Autowired
    public EnhancedSecurityAuditService(final AuditMetricsService auditMetricsService,
                                        final ObjectProvider<SecurityAuditEventRepository> repositoryProvider,
                                        final ObjectProvider<AuditEntityMapper> entityMapperProvider) {
        this(auditMetricsService,
                repositoryProvider == null ? null : repositoryProvider.getIfAvailable(),
                entityMapperProvider == null ? null : entityMapperProvider.getIfAvailable(),
                RETRY_INTERVAL);
    }

    /** 完整构造器（包可见，供单测注入可控重试间隔） */
    EnhancedSecurityAuditService(final AuditMetricsService auditMetricsService,
                                 final SecurityAuditEventRepository repository,
                                 final AuditEntityMapper entityMapper,
                                 final Duration retryInterval) {
        this.auditMetricsService = auditMetricsService;
        this.repository = repository;
        this.entityMapper = entityMapper;
        this.primaryStorageConfigured = this.repository != null && this.entityMapper != null;
        this.fallbackEnabled = true;
        this.bufferThreshold = 100;
        this.retryInterval = retryInterval;
    }

    /** 启动定期刷新任务（Spring 容器装配后调用；单测直接 new 不触发，避免订阅泄漏）。 */
    @PostConstruct
    public void startPeriodicFlushTask() {
        this.flushDisposable = Flux.interval(Duration.ofSeconds(FLUSH_INTERVAL_SECONDS))
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        tick -> flushBufferQuietly(),
                        error -> log.error("审计日志定期刷新任务异常终止", error)
                );
        if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(this::flushBufferQuietly, "trunkline-audit-flush"));
        }
        log.info("增强审计服务已启动：主存储={}, 刷新间隔={}s, 缓存上限={}, 失败队列上限={}",
                primaryStorageConfigured, FLUSH_INTERVAL_SECONDS, MAX_BUFFER_SIZE, MAX_FAILED_BUFFER_SIZE);
    }

    @Override
    public Mono<Void> recordEvent(final SecurityAuditEvent event) {
        if (event == null) {
            return Mono.empty();
        }
        // repository.save 为阻塞 JPA 调用，必须切 boundedElastic，避免占用 Netty 事件循环线程
        return Mono.fromRunnable(() -> {
            final long startTime = System.currentTimeMillis();
            try {
                totalEventCount.incrementAndGet();
                offerToBoundedBuffer(event);

                if (writeToPrimaryStorage(event)) {
                    auditMetricsService.recordEvent(event.getEventType(), true);
                } else {
                    // 主存储已配置且写入失败 → 降级备用日志 + 入失败队列待重试
                    primaryStorageFailureCount.incrementAndGet();
                    auditMetricsService.recordPrimaryStorageFailure();
                    final boolean fallbackSuccess = fallbackEnabled && writeToFallbackStorage(event);
                    if (fallbackSuccess) {
                        auditMetricsService.recordFallbackStorageSuccess();
                        enqueueFailed(event);
                    } else {
                        deadLetter(event);
                    }
                    auditMetricsService.recordEvent(event.getEventType(), false);
                }

                if (failedEventBuffer.size() >= bufferThreshold) {
                    flushBufferQuietly();
                }
            } catch (Exception e) {
                log.error("记录审计事件异常，eventId={}", event.getEventId(), e);
                if (fallbackEnabled) {
                    try {
                        writeToFallbackStorage(event);
                    } catch (Exception nested) {
                        deadLetter(event);
                    }
                }
                auditMetricsService.recordEvent(
                        event.getEventType() != null ? event.getEventType() : "UNKNOWN", false);
            } finally {
                auditMetricsService.recordWriteDuration(System.currentTimeMillis() - startTime);
                auditMetricsService.updateBufferSize(eventBuffer.size());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> recordAuthenticationEvent(final String userId, final String clientIp, final String userAgent,
                                                final boolean success, final String failureReason) {
        SecurityAuditEvent event = SecurityAuditEvent.builder()
                .eventType(success ? "AUTHENTICATION_SUCCESS" : "AUTHENTICATION_FAILURE")
                .userId(userId)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .action("AUTHENTICATE")
                .success(success)
                .failureReason(failureReason)
                .timestamp(LocalDateTime.now())
                .build();

        return recordEvent(event);
    }

    @Override
    public Mono<Void> recordSanitizationEvent(
            final String userId, final String contentType,
            final String ruleId, final int matchCount) {
        SecurityAuditEvent event = SecurityAuditEvent.builder()
                .eventType(AuditEventType.DATA_SANITIZATION.name())
                .userId(userId)
                .action("SANITIZE")
                .success(true)
                .timestamp(LocalDateTime.now())
                .build();

        return recordEvent(event);
    }

    @Override
    public Flux<SecurityAuditEvent> queryEvents(final LocalDateTime startTime, final LocalDateTime endTime,
                                               final String eventType, final String userId, final int limit) {
        return Flux.fromIterable(eventBuffer)
                .filter(event -> event.getTimestamp() != null
                        && event.getTimestamp().isAfter(startTime)
                        && event.getTimestamp().isBefore(endTime))
                .filter(event -> eventType == null || eventType.equals(event.getEventType()))
                .filter(event -> userId == null || userId.equals(event.getUserId()))
                .sort((e1, e2) -> e2.getTimestamp().compareTo(e1.getTimestamp()))
                .take(limit);
    }

    @Override
    public Mono<Map<String, Object>> getSecurityStatistics(final LocalDateTime startTime, final LocalDateTime endTime) {
        return Mono.fromSupplier(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalEvents", totalEventCount.get());
            stats.put("primaryStorageFailures", primaryStorageFailureCount.get());
            stats.put("fallbackStorageSuccesses", fallbackStorageSuccessCount.get());
            stats.put("bufferSize", eventBuffer.size());
            stats.put("healthScore", calculateHealthScore());
            return stats;
        });
    }

    @Override
    public Mono<Long> cleanupExpiredLogs(final int retentionDays) {
        return Mono.fromSupplier(() -> 0L);
    }

    @Override
    public Mono<Boolean> shouldTriggerAlert(final String eventType, final int timeWindowMinutes, final int threshold) {
        return Mono.just(false);
    }

    @Override
    public Mono<Long> countEvents(final LocalDateTime startTime, final LocalDateTime endTime,
                                  final List<AuditEventType> eventTypes, final String clientIp,
                                  final Boolean success) {
        return Mono.fromSupplier(() -> eventBuffer.stream()
                .filter(event -> event.getTimestamp() != null
                        && event.getTimestamp().isAfter(startTime)
                        && event.getTimestamp().isBefore(endTime))
                .filter(event -> eventTypes == null || eventTypes.isEmpty()
                        || eventTypes.stream().anyMatch(type -> type.name().equals(event.getEventType())))
                .filter(event -> clientIp == null || clientIp.equals(event.getClientIp()))
                .filter(event -> success == null || success == event.isSuccess())
                .count());
    }

    /**
     * 写入主存储。未配置主存储时以文件日志为唯一通道（保证事件仍有痕迹）；
     * 配置后真实落库，失败返回 false 由调用方降级。
     */
    private boolean writeToPrimaryStorage(final SecurityAuditEvent event) {
        if (!primaryStorageConfigured) {
            // 无主存储：文件日志即本模式的主通道，写入成功即视为落库成功
            return writeToFallbackStorage(event);
        }
        try {
            final SecurityAuditEventEntity entity = entityMapper.securityEventToEntity(event);
            repository.save(entity);
            return true;
        } catch (Exception e) {
            log.warn("主存储写入审计事件失败: eventId={}, err={}", event.getEventId(), e.getMessage());
            return false;
        }
    }

    /** 写入备用存储（文件日志），返回是否成功 */
    private boolean writeToFallbackStorage(final SecurityAuditEvent event) {
        try {
            final String timestamp = event.getTimestamp() != null
                    ? event.getTimestamp().format(TIMESTAMP_FORMATTER)
                    : LocalDateTime.now().format(TIMESTAMP_FORMATTER);

            log.info("[AUDIT-FALLBACK] {} | eventId={} | type={} | userId={} | action={} | success={} | ip={}",
                    timestamp,
                    event.getEventId(),
                    event.getEventType(),
                    event.getUserId(),
                    event.getAction(),
                    event.isSuccess(),
                    event.getClientIp());

            fallbackStorageSuccessCount.incrementAndGet();
            return true;
        } catch (Exception e) {
            log.error("备用存储写入失败", e);
            return false;
        }
    }

    /** 死信兜底：所有结构化存储通道失败时，以 ERROR 级别保留完整关键字段的痕迹（日志系统通常落盘）。 */
    private void deadLetter(final SecurityAuditEvent event) {
        deadLetterCount.incrementAndGet();
        log.error("[AUDIT-DEAD-LETTER] 审计事件所有存储通道失败，仅保留错误日志痕迹: "
                        + "eventId={} | type={} | userId={} | action={} | success={} | ip={} | ts={}",
                event.getEventId(), event.getEventType(), event.getUserId(), event.getAction(),
                event.isSuccess(), event.getClientIp(), event.getTimestamp());
    }

    /** 失败事件入有界重试队列；超限淘汰的条目走死信兜底，保证有痕迹 */
    private void enqueueFailed(final SecurityAuditEvent event) {
        if (failedEventBuffer.size() >= MAX_FAILED_BUFFER_SIZE) {
            final FailedEventEntry evicted = failedEventBuffer.poll();
            if (evicted != null) {
                evictedFromFailedCount.incrementAndGet();
                deadLetter(evicted.event);
            }
        }
        failedEventBuffer.offer(new FailedEventEntry(event, Instant.now().plus(retryInterval)));
    }

    /** 有界内存查询缓存：超限淘汰最老条目（落库已在 recordEvent 完成，不影响持久化） */
    private void offerToBoundedBuffer(final SecurityAuditEvent event) {
        eventBuffer.offer(event);
        while (eventBuffer.size() > MAX_BUFFER_SIZE) {
            final SecurityAuditEvent evicted = eventBuffer.poll();
            if (evicted != null) {
                evictedFromBufferCount.incrementAndGet();
            }
        }
    }

    /** 刷新失败重试队列（包可见，便于同包单测触发） */
    void flushBuffer() {
        retryFailedEvents();
    }

    private void flushBufferQuietly() {
        try {
            flushBuffer();
        } catch (Exception e) {
            log.error("刷新审计缓冲区失败", e);
        }
    }

    /**
     * 失败事件重试：仅对已到重试时间且未耗尽次数的事件补落主存储；
     * <b>落库成功才出队</b>，失败则带递增退避重新入队，耗尽走死信。
     */
    private void retryFailedEvents() {
        final int budget = failedEventBuffer.size();
        final Instant now = Instant.now();
        for (int i = 0; i < budget; i++) {
            final FailedEventEntry entry = failedEventBuffer.poll();
            if (entry == null) {
                break;
            }
            if (entry.attempts >= MAX_RETRY_ATTEMPTS) {
                deadLetter(entry.event);
                continue;
            }
            if (now.isBefore(entry.nextRetryAt)) {
                failedEventBuffer.offer(entry); // 未到重试时间，放回队尾
                continue;
            }
            if (primaryStorageConfigured && writeToPrimaryStorage(entry.event)) {
                log.info("失败审计事件重试补落库成功: eventId={}, attempts={}",
                        entry.event.getEventId(), entry.attempts);
            } else {
                entry.attempts++;
                entry.nextRetryAt = Instant.now().plus(retryInterval.multipliedBy(entry.attempts));
                failedEventBuffer.offer(entry);
            }
        }
    }

    /** 容器关闭：先做最后一次刷新，再取消刷新订阅（幂等） */
    @PreDestroy
    public void shutdown() {
        flushBufferQuietly();
        dispose();
        log.info("增强审计服务已关闭");
    }

    /** 取消定期刷新订阅（幂等） */
    public void dispose() {
        final Disposable disposable = this.flushDisposable;
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /** 获取审计系统健康状态 */
    public Map<String, Object> getHealthStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("totalEvents", totalEventCount.get());
        status.put("primaryStorageFailures", primaryStorageFailureCount.get());
        status.put("fallbackStorageSuccesses", fallbackStorageSuccessCount.get());
        status.put("bufferSize", eventBuffer.size());
        status.put("failedBufferSize", failedEventBuffer.size());
        status.put("evictedFromBuffer", evictedFromBufferCount.get());
        status.put("evictedFromFailed", evictedFromFailedCount.get());
        status.put("deadLetters", deadLetterCount.get());
        status.put("primaryStorageConfigured", primaryStorageConfigured);
        status.put("healthScore", calculateHealthScore());
        return status;
    }

    /** 计算健康分数（0-100） */
    private int calculateHealthScore() {
        long total = totalEventCount.get();
        if (total == 0) {
            return 100;
        }
        long failures = primaryStorageFailureCount.get();
        double failureRate = (double) failures / total;
        return Math.max(0, (int) ((1 - failureRate) * 100));
    }

    /** 获取失败事件快照（用于重试诊断） */
    public Collection<SecurityAuditEvent> getFailedEvents() {
        Collection<SecurityAuditEvent> result = new ArrayList<>();
        for (FailedEventEntry entry : failedEventBuffer) {
            result.add(entry.event);
        }
        return result;
    }

    /** 失败事件条目：事件 + 已重试次数 + 下次可重试时间 */
    private static final class FailedEventEntry {
        private final SecurityAuditEvent event;
        private int attempts;
        private Instant nextRetryAt;

        private FailedEventEntry(final SecurityAuditEvent event, final Instant nextRetryAt) {
            this.event = event;
            this.attempts = 0;
            this.nextRetryAt = nextRetryAt;
        }
    }
}
