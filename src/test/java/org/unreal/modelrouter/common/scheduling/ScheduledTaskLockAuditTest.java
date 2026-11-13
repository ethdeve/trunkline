package org.unreal.modelrouter.common.scheduling;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O12 定时任务分布式锁覆盖审计（v1.3 新增，开源侧落地）。
 *
 * <p>多副本部署下，带外部副作用的 {@code @Scheduled} 任务（发告警、出账单、发通知、归档落盘）
 * 必须走 {@link ScheduledTaskLock#tryAcquire(String, java.time.Duration)}；幂等清理类可豁免，
 * 但须在 {@link #EXEMPT_CLASSES} 登记理由。本测试把"40 处 @Scheduled 仅 4 类 5 处加锁"的现状
 * 固化为受控基线，并机械化为断言：</p>
 * <ul>
 *   <li>任何被扫描到的 {@code @Scheduled} 任务类，必须 ∈ {@link #PROTECTED_CLASSES} ∪ {@link #EXEMPT_CLASSES}；
 *       新增任务若既不持锁也不登记豁免 → 测试失败，强制 Code Review 时显式决策。</li>
 *   <li>受保护类必须真实存在、确实含 {@code @Scheduled}、且源码调用 {@code taskLock.tryAcquire(...)}（防"假保护"退化）。</li>
 *   <li>豁免类必须真实存在且确实含 {@code @Scheduled}（防误列 / 类改名后幽灵条目）。</li>
 * </ul>
 *
 * <p>商业版新增的 license 到期检查 / 租户额度重置 / 月度账单生成等任务，只要落在
 * {@code org.unreal.modelrouter} 扫描范围内，同样受本基线约束（若在企业包，则企业仓应复用此范式）。</p>
 */
@DisplayName("O12 定时任务分布式锁覆盖审计")
class ScheduledTaskLockAuditTest {

    /** 已知受 ScheduledTaskLock 保护的类（4 类 5 处：AuditLogCleanupTask×2 / ApiKeyRotationScheduler / SecurityLogArchiveService / SecurityAlertScheduler）。 */
    private static final Set<String> PROTECTED_CLASSES = Set.of(
            "org.unreal.modelrouter.auth.security.audit.AuditLogCleanupTask",
            "org.unreal.modelrouter.auth.security.service.ApiKeyRotationScheduler",
            "org.unreal.modelrouter.auth.security.service.SecurityLogArchiveService",
            "org.unreal.modelrouter.monitor.monitoring.security.SecurityAlertScheduler"
    );

    /**
     * 豁免类 + 理由（幂等清理 / 只读巡检 / 本地缓存或内存状态重算，多副本重复执行无害）。
     * 新增任务若属此类，须在此登记理由，或在 PROTECTED_CLASSES 登记为受锁保护。
     */
    private static final Map<String, String> EXEMPT_CLASSES = new TreeMap<>(Map.ofEntries(
            Map.entry("org.unreal.modelrouter.auth.security.cache.JwtBlacklistCacheManager",
                    "JWT 黑名单本地缓存刷新，只读幂等"),
            Map.entry("org.unreal.modelrouter.auth.security.service.ApiKeyExpirationScheduler",
                    "API Key 过期清理，幂等删除"),
            Map.entry("org.unreal.modelrouter.auth.security.metrics.StorageHealthMetricsService",
                    "存储健康指标采集，只读巡检"),
            Map.entry("org.unreal.modelrouter.router.ratelimit.RateLimitMetricsService",
                    "限流指标统计刷新，只读"),
            Map.entry("org.unreal.modelrouter.auth.security.quota.QuotaLedgerService",
                    "本地额度账本快照重算，幂等"),
            Map.entry("org.unreal.modelrouter.auth.security.service.ApiKeyQuotaCleanupScheduler",
                    "API Key 额度清理，幂等删除"),
            Map.entry("org.unreal.modelrouter.auth.security.service.ApiKeyService",
                    "API Key 同步/清理定时，幂等"),
            Map.entry("org.unreal.modelrouter.router.circuitbreaker.AdaptiveThresholdManager",
                    "熔断自适应阈值本地内存重算，幂等"),
            Map.entry("org.unreal.modelrouter.router.checker.ServerChecker",
                    "服务健康探测，只读巡检"),
            Map.entry("org.unreal.modelrouter.router.checker.RateLimiterCleanupChecker",
                    "限流器空闲项清理，幂等"),
            Map.entry("org.unreal.modelrouter.auth.security.service.impl.JwtCleanupServiceImpl",
                    "JWT 清理，幂等删除"),
            Map.entry("org.unreal.modelrouter.auth.security.service.JwtTokenExpirationScheduler",
                    "JWT 过期清理，幂等删除"),
            Map.entry("org.unreal.modelrouter.auth.security.service.impl.SecurityBlacklistServiceImpl",
                    "安全黑名单清理，幂等"),
            Map.entry("org.unreal.modelrouter.monitor.callhistory.ApiCallHistoryCleanupScheduler",
                    "调用历史清理，幂等删除"),
            Map.entry("org.unreal.modelrouter.monitor.tracing.exporter.ExporterHealthChecker",
                    "导出器健康探测，只读巡检"),
            Map.entry("org.unreal.modelrouter.persistence.store.persistence.integration.RateLimiterPersistenceIntegration",
                    "限流持久化同步，幂等重试"),
            Map.entry("org.unreal.modelrouter.monitor.service.ModelCallAnalyzer",
                    "模型调用分析统计刷新，只读"),
            Map.entry("org.unreal.modelrouter.monitor.monitoring.reporter.MetricsBatchReporter",
                    "指标批量上报，重复上报无害/幂等"),
            Map.entry("org.unreal.modelrouter.monitor.monitoring.circuitbreaker.MetricsCacheAndRetry",
                    "指标缓存与重试，本地内存幂等"),
            Map.entry("org.unreal.modelrouter.monitor.monitoring.registry.MetricLifecycleManager",
                    "指标生命周期本地管理，幂等")
    ));

    @Test
    @DisplayName("所有 @Scheduled 任务必须受锁保护或在豁免基线中登记")
    void allScheduledTasksCovered() throws Exception {
        final Set<String> scanned = scanScheduledTaskClasses();

        // 1) 受保护类：真实存在 + 含 @Scheduled + 源码确实调用 taskLock.tryAcquire(...)
        for (final String pc : PROTECTED_CLASSES) {
            assertTrue(scanned.contains(pc),
                    "受保护类应在扫描到的 @Scheduled 类中（类名/包是否变更?）: " + pc);
            assertTrue(sourceCallsTaskLock(pc),
                    "受保护类源码应调用 taskLock.tryAcquire(...)（防止假保护退化）: " + pc);
        }

        // 2) 每个扫描到的 @Scheduled 类必须 ∈ 受保护 ∪ 豁免
        for (final String sc : scanned) {
            final boolean covered = PROTECTED_CLASSES.contains(sc) || EXEMPT_CLASSES.containsKey(sc);
            assertTrue(covered,
                    "未审查的定时任务类，须在 PROTECTED_CLASSES 登记受锁保护，或在 EXEMPT_CLASSES 登记豁免理由: "
                            + sc);
        }

        // 3) 豁免类必须真实存在且确实含 @Scheduled（防止类名变更后的幽灵条目）
        for (final var entry : EXEMPT_CLASSES.entrySet()) {
            assertTrue(scanned.contains(entry.getKey()),
                    "豁免类应在扫描到的 @Scheduled 类中（类名/包是否变更?）: "
                            + entry.getKey() + " -> " + entry.getValue());
        }
    }

    /**
     * 扫描 {@code org.unreal.modelrouter} 下所有类（含 @Bean 工厂注册而非 @Component 的类），
     * 收集带 {@link Scheduled} 注解方法的类。覆盖所有 Spring 注册方式，避免漏扫。
     */
    private Set<String> scanScheduledTaskClasses() throws Exception {
        final Set<String> result = new TreeSet<>();
        final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final Resource[] resources = resolver.getResources("classpath*:org/unreal/modelrouter/**/*.class");
        for (final Resource resource : resources) {
            final String url = resource.getURL().toString();
            if (url.contains("test-classes")) {
                continue; // 排除测试类，避免误扫
            }
            final int idx = url.indexOf("org/unreal");
            if (idx < 0) {
                continue;
            }
            String className = url.substring(idx).replace('/', '.').replace('\\', '.');
            className = className.substring(0, className.length() - ".class".length());
            if (className.endsWith("Test") || className.endsWith("Tests")) {
                continue;
            }
            final Class<?> clazz = Class.forName(className);
            for (final Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    result.add(className);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 读取组件源码，判断其是否调用 {@code taskLock.tryAcquire(...)}（ScheduledTaskLock 保护标记）。
     * 源码不在本地（如来自依赖 jar）时返回 false，由受控清单约束兜底。
     */
    private boolean sourceCallsTaskLock(final String fqn) {
        final Path source = Paths.get("src/main/java",
                fqn.replace('.', '/') + ".java");
        if (!Files.exists(source)) {
            return false;
        }
        try {
            final String content = Files.readString(source);
            return content.contains("taskLock.tryAcquire(");
        } catch (final IOException e) {
            return false;
        }
    }
}
