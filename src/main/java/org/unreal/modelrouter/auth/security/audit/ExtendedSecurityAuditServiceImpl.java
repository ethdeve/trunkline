package org.unreal.modelrouter.auth.security.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.common.dto.AuditEvent;
import org.unreal.modelrouter.common.dto.AuditEventQuery;
import org.unreal.modelrouter.common.dto.AuditEventType;
import org.unreal.modelrouter.common.dto.SecurityReport;
import org.unreal.modelrouter.enterprise.spi.AuditChainListener;
import org.unreal.modelrouter.persistence.jpa.entity.SecurityAuditEventEntity;
import org.unreal.modelrouter.persistence.jpa.entity.SecurityAuditEventEntity.RiskLevel;
import org.unreal.modelrouter.persistence.jpa.repository.SecurityAuditEventRepository;
import org.unreal.modelrouter.auth.security.model.SecurityAuditEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 扩展安全审计服务实现（JPA版本）
 * 实现真实的审计日志存储和查询功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtendedSecurityAuditServiceImpl implements ExtendedSecurityAuditService {

    private final SecurityAuditEventRepository auditRepository;
    private final AuditEntityMapper entityMapper;
    private final SecurityReportGenerator reportGenerator;
    /**
     * B1 审计哈希链订阅者（企业插件按 {@code auditChain} 功能位注册）。
     * 可选依赖：社区版无 Bean，落库出口零开销；企业侧实现失败须自行兜底，
     * 本类只对未兜底的异常做 WARN 兜底，绝不影响审计主流程。
     */
    private final ObjectProvider<AuditChainListener> auditChainListener;

    // 安全事件类型
    private static final List<AuditEventType> SECURITY_EVENT_TYPES = Arrays.asList(
            AuditEventType.SECURITY_ALERT,
            AuditEventType.SUSPICIOUS_ACTIVITY,
            AuditEventType.AUTHENTICATION_FAILED,
            AuditEventType.AUTHORIZATION_FAILED
    );

    // ========== JWT令牌审计方法 ==========

    @Override
    @Transactional
    public Mono<Void> auditTokenIssued(final String userId, final String tokenId,
            final String ipAddress, final String userAgent) {
        return Mono.fromRunnable(() -> {
            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.JWT_TOKEN_ISSUED)
                    .userId(userId)
                    .resourceId(tokenId)
                    .clientIp(ipAddress)
                    .userAgent(userAgent)
                    .action("ISSUE")
                    .details("JWT令牌颁发成功")
                    .success(true)
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.info("JWT令牌颁发审计记录: userId={}, tokenId={}", userId, tokenId);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> auditTokenRefreshed(final String userId, final String oldTokenId,
            final String newTokenId, final String ipAddress) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("oldTokenId", oldTokenId);
            metadata.put("newTokenId", newTokenId);

            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.JWT_TOKEN_REFRESHED)
                    .userId(userId)
                    .resourceId(newTokenId)
                    .clientIp(ipAddress)
                    .action("REFRESH")
                    .details("JWT令牌刷新成功")
                    .success(true)
                    .metadata(entityMapper.toJson(metadata))
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.info("JWT令牌刷新审计记录: userId={}", userId);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> auditTokenRevoked(final String userId, final String tokenId,
            final String reason, final String revokedBy) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("revokedBy", revokedBy);
            metadata.put("reason", reason);

            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.JWT_TOKEN_REVOKED)
                    .userId(userId)
                    .resourceId(tokenId)
                    .action("REVOKE")
                    .details("JWT令牌撤销: " + reason)
                    .success(true)
                    .metadata(entityMapper.toJson(metadata))
                    .riskLevel(RiskLevel.MEDIUM)
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.info("JWT令牌撤销审计记录: userId={}, tokenId={}, reason={}", userId, tokenId, reason);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> auditTokenValidated(final String userId, final String tokenId,
            final boolean isValid, final String ipAddress) {
        return Mono.fromRunnable(() -> {
            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.JWT_TOKEN_VALIDATED)
                    .userId(userId)
                    .resourceId(tokenId)
                    .clientIp(ipAddress)
                    .action("VALIDATE")
                    .details(isValid ? "JWT令牌验证成功" : "JWT令牌验证失败")
                    .success(isValid)
                    .failureReason(isValid ? null : "令牌无效或已过期")
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.debug("JWT令牌验证审计记录: userId={}, valid={}", userId, isValid);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ========== API Key审计方法 ==========

    @Override
    @Transactional
    public Mono<Void> auditApiKeyCreated(final String keyId, final String createdBy, final String ipAddress) {
        return Mono.fromRunnable(() -> {
            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.API_KEY_CREATED)
                    .userId(createdBy)
                    .resourceId(keyId)
                    .clientIp(ipAddress)
                    .action("CREATE")
                    .details("API Key创建成功")
                    .success(true)
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.info("API Key创建审计记录: keyId={}, createdBy={}", keyId, createdBy);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> auditApiKeyUsed(final String keyId, final String endpoint,
            final String ipAddress, final boolean success) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("endpoint", endpoint);

            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.API_KEY_USED)
                    .resourceId(keyId)
                    .clientIp(ipAddress)
                    .resource(endpoint)
                    .action("USE")
                    .details(success ? "API Key使用成功" : "API Key使用失败")
                    .success(success)
                    .failureReason(success ? null : "认证失败或权限不足")
                    .metadata(entityMapper.toJson(metadata))
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.debug("API Key使用审计记录: keyId={}, endpoint={}, success={}", keyId, endpoint, success);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> auditApiKeyRevoked(final String keyId, final String reason, final String revokedBy) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("revokedBy", revokedBy);
            metadata.put("reason", reason);

            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.API_KEY_REVOKED)
                    .userId(revokedBy)
                    .resourceId(keyId)
                    .action("REVOKE")
                    .details("API Key撤销: " + reason)
                    .success(true)
                    .metadata(entityMapper.toJson(metadata))
                    .riskLevel(RiskLevel.MEDIUM)
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.info("API Key撤销审计记录: keyId={}, reason={}", keyId, reason);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> auditApiKeyExpired(final String keyId) {
        return Mono.fromRunnable(() -> {
            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.API_KEY_EXPIRED)
                    .resourceId(keyId)
                    .action("EXPIRE")
                    .details("API Key已过期")
                    .success(true)
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.info("API Key过期审计记录: keyId={}", keyId);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ========== 安全事件审计方法 ==========

    @Override
    @Transactional
    public Mono<Void> auditSecurityEvent(final String eventType, final String details,
            final String userId, final String ipAddress) {
        return Mono.fromRunnable(() -> {
            AuditEventType type = entityMapper.parseEventType(eventType);
            RiskLevel riskLevel = entityMapper.determineRiskLevel(type, false);

            // 身份全生命周期举证：原始事件类型串必须留痕（枚举未覆盖时落库不丢真名）；
            // success 按事件名推断（*FAILED 为失败，其余视为成功动作），不再硬编码 false。
            Map<String, Object> identityMeta = new HashMap<>();
            identityMeta.put("rawEventType", eventType);
            identityMeta.put("details", details == null ? "" : details);
            boolean success = !eventType.toUpperCase().endsWith("FAILED");

            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(type)
                    .userId(userId)
                    .clientIp(ipAddress)
                    .action("SECURITY_EVENT")
                    .details(details)
                    .success(success)
                    .metadata(entityMapper.toJson(identityMeta))
                    .riskLevel(riskLevel)
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.warn("安全事件审计记录: type={}, userId={}, details={}", eventType, userId, details);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> auditSuspiciousActivity(final String activity, final String userId,
            final String ipAddress, final String details) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("activity", activity);
            metadata.put("details", details);

            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.SUSPICIOUS_ACTIVITY)
                    .userId(userId)
                    .clientIp(ipAddress)
                    .action("SUSPICIOUS")
                    .details(activity)
                    .success(false)
                    .metadata(entityMapper.toJson(metadata))
                    .riskLevel(RiskLevel.HIGH)
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
            log.warn("可疑活动审计记录: activity={}, userId={}, ip={}", activity, userId, ipAddress);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ========== 查询方法 ==========

    @Override
    public Flux<AuditEvent> findAuditEvents(final AuditEventQuery query) {
        return Mono.fromCallable(() -> {
            PageRequest pageRequest = PageRequest.of(
                    query.getPage(),
                    Math.min(query.getSize(), 100),
                    Sort.by(Sort.Direction.fromString(query.getSortDirection()), query.getSortBy())
            );

            // eventTypes 集合参数在 PG 上无法参与 `:x IS NULL` 判型（42P18），
            // 查询侧已去掉 IS NULL 子句：null/空 = 不过滤，这里兜底传全枚举值。
            final List<AuditEventType> effectiveEventTypes =
                    (query.getEventTypes() == null || query.getEventTypes().isEmpty())
                            ? Arrays.asList(AuditEventType.values())
                            : query.getEventTypes();

            Page<SecurityAuditEventEntity> page = auditRepository.findByConditions(
                    query.getStartTime(),
                    query.getEndTime(),
                    effectiveEventTypes,
                    query.getUserId(),
                    query.getResourceId(),
                    query.getIpAddress(),
                    query.getSuccess(),
                    null,
                    null,
                    pageRequest
            );

            return page.getContent().stream()
                    .map(entityMapper::entityToDto)
                    .collect(Collectors.toList());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Long> countAuditEvents(final AuditEventQuery query) {
        return Mono.fromCallable(() -> {
            return auditRepository.countByConditions(
                    query.getStartTime(),
                    query.getEndTime(),
                    (query.getEventTypes() == null || query.getEventTypes().isEmpty())
                            ? Arrays.asList(AuditEventType.values())
                            : query.getEventTypes(),
                    query.getUserId(),
                    query.getResourceId(),
                    query.getIpAddress(),
                    query.getSuccess(),
                    null,
                    null
            );
        })
        .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SecurityReport> generateSecurityReport(final LocalDateTime from, final LocalDateTime to) {
        return reportGenerator.generateSecurityReport(from, to);
    }

    @Override
    @Transactional
    public Mono<Void> recordAuditEvent(final AuditEvent auditEvent) {
        return Mono.fromRunnable(() -> {
            SecurityAuditEventEntity entity = entityMapper.dtoToEntity(auditEvent);
            persist(entity);
            log.debug("审计事件记录: type={}, userId={}", auditEvent.getType(), auditEvent.getUserId());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> batchRecordAuditEvents(final List<AuditEvent> auditEvents) {
        return Mono.fromRunnable(() -> {
            List<SecurityAuditEventEntity> entities = auditEvents.stream()
                    .map(entityMapper::dtoToEntity)
                    .collect(Collectors.toList());
            persistAll(entities);
            log.info("批量审计事件记录: count={}", auditEvents.size());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Flux<AuditEvent> getUserAuditEvents(final String userId, final LocalDateTime startTime,
            final LocalDateTime endTime, final int limit) {
        return Mono.fromCallable(() -> {
            List<SecurityAuditEventEntity> entities = auditRepository.findByTimeRange(startTime, endTime);
            return entities.stream()
                    .filter(e -> userId.equals(e.getUserId()))
                    .limit(limit)
                    .map(entityMapper::entityToDto)
                    .collect(Collectors.toList());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<AuditEvent> getIpAuditEvents(final String ipAddress, final LocalDateTime startTime,
            final LocalDateTime endTime, final int limit) {
        return Mono.fromCallable(() -> {
            List<SecurityAuditEventEntity> entities = auditRepository.findByTimeRange(startTime, endTime);
            return entities.stream()
                    .filter(e -> ipAddress.equals(e.getClientIp()))
                    .limit(limit)
                    .map(entityMapper::entityToDto)
                    .collect(Collectors.toList());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable);
    }

    // ========== SecurityAuditService方法 ==========

    @Override
    @Transactional
    public Mono<Void> recordEvent(final SecurityAuditEvent event) {
        return Mono.fromRunnable(() -> {
            SecurityAuditEventEntity entity = entityMapper.securityEventToEntity(event);
            persist(entity);
            log.debug("安全审计事件记录: eventType={}", event.getEventType());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> recordAuthenticationEvent(final String userId, final String clientIp, final String userAgent,
                                               final boolean success, final String failureReason) {
        return Mono.fromRunnable(() -> {
            AuditEventType eventType = success 
            ? AuditEventType.JWT_TOKEN_ISSUED : AuditEventType.AUTHENTICATION_FAILED;

            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(eventType)
                    .userId(userId)
                    .clientIp(clientIp)
                    .userAgent(userAgent)
                    .action("AUTHENTICATE")
                    .details(success ? "认证成功" : "认证失败")
                    .success(success)
                    .failureReason(failureReason)
                    .riskLevel(success ? RiskLevel.LOW : RiskLevel.MEDIUM)
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Transactional
    public Mono<Void> recordSanitizationEvent(final String userId, final String contentType,
            final String ruleId, final int matchCount) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("contentType", contentType);
            metadata.put("ruleId", ruleId);
            metadata.put("matchCount", matchCount);

            SecurityAuditEventEntity entity = SecurityAuditEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(AuditEventType.DATA_SANITIZATION)
                    .userId(userId)
                    .action("SANITIZE")
                    .details("数据脱敏处理")
                    .success(true)
                    .metadata(entityMapper.toJson(metadata))
                    .timestamp(LocalDateTime.now())
                    .build();
            persist(entity);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Flux<SecurityAuditEvent> queryEvents(final LocalDateTime startTime, final LocalDateTime endTime,
                                               final String eventType, final String userId, final int limit) {
        return Mono.fromCallable(() -> {
            List<SecurityAuditEventEntity> entities = auditRepository.findByTimeRange(startTime, endTime);
            return entities.stream()
                    .filter(e -> eventType == null || eventType.equals(e.getEventType().name()))
                    .filter(e -> userId == null || userId.equals(e.getUserId()))
                    .limit(limit)
                    .map(entityMapper::entityToSecurityEvent)
                    .collect(Collectors.toList());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Map<String, Object>> getSecurityStatistics(final LocalDateTime startTime, final LocalDateTime endTime) {
        return reportGenerator.getSecurityStatistics(startTime, endTime);
    }

    @Override
    public Mono<Long> cleanupExpiredLogs(final int retentionDays) {
        return reportGenerator.cleanupExpiredLogs(retentionDays);
    }

    @Override
    public Mono<Boolean> shouldTriggerAlert(final String eventType, final int timeWindowMinutes, final int threshold) {
        return reportGenerator.shouldTriggerAlert(eventType, timeWindowMinutes, threshold);
    }

    @Override
    public Mono<Long> countEvents(final LocalDateTime startTime, final LocalDateTime endTime,
                                  final List<AuditEventType> eventTypes, final String clientIp,
                                  final Boolean success) {
        return reportGenerator.countEvents(startTime, endTime, eventTypes, clientIp, success);
    }

    // ========== 统一落库出口（B1 审计哈希链钩子） ==========

    /**
     * 统一落库出口：保存实体后把链上快照交给订阅者（B1）。
     * 全部审计写路径（14 处 save + 1 处 saveAll）都收敛到 {@link #persist} /
     * {@link #persistAll}，保证「审计行入库 ⇒ 订阅者可见快照」不变量只写一处。
     */
    private SecurityAuditEventEntity persist(final SecurityAuditEventEntity entity) {
        try {
            final SecurityAuditEventEntity saved = auditRepository.save(entity);
            publishChainEvent(saved);
            return saved;
        } catch (final RuntimeException e) {
            // 审计是合规底座：主存储失败必须留痕，绝不静默丢事件。
            // 结构化 ERROR 日志供日志采集兜底；不吞异常，让上游 error consumer 感知。
            log.error("AUDIT_LOSS audit persist failed: eventType={}, time={}, id={}, userId={}",
                    entity.getEventType(), entity.getTimestamp(), entity.getEventId(), entity.getUserId(), e);
            throw e;
        }
    }

    private void persistAll(final List<SecurityAuditEventEntity> entities) {
        try {
            auditRepository.saveAll(entities);
            publishChainEvents(entities);
        } catch (final RuntimeException e) {
            // 批量落库同样不留静默丢失：逐条可辨识信息 + 原始异常，向上抛出。
            log.error("AUDIT_LOSS audit persistAll failed: size={}, eventTypes={}",
                    entities.size(), entities.stream().map(SecurityAuditEventEntity::getEventType).toList(), e);
            throw e;
        }
    }

    private void publishChainEvent(final SecurityAuditEventEntity entity) {
        final AuditChainListener listener = auditChainListener == null
                ? null : auditChainListener.getIfAvailable();
        if (listener == null || !listener.chainEnabled()) {
            return;
        }
        try {
            listener.onAuditEventPersisted(toChainEvent(entity));
        } catch (final RuntimeException e) {
            // 实现方未兜底时主干兜底：链故障绝不影响审计主流程
            log.warn("审计哈希链追加回调失败（不影响审计主流程）: eventId={}, error={}",
                    entity.getEventId(), e.toString());
        }
    }

    private void publishChainEvents(final List<SecurityAuditEventEntity> entities) {
        final AuditChainListener listener = auditChainListener == null
                ? null : auditChainListener.getIfAvailable();
        if (listener == null || !listener.chainEnabled() || entities.isEmpty()) {
            return;
        }
        try {
            final List<AuditChainListener.AuditChainEvent> snapshots = new ArrayList<>(entities.size());
            for (final SecurityAuditEventEntity entity : entities) {
                snapshots.add(toChainEvent(entity));
            }
            listener.onAuditEventsPersisted(snapshots);
        } catch (final RuntimeException e) {
            log.warn("审计哈希链批量追加回调失败（不影响审计主流程）: count={}, error={}",
                    entities.size(), e.toString());
        }
    }

    private AuditChainListener.AuditChainEvent toChainEvent(final SecurityAuditEventEntity entity) {
        return new AuditChainListener.AuditChainEvent(
                entity.getEventId(),
                entity.getEventType() == null ? null : entity.getEventType().name(),
                entity.getEventCategory(),
                entity.getUserId(),
                entity.getResourceId(),
                entity.getClientIp(),
                entity.getUserAgent(),
                entity.getTimestamp(),
                entity.getResource(),
                entity.getAction(),
                entity.getDetails(),
                entity.getSuccess(),
                entity.getFailureReason(),
                entity.getMetadata(),
                entity.getRequestId(),
                entity.getSessionId(),
                entity.getRiskLevel() == null ? null : entity.getRiskLevel().name());
    }
}