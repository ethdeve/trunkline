package org.unreal.modelrouter.router.checker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.model.InstanceKeys;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.monitor.tracing.TracingContext;
import org.unreal.modelrouter.monitor.tracing.health.HealthCheckTracingEnhancer;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.common.util.NetUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ServerChecker {

    private static final Logger log = LoggerFactory.getLogger(ServerChecker.class);

    private final ModelServiceRegistry modelServiceRegistry;
    private final ServiceStateManager serviceStateManager;

    @Autowired(required = false)
    private MetricsCollector metricsCollector;

    @Autowired(required = false)
    private ServiceInstanceRepository serviceInstanceRepository;

    // 缓存实例之前的状态，用于检测状态变化
    private final Map<String, Boolean> previousInstanceStates = new ConcurrentHashMap<>();

    // #651：in-progress 守卫，防止上一轮健康检查未完成时轮次叠加
    private final AtomicBoolean inProgress = new AtomicBoolean();

    // #651：健康检查调度参数（外部化配置；单测/无配置时回退默认值）
    @Value("${trunkline.health-check.concurrency:4}")
    private int concurrency = 4;

    @Value("${trunkline.health-check.connect-timeout-ms:5000}")
    private int connectTimeoutMs = 5000;

    public ServerChecker(final ModelServiceRegistry modelServiceRegistry,
                         final ServiceStateManager serviceStateManager) {
        this.modelServiceRegistry = modelServiceRegistry;
        this.serviceStateManager = serviceStateManager;
    }

    /**
     * 当配置更新时，清理过期的实例状态缓存
     */
    public void clearExpiredInstanceStates() {
        log.info("清理过期的实例状态缓存，清理前缓存大小: {}", previousInstanceStates.size());
        previousInstanceStates.clear();
        log.info("实例状态缓存清理完成");
    }

    /**
     * 定时检查所有服务实例的健康状态
     * 默认每 30 秒执行一次（可通过 trunkline.health-check.interval-ms 配置）。
     * 使用 in-progress 守卫 + 并发执行，避免上一轮未完成时轮次叠加（#651）。
     */
    @Scheduled(fixedRateString = "${trunkline.health-check.interval-ms:30000}")
    public void checkAllServices() {
        if (!inProgress.compareAndSet(false, true)) {
            log.warn("上轮健康检查尚未完成，跳过本轮以避免轮次叠加");
            return;
        }
        try {
            doCheckAllServices();
        } finally {
            inProgress.set(false);
        }
    }

    /**
     * 实际健康检查逻辑，由 {@link #checkAllServices()} 在 in-progress 守卫内调用。
     */
    private void doCheckAllServices() {
        long batchStartTime = System.currentTimeMillis();
        log.info("开始检查所有服务实例的健康状态");

        Map<ModelServiceRegistry.ServiceType, List<ModelRouterProperties.ModelInstance>> instanceRegistry =
                modelServiceRegistry.getAllInstances();

        // 增强日志输出，帮助诊断问题
        if (instanceRegistry == null) {
            log.warn("服务实例注册表为null");
            return;
        }

        if (instanceRegistry.isEmpty()) {
            log.warn("未找到配置的服务实例");
            // 添加更多调试信息
            log.debug("检查配置是否正确加载...");
            try {
                // 尝试获取服务类型列表
                Set<ModelServiceRegistry.ServiceType> availableTypes = modelServiceRegistry.getAvailableServiceTypes();
                log.debug("可用服务类型: {}", availableTypes != null ? availableTypes.size() : 0);

                // 检查每个服务类型的实例
                for (ModelServiceRegistry.ServiceType type : ModelServiceRegistry.ServiceType.values()) {
                    Set<String> models = modelServiceRegistry.getAvailableModels(type);
                    log.debug("服务类型 {} 包含 {} 个模型", type, models != null ? models.size() : 0);
                }
            } catch (Exception e) {
                log.error("检查配置时发生错误", e);
            }
            return;
        }

        List<Runnable> tasks = getRunnable(instanceRegistry);

        // 统计信息
        int totalServices = instanceRegistry.size();
        int totalInstances = instanceRegistry.values().stream()
            .mapToInt(List::size)
            .sum();
        int healthyServices = 0;
        int healthyInstances = 0;

        // 执行所有检查任务（并发执行于 boundedElastic，单个任务异常不影响其他任务）
        runHealthCheckTasks(tasks);

        // 统计健康状态
        for (Map.Entry<ModelServiceRegistry.ServiceType,
                List<ModelRouterProperties.ModelInstance>> entry : instanceRegistry.entrySet()) {
            String serviceType = entry.getKey().name();
            List<ModelRouterProperties.ModelInstance> instances = entry.getValue();

            boolean serviceHasHealthyInstance = false;
            for (ModelRouterProperties.ModelInstance instance : instances) {
                if (serviceStateManager.isInstanceHealthy(serviceType, instance)) {
                    healthyInstances++;
                    serviceHasHealthyInstance = true;
                }
            }

            if (serviceHasHealthyInstance) {
                healthyServices++;
            }
        }

        long batchDuration = System.currentTimeMillis() - batchStartTime;

        // 记录批次完成追踪
        try {
            org.unreal.modelrouter.monitor.tracing.health.HealthCheckTracingEnhancer enhancer =
                org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.monitor.tracing.health.HealthCheckTracingEnhancer.class);
            enhancer.logHealthCheckBatchComplete(totalServices, healthyServices,
                totalInstances, healthyInstances, batchDuration);
        } catch (Exception e) {
            // 忽略追踪错误
        }

        log.debug("所有服务实例健康检查完成，健康服务: {}/{}, 健康实例: {}/{}, 耗时: {}ms",
            healthyServices, totalServices, healthyInstances, totalInstances, batchDuration);
    }

    /**
     * 并发执行健康检查任务：在 boundedElastic 线程池上并行执行，
     * 总耗时≈单个任务最大耗时而非串行之和（#651）。
     * 单个任务抛异常时仅记录日志，不影响其他任务（保留原逐 task try-catch 隔离语义）。
     *
     * @param tasks 健康检查任务列表
     */
    private void runHealthCheckTasks(final List<Runnable> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        int concurrencyLevel = Math.max(1, concurrency);
        try {
            Flux.fromIterable(tasks)
                .flatMap(task -> Mono.fromRunnable(() -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        log.error("执行服务检查任务时发生错误", e);
                    }
                }).subscribeOn(Schedulers.boundedElastic()), concurrencyLevel)
                .then()
                .block();
        } catch (Exception e) {
            log.error("健康检查并发执行异常", e);
        }
    }

    protected List<Runnable> getRunnable(
            final Map<ModelServiceRegistry.ServiceType,
                    List<ModelRouterProperties.ModelInstance>> instanceRegistry) {
        List<Runnable> tasks = new ArrayList<>();

        for (Map.Entry<ModelServiceRegistry.ServiceType,
                List<ModelRouterProperties.ModelInstance>> entry : instanceRegistry.entrySet()) {
            ModelServiceRegistry.ServiceType serviceType = entry.getKey();
            List<ModelRouterProperties.ModelInstance> instances = entry.getValue();

            if (instances != null && !instances.isEmpty()) {
                tasks.add(() -> checkServiceInstances(serviceType.name(), instances));
            }
        }
        return tasks;
    }

    /**
     * 检查特定服务类型的所有实例
     */
    private void checkServiceInstances(
            final String serviceType,
            final List<ModelRouterProperties.ModelInstance> instances) {
        boolean hasHealthyInstance = false;

        for (ModelRouterProperties.ModelInstance instance : instances) {
            // 获取健康检查追踪增强器
            HealthCheckTracingEnhancer tracingEnhancer = null;
            TracingContext tracingContext = null;
            try {
                tracingEnhancer = ApplicationContextProvider.getBean(HealthCheckTracingEnhancer.class);
                tracingContext = tracingEnhancer.createHealthCheckContext(serviceType, instance);
                tracingEnhancer.logHealthCheckStart(serviceType, instance, tracingContext);
            } catch (Exception e) {
                log.error("无法创建健康检查追踪上下文: {}", e.getMessage());
            }
            
            try {
                URI uri = new URI(instance.getBaseUrl());
                String host = uri.getHost();
                int port = uri.getPort();

                // 如果端口未指定，使用协议默认端口
                if (port == -1) {
                    String scheme = uri.getScheme();
                    if ("http".equals(scheme)) {
                        port = 80;
                    } else if ("https".equals(scheme)) {
                        port = 443;
                    }
                }

                // 使用 NetUtils 检查 socket 连接（同步方法，超时外部化）
                long startTime = System.currentTimeMillis();
                NetUtils.NetConnect result = NetUtils.testSocketConnect(host, port, connectTimeoutMs);
                long responseTime = System.currentTimeMillis() - startTime;

                // #636: 实例唯一标识符解析 instance-id → baseUrl → name，未配置 id 不再共享 ":null" 键
                String instanceKey = InstanceKeys.healthKey(serviceType, instance);
                
                // 获取之前的健康状态
                boolean previousHealthStatus = previousInstanceStates.getOrDefault(instanceKey, true);
                boolean currentHealthStatus = result.isConnect();

                if (result.isConnect()) {
                    hasHealthyInstance = true;
                    serviceStateManager.updateInstanceHealthStatus(serviceType, instance, true);
                    updateDatabaseHealthStatus(instance.getName(), instance.getInstanceId(), "HEALTHY", null);
                    log.debug("实例 {} 连接成功: {}", instance.getName(), result.getMsg());
                    recordHealthCheckMetrics(getAdapterType(instance), instance.getName(), true, responseTime);
                    
                    // 记录健康检查完成事件
                    if (tracingEnhancer != null && tracingContext != null) {
                        tracingEnhancer.logHealthCheckComplete(
                                serviceType, instance, true, responseTime,
                                result.getMsg(), tracingContext);
                    }
                } else {
                    serviceStateManager.updateInstanceHealthStatus(serviceType, instance, false);
                    updateDatabaseHealthStatus(instance.getName(),
                            instance.getInstanceId(), "UNHEALTHY", result.getMsg());
                    log.warn("实例 {} 连接失败: {}", instance.getName(), result.getMsg());
                    recordHealthCheckMetrics(
                            getAdapterType(instance), instance.getName(), false, responseTime);
                    
                    // 记录健康检查完成事件
                    if (tracingEnhancer != null && tracingContext != null) {
                        tracingEnhancer.logHealthCheckComplete(
                                serviceType, instance, false, responseTime,
                                result.getMsg(), tracingContext);
                    }
                }
                
                // 检查实例状态是否发生变化
                if (previousHealthStatus != currentHealthStatus) {
                    // 记录状态变更事件
                    if (tracingEnhancer != null) {
                        tracingEnhancer.logInstanceStateChange(
                            serviceType, 
                            instance, 
                            previousHealthStatus, 
                            currentHealthStatus, 
                            result.getMsg()
                        );
                    }
                    log.info("实例 {} 状态发生变化: {} -> {}", 
                        instance.getName(), 
                        previousHealthStatus ? "健康" : "不健康", 
                        currentHealthStatus ? "健康" : "不健康");
                }
                
                // 更新缓存的状态
                previousInstanceStates.put(instanceKey, currentHealthStatus);

            } catch (URISyntaxException e) {
                log.error("无效的URL格式: {}", instance.getBaseUrl(), e);
                // #650 修复：补齐与连接失败分支一致的四步，将非法 URL 实例标记为不健康，
                // 避免 ServiceStateManager 默认健康位图（BitSet 无记录默认健康）把非法 URL 实例放进候选
                String instanceKey = InstanceKeys.healthKey(serviceType, instance);
                serviceStateManager.updateInstanceHealthStatus(serviceType, instance, false);
                updateDatabaseHealthStatus(instance.getName(), instance.getInstanceId(), "UNHEALTHY",
                        "无效的URL格式: " + e.getMessage());
                previousInstanceStates.put(instanceKey, false);
                recordHealthCheckMetrics(getAdapterType(instance), instance.getName(), false, 0);
                // 记录健康检查完成事件（失败）
                if (tracingEnhancer != null && tracingContext != null) {
                    tracingEnhancer.logHealthCheckComplete(
                            serviceType, instance, false, 0,
                            "无效的URL格式: " + e.getMessage(), tracingContext);
                }
            } finally {
                // 完成追踪上下文
                if (tracingContext != null) {
                    tracingContext.clear();
                }
            }
        }

        // 更新服务健康状态
        boolean previousServiceState = serviceStateManager.isServiceHealthy(serviceType);
        serviceStateManager.updateServiceHealthStatus(serviceType, hasHealthyInstance);

        // 检查服务状态是否发生变化
        if (previousServiceState != hasHealthyInstance) {
            try {
                HealthCheckTracingEnhancer tracingEnhancer =
                        ApplicationContextProvider.getBean(HealthCheckTracingEnhancer.class);
                int totalInstances = instances.size();
                int healthyInstances = 0;
                for (ModelRouterProperties.ModelInstance instance : instances) {
                    if (serviceStateManager.isInstanceHealthy(serviceType, instance)) {
                        healthyInstances++;
                    }
                }
                tracingEnhancer.logServiceStateChange(
                        serviceType, hasHealthyInstance, totalInstances, healthyInstances);
            } catch (Exception e) {
                log.debug("无法记录服务状态变更事件: {}", e.getMessage());
            }
            
            log.info("服务 {} 状态发生变化: {} -> {}", 
                serviceType, 
                previousServiceState ? "健康" : "不健康", 
                hasHealthyInstance ? "健康" : "不健康");
        }

        if (hasHealthyInstance) {
            log.info("{} 服务至少有一个实例是健康的", serviceType);
        } else {
            log.warn("{} 服务所有实例都不可达", serviceType);
        }
    }

    /**
     * 记录健康检查指标
     */
    private void recordHealthCheckMetrics(
            final String adapter, final String instance,
            final boolean healthy, final long responseTime) {
        if (metricsCollector != null) {
            try {
                metricsCollector.recordHealthCheck(adapter, instance, healthy, responseTime);
            } catch (Exception e) {
                log.warn("Failed to record health check metrics: {}", e.getMessage());
            }
        }
    }

    /**
     * 根据实例信息推断适配器类型
     */
    private String getAdapterType(final ModelRouterProperties.ModelInstance instance) {
        // 根据实例的URL或其他特征推断适配器类型
        String baseUrl = instance.getBaseUrl();
        if (baseUrl == null) {
            return "normal";
        }
        baseUrl = baseUrl.toLowerCase();
        if (baseUrl.contains("ollama")) {
            return "ollama";
        } else if (baseUrl.contains("vllm")) {
            return "vllm";
        } else if (baseUrl.contains("gpustack")) {
            return "gpustack";
        } else if (baseUrl.contains("xinference")) {
            return "xinference";
        } else if (baseUrl.contains("localai")) {
            return "localai";
        } else {
            return "normal";
        }
    }
    
    /**
     * 记录服务实例注册事件
     * 
     * @param serviceType 服务类型
     * @param instance 服务实例
     */
    public void logServiceInstanceRegistered(
            final String serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        try {
            HealthCheckTracingEnhancer tracingEnhancer =
                    ApplicationContextProvider.getBean(HealthCheckTracingEnhancer.class);
            tracingEnhancer.logServiceInstanceRegistered(serviceType, instance);
        } catch (Exception e) {
            log.debug("无法记录服务实例注册事件: {}", e.getMessage());
        }
    }
    
    /**
     * 记录服务实例发现事件
     * 
     * @param serviceType 服务类型
     * @param instance 服务实例
     */
    public void logServiceInstanceDiscovered(
            final String serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        try {
            HealthCheckTracingEnhancer tracingEnhancer =
                    ApplicationContextProvider.getBean(HealthCheckTracingEnhancer.class);
            tracingEnhancer.logServiceInstanceDiscovered(serviceType, instance);
        } catch (Exception e) {
            log.debug("无法记录服务实例发现事件: {}", e.getMessage());
        }
    }
    /**
     * 更新数据库中实例的健康状态
     *
     * @param instanceName 实例名称
     * @param instanceId 实例唯一ID (UUID)
     * @param healthStatus 健康状态 (HEALTHY, UNHEALTHY, UNKNOWN)
     * @param errorMessage 错误信息
     */
    private void updateDatabaseHealthStatus(
            final String instanceName, final String instanceId,
            final String healthStatus, final String errorMessage) {
        if (serviceInstanceRepository == null) {
            return;
        }
        try {
            // v2.x: 优先使用 instanceId 查找，避免同名实例导致的重复记录错误
            if (instanceId != null && !instanceId.isEmpty()) {
                serviceInstanceRepository.findByInstanceId(instanceId).ifPresent(entity -> {
                    entity.setHealthStatus(healthStatus);
                    entity.setErrorMessage(errorMessage);
                    serviceInstanceRepository.save(entity);
                    log.debug("更新数据库实例 {} (instanceId={}) 健康状态: {}", instanceName, instanceId, healthStatus);
                });
            } else {
                // v2.x: 如果 instanceId 为空（内存对象从 YAML 加载），更新所有同名实例
                List<ServiceInstanceEntity> entities = serviceInstanceRepository.findAllByInstanceName(instanceName);
                if (entities.isEmpty()) {
                    log.debug("未找到实例 {} 的数据库记录", instanceName);
                    return;
                }
                for (ServiceInstanceEntity entity : entities) {
                    entity.setHealthStatus(healthStatus);
                    entity.setErrorMessage(errorMessage);
                    serviceInstanceRepository.save(entity);
                }
                log.debug("更新数据库实例 {} (共{}个同名实例) 健康状态: {}", instanceName, entities.size(), healthStatus);
            }
        } catch (Exception e) {
            log.error("更新数据库实例健康状态失败: {}", e.getMessage());
        }
    }
}
