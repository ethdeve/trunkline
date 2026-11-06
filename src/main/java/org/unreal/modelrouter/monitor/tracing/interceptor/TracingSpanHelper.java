package org.unreal.modelrouter.monitor.tracing.interceptor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.monitor.tracing.TracingContext;
import org.unreal.modelrouter.monitor.tracing.logger.StructuredLogger;
import org.unreal.modelrouter.monitor.tracing.query.TraceQueryService;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 追踪Span辅助类
 *
 * 从 ControllerTracingInterceptor 提取的Span记录相关私有方法，包括：
 * - 实例选择追踪
 * - 适配器调用追踪
 * - 子Span记录
 * - Controller属性设置
 * - Controller日志记录
 * - 成功/错误响应处理
 *
 * @author JAiRouter Team
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TracingSpanHelper {

    private final StructuredLogger structuredLogger;
    private final TraceQueryService traceQueryService;

    /**
     * 实际执行实例选择追踪
     */
    void doTraceInstanceSelection(
            final TracingContext tracingContext,
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final String clientIp,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final Throwable error) {

        Instant startTime = Instant.now();
        // 入口线程捕获 traceId 快照（本方法为同步调用，上下文此刻必然有效）
        final String capturedTraceId = tracingContext.getTraceId();
        // 附加到链路记录的属性（前端链路详情的步骤副标题取自这里）
        final Map<String, Object> spanAttrs = new HashMap<>();
        final boolean[] failed = {false};

        // 创建实例选择Span
        String operationName = "InstanceSelection";
        Span selectionSpan = tracingContext.createChildSpan(
            operationName,
            SpanKind.INTERNAL,
            tracingContext.getCurrentSpan()
        );

        try {
            // 设置实例选择属性
            selectionSpan.setAttribute("service.type", serviceType.name());
            selectionSpan.setAttribute("model.name", modelName);
            selectionSpan.setAttribute("client.ip", clientIp != null ? clientIp : "unknown");
            spanAttrs.put("service.type", serviceType.name());
            spanAttrs.put("model.name", modelName);
            spanAttrs.put("client.ip", clientIp != null ? clientIp : "unknown");

            if (selectedInstance != null) {
                selectionSpan.setAttribute("instance.id", selectedInstance.getInstanceId());
                selectionSpan.setAttribute("instance.name", selectedInstance.getName());
                selectionSpan.setAttribute("instance.base_url", selectedInstance.getBaseUrl());
                selectionSpan.setAttribute("instance.adapter",
                    selectedInstance.getAdapter() != null ? selectedInstance.getAdapter() : "default");
                selectionSpan.setAttribute("instance.weight", selectedInstance.getWeight());
                spanAttrs.put("instance.id", selectedInstance.getInstanceId());
                spanAttrs.put("instance.name", selectedInstance.getName());
                spanAttrs.put("instance.base_url", selectedInstance.getBaseUrl());
                spanAttrs.put("instance.adapter",
                    selectedInstance.getAdapter() != null ? selectedInstance.getAdapter() : "default");
                spanAttrs.put("instance.weight", selectedInstance.getWeight());

                // 记录实例选择成功事件
                Map<String, Object> eventData = new HashMap<>();
                eventData.put("service_type", serviceType.name());
                eventData.put("model_name", modelName);
                eventData.put("selected_instance", selectedInstance.getInstanceId());
                eventData.put("instance_url", selectedInstance.getBaseUrl());
                eventData.put("adapter", selectedInstance.getAdapter() != null
                    ? selectedInstance.getAdapter() : "default");

                structuredLogger.logBusinessEvent("instance_selected", eventData, tracingContext);

                selectionSpan.setStatus(StatusCode.OK);

                long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                selectionSpan.setAttribute("duration_ms", duration);

                log.debug("实例选择完成: service={}, model={}, instance={}, duration={}ms",
                    serviceType, modelName, selectedInstance.getInstanceId(), duration);
            } else if (error != null) {
                // 实例选择失败
                failed[0] = true;
                spanAttrs.put("error.type", error.getClass().getSimpleName());
                spanAttrs.put("error.message", error.getMessage() != null ? error.getMessage() : "");
                selectionSpan.setStatus(StatusCode.ERROR, error.getMessage());
                selectionSpan.recordException(error);
                selectionSpan.setAttribute("error.type", error.getClass().getSimpleName());
                selectionSpan.setAttribute("error.message", error.getMessage());

                long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                selectionSpan.setAttribute("duration_ms", duration);

                // 记录实例选择失败事件
                Map<String, Object> errorData = new HashMap<>();
                errorData.put("service_type", serviceType.name());
                errorData.put("model_name", modelName);
                errorData.put("error_type", error.getClass().getSimpleName());
                errorData.put("error_message", error.getMessage());
                errorData.put("duration_ms", duration);

                structuredLogger.logError(error, tracingContext, errorData);

                log.warn("实例选择失败: service={}, model={}, error={}, duration={}ms",
                    serviceType, modelName, error.getMessage(), duration);
            }
        } catch (Exception e) {
            failed[0] = true;
            spanAttrs.put("error.type", e.getClass().getSimpleName());
            spanAttrs.put("error.message", e.getMessage() != null ? e.getMessage() : "");
            selectionSpan.setStatus(StatusCode.ERROR, e.getMessage());
            selectionSpan.recordException(e);
            log.warn("记录实例选择追踪失败", e);
        } finally {
            if (selectionSpan.isRecording()) {
                selectionSpan.end();
            }
            recordChildSpan(selectionSpan, tracingContext, startTime, operationName, capturedTraceId, spanAttrs, failed[0]);
        }
    }

    /**
     * 实际执行适配器调用追踪
     */
    <T> Mono<T> doTraceAdapterCall(
            final TracingContext tracingContext,
            final String adapterName,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final Supplier<Mono<T>> operation) {

        Instant startTime = Instant.now();
        // 入口线程捕获 traceId 快照：适配器完成回调可能在其它线程上执行，
        // 届时共享上下文的 currentSpan 存在可见性竞态，实时取值可能为空
        final String capturedTraceId = tracingContext.getTraceId();
        // 附加到链路记录的属性（前端链路详情的步骤副标题取自这里）
        final Map<String, Object> spanAttrs = new HashMap<>();
        spanAttrs.put("adapter.name", adapterName);
        spanAttrs.put("adapter.service_type", serviceType.name());
        spanAttrs.put("adapter.instance_id", instance.getInstanceId());
        spanAttrs.put("adapter.instance_url", instance.getBaseUrl());

        // 创建适配器调用Span
        String operationName = String.format("Adapter.%s.%s", adapterName, serviceType.name());
        Span adapterSpan = tracingContext.createChildSpan(
            operationName,
            SpanKind.INTERNAL,
            tracingContext.getCurrentSpan()
        );

        // 设置适配器属性
        adapterSpan.setAttribute("adapter.name", adapterName);
        adapterSpan.setAttribute("adapter.service_type", serviceType.name());
        adapterSpan.setAttribute("adapter.instance_id", instance.getInstanceId());
        adapterSpan.setAttribute("adapter.instance_url", instance.getBaseUrl());

        // 记录适配器调用开始
        Map<String, Object> startData = new HashMap<>();
        startData.put("adapter", adapterName);
        startData.put("service_type", serviceType.name());
        startData.put("instance_id", instance.getInstanceId());
        startData.put("instance_url", instance.getBaseUrl());

        structuredLogger.logBusinessEvent("adapter_call_start", startData, tracingContext);

        return operation.get()
            .doOnSuccess(result -> {
                long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                adapterSpan.setStatus(StatusCode.OK);
                adapterSpan.setAttribute("duration_ms", duration);

                // 记录适配器调用成功
                Map<String, Object> successData = new HashMap<>();
                successData.put("adapter", adapterName);
                successData.put("service_type", serviceType.name());
                successData.put("instance_id", instance.getInstanceId());
                successData.put("duration_ms", duration);
                successData.put("success", true);

                structuredLogger.logBusinessEvent("adapter_call_complete", successData, tracingContext);

                log.debug("适配器调用成功: adapter={}, service={}, instance={}, duration={}ms",
                    adapterName, serviceType, instance.getInstanceId(), duration);
            })
            .doOnError(error -> {
                long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                adapterSpan.setStatus(StatusCode.ERROR, error.getMessage());
                adapterSpan.recordException(error);
                adapterSpan.setAttribute("duration_ms", duration);

                // 记录适配器调用失败
                Map<String, Object> errorData = new HashMap<>();
                errorData.put("adapter", adapterName);
                errorData.put("service_type", serviceType.name());
                errorData.put("instance_id", instance.getInstanceId());
                errorData.put("duration_ms", duration);
                errorData.put("success", false);
                errorData.put("error_type", error.getClass().getSimpleName());
                errorData.put("error_message", error.getMessage());

                structuredLogger.logError(error, tracingContext, errorData);

                log.warn("适配器调用失败: adapter={}, service={}, instance={}, error={}",
                    adapterName, serviceType, instance.getInstanceId(), error.getMessage());
            })
            .doFinally(signalType -> {
                if (adapterSpan.isRecording()) {
                    adapterSpan.end();
                }
                recordChildSpan(adapterSpan, tracingContext, startTime, operationName, capturedTraceId,
                    spanAttrs, signalType == reactor.core.publisher.SignalType.ON_ERROR);
            });
    }

    /**
     * 记录子Span到TraceQueryService
     *
     * <p>v3.5 修复：共享 TracingContext 的 currentSpan 跨线程读取存在可见性竞态，
     * 完成回调线程上 getTraceId() 可能返回空串，导致子 span（Adapter/Controller）
     * 以空 traceId 落库（脏数据）或链路缺步骤。调用方须在入口线程捕获 traceId
     * 快照传入；快照与上下文取值均为空时跳过落库。</p>
     *
     * @param capturedTraceId 调用方在入口线程捕获的 traceId 快照（可为 null）
     * @param attributes 附加到链路记录上的属性（model/instance/adapter 等，供前端链路详情展示）
     * @param error      该子步骤是否失败
     */
    void recordChildSpan(
            final Span span, final TracingContext tracingContext,
            final Instant startTime, final String operationName,
            final String capturedTraceId,
            final Map<String, Object> attributes, final boolean error) {
        if (traceQueryService == null || tracingContext == null || span == null) {
            return;
        }

        // traceId 快照优先，缺失时回退上下文实时取值；两者皆空则拒绝落库（避免产生空 traceId 脏数据）
        final String traceId = (capturedTraceId != null && !capturedTraceId.isBlank())
            ? capturedTraceId : tracingContext.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            log.debug("子Span落库跳过：追踪编号缺失, operation={}", operationName);
            return;
        }

        try {
            long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
            Instant endTime = Instant.now();

            // 使用 TraceQueryService.SpanRecord 内部类
            TraceQueryService.SpanRecord spanRecord = new TraceQueryService.SpanRecord(
                span.getSpanContext().getSpanId(),
                traceId,
                operationName != null ? operationName : "child-span",
                startTime,
                endTime,
                duration,
                error,
                error ? "ERROR" : "OK",
                attributes != null ? attributes : new HashMap<>()
            );

            // 使用同步方法记录 span，确保数据存储完成
            // Controller层请求都是后端API请求，使用 "server" 作为服务标识
            traceQueryService.recordTraceSync(
                traceId,
                "server",
                Collections.singletonList(spanRecord),
                duration
            );

            log.debug("子Span已记录: traceId={}, spanId={}, operation={}, duration={}ms",
                traceId, span.getSpanContext().getSpanId(), operationName, duration);
        } catch (Exception e) {
            log.debug("记录子Span失败", e);
        }
    }

    /**
     * 设置Controller层属性
     */
    void setControllerAttributes(
            final Span span,
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final ServerHttpRequest httpRequest,
            final String methodName) {

        try {
            span.setAttribute("controller.method", methodName);
            span.setAttribute("service.type", serviceType.name());
            span.setAttribute("model.name", modelName);
            span.setAttribute("http.method", httpRequest.getMethod().name());
            span.setAttribute("http.path", httpRequest.getPath().value());

            // 添加请求头信息（脱敏）
            String contentType = httpRequest.getHeaders().getFirst("Content-Type");
            if (contentType != null) {
                span.setAttribute("http.content_type", contentType);
            }

            String contentLength = httpRequest.getHeaders().getFirst("Content-Length");
            if (contentLength != null) {
                try {
                    span.setAttribute("http.request_size", Long.parseLong(contentLength));
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
        } catch (Exception e) {
            log.debug("设置Controller属性失败", e);
        }
    }

    /**
     * 记录Controller调用开始
     */
    void logControllerStart(
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final ServerHttpRequest httpRequest,
            final String methodName,
            final TracingContext tracingContext) {

        try {
            Map<String, Object> startData = new HashMap<>();
            startData.put("controller_method", methodName);
            startData.put("service_type", serviceType.name());
            startData.put("model_name", modelName);
            startData.put("http_method", httpRequest.getMethod().name());
            startData.put("http_path", httpRequest.getPath().value());

            structuredLogger.logBusinessEvent("controller_call_start", startData, tracingContext);
        } catch (Exception e) {
            log.debug("记录Controller开始日志失败", e);
        }
    }

    /**
     * 处理Controller成功响应
     */
    void handleControllerSuccess(
            final Span span,
            final ResponseEntity<?> response,
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final String methodName,
            final long duration,
            final TracingContext tracingContext) {

        try {
            span.setStatus(StatusCode.OK);
            span.setAttribute("duration_ms", duration);
            span.setAttribute("http.status_code", response.getStatusCode().value());

            // 记录响应大小
            if (response.getBody() != null) {
                String bodyStr = response.getBody().toString();
                span.setAttribute("http.response_size", bodyStr.length());
            }

            // 记录Controller调用成功
            Map<String, Object> successData = new HashMap<>();
            successData.put("controller_method", methodName);
            successData.put("service_type", serviceType.name());
            successData.put("model_name", modelName);
            successData.put("duration_ms", duration);
            successData.put("status_code", response.getStatusCode().value());
            successData.put("success", true);

            structuredLogger.logBusinessEvent("controller_call_complete", successData, tracingContext);

            log.debug("Controller调用成功: method={}, service={}, model={}, duration={}ms, status={}",
                methodName, serviceType, modelName, duration, response.getStatusCode().value());

        } catch (Exception e) {
            log.debug("处理Controller成功响应失败", e);
        }
    }

    /**
     * 处理Controller错误响应
     */
    void handleControllerError(
            final Span span,
            final Throwable error,
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final String methodName,
            final long duration,
            final TracingContext tracingContext) {

        try {
            span.setStatus(StatusCode.ERROR, error.getMessage());
            span.recordException(error);
            span.setAttribute("duration_ms", duration);

            // 记录Controller调用失败
            Map<String, Object> errorData = new HashMap<>();
            errorData.put("controller_method", methodName);
            errorData.put("service_type", serviceType.name());
            errorData.put("model_name", modelName);
            errorData.put("duration_ms", duration);
            errorData.put("success", false);
            errorData.put("error_type", error.getClass().getSimpleName());
            errorData.put("error_message", error.getMessage());

            structuredLogger.logError(error, tracingContext, errorData);

            log.warn("Controller调用失败: method={}, service={}, model={}, error={}",
                methodName, serviceType, modelName, error.getMessage());

        } catch (Exception e) {
            log.debug("处理Controller错误响应失败", e);
        }
    }
}
