package org.unreal.modelrouter.router.adapter.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.sanitization.SanitizationService;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.monitor.callhistory.config.CallHistoryProperties;
import org.unreal.modelrouter.monitor.callhistory.config.RecordLevel;
import org.unreal.modelrouter.persistence.repository.ModelCallStatsRepository;
import org.unreal.modelrouter.monitor.callhistory.ApiCallHistoryRecorder;
import org.unreal.modelrouter.monitor.callhistory.dto.CallHistoryRecordDTO;
import org.unreal.modelrouter.monitor.tracing.TracingContextHolder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 适配器监控记录器
 *
 * 负责记录和上报适配器调用的监控指标，包括：
 * - 请求开始/完成时间记录
 * - 错误记录
 * - 后端调用指标
 * - 模型调用统计
 * - 服务注册调用记录 (v2.26.0)
 * - API 调用历史记录 (v2.7.8)
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Slf4j
@Component
public class AdapterMetricsRecorder {

    private final MetricsCollector metricsCollector;
    private final ModelCallStatsRepository statsRepository;
    private final ModelServiceRegistry registry;

    @Autowired(required = false)
    private ApiCallHistoryRecorder callHistoryRecorder;

    @Autowired(required = false)
    private CallHistoryProperties callHistoryProperties;

    @Autowired(required = false)
    private SanitizationService sanitizationService;

    public AdapterMetricsRecorder(MetricsCollector metricsCollector,
                                  ModelCallStatsRepository statsRepository,
                                  ModelServiceRegistry registry) {
        this.metricsCollector = metricsCollector;
        this.statsRepository = statsRepository;
        this.registry = registry;
    }

    /**
     * 记录请求开始
     * 
     * @param adapterType 适配器类型（如 gpustack, ollama 等）
     * @param instanceId 实例 ID
     * @param serviceType 服务类型（如 CHAT, EMBEDDING 等）
     * @param modelName 模型名称
     */
    public void recordRequestStart(
            final String adapterType,
            final String instanceId,
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName) {
        
        if (log.isDebugEnabled()) {
            log.debug("记录请求开始：adapter={}, instance={}, service={}, model={}",
                    adapterType, instanceId, serviceType, modelName);
        }
        
        // 注意：ModelCallStatsRepository 没有 recordCallStart 方法，
        // 请求开始时不更新统计，等待完成时再更新
    }

    /**
     * 记录请求完成
     * 
     * @param adapterType 适配器类型
     * @param instanceId 实例 ID
     * @param durationMs 调用耗时（毫秒）
     * @param success 是否成功
     * @param errorCode 错误代码（失败时）
     * @param modelName 模型名称
     * @param serviceType 服务类型
     */
    public void recordRequestComplete(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType) {
        
        // 记录后端调用指标
        if (metricsCollector != null) {
            metricsCollector.recordBackendCall(adapterType, instanceId, durationMs, success);
        }
        
        // 记录到统计仓库 - 使用 updateStats 方法
        if (statsRepository != null && serviceType != null && modelName != null) {
            statsRepository.updateStats(serviceType.name(), modelName, success, durationMs);
        }
        
        if (log.isDebugEnabled()) {
            log.debug("记录请求完成：adapter={}, instance={}, duration={}ms, success={}, errorCode={}",
                    adapterType, instanceId, durationMs, success, errorCode);
        }
    }

    /**
     * 记录错误
     * 
     * @param adapterType 适配器类型
     * @param instanceId 实例 ID
     * @param errorCode 错误代码
     * @param error 错误对象
     * @param durationMs 调用耗时（毫秒）
     * @param serviceType 服务类型
     */
    public void recordError(
            final String adapterType,
            final String instanceId,
            final String errorCode,
            final Throwable error,
            final long durationMs,
            final ModelServiceRegistry.ServiceType serviceType) {
        
        // 记录错误指标
        if (metricsCollector != null) {
            metricsCollector.recordBackendCall(adapterType, instanceId, durationMs, false);
            
            // 记录追踪指标（如果有错误）
            if (error != null) {
                metricsCollector.recordTrace(
                    errorCode != null ? errorCode : "UNKNOWN",
                    instanceId,
                    "adapter_error",
                    durationMs,
                    false
                );
            }
        }
        
        if (log.isDebugEnabled()) {
            log.debug("记录错误：adapter={}, instance={}, errorCode={}, duration={}ms, error={}",
                    adapterType, instanceId, errorCode, durationMs, 
                    error != null ? error.getMessage() : "null");
        }
    }

    /**
     * 记录重试事件
     * 
     * @param adapterType 适配器类型
     * @param instanceId 实例 ID
     * @param retryCount 重试次数
     * @param error 导致重试的错误
     */
    public void recordRetry(
            final String adapterType,
            final String instanceId,
            final int retryCount,
            final Throwable error) {
        
        if (log.isDebugEnabled()) {
            log.debug("记录重试：adapter={}, instance={}, retryCount={}, error={}",
                    adapterType, instanceId, retryCount, 
                    error != null ? error.getMessage() : "null");
        }
        
        // 重试时记录一次失败尝试
        if (metricsCollector != null) {
            metricsCollector.recordBackendCall(adapterType, instanceId, 0, false);
        }
    }

    /**
     * 记录请求大小
     * 
     * @param serviceType 服务类型
     * @param requestSize 请求大小（字节）
     * @param responseSize 响应大小（字节）
     */
    public void recordRequestSize(
            final ModelServiceRegistry.ServiceType serviceType,
            final long requestSize,
            final long responseSize) {
        
        if (metricsCollector != null) {
            metricsCollector.recordRequestSize(
                serviceType != null ? serviceType.name() : "UNKNOWN",
                requestSize,
                responseSize
            );
        }
        
        if (log.isDebugEnabled()) {
            log.debug("记录请求大小：service={}, requestSize={} bytes, responseSize={} bytes",
                    serviceType, requestSize, responseSize);
        }
    }

    /**
     * 记录响应时间
     * 
     * @param serviceType 服务类型
     * @param method HTTP 方法
     * @param responseTime 响应时间（毫秒）
     * @param status 状态码
     */
    public void recordResponseTime(
            final ModelServiceRegistry.ServiceType serviceType,
            final String method,
            final long responseTime,
            final String status) {
        
        if (metricsCollector != null) {
            metricsCollector.recordRequest(
                serviceType != null ? serviceType.name() : "UNKNOWN",
                method,
                responseTime,
                status
            );
        }
        
        if (log.isDebugEnabled()) {
            log.debug("记录响应时间：service={}, method={}, responseTime={}ms, status={}",
                    serviceType, method, responseTime, status);
        }
    }

    // ========== v2.26.0: 服务注册调用记录 ==========

    /**
     * v2.9.3: 记录调用成功到服务注册（带调用时长和成功状态）
     * 
     * @param serviceType 服务类型
     * @param instance 实例
     * @param durationMs 调用耗时（毫秒）
     * @param success 是否成功
     */
    public void recordCallSuccessToRegistry(
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final long durationMs,
            final boolean success) {
        
        if (registry != null && serviceType != null && instance != null) {
            registry.recordCallComplete(serviceType, instance, durationMs, success);
        }
        
        if (log.isDebugEnabled()) {
            log.debug("记录调用成功：service={}, instance={}, duration={}ms", serviceType, instance.getName(), durationMs);
        }
    }

    /**
     * v2.9.3: 记录调用失败到服务注册（带调用时长和错误码）
     * 
     * @param serviceType 服务类型
     * @param instance 实例
     * @param durationMs 调用耗时（毫秒）
     * @param errorCode 错误码
     */
    public void recordCallFailureToRegistry(
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final long durationMs,
            final String errorCode) {
        
        if (registry != null && serviceType != null && instance != null) {
            registry.recordCallFailure(serviceType, instance, durationMs, errorCode);
        }
        
        if (log.isDebugEnabled()) {
            log.debug("记录调用失败：service={}, instance={}, duration={}ms, errorCode={}", 
                    serviceType, instance.getName(), durationMs, errorCode);
        }
    }

    /**
     * issue #647: 记录客户端侧 4xx 到服务注册——只释放在途计数，不触发实例熔断。
     */
    public void recordClientErrorToRegistry(
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final long durationMs,
            final String errorCode) {

        if (registry != null && serviceType != null && instance != null) {
            registry.recordClientErrorComplete(serviceType, instance, durationMs);
        }

        if (log.isDebugEnabled()) {
            log.debug("记录客户端错误（不计熔断）：service={}, instance={}, duration={}ms, errorCode={}",
                    serviceType, instance.getName(), durationMs, errorCode);
        }
    }

    /**
     * 完整的调用成功记录（统一入口，不含请求/响应体）
     * 
     * 同时更新：Registry + MetricsCollector + StatsRepository
     * 
     * @param adapterType 适配器类型
     * @param instanceId 实例ID
     * @param durationMs 耗时（毫秒）
     * @param success 是否成功
     * @param errorCode 错误代码（失败时）
     * @param modelName 模型名称
     * @param serviceType 服务类型
     * @param instance 实例对象
     */
    public void recordCompleteCall(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        recordCompleteCall(adapterType, instanceId, durationMs, success, errorCode,
                modelName, serviceType, instance, null, null);
    }

    /**
     * 完整的调用统计记录（不含调用历史，v2.9.2 用于 processor 已记录历史时的统计更新）
     *
     * 同时更新：Registry + MetricsCollector + StatsRepository
     *
     * @param adapterType  适配器类型
     * @param instanceId   实例ID
     * @param durationMs   耗时（毫秒）
     * @param success      是否成功
     * @param errorCode    错误代码（失败时）
     * @param modelName    模型名称
     * @param serviceType  服务类型
     * @param instance     实例对象
     */
    public void recordCompleteCallStats(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        recordStats(adapterType, instanceId, durationMs, success, errorCode, modelName, serviceType, instance);

        if (log.isDebugEnabled()) {
            log.debug("完整调用统计记录：adapter={}, instance={}, duration={}ms, success={}, errorCode={}",
                    adapterType, instanceId, durationMs, success, errorCode);
        }
    }

    /**
     * 完整的调用成功记录（含请求/响应体捕获 - v2.9.2 记录治理）
     *
     * 当 recordLevel != METADATA_ONLY 时，记录请求体和响应体。
     * SUMMARY 级别：通过 SanitizationService 脱敏后再记录。
     * FULL 级别：直接记录原始内容。
     *
     * @param adapterType  适配器类型
     * @param instanceId   实例ID
     * @param durationMs   耗时（毫秒）
     * @param success      是否成功
     * @param errorCode    错误代码（失败时）
     * @param modelName    模型名称
     * @param serviceType  服务类型
     * @param instance     实例对象
     * @param requestBody  请求体原始内容（可为 null，METADATA_ONLY 时忽略）
     * @param responseBody 响应体原始内容（可为 null，METADATA_ONLY 时忽略）
     */
    public void recordCompleteCall(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final String requestBody,
            final String responseBody) {
        // traceId 在当前线程就近捕获（兜底重载）：仅当调用方位于追踪上下文存活的线程上才有效
        recordCompleteCall(adapterType, instanceId, durationMs, success, errorCode,
                modelName, serviceType, instance, requestBody, responseBody,
                TracingContextHolder.getCurrentTraceId());
    }

    /**
     * 完整的调用成功记录（含请求/响应体捕获），traceId 由调用方<b>同步捕获后显式透传</b>。
     *
     * <p><b>为什么必须透传</b>：本方法内部的落库可能发生在异步脱敏回调线程上，
     * {@link TracingContextHolder} 是 ThreadLocal 实现——回调线程上没有请求的追踪上下文，
     * 运行时再取只会拿到空串（落库侧退化为随机 UUID），调用历史就无法与追踪系统对账。
     * 调用方须在请求处理线程（上下文存活）同步捕获后传入
     * （与流式路径 {@code traceIdForRecord} 同一修法）。</p>
     *
     * @param traceId 调用方同步捕获的追踪 ID；空串/null 时退回运行时取值
     */
    public void recordCompleteCall(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final String requestBody,
            final String responseBody,
            final String traceId) {
        recordCompleteCallInternal(adapterType, instanceId, durationMs, success, errorCode,
                modelName, serviceType, instance, requestBody, responseBody, traceId, false);
    }

    /**
     * issue #647：客户端侧 4xx 的完整调用记录（含调用历史）。
     *
     * <p>与普通 {@code recordCompleteCall(..., success=false)} 的差别只在 Registry：
     * 走 {@code registry.recordClientErrorComplete} 释放在途但不触发熔断失败；
     * MetricsCollector / StatsRepository / 调用历史照常记录 4xx，避免观测面丢数据。</p>
     *
     * @param errorCode 真实 4xx 状态码字符串（调用方从异常提取，不用 classifyError 的 "503"）
     */
    public void recordClientErrorCompleteCall(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        recordCompleteCallInternal(adapterType, instanceId, durationMs, false, errorCode,
                modelName, serviceType, instance, null, null,
                TracingContextHolder.getCurrentTraceId(), true);
    }

    private void recordCompleteCallInternal(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final String requestBody,
            final String responseBody,
            final String traceId,
            final boolean clientSideError) {

        // v2.9.2: 统计更新（Registry + MetricsCollector + StatsRepository）
        recordStats(adapterType, instanceId, durationMs, success, errorCode,
                modelName, serviceType, instance, clientSideError);

        // v2.7.8: 记录 API 调用历史（v2.9.2: 含请求/响应体）
        // G1 修复：落库触发与"是否携带报文"解耦。此前在 METADATA_ONLY 下把 requestBody/responseBody 置为 null，
        // 再走 Mono.zip(justOrEmpty(null), justOrEmpty(null)) —— zip(empty, empty) 空完成，success 回调永不触发，
        // 导致默认配置下非流式调用历史静默不落库。
        // 现改为：元数据级直接构造元数据记录落库（不依赖任何异步脱敏），SUMMARY/FULL 才走脱敏 zip。
        if (callHistoryRecorder != null && serviceType != null && modelName != null) {
            final RecordLevel level = resolveRecordLevel();
            final boolean needSanitize = level == RecordLevel.SUMMARY && sanitizationService != null;
            final SanitizationService sanitization = this.sanitizationService;
            final ApiCallHistoryRecorder recorder = this.callHistoryRecorder;
            final String finalAdapterType = adapterType;
            final String finalInstanceId = instanceId;
            final ModelRouterProperties.ModelInstance finalInstance = instance;
            final String finalServiceType = serviceType.name();
            final String finalModelName = modelName;
            final long finalDurationMs = durationMs;
            final boolean finalSuccess = success;
            final String finalErrorCode = errorCode;

            if (level == RecordLevel.METADATA_ONLY) {
                // 元数据级：直接落库（buffer.offer 非阻塞，无需 boundedElastic）。
                try {
                    recorder.record(buildCallHistoryRecord(finalServiceType, finalModelName, finalAdapterType,
                            finalInstanceId, finalInstance, finalDurationMs, finalSuccess, finalErrorCode, null, null,
                            traceId));
                } catch (Exception e) {
                    log.debug("Failed to record call history (metadata): {}", e.getMessage());
                }
            } else {
                Mono.zip(
                                sanitizeOptional(needSanitize, sanitization, requestBody),
                                sanitizeOptional(needSanitize, sanitization, responseBody))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(tuple -> {
                            try {
                                recorder.record(buildCallHistoryRecord(finalServiceType, finalModelName, finalAdapterType,
                                        finalInstanceId, finalInstance, finalDurationMs, finalSuccess, finalErrorCode,
                                        tuple.getT1(), tuple.getT2(), traceId));
                            } catch (Exception e) {
                                log.debug("Failed to record call history: {}", e.getMessage());
                            }
                        }, err -> log.debug("Failed to sanitize call history: {}", err.getMessage()));
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("完整调用记录：adapter={}, instance={}, duration={}ms, success={}, errorCode={}",
                    adapterType, instanceId, durationMs, success, errorCode);
        }
    }

    /**
     * 更新统计（Registry + MetricsCollector + StatsRepository）
     */
    private void recordStats(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        recordStats(adapterType, instanceId, durationMs, success, errorCode,
                modelName, serviceType, instance, false);
    }

    /**
     * 更新统计（Registry + MetricsCollector + StatsRepository）。
     *
     * @param clientSideError 是否客户端侧 4xx（issue #647）：为 true 时 Registry 侧
     *                        只释放在途、不记熔断失败；其余观测面与常规失败一致
     */
    private void recordStats(
            final String adapterType,
            final String instanceId,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String modelName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final boolean clientSideError) {

        // 更新 Registry（v2.9.3: 传递 durationMs 到负载均衡器钩子）
        if (success) {
            recordCallSuccessToRegistry(serviceType, instance, durationMs, success);
        } else if (clientSideError) {
            recordClientErrorToRegistry(serviceType, instance, durationMs, errorCode);
        } else {
            recordCallFailureToRegistry(serviceType, instance, durationMs, errorCode);
        }

        // 更新 MetricsCollector
        if (metricsCollector != null) {
            metricsCollector.recordBackendCall(adapterType, instanceId, durationMs, success);
        }

        // 更新 StatsRepository
        if (statsRepository != null && serviceType != null && modelName != null) {
            statsRepository.updateStats(serviceType.name(), modelName, success, durationMs);
            if (!success && errorCode != null) {
                statsRepository.recordErrorCode(serviceType.name(), modelName, errorCode);
            }
        }
    }

    /**
     * 解析当前记录级别（默认 METADATA_ONLY）
     */
    private RecordLevel resolveRecordLevel() {
        if (callHistoryProperties != null) {
            return callHistoryProperties.getRecordLevel();
        }
        return RecordLevel.METADATA_ONLY;
    }

    /**
     * 异步脱敏：SUMMARY 且装配了 SanitizationService 时走脱敏链路，否则原样透传。
     * 超时/异常回退为原文，保证调用历史仍可记录。
     */
    private Mono<String> sanitizeOptional(final boolean needSanitize,
                                          final SanitizationService sanitization,
                                          final String raw) {
        if (!needSanitize || sanitization == null) {
            return Mono.justOrEmpty(raw);
        }
        return sanitization.sanitizeForStorage(raw, "application/json")
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    log.debug("Sanitization failed: {}", e.getMessage());
                    return Mono.justOrEmpty(raw);
                })
                .map(sanitized -> sanitized != null ? sanitized : raw)
                .defaultIfEmpty(raw);
    }

    /**
     * 构造调用历史记录 DTO（元数据 + 可选报文）。
     * 提取为方法避免 METADATA_ONLY / SUMMARY·FULL 两条路径重复组装字段。
     *
     * @param traceId 调用方同步捕获的追踪 ID（优先使用）；空串/null 时退回当前线程取值
     */
    private CallHistoryRecordDTO buildCallHistoryRecord(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceId,
            final ModelRouterProperties.ModelInstance instance,
            final long durationMs,
            final boolean success,
            final String errorCode,
            final String requestBody,
            final String responseBody,
            final String traceId) {
        // 关联链路追踪：优先用调用方同步捕获的 traceId（异步回调线程无追踪上下文）；
        // 两者皆空时落库侧兜底随机 UUID
        final String effectiveTraceId = traceId != null && !traceId.isBlank()
                ? traceId : TracingContextHolder.getCurrentTraceId();
        return CallHistoryRecordDTO.builder()
                .traceId(effectiveTraceId)
                .serviceType(serviceType)
                .modelName(modelName)
                .provider(provider)
                .instanceName(instanceId)
                .instanceUrl(instance != null ? instance.getBaseUrl() : null)
                .responseTimeMs(durationMs)
                .isSuccess(success)
                .errorCode(errorCode)
                .requestBody(requestBody)
                .responseBody(responseBody)
                .build();
    }
}
