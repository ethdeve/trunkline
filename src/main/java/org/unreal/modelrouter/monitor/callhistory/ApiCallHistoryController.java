package org.unreal.modelrouter.monitor.callhistory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.auth.security.audit.SecurityAuditService;
import org.unreal.modelrouter.auth.security.model.SecurityAuditEvent;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.monitor.callhistory.config.RecordLevel;
import org.unreal.modelrouter.monitor.callhistory.dto.CallHistoryQueryDTO;
import org.unreal.modelrouter.monitor.callhistory.dto.CallHistoryRecordDTO;
import org.unreal.modelrouter.monitor.callhistory.dto.CallHistoryStatisticsDTO;
import org.unreal.modelrouter.persistence.jpa.entity.ApiCallHistoryEntity;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.unreal.modelrouter.common.exception.ApiException;

/**
 * API 调用历史控制器
 * 提供调用历史的记录、查询、统计和清理 REST API
 *
 * @author JAiRouter Team
 * @since 2.7.8
 * @modified Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/call-history")
@RequiredArgsConstructor
@Tag(name = "API 调用历史", description = "API 调用历史记录、查询和统计接口")
// v2.9.4-fix: RBAC 由 SecurityConfiguration URL 规则保护（/api/call-history/** -> ADMIN）。
// 本 controller 主体返回同步 ResponseEntity，类级 @PreAuthorize 在 @EnableReactiveMethodSecurity 下
// 要求 Publisher 返回类型，真实请求会报 500（Reactor Context 错误）。
// 例外：/mine（员工自助端点）返回 Mono，因此可以安全挂方法级 @PreAuthorize。
public class ApiCallHistoryController {

    private final ApiCallHistoryService callHistoryService;
    private final ApiCallHistoryRecorder callHistoryRecorder;
    private final SecurityAuditService securityAuditService;
    private final CallHistoryExportService callHistoryExportService;

    /**
     * 记录调用历史（手动）
     */
    @PostMapping("/record")
    @Operation(summary = "记录调用历史", description = "手动记录一次 API 调用历史")
    public ResponseEntity<RouterResponse<Void>> record(
            @RequestBody final CallHistoryRecordDTO record) {

        callHistoryService.record(record);
        return ResponseEntity.ok(RouterResponse.success(null, "调用历史记录成功"));
    }

    /**
     * 分页查询调用历史
     */
    @GetMapping
    @Operation(summary = "分页查询调用历史", description = "根据条件分页查询 API 调用历史")
    public ResponseEntity<RouterResponse<Page<ApiCallHistoryEntity>>> query(
            @Parameter(description = "查询参数")
            @ModelAttribute CallHistoryQueryDTO query) {

        if (query == null) {
            query = new CallHistoryQueryDTO();
        }

        Page<ApiCallHistoryEntity> result = callHistoryService.query(query);
        return ResponseEntity.ok(RouterResponse.success(result));
    }

    /**
     * 「我的调用记录」（v3.5 Trunkline 员工侧自助端点）。
     *
     * <p><b>归属强制在服务端</b>：{@code userId} 一律取 {@link Authentication#getName()}，
     * 且本方法<b>只接受 {@code page} / {@code size} 两个分页参数</b>——客户端即使伪造
     * {@code userId} / {@code keyword} 等归属参数也不会被绑定，从根上消除越权查看他人记录的面。
     * 前端原实现是「把 userId 拼到 URL 上由后端过滤」，员工改一下地址栏就能看同事的记录，
     * 那个洞由本端点封死。</p>
     *
     * <p>与全量端点 {@code GET /api/call-history} 严格分开：后者含他人数据，
     * 仍要求 {@code callhistory:view}（普通员工 403 是正确行为）。</p>
     *
     * <p><b>返回 {@link Mono} 而非同步 {@code ResponseEntity}</b>：本项目开启了
     * {@code @EnableReactiveMethodSecurity}，方法级 {@code @PreAuthorize} 仅对返回
     * {@code Publisher} 的方法生效，同步返回会在真实请求时报 500（Reactor Context 错误）。
     * 本 controller 其余端点因返回同步类型而未加方法级注解，改由 URL 权限矩阵约束。</p>
     *
     * @param authentication 当前登录用户（principal.name 为归属）
     * @param page           页码（从 0 开始，缺省 0）
     * @param size           每页大小（缺省 20，上限 100）
     * @return 仅属于当前登录用户的调用记录分页
     */
    @GetMapping("/mine")
    @Operation(summary = "获取我的调用记录",
            description = "仅返回当前登录用户名下的调用记录，归属由服务端从 principal 强制解析")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Mono<ResponseEntity<RouterResponse<Page<ApiCallHistoryEntity>>>> queryMine(
            final Authentication authentication,
            @RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {

        final String owner = authentication != null ? authentication.getName() : null;
        final int effPage = page != null && page >= 0 ? page : 0;
        final int effSize = size != null && size > 0 ? Math.min(size, 100) : 20;

        return Mono.fromCallable(() -> {
                    CallHistoryQueryDTO query = new CallHistoryQueryDTO();
                    // 服务端强制归属：只认 principal，不认任何客户端传参
                    query.setUserId(owner);
                    query.setPage(effPage);
                    query.setSize(effSize);
                    return callHistoryService.query(query);
                })
                // JPA 为阻塞 API，必须离开 Netty EventLoop（与 QuotaMonitoringController 同口径）
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.ok(RouterResponse.success(result)))
                .onErrorResume(e -> {
                    log.error("获取我的调用记录失败", e);
                    return Mono.error(ApiException.of("INTERNAL_ERROR", "获取我的调用记录失败"));
                });
    }

    /**
     * 导出调用记录（任务 58：运维自助导出，CSV / JSON）
     *
     * <p>沿用与 {@link #query} 完全一致的筛选条件，导出内容与页面所见一致（封顶 10000 行）。
     * <b>只导元数据</b>：请求/响应正文（含加密正文）不进导出管道——正文可能携带 Prompt 明文，
     * 且完整内容读取属 FULL_CONTENT_ACCESS 审计语义，导出不应绕过。</p>
     *
     * <p>权限：走 {@code /api/call-history/**} 的 GET 规则（callhistory:view）。</p>
     *
     * @param query  筛选条件（与分页查询相同）
     * @param format 导出格式：csv（缺省）/ json
     */
    @GetMapping("/export")
    @Operation(summary = "导出调用记录",
            description = "按筛选条件导出调用记录元数据（不含请求/响应正文），支持 CSV / JSON，封顶 10000 行")
    public ResponseEntity<byte[]> export(
            @Parameter(description = "查询参数（与分页查询一致）")
            @ModelAttribute CallHistoryQueryDTO query,
            @Parameter(description = "导出格式：csv（缺省）/ json")
            @RequestParam(required = false, defaultValue = "csv") String format) {

        // 格式非法等参数错误抛 ApiException，由 GlobalControllerExceptionHandler 统一返回 RouterResponse
        return fileResponse(callHistoryExportService.exportForAdmin(query, format));
    }

    /**
     * 导出「我的调用记录」（任务 58 员工自助侧，CSV / JSON）
     *
     * <p>与 {@link #queryMine} 同口径：归属由服务端从 principal 强制解析，
     * 客户端只能传时间范围与格式，不能指定 userId / keyword 等归属参数。
     * 返回 Mono 以支持方法级 @PreAuthorize（USER/OPERATOR/ADMIN 皆可导自己的数据）。</p>
     *
     * @param authentication 当前登录用户
     * @param format         导出格式：csv（缺省）/ json
     * @param startTime      开始时间（可选，ISO-8601）
     * @param endTime        结束时间（可选，ISO-8601）
     */
    @GetMapping("/mine/export")
    @Operation(summary = "导出我的调用记录",
            description = "仅导出当前登录用户名下的调用记录元数据（不含正文），支持 CSV / JSON，封顶 10000 行")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Mono<ResponseEntity<byte[]>> exportMine(
            final Authentication authentication,
            @Parameter(description = "导出格式：csv（缺省）/ json")
            @RequestParam(required = false, defaultValue = "csv") String format,
            @Parameter(description = "开始时间 (ISO-8601，可选)")
            @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间 (ISO-8601，可选)")
            @RequestParam(required = false) String endTime) {

        final String owner = authentication != null ? authentication.getName() : null;
        return Mono.fromCallable(() -> callHistoryExportService.exportForMine(owner, startTime, endTime, format))
                // JPA 为阻塞 API，必须离开 Netty EventLoop（与 queryMine 同口径）
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::fileResponse)
                .onErrorResume(e -> {
                    log.error("导出我的调用记录失败", e);
                    return Mono.error(ApiException.of("INTERNAL_ERROR", "导出我的调用记录失败"));
                });
    }

    /** 把导出文件包装成附件下载响应 */
    private ResponseEntity<byte[]> fileResponse(final CallHistoryExportService.ExportFile file) {
        return ResponseEntity.ok()
                .header("Content-Type", file.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"")
                .body(file.content());
    }

    /**
     * 根据 traceId 查询调用链路
     */
    @GetMapping("/trace/{traceId}")
    @Operation(summary = "根据 traceId 查询调用链路", description = "查询同一 traceId 下的所有调用记录")
    public ResponseEntity<RouterResponse<List<ApiCallHistoryEntity>>> findByTraceId(
            @Parameter(description = "追踪 ID")
            @PathVariable String traceId) {

        List<ApiCallHistoryEntity> records = callHistoryService.findByTraceId(traceId);
        return ResponseEntity.ok(RouterResponse.success(records));
    }

    /**
     * 查询单条记录详情（解密后）
     * 若记录级别为 FULL，会触发 FULL_CONTENT_ACCESS 审计事件
     */
    @GetMapping("/{id}/detail")
    @Operation(summary = "查询记录详情", description = "根据 ID 查询单条调用历史记录，自动解密加密内容")
    public ResponseEntity<RouterResponse<ApiCallHistoryEntity>> getDetail(
            @Parameter(description = "记录 ID")
            @PathVariable Long id) {

        return callHistoryService.findById(id)
                .map(entity -> {
                    // 若记录级别为 FULL，在返回前记录审计事件
                    if (RecordLevel.FULL.name().equals(entity.getRecordLevel())) {
                        emitFullContentAccessAudit(entity.getId());
                    }
                    callHistoryService.decryptBodies(entity);
                    return ResponseEntity.ok(RouterResponse.success(entity));
                })
                .orElseGet(() -> ResponseEntity.status(ApiException.resolveStatus("NOT_FOUND")).body(RouterResponse.error("记录不存在: id=" + id)));
    }

    /**
     * 获取最近的调用记录
     */
    @GetMapping("/recent")
    @Operation(summary = "获取最近的调用记录", description = "获取最近发生的 API 调用记录")
    public ResponseEntity<RouterResponse<List<ApiCallHistoryEntity>>> findRecent(
            @Parameter(description = "最大数量")
            @RequestParam(defaultValue = "20") int limit) {

        List<ApiCallHistoryEntity> records = callHistoryService.findRecent(limit);
        return ResponseEntity.ok(RouterResponse.success(records));
    }

    /**
     * 查询错误调用
     */
    @GetMapping("/errors")
    @Operation(summary = "查询错误调用", description = "查询失败的 API 调用记录")
    public ResponseEntity<RouterResponse<List<ApiCallHistoryEntity>>> findErrors(
            @Parameter(description = "开始时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,

            @Parameter(description = "结束时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,

            @Parameter(description = "最大数量")
            @RequestParam(defaultValue = "50") int limit) {

        List<ApiCallHistoryEntity> records = callHistoryService.findErrors(startTime, endTime, limit);
        return ResponseEntity.ok(RouterResponse.success(records));
    }

    /**
     * 查询慢调用
     */
    @GetMapping("/slow")
    @Operation(summary = "查询慢调用", description = "查询响应时间超过阈值的 API 调用记录")
    public ResponseEntity<RouterResponse<List<ApiCallHistoryEntity>>> findSlowCalls(
            @Parameter(description = "慢调用阈值 (毫秒)")
            @RequestParam(required = false) Long threshold,

            @Parameter(description = "开始时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,

            @Parameter(description = "结束时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,

            @Parameter(description = "最大数量")
            @RequestParam(defaultValue = "50") int limit) {

        List<ApiCallHistoryEntity> records = callHistoryService.findSlowCalls(threshold, startTime, endTime, limit);
        return ResponseEntity.ok(RouterResponse.success(records));
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/statistics")
    @Operation(summary = "获取统计信息", description = "获取 API 调用历史的多维度统计信息")
    public ResponseEntity<RouterResponse<CallHistoryStatisticsDTO>> getStatistics(
            @Parameter(description = "开始时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,

            @Parameter(description = "结束时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        CallHistoryStatisticsDTO statistics = callHistoryService.getStatistics(startTime, endTime);
        return ResponseEntity.ok(RouterResponse.success(statistics));
    }

    /**
     * 获取仪表盘数据
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取仪表盘数据", description = "获取调用历史仪表盘所需的核心数据")
    public ResponseEntity<RouterResponse<Map<String, Object>>> getDashboard(
            @Parameter(description = "开始时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,

            @Parameter(description = "结束时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        // 统计信息
        CallHistoryStatisticsDTO statistics = callHistoryService.getStatistics(startTime, endTime);

        // 最近调用
        List<ApiCallHistoryEntity> recentCalls = callHistoryService.findRecent(10);

        // 构建仪表盘数据
        Map<String, Object> dashboard = new HashMap<>();
        dashboard.put("statistics", statistics);
        dashboard.put("recentCalls", recentCalls);
        dashboard.put("recorderStats", Map.of(
                "bufferSize", callHistoryRecorder.getBufferSize(),
                "totalRecords", callHistoryRecorder.getTotalRecords(),
                "droppedRecords", callHistoryRecorder.getDroppedRecords()
        ));

        return ResponseEntity.ok(RouterResponse.success(dashboard));
    }

    /**
     * 清理过期数据
     */
    @DeleteMapping("/cleanup")
    @Operation(summary = "清理过期数据", description = "删除指定时间之前的调用历史记录")
    public ResponseEntity<RouterResponse<Map<String, Object>>> cleanup(
            @Parameter(description = "截止时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cutoffTime) {

        int deletedCount;
        if (cutoffTime != null) {
            deletedCount = callHistoryService.cleanup(cutoffTime);
        } else {
            deletedCount = callHistoryService.cleanupByRetentionDays();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("deletedCount", deletedCount);
        result.put("cutoffTime", cutoffTime);

        return ResponseEntity.ok(RouterResponse.success(result, "成功删除 " + deletedCount + " 条调用历史记录"));
    }

    /**
     * 获取总记录数
     */
    @GetMapping("/count")
    @Operation(summary = "获取总记录数", description = "获取调用历史的总记录数")
    public ResponseEntity<RouterResponse<Map<String, Object>>> count() {
        long count = callHistoryService.countAll();

        Map<String, Object> result = new HashMap<>();
        result.put("count", count);

        return ResponseEntity.ok(RouterResponse.success(result));
    }

    /**
     * 发送 FULL_CONTENT_ACCESS 审计事件
     *
     * @param recordId 记录 ID
     */
    private void emitFullContentAccessAudit(final Long recordId) {
        try {
            SecurityAuditEvent event = SecurityAuditEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("FULL_CONTENT_ACCESS")
                    .resource("call-history")
                    .action("DETAIL")
                    .success(true)
                    .additionalData(Map.of("recordId", recordId))
                    .build();
            securityAuditService.recordEvent(event).subscribe(
                    v -> log.debug("FULL_CONTENT_ACCESS 审计事件已记录: recordId={}", recordId),
                    e -> log.warn("记录 FULL_CONTENT_ACCESS 审计事件失败: {}", e.getMessage())
            );
        } catch (Exception e) {
            log.warn("发送 FULL_CONTENT_ACCESS 审计事件异常: {}", e.getMessage());
        }
    }
}
