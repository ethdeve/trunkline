package org.unreal.modelrouter.auth.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.auth.security.model.SecurityAuditEvent;
import org.unreal.modelrouter.persistence.jpa.entity.SecurityAuditEventEntity;
import org.unreal.modelrouter.persistence.jpa.repository.SecurityAuditEventRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M-09 回归测试：增强审计服务"接线即丢事件"缺陷修复。
 *
 * <p>原缺陷：writeToPrimaryStorage 恒 true（从不落库）、flushBuffer poll 后仅 log.debug（静默丢弃）、
 * 刷新订阅从未 dispose、失败队列无上限且从不重试。本测试验证：真实落库、失败入队重试、
 * 落库成功才出队、重试耗尽走死信、队列有界、订阅可 dispose。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnhancedSecurityAuditService 可靠性（M-09）")
class EnhancedSecurityAuditServiceReliabilityTest {

    @Mock
    private AuditMetricsService auditMetricsService;

    @Mock
    private SecurityAuditEventRepository repository;

    private AuditEntityMapper mapper;

    /** 有主存储、零重试间隔（到期即可重试）的服务 */
    private EnhancedSecurityAuditService service;

    @BeforeEach
    void setUp() {
        mapper = new AuditEntityMapper(new ObjectMapper());
        service = new EnhancedSecurityAuditService(auditMetricsService, repository, mapper, Duration.ZERO);
    }

    private SecurityAuditEvent event(final String id) {
        return SecurityAuditEvent.builder()
                .eventId(id)
                .eventType("SUSPICIOUS_ACTIVITY")
                .userId("u-" + id)
                .clientIp("10.0.0.1")
                .action("DETECT")
                .success(false)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("主存储落库")
    class PrimaryStorageTests {

        @Test
        @DisplayName("主存储可用时真实落库并记成功")
        void writesToPrimaryStorageWhenConfigured() {
            when(repository.save(any(SecurityAuditEventEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.recordEvent(event("e-1")).block();

            verify(repository, times(1)).save(any(SecurityAuditEventEntity.class));
            verify(auditMetricsService).recordEvent(eq("SUSPICIOUS_ACTIVITY"), eq(true));
            verify(auditMetricsService, never()).recordPrimaryStorageFailure();
            assertTrue(service.getFailedEvents().isEmpty());
            assertEquals(true, service.getHealthStatus().get("primaryStorageConfigured"));
        }

        @Test
        @DisplayName("主存储写失败时降级备用并计入失败队列")
        void failedPrimaryWriteEnqueuesForRetry() {
            when(repository.save(any(SecurityAuditEventEntity.class)))
                    .thenThrow(new RuntimeException("db down"));

            service.recordEvent(event("e-2")).block();

            verify(auditMetricsService).recordPrimaryStorageFailure();
            verify(auditMetricsService).recordFallbackStorageSuccess();
            verify(auditMetricsService).recordEvent(eq("SUSPICIOUS_ACTIVITY"), eq(false));
            Collection<SecurityAuditEvent> failed = service.getFailedEvents();
            assertEquals(1, failed.size());
            assertEquals("e-2", failed.iterator().next().getEventId());
        }
    }

    @Nested
    @DisplayName("失败重试与出队")
    class RetryTests {

        @Test
        @DisplayName("重试成功才出队（先失败后成功）")
        void dequeuesOnlyAfterSuccessfulRetry() {
            when(repository.save(any(SecurityAuditEventEntity.class)))
                    .thenThrow(new RuntimeException("transient"))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.recordEvent(event("e-3")).block();
            assertEquals(1, service.getFailedEvents().size(), "首次落库失败应入队");

            // 触发一次重试：Storage 恢复 → 补落库成功 → 出队
            service.flushBuffer();

            assertTrue(service.getFailedEvents().isEmpty(), "补落库成功后必须出队");
            verify(repository, times(2)).save(any(SecurityAuditEventEntity.class));
        }

        @Test
        @DisplayName("重试未成功时保留在队列")
        void keepsInQueueWhenRetryStillFails() {
            when(repository.save(any(SecurityAuditEventEntity.class)))
                    .thenThrow(new RuntimeException("still down"));

            service.recordEvent(event("e-4")).block();
            service.flushBuffer(); // 第 1 次重试仍失败

            assertEquals(1, service.getFailedEvents().size(), "落库未成功不得出队");
        }

        @Test
        @DisplayName("重试次数耗尽后走死信并清空队列")
        void deadLetterAfterRetryExhausted() {
            when(repository.save(any(SecurityAuditEventEntity.class)))
                    .thenThrow(new RuntimeException("permanent"));

            service.recordEvent(event("e-5")).block();
            // 首次落库失败 attempts=0；每次 flush 失败 attempts++：1,2,3；第 4 次 flush 时 attempts>=3 → 死信
            service.flushBuffer();
            service.flushBuffer();
            service.flushBuffer();
            service.flushBuffer();

            assertTrue(service.getFailedEvents().isEmpty(), "耗尽后应清空队列");
            assertEquals(1L, service.getHealthStatus().get("deadLetters"));
        }
    }

    @Nested
    @DisplayName("有界与生命周期")
    class BoundAndLifecycleTests {

        @Test
        @DisplayName("失败队列有上限，超限条目走死信")
        void failedBufferIsBounded() {
            // 长重试间隔：失败事件在测试期内不会因"到期重试"出队，才能累积到上限触发超限淘汰
            // （若用 ZERO 间隔，每满 bufferThreshold 就 flush，事件会先被"重试耗尽→死信"消耗，队列到不了 500）
            EnhancedSecurityAuditService boundedService =
                    new EnhancedSecurityAuditService(auditMetricsService, repository, mapper, Duration.ofHours(1));
            when(repository.save(any(SecurityAuditEventEntity.class)))
                    .thenThrow(new RuntimeException("down"));

            // 塞入超过上限（500）条失败事件
            for (int i = 0; i < 505; i++) {
                boundedService.recordEvent(event("b-" + i)).block();
            }

            Map<String, Object> status = boundedService.getHealthStatus();
            assertEquals(500, boundedService.getFailedEvents().size(), "失败队列应恰好停在上限 500");
            assertTrue(((Number) status.get("evictedFromFailed")).longValue() >= 1, "超限淘汰应计数");
            assertTrue(((Number) status.get("deadLetters")).longValue() >= 1, "超限淘汰条目应走死信");
        }

        @Test
        @DisplayName("dispose 幂等且可安全重复调用")
        void disposeIsIdempotent() {
            // 未启动 @PostConstruct（单测直接 new），flushDisposable 为 null，dispose 应安全
            assertDoesNotThrow(() -> {
                service.dispose();
                service.dispose();
            });
        }

        @Test
        @DisplayName("无主存储时走文件日志通道，不记主存储失败")
        void fallbackModeWhenNoPrimaryStorage() {
            EnhancedSecurityAuditService fileOnly = new EnhancedSecurityAuditService(auditMetricsService);

            fileOnly.recordEvent(event("f-1")).block();

            assertEquals(false, fileOnly.getHealthStatus().get("primaryStorageConfigured"));
            verify(auditMetricsService, never()).recordPrimaryStorageFailure();
            verify(auditMetricsService).recordEvent(eq("SUSPICIOUS_ACTIVITY"), eq(true));
        }
    }
}
