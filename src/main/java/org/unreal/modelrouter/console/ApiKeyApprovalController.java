package org.unreal.modelrouter.console;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API Key 申请-审批流（补齐 MyKeysView 里"提交申请仅复制文本"的占位）。
 *
 * <p>本控制器只管理<b>申请单</b>状态机：{@code PENDING → APPROVED / REJECTED}。
 * 虚拟密钥（keyMasked / keyEncrypted / reveal）本身 OSS 早已落地，审批通过后
 * 由管理员走既有的 {@code POST /api/auth/api-keys} 颁发真实密钥，审批单记录
 * decision 与审批人，不重复实现密钥生成逻辑。</p>
 *
 * <p>数据存放于 {@code config_data} 的 {@code console.api-key-requests}，零新表、
 * 零 V1 变更。申请人由服务端从 principal 解析，不信任前端传参（防越权）。</p>
 */
@RestController
@RequestMapping("/api/auth/api-key-requests")
@Tag(name = "API Key 审批", description = "密钥申请单的提交、审批与状态跟踪")
public class ApiKeyApprovalController {

    static final String KEY = "console.api-key-requests";

    private final ConsoleExtensionStore store;

    public ApiKeyApprovalController(final ConsoleExtensionStore store) {
        this.store = store;
    }

    /** 员工提交申请：归属人从 principal 强制解析。 */
    @PostMapping
    @Operation(summary = "提交密钥申请单")
    public ResponseEntity<RouterResponse<Map<String, Object>>> submit(
            @RequestBody final Map<String, Object> body, final Authentication authentication) {
        final String purpose = body.get("purpose") == null ? "" : String.valueOf(body.get("purpose")).trim();
        if (purpose.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "purpose is required");
        }
        // #9 数据分级出境：申请单带期望密级，默认 PUBLIC（与运行时兜底一致）；
        // 只接受 PUBLIC / INTERNAL / CONFIDENTIAL 三档，非法值回落 PUBLIC。
        final String desiredClassification = normalizeClassification(body.get("desiredClassification"));
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("requestId", UUID.randomUUID().toString());
        record.put("applicant", authentication == null ? "anonymous" : authentication.getName());
        record.put("purpose", purpose);
        record.put("desiredPermissions", body.getOrDefault("desiredPermissions", ""));
        record.put("desiredClassification", desiredClassification);
        record.put("status", "PENDING");
        record.put("createdAt", java.time.Instant.now().toString());
        record.put("decidedBy", "");
        record.put("decisionNote", "");
        store.upsert(KEY, "requestId", record);
        return ResponseEntity.ok(RouterResponse.success(store.plain(record)));
    }

    /** 我的申请单。 */
    @GetMapping("/mine")
    @Operation(summary = "列出我的申请单")
    public ResponseEntity<RouterResponse<List<Map<String, Object>>>> mine(final Authentication authentication) {
        final String user = authentication == null ? "anonymous" : authentication.getName();
        final List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (final Map<String, Object> r : store.list(KEY)) {
            if (user.equals(r.get("applicant"))) result.add(store.plain(r));
        }
        return ResponseEntity.ok(RouterResponse.success(result));
    }

    /** 管理端：全部申请单。 */
    @GetMapping
    @Operation(summary = "列出全部申请单")
    public ResponseEntity<RouterResponse<List<Map<String, Object>>>> all() {
        final List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (final Map<String, Object> r : store.list(KEY)) result.add(store.plain(r));
        return ResponseEntity.ok(RouterResponse.success(result));
    }

    /** 审批：APPROVED / REJECTED。 */
    @PostMapping("/{requestId}/decide")
    @Operation(summary = "审批申请单")
    public ResponseEntity<RouterResponse<Map<String, Object>>> decide(
            @PathVariable final String requestId,
            @RequestBody final Map<String, Object> body,
            final Authentication authentication) {
        final String decision = body.get("decision") == null ? "" : String.valueOf(body.get("decision")).toUpperCase();
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be APPROVED or REJECTED");
        }
        Map<String, Object> target = null;
        for (final Map<String, Object> r : store.list(KEY)) {
            if (requestId.equals(r.get("requestId"))) { target = new LinkedHashMap<>(r); break; }
        }
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "request not found");
        if (!"PENDING".equals(target.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request already decided");
        }
        target.put("status", decision);
        target.put("decidedBy", authentication == null ? "anonymous" : authentication.getName());
        target.put("decisionNote", body.get("decisionNote") == null ? "" : String.valueOf(body.get("decisionNote")));
        target.put("decidedAt", java.time.Instant.now().toString());
        // #9 数据分级出境：审批定最终密级，作为颁发 Key 时预填的信任锚。
        // 未显式指定时沿用申请人的期望密级（默认 PUBLIC）；三档校验，非法值回落 PUBLIC。
        if ("APPROVED".equals(decision)) {
            final String requested = body.get("finalClassification") == null
                    ? String.valueOf(target.getOrDefault("desiredClassification", "PUBLIC"))
                    : String.valueOf(body.get("finalClassification"));
            target.put("finalClassification", normalizeClassification(requested));
        }
        store.upsert(KEY, "requestId", target);
        return ResponseEntity.ok(RouterResponse.success(store.plain(target)));
    }

    /** 密级归一化：大小写不敏感，三档之外（含 null / 空）一律回落 PUBLIC。 */
    private static String normalizeClassification(final Object raw) {
        if (raw == null) {
            return "PUBLIC";
        }
        final String value = String.valueOf(raw).trim();
        for (final String code : new String[]{"PUBLIC", "INTERNAL", "CONFIDENTIAL"}) {
            if (code.equalsIgnoreCase(value)) {
                return code;
            }
        }
        return "PUBLIC";
    }
}
