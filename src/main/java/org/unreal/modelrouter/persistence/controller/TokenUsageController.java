package org.unreal.modelrouter.persistence.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecentVO;
import org.unreal.modelrouter.monitor.dto.TokenUsageStatisticsDTO;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;
import org.unreal.modelrouter.monitor.service.TokenUsageService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 使用量统计控制器
 * 提供 Token 使用量的记录、查询和统计 REST API
 *
 * @author JAiRouter Team
 * @since 1.9.5
 * @modified Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/token-usage")
@RequiredArgsConstructor
@Tag(name = "Token 使用量统计", description = "Token 使用量记录、查询和统计接口")
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    /**
     * 记录 Token 使用量
     */
    @PostMapping("/record")
    @Operation(summary = "记录 Token 使用量", description = "记录单次 AI 模型调用的 Token 使用量")
    public ResponseEntity<RouterResponse<Void>> recordTokenUsage(
            @RequestBody final TokenUsageRecordDTO record,
            final Authentication authentication) {

        final String rejection = crossPrincipalWriteError(authentication, record.getApiKeyId());
        if (rejection != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(RouterResponse.error(rejection, "TOKEN_USAGE_FORBIDDEN"));
        }

        tokenUsageService.recordTokenUsage(record);
        return ResponseEntity.ok(RouterResponse.success(null, "Token 使用量记录成功"));
    }

    /**
     * 批量记录 Token 使用量
     */
    @PostMapping("/record/batch")
    @Operation(summary = "批量记录 Token 使用量", description = "批量记录 AI 模型调用的 Token 使用量")
    public ResponseEntity<RouterResponse<Void>> recordTokenUsageBatch(
            @RequestBody final List<TokenUsageRecordDTO> records,
            final Authentication authentication) {

        if (records != null) {
            for (final TokenUsageRecordDTO record : records) {
                final String rejection = crossPrincipalWriteError(authentication, record.getApiKeyId());
                if (rejection != null) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(RouterResponse.error(rejection, "TOKEN_USAGE_FORBIDDEN"));
                }
            }
        }

        tokenUsageService.recordTokenUsageBatch(records);
        return ResponseEntity.ok(RouterResponse.success(null, "批量记录成功"));
    }

    /**
     * 写入前的属主校验（issue #254）.
     *
     * <p>本端点在权限豁免清单上（仅要求已认证，见 {@code AccessExemptEndpoints}），而请求体里的
     * {@code apiKeyId} / {@code userId} 等属主字段完全由调用方控制（{@link TokenUsageRecordDTO}），
     * 服务层原样落库。若不校验，任何持有效 API Key 的主体都能把用量记到<b>他人</b>的 key 上，
     * 污染统计与计费数据。配额强制不受影响 —— {@code auth.security.quota} 不读该表。</p>
     *
     * <p><b>收敛范围刻意最小</b>：仅当调用主体本身就是 API Key 时要求「只能上报自己」
     * （{@link ApiKeyAuthentication#getName()} 返回的就是 keyId）；JWT / 管理面主体的行为
     * 保持不变 —— 代报属尚未定义的调用形态，需产品结论后再收紧。主体为 {@code null} 时放行：
     * 该端点仍要求认证，未认证请求在安全链上已被拒绝，此处不会成为绕过点。</p>
     *
     * @param authentication  当前认证主体，可为 {@code null}
     * @param claimedApiKeyId 请求体声明的 apiKeyId，可为 {@code null}
     * @return 拒绝原因；{@code null} 表示放行
     */
    private String crossPrincipalWriteError(final Authentication authentication, final String claimedApiKeyId) {
        if (!(authentication instanceof ApiKeyAuthentication)) {
            return null;
        }
        final String ownKeyId = authentication.getName();
        if (ownKeyId == null || claimedApiKeyId == null || ownKeyId.equals(claimedApiKeyId)) {
            return null;
        }
        return "API Key 主体只能上报自身的 Token 用量（声明他人 apiKeyId 已拒绝）";
    }

    /**
     * 获取 Token 使用量统计信息
     */
    @GetMapping("/statistics")
    @Operation(summary = "获取 Token 使用量统计信息", description = "获取指定时间范围内的 Token 使用量统计信息，包括按模型、服务类型、周、月等维度的统计")
    public ResponseEntity<RouterResponse<TokenUsageStatisticsDTO>> getTokenUsageStatistics(
            @Parameter(description = "开始时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,

            @Parameter(description = "结束时间 (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime) {

        TokenUsageStatisticsDTO statistics = tokenUsageService.getTokenUsageStatistics(startTime, endTime);
        return ResponseEntity.ok(RouterResponse.success(statistics));
    }

    /**
     * 获取最近的使用记录
     *
     * <p><b>P0-L 越权泄漏收敛</b>：此前本端点直接返回 {@link TokenUsageEntity} 全字段，
     * 其中 {@code clientIp} / {@code instanceUrl} / {@code errorMessage} / {@code metadata}
     * 属于内部排障与安全审计信息，随列表整包外泄会让任一登录用户看到全公司调用明细
     * （含每个人的来源 IP 与上游报错原文）。现改为返回白名单投影
     * {@link TokenUsageRecentVO}，上述字段一律不出现在响应里。</p>
     *
     * <p><b>为什么不在此处加方法级 {@code @PreAuthorize}</b>：本项目启用了
     * {@code @EnableReactiveMethodSecurity}，而本控制器返回的是<b>同步</b>
     * {@code ResponseEntity}，方法级注解在该组合下会导致请求 500
     * （参见 {@code ApiCallHistoryController} 的同款注释）。访问控制统一由
     * 固定角色矩阵（{@code FixedRoleAccessPolicy} → OPS 级）
     * 承担；该 URL 不在员工角色范围内（固定矩阵 USER 仅自助端点），
     * 员工无法再访问本端点，运维 / 管理员不受影响。</p>
     */
    @GetMapping("/recent")
    @Operation(summary = "获取最近的使用记录", description = "获取最近发生的 Token 使用记录列表（已裁剪内部字段）")
    public ResponseEntity<RouterResponse<List<TokenUsageRecentVO>>> getRecentUsage(
            @Parameter(description = "最大数量")
            @RequestParam(defaultValue = "20")
            int limit) {

        final List<TokenUsageEntity> records = tokenUsageService.getRecentUsage(limit);
        final List<TokenUsageRecentVO> items = records == null
                ? List.of()
                : records.stream()
                        .map(TokenUsageRecentVO::fromEntity)
                        .filter(java.util.Objects::nonNull)
                        .toList();
        return ResponseEntity.ok(RouterResponse.success(items));
    }

}
