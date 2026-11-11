package org.unreal.modelrouter.monitor.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.monitor.tracing.query.TraceQueryService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 追踪查询控制器
 * 
 * 提供追踪数据查询和分析的REST API接口，包括：
 * - 追踪链路查询
 * - 追踪数据搜索
 * - 服务统计信息
 * - 性能分析数据
 * 
 * @author JAiRouter Team
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/tracing/query")
@Tag(name = "追踪查询", description = "追踪数据查询和分析API")
public class TracingQueryController {

    private final TraceQueryService traceQueryService;
    
    // 手动添加构造函数
    public TracingQueryController(final TraceQueryService traceQueryService) {
        this.traceQueryService = traceQueryService;
    }
    
    @GetMapping("/trace/{traceId}")
    @Operation(summary = "获取追踪链路详情", description = "根据traceId获取完整的追踪链路信息")
    @ApiResponse(responseCode = "200", description = "成功返回追踪链路")
    @ApiResponse(responseCode = "404", description = "追踪链路不存在")
    public Mono<ResponseEntity<Map<String, Object>>> getTraceChain(
            @Parameter(description = "追踪ID") @PathVariable final String traceId) {
        
        return traceQueryService.getTraceChain(traceId)
            .map(traceChain -> {
                if (traceChain == null) {
                    return ResponseEntity.notFound().<Map<String, Object>>build();
                }
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", traceChain);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @GetMapping("/search")
    @Operation(summary = "搜索追踪数据", description = "根据条件搜索追踪数据")
    @ApiResponse(responseCode = "200", description = "成功返回搜索结果")
    public Mono<ResponseEntity<Map<String, Object>>> searchTraces(
            @Parameter(description = "开始时间戳（毫秒）") @RequestParam(required = false) final Long startTime,
            @Parameter(description = "结束时间戳（毫秒）") @RequestParam(required = false) final Long endTime,
            @Parameter(description = "服务名") @RequestParam(required = false) final String serviceName,
            @Parameter(description = "操作名") @RequestParam(required = false) final String operationName,
            @Parameter(description = "追踪ID") @RequestParam(required = false) final String traceId,
            @Parameter(description = "最小持续时间（毫秒）") @RequestParam(defaultValue = "0") final double minDuration,
            @Parameter(description = "最大持续时间（毫秒）") @RequestParam(defaultValue = "0") final double maxDuration,
            @Parameter(description = "是否有错误") @RequestParam(required = false) final Boolean hasError,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") final int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") final int size) {
        
        TraceQueryService.TraceSearchCriteria criteria = new TraceQueryService.TraceSearchCriteria();
        if (startTime != null) criteria.setStartTime(Instant.ofEpochMilli(startTime));
        if (endTime != null) criteria.setEndTime(Instant.ofEpochMilli(endTime));
        criteria.setServiceName(serviceName);
        criteria.setOperationName(operationName);
        criteria.setTraceId(traceId);
        criteria.setMinDuration(minDuration);
        criteria.setMaxDuration(maxDuration);
        criteria.setHasError(hasError);
        criteria.setPage(page);
        criteria.setSize(size);
        
        return traceQueryService.searchTracesWithPagination(criteria)
            .map(result -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", result);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @GetMapping("/recent")
    @Operation(summary = "获取最近的追踪", description = "获取最近的追踪记录")
    @ApiResponse(responseCode = "200", description = "成功返回最近追踪")
    public Mono<ResponseEntity<Map<String, Object>>> getRecentTraces(
            @Parameter(description = "限制数量") @RequestParam(defaultValue = "50") final int limit) {
        
        return traceQueryService.getRecentTraces(limit)
            .collectList()
            .map(traces -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", traces);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @GetMapping("/services")
    @Operation(summary = "获取服务统计", description = "获取所有服务的统计信息")
    @ApiResponse(responseCode = "200", description = "成功返回服务统计")
    public Mono<ResponseEntity<Map<String, Object>>> getServiceStatistics() {
        return traceQueryService.getServiceStatistics()
            .map(services -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", services);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @GetMapping("/statistics")
    @Operation(summary = "获取追踪统计", description = "获取指定时间范围内的追踪统计信息")
    @ApiResponse(responseCode = "200", description = "成功返回统计信息")
    public Mono<ResponseEntity<Map<String, Object>>> getTraceStatistics(
            @Parameter(description = "开始时间戳（毫秒）") @RequestParam(required = false) final Long startTime,
            @Parameter(description = "结束时间戳（毫秒）") @RequestParam(required = false) final Long endTime) {
        
        // 设置默认时间范围（最近1小时）
        long now = System.currentTimeMillis();
        long start = startTime != null ? startTime : now - 3600000; // 1小时前
        long end = endTime != null ? endTime : now;
        
        return traceQueryService.getTraceStatistics(start, end)
            .map(stats -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", stats);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @PostMapping("/export")
    @Operation(summary = "导出追踪数据", description = "导出指定条件的追踪数据")
    @ApiResponse(responseCode = "200", description = "成功导出数据")
    public Mono<ResponseEntity<Map<String, Object>>> exportTraces(
            @RequestBody final TraceQueryService.TraceExportRequest exportRequest) {
        
        return traceQueryService.exportTraces(exportRequest)
            .map(result -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", result);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @PostMapping("/cleanup")
    @Operation(summary = "清理过期追踪", description = "清理指定时间之前的追踪数据")
    @ApiResponse(responseCode = "200", description = "成功清理数据")
    public Mono<ResponseEntity<Map<String, Object>>> cleanupExpiredTraces(
            @Parameter(description = "保留小时数")
            @RequestParam(defaultValue = "24") @Min(1) @Max(8760) final int retentionHours) {

        // 必须用 long 起步：int 相乘在 retentionHours > 596523 时溢出为负数，
        // 会让 cutoff 落到未来、removeIf 清空全部追踪数据。@Min/@Max 是第一道闸，
        // 这里的显式 long 转换是第二道（防止校验被绕过时仍能兜住）。
        long maxAgeMillis = (long) retentionHours * 60 * 60 * 1000L;
        
        return traceQueryService.cleanupExpiredTraces(maxAgeMillis)
            .map(removedCount -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", Map.of(
                    "removedCount", removedCount,
                    "retentionHours", retentionHours
                ));
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @GetMapping("/operations")
    @Operation(summary = "获取操作列表", description = "获取指定服务的操作列表")
    @ApiResponse(responseCode = "200", description = "成功返回操作列表")
    public Mono<ResponseEntity<Map<String, Object>>> getOperations(
            @Parameter(description = "服务名") @RequestParam(required = false) final String serviceName) {
        
        return Mono.fromCallable(() -> {
            // 这里应该从TraceQueryService获取操作列表，暂时返回模拟数据
            List<String> operations;
            if (serviceName != null) {
                operations = List.of(
                    serviceName + ".process",
                    serviceName + ".validate",
                    serviceName + ".transform"
                );
            } else {
                operations = List.of(
                    "chat.process",
                    "embedding.process", 
                    "rerank.process",
                    "tts.process",
                    "stt.process"
                );
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", operations);
            return ResponseEntity.ok(response);
        });
    }

    @GetMapping("/health")
    @Operation(summary = "查询服务健康检查", description = "检查追踪查询服务的健康状态")
    @ApiResponse(responseCode = "200", description = "服务健康")
    public Mono<ResponseEntity<Map<String, Object>>> getQueryServiceHealth() {
        return Mono.fromCallable(() -> {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "UP");
            health.put("service", "TraceQueryService");
            health.put("timestamp", Instant.now());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", health);
            return ResponseEntity.ok(response);
        });
    }

    // Performance Analysis Endpoints for Performance.vue

    @GetMapping("/performance/stats")
    @Operation(summary = "获取性能统计", description = "获取指定时间范围内的性能统计数据")
    @ApiResponse(responseCode = "200", description = "成功返回性能统计")
    public Mono<ResponseEntity<Map<String, Object>>> getPerformanceStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) final String startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) final String endTime) {
        
        return Mono.fromCallable(() -> {
            // 基于现有的追踪统计数据生成性能统计
            long now = System.currentTimeMillis();
            long start = startTime != null ? parseTimeString(startTime) : now - 3600000;
            long end = endTime != null ? parseTimeString(endTime) : now;
            return new long[]{start, end};
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(timeRange -> {
            return traceQueryService.getTraceStatistics(timeRange[0], timeRange[1]);
        })
        .map(stats -> {
            Map<String, Object> performanceStats = new HashMap<>();
            performanceStats.put("totalRequests", stats.getTotalTraces());
            performanceStats.put("successfulRequests", stats.getSuccessfulTraces());
            performanceStats.put("errorRequests", stats.getErrorTraces());
            performanceStats.put("avgLatency", stats.getAvgDuration());
            performanceStats.put("maxLatency", stats.getMaxDuration());
            performanceStats.put("minLatency", stats.getMinDuration());
            performanceStats.put("errorRate", stats.getTotalTraces() > 0
                ? (double) stats.getErrorTraces() / stats.getTotalTraces() * 100 : 0.0);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", performanceStats);
            return ResponseEntity.ok(response);
        })
        .onErrorReturn(ResponseEntity.internalServerError().build());
    }

    @GetMapping("/performance/latency")
    @Operation(summary = "获取延迟分析", description = "获取延迟分析数据（基于时间范围内的真实追踪数据计算）")
    @ApiResponse(responseCode = "200", description = "成功返回延迟分析")
    public Mono<ResponseEntity<Map<String, Object>>> getLatencyAnalysis(
            @Parameter(description = "开始时间戳（毫秒）") @RequestParam(required = false) final Long startTime,
            @Parameter(description = "结束时间戳（毫秒）") @RequestParam(required = false) final Long endTime) {

        long[] range = resolveTimeRange(startTime, endTime);
        return traceQueryService.getTracesInTimeRange(range[0], range[1])
            .map(traces -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);

                // 构建延迟分析数据结构
                Map<String, Object> latencyData = new HashMap<>();

                // 服务延迟分布数据（真实数据）
                List<Map<String, Object>> distributionData = new ArrayList<>();
                for (Map.Entry<String, List<TraceQueryService.TraceRecord>> entry
                        : buildServiceStats(traces, range[0], range[1]).entrySet()) {
                    List<Double> durations = entry.getValue().stream()
                        .mapToDouble(TraceQueryService.TraceRecord::getDuration)
                        .sorted().boxed().collect(Collectors.toList());
                    Map<String, Object> serviceData = new HashMap<>();
                    serviceData.put("service", entry.getKey());
                    serviceData.put("avgLatency", durations.stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0.0));
                    serviceData.put("p95Latency", percentile(durations, 0.95));
                    serviceData.put("p99Latency", percentile(durations, 0.99));
                    distributionData.add(serviceData);
                }
                latencyData.put("distribution", distributionData);

                // 延迟趋势数据（真实数据，按小时分桶）
                latencyData.put("trend", buildLatencyTrend(traces, range[0], range[1]));

                response.put("data", latencyData);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @GetMapping("/performance/errors")
    @Operation(summary = "获取错误分析", description = "获取错误分析数据（基于时间范围内的真实追踪数据计算）")
    @ApiResponse(responseCode = "200", description = "成功返回错误分析")
    public Mono<ResponseEntity<Map<String, Object>>> getErrorAnalysis(
            @Parameter(description = "开始时间戳（毫秒）") @RequestParam(required = false) final Long startTime,
            @Parameter(description = "结束时间戳（毫秒）") @RequestParam(required = false) final Long endTime) {

        long[] range = resolveTimeRange(startTime, endTime);
        return traceQueryService.getTracesInTimeRange(range[0], range[1])
            .map(traces -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);

                // 构建错误分析数据结构
                Map<String, Object> errorData = new HashMap<>();

                // 错误率分布数据 - 按服务聚合（真实数据）
                errorData.put("errorRateDistribution", buildServiceStats(traces, range[0], range[1]).entrySet().stream()
                    .map(entry -> {
                        long total = entry.getValue().size();
                        long errors = countErrorTraces(entry.getValue());
                        Map<String, Object> serviceData = new HashMap<>();
                        serviceData.put("service", entry.getKey());
                        serviceData.put("totalRequests", total);
                        serviceData.put("errorCount", errors);
                        serviceData.put("errorRate", total > 0
                            ? Math.round(errors * 10000.0 / total) / 100.0 : 0.0);
                        return serviceData;
                    })
                    .collect(Collectors.toList()));

                // 错误趋势数据（真实数据，按小时分桶）
                errorData.put("errorTrend", buildErrorTrend(traces, range[0], range[1]));

                // 常见错误数据（真实数据，按错误类型聚合）
                errorData.put("commonErrors", buildCommonErrors(traces));

                response.put("data", errorData);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    @GetMapping("/performance/throughput")
    @Operation(summary = "获取吞吐量分析", description = "获取吞吐量分析数据（基于时间范围内的真实追踪数据计算）")
    @ApiResponse(responseCode = "200", description = "成功返回吞吐量分析")
    public Mono<ResponseEntity<Map<String, Object>>> getThroughputAnalysis(
            @Parameter(description = "开始时间戳（毫秒）") @RequestParam(required = false) final Long startTime,
            @Parameter(description = "结束时间戳（毫秒）") @RequestParam(required = false) final Long endTime) {

        long[] range = resolveTimeRange(startTime, endTime);
        return traceQueryService.getTracesInTimeRange(range[0], range[1])
            .map(traces -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);

                // 构建吞吐量分析数据结构
                Map<String, Object> throughputData = new HashMap<>();

                // 请求分布数据 - 按服务聚合（真实数据）
                long windowSeconds = Math.max(1, (range[1] - range[0]) / 1000);
                throughputData.put("requestDistribution",
                    buildServiceStats(traces, range[0], range[1]).entrySet().stream()
                        .map(entry -> {
                            long total = entry.getValue().size();
                            Map<String, Object> serviceData = new HashMap<>();
                            serviceData.put("service", entry.getKey());
                            serviceData.put("totalRequests", total);
                            serviceData.put("requestsPerSecond",
                                Math.round(total * 100.0 / windowSeconds) / 100.0);
                            return serviceData;
                        })
                        .collect(Collectors.toList()));

                // QPS/请求量趋势数据（真实数据，按小时分桶）
                throughputData.put("qpsTrend", buildThroughputTrend(traces, range[0], range[1]));

                response.put("data", throughputData);
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().<Map<String, Object>>build());
    }

    // Helper methods for performance analysis

    private long parseTimeString(final String timeStr) {
        try {
            return Instant.parse(timeStr).toEpochMilli();
        } catch (Exception e) {
            // 尝试解析 YYYY-MM-DD HH:mm:ss 格式
            try {
                return java.time.LocalDateTime.parse(timeStr.replace(" ", "T"))
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
            } catch (Exception ex) {
                log.warn("无法解析时间字符串: {}, 使用当前时间",
                        org.unreal.modelrouter.common.util.LogSanitizer.sanitize(timeStr));
                return System.currentTimeMillis();
            }
        }
    }

    /**
     * 解析时间范围：默认最近 24 小时（与小时粒度趋势图对应）。
     */
    private long[] resolveTimeRange(final Long startTime, final Long endTime) {
        long end = endTime != null ? endTime : System.currentTimeMillis();
        long start = startTime != null ? startTime : end - 24 * 3600000L;
        // 防止 startTime > endTime 或超大区间让 bucketTracesByHour 逐小时造出亿万级 ArrayList
        if (start > end) {
            long swap = start;
            start = end;
            end = swap;
        }
        final long maxRangeMillis = 31L * 24 * 3600 * 1000;
        long span = end - start;
        if (span > maxRangeMillis || span < 0) {
            start = end - maxRangeMillis;
        }
        return new long[]{start, end};
    }

    /**
     * 按服务分组时间范围内的追踪记录。
     */
    private Map<String, List<TraceQueryService.TraceRecord>> buildServiceStats(
            final List<TraceQueryService.TraceRecord> traces, final long start, final long end) {
        Map<String, List<TraceQueryService.TraceRecord>> byService = new HashMap<>();
        for (TraceQueryService.TraceRecord trace : traces) {
            byService.computeIfAbsent(trace.getServiceName(), k -> new ArrayList<>()).add(trace);
        }
        return byService;
    }

    private long countErrorTraces(final List<TraceQueryService.TraceRecord> traces) {
        return traces.stream()
            .filter(t -> t.getSpans().stream().anyMatch(TraceQueryService.SpanRecord::isError))
            .count();
    }

    /**
     * 按小时分桶（桶与时间范围对齐，空桶保留 0 值，保证趋势与统计口径一致）。
     */
    private TreeMap<Long, List<TraceQueryService.TraceRecord>> bucketTracesByHour(
            final List<TraceQueryService.TraceRecord> traces, final long start, final long end) {
        TreeMap<Long, List<TraceQueryService.TraceRecord>> buckets = new TreeMap<>();
        long startHour = Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.HOURS).toEpochMilli();
        long endHour = Instant.ofEpochMilli(end).truncatedTo(ChronoUnit.HOURS).toEpochMilli();
        for (long h = startHour; h <= endHour; h += 3600000L) {
            buckets.put(h, new ArrayList<>());
        }
        for (TraceQueryService.TraceRecord trace : traces) {
            long hour = trace.getCreatedAt().truncatedTo(ChronoUnit.HOURS).toEpochMilli();
            buckets.computeIfAbsent(hour, k -> new ArrayList<>()).add(trace);
        }
        return buckets;
    }

    private String hourLabel(final long hourEpochMilli, final boolean withDate) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(hourEpochMilli),
            ZoneId.systemDefault());
        return withDate
            ? time.format(DateTimeFormatter.ofPattern("MM-dd HH:00"))
            : time.format(DateTimeFormatter.ofPattern("HH:00"));
    }

    /**
     * 错误趋势：每小时桶的 errorCount / errorRate（百分比），
     * 与 /tracing/query/statistics 的 errorTraces 同源同口径。
     */
    private List<Map<String, Object>> buildErrorTrend(
            final List<TraceQueryService.TraceRecord> traces, final long start, final long end) {
        boolean withDate = bucketTracesByHour(traces, start, end).size() > 24;
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<Long, List<TraceQueryService.TraceRecord>> entry
                : bucketTracesByHour(traces, start, end).entrySet()) {
            int total = entry.getValue().size();
            long errorCount = countErrorTraces(entry.getValue());
            Map<String, Object> point = new HashMap<>();
            point.put("timestamp", entry.getKey());
            point.put("hour", hourLabel(entry.getKey(), withDate));
            point.put("total", total);
            point.put("errorCount", errorCount);
            point.put("errorRate", total > 0
                ? Math.round(errorCount * 10000.0 / total) / 100.0 : 0.0);
            trend.add(point);
        }
        return trend;
    }

    /**
     * 吞吐趋势：每小时桶的 requestCount / qps。
     */
    private List<Map<String, Object>> buildThroughputTrend(
            final List<TraceQueryService.TraceRecord> traces, final long start, final long end) {
        boolean withDate = bucketTracesByHour(traces, start, end).size() > 24;
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<Long, List<TraceQueryService.TraceRecord>> entry
                : bucketTracesByHour(traces, start, end).entrySet()) {
            long requestCount = entry.getValue().size();
            Map<String, Object> point = new HashMap<>();
            point.put("timestamp", entry.getKey());
            point.put("hour", hourLabel(entry.getKey(), withDate));
            point.put("requestCount", requestCount);
            point.put("qps", Math.round(requestCount / 3600.0 * 10000.0) / 10000.0);
            trend.add(point);
        }
        return trend;
    }

    /**
     * 延迟趋势：每小时桶的 p50/p95/p99/avg（毫秒，基于真实追踪时长）。
     */
    private List<Map<String, Object>> buildLatencyTrend(
            final List<TraceQueryService.TraceRecord> traces, final long start, final long end) {
        boolean withDate = bucketTracesByHour(traces, start, end).size() > 24;
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<Long, List<TraceQueryService.TraceRecord>> entry
                : bucketTracesByHour(traces, start, end).entrySet()) {
            List<Double> durations = entry.getValue().stream()
                .mapToDouble(TraceQueryService.TraceRecord::getDuration)
                .sorted().boxed().collect(Collectors.toList());
            Map<String, Object> point = new HashMap<>();
            point.put("timestamp", entry.getKey());
            point.put("hour", hourLabel(entry.getKey(), withDate));
            point.put("p50", Math.round(percentile(durations, 0.50) * 10.0) / 10.0);
            point.put("p95", Math.round(percentile(durations, 0.95) * 10.0) / 10.0);
            point.put("p99", Math.round(percentile(durations, 0.99) * 10.0) / 10.0);
            point.put("avg", Math.round(durations.stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0) * 10.0) / 10.0);
            trend.add(point);
        }
        return trend;
    }

    /**
     * 计算分位数（入参须为升序排列的时长列表）。
     */
    private double percentile(final List<Double> sortedDurations, final double p) {
        if (sortedDurations == null || sortedDurations.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(p * sortedDurations.size()) - 1;
        return sortedDurations.get(Math.max(0, Math.min(index, sortedDurations.size() - 1)));
    }

    /**
     * 常见错误：按错误类型（span 状态码）聚合，取出现次数前 10。
     */
    private List<Map<String, Object>> buildCommonErrors(final List<TraceQueryService.TraceRecord> traces) {
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        for (TraceQueryService.TraceRecord trace : traces) {
            for (TraceQueryService.SpanRecord span : trace.getSpans()) {
                if (!span.isError()) {
                    continue;
                }
                String errorType = span.getStatusCode() != null && !span.getStatusCode().isBlank()
                    ? span.getStatusCode() : "UNKNOWN";
                Map<String, Object> item = byType.computeIfAbsent(errorType, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("errorType", k);
                    m.put("count", 0);
                    m.put("service", trace.getServiceName());
                    return m;
                });
                item.put("count", ((Number) item.get("count")).intValue() + 1);
            }
        }
        return byType.values().stream()
            .sorted((a, b) -> ((Number) b.get("count")).intValue() - ((Number) a.get("count")).intValue())
            .limit(10)
            .collect(Collectors.toList());
    }
}