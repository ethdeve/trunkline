package org.unreal.modelrouter.router.checker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.monitor.tracing.health.HealthCheckTracingEnhancer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServerCheckerTest {

    private ServerChecker serverChecker;

    @Mock
    private ModelServiceRegistry modelServiceRegistry;

    @Mock
    private ServiceStateManager serviceStateManager;

    @Mock
    private MetricsCollector metricsCollector;

    @Mock
    private HealthCheckTracingEnhancer tracingEnhancer;

    @BeforeEach
    void setUp() {
        serverChecker = new ServerChecker(modelServiceRegistry, serviceStateManager);
    }

    @Test
    void testCheckAllServicesWithEmptyRegistry() {
        // Given
        when(modelServiceRegistry.getAllInstances()).thenReturn(new HashMap<>());

        // When
        serverChecker.checkAllServices();

        // Then
        // 验证没有执行任何检查
        verifyNoInteractions(serviceStateManager);
    }

    @Test
    void testCheckAllServicesWithInstances() {
        // Given
        Map<ModelServiceRegistry.ServiceType, List<ModelRouterProperties.ModelInstance>> instanceRegistry = new HashMap<>();
        List<ModelRouterProperties.ModelInstance> instances = new ArrayList<>();

        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("test-instance");
        instance.setBaseUrl("http://localhost:8080");
        instances.add(instance);

        instanceRegistry.put(ModelServiceRegistry.ServiceType.chat, instances);
        when(modelServiceRegistry.getAllInstances()).thenReturn(instanceRegistry);

        // When
        serverChecker.checkAllServices();

        // Then
        // 验证检查了实例的健康状态
        verify(serviceStateManager, atLeastOnce()).isInstanceHealthy(any(), any());
    }

    /**
     * #650 修复验证：非法 URL 实例必须被标记为不健康（此前缺失，默认健康位图会把它放进候选），
     * 且同批次合法实例仍被正常探活（连接失败分支同样写健康位图，隔离保留）。
     */
    @Test
    void testIllegalUrlInstanceMarkedUnhealthyAndLegalStillChecked() {
        Map<ModelServiceRegistry.ServiceType, List<ModelRouterProperties.ModelInstance>> registry = new HashMap<>();

        ModelRouterProperties.ModelInstance illegal = new ModelRouterProperties.ModelInstance();
        illegal.setName("bad");
        illegal.setBaseUrl("not-a-valid-url");

        ModelRouterProperties.ModelInstance legal = new ModelRouterProperties.ModelInstance();
        legal.setName("ok");
        legal.setBaseUrl("http://localhost:1"); // 关闭端口 -> 连接失败，但会被正常处理

        registry.put(ModelServiceRegistry.ServiceType.chat, List.of(illegal, legal));
        when(modelServiceRegistry.getAllInstances()).thenReturn(registry);

        // When
        serverChecker.checkAllServices();

        // Then：非法 URL 实例被标记为不健康（#650 核心修复点）
        verify(serviceStateManager).updateInstanceHealthStatus(eq("chat"), eq(illegal), eq(false));
        // 合法实例仍被探活（连接失败分支同样写健康位图）
        verify(serviceStateManager).updateInstanceHealthStatus(eq("chat"), eq(legal), eq(false));
    }

    /**
     * #651 修复验证：上一轮健康检查未完成时，新一轮被 in-progress 守卫拦截并跳过，
     * 任务只执行一次（反证修复前轮次叠加会重复执行）。
     */
    @Test
    void testHealthCheckGuardSkipsOverlappingRound() throws Exception {
        when(modelServiceRegistry.getAllInstances()).thenReturn(nonEmptyRegistry());

        AtomicInteger executed = new AtomicInteger();
        List<Runnable> tasks = List.of((Runnable) () -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            executed.incrementAndGet();
        });

        ServerChecker checker = new SlowServerChecker(modelServiceRegistry, serviceStateManager, tasks);

        // 捕获“跳过”日志
        Logger log = (Logger) LoggerFactory.getLogger(ServerChecker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);

        Thread background = new Thread(checker::checkAllServices);
        background.start();
        // 等待首轮进入 in-progress（入口在任务执行前即置位守卫）
        Thread.sleep(200);
        // 同一时刻再次触发：应被守卫拦截并跳过
        checker.checkAllServices();
        background.join();
        log.detachAppender(appender);

        assertEquals(1, executed.get(), "重叠轮次不应重复执行健康检查任务");
        boolean skipped = appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("跳过"));
        assertTrue(skipped, "重叠轮次应记录跳过日志");
    }

    /**
     * #651 修复验证：多个实例健康检查并发执行于 boundedElastic，
     * 总耗时≈单任务耗时（≈300ms）而非串行之和（n×300ms）。
     */
    @Test
    void testHealthCheckTasksRunConcurrently() {
        when(modelServiceRegistry.getAllInstances()).thenReturn(nonEmptyRegistry());

        AtomicInteger executed = new AtomicInteger();
        int n = 3;
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                executed.incrementAndGet();
            });
        }

        ServerChecker checker = new SlowServerChecker(modelServiceRegistry, serviceStateManager, tasks);
        long start = System.nanoTime();
        checker.checkAllServices();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(n, executed.get(), "所有任务都应被执行");
        // 并发执行：总耗时≈单任务(300ms)，远小于串行 n*300=900ms
        assertTrue(elapsedMs < 700, "健康检查应并发执行，实际耗时 " + elapsedMs + "ms");
    }

    private Map<ModelServiceRegistry.ServiceType, List<ModelRouterProperties.ModelInstance>> nonEmptyRegistry() {
        Map<ModelServiceRegistry.ServiceType, List<ModelRouterProperties.ModelInstance>> registry = new HashMap<>();
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("placeholder");
        instance.setBaseUrl("http://localhost:1");
        registry.put(ModelServiceRegistry.ServiceType.chat, List.of(instance));
        return registry;
    }

    /**
     * 测试桩：覆写 getRunnable 以注入可控（慢/并发）任务，验证 #651 的守卫与并发语义。
     */
    static final class SlowServerChecker extends ServerChecker {
        private final List<Runnable> tasks;

        SlowServerChecker(final ModelServiceRegistry reg,
                          final ServiceStateManager st,
                          final List<Runnable> tasks) {
            super(reg, st);
            this.tasks = tasks;
        }

        @Override
        protected List<Runnable> getRunnable(
                final Map<ModelServiceRegistry.ServiceType,
                        List<ModelRouterProperties.ModelInstance>> registry) {
            return tasks;
        }
    }
}
