/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.privacy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtPrincipal;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.enterprise.noop.SingleTenantContext;
import org.unreal.modelrouter.enterprise.spi.DataDomain;
import org.unreal.modelrouter.enterprise.spi.DataEraser;
import org.unreal.modelrouter.enterprise.spi.ErasureAuditRecorder;
import org.unreal.modelrouter.enterprise.spi.ErasureReport;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 数据主体权利端点（#10 被遗忘权精确擦除）。
 *
 * <p><b>归属铁律</b>：{@code /mine} 的 userId/tenantId 一律从服务端认证上下文解析，
 * <b>绝不信任请求体里的任何身份字段</b>（防水平越权擦他人数据）。{@code /tenant} 为高危
 * 操作，仅 ADMIN 可达，tenantId 同样取自服务端上下文，请求体只能指定擦除域范围。</p>
 *
 * <p><b>能力门控</b>：擦除执行是企业能力，通过 {@link DataEraser} SPI 注入；社区版无 Bean
 * 时返回"不可用"报告（success=false），不会擦任何数据。留痕经 {@link ErasureAuditRecorder}
 * SPI（社区版 no-op）。JPA 为阻塞 API，统一切 boundedElastic 执行。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/privacy/erasure")
@RequiredArgsConstructor
@Tag(name = "数据主体权利", description = "被遗忘权精确擦除（企业版能力）")
public class ErasureController {

    private final ObjectProvider<DataEraser> eraserProvider;
    private final ObjectProvider<ErasureAuditRecorder> recorderProvider;

    /** 请求体仅可指定擦除域范围，身份字段一律不读（由服务端强制解析）。 */
    public record EraseRequest(Set<DataDomain> domains) {
    }

    /**
     * 数据主体擦除自己的全部业务数据。归属由 principal 强制解析。
     */
    @PostMapping("/mine")
    @Operation(summary = "擦除我的数据", description = "userId/tenantId 由服务端从登录态强制解析，忽略请求体身份字段")
    public Mono<ResponseEntity<RouterResponse<ErasureReport>>> eraseMine(
            final Authentication authentication,
            @RequestBody(required = false) final EraseRequest request) {
        final String userId = authentication != null ? authentication.getName() : null;
        final String tenantId = resolveTenant(authentication);
        final Set<DataDomain> domains = request == null ? null : request.domains();
        log.info("Erasure /mine requested: user={} tenant={}", userId, tenantId);
        return doErase(ErasureReport.SubjectType.USER, userId, tenantId, domains, authentication);
    }

    /**
     * 管理员擦除整个租户的业务数据（高危）。tenantId 由服务端上下文解析，不接受请求体传参。
     */
    @PostMapping("/tenant")
    @Operation(summary = "擦除租户数据（管理员）", description = "tenantId 由服务端从登录态强制解析")
    public Mono<ResponseEntity<RouterResponse<ErasureReport>>> eraseTenant(
            final Authentication authentication,
            @RequestBody(required = false) final EraseRequest request) {
        final String tenantId = resolveTenant(authentication);
        final String operator = authentication != null ? authentication.getName() : null;
        final Set<DataDomain> domains = request == null ? null : request.domains();
        log.warn("Erasure /tenant requested: tenant={} by={}", tenantId, operator);
        return doErase(ErasureReport.SubjectType.TENANT, tenantId, tenantId, domains, authentication);
    }

    private Mono<ResponseEntity<RouterResponse<ErasureReport>>> doErase(
            final ErasureReport.SubjectType subjectType, final String subject, final String tenantId,
            final Set<DataDomain> domains, final Authentication authentication) {
        return Mono.fromCallable(() -> {
            final DataEraser eraser = eraserProvider.getIfAvailable();
            final String operator = authentication != null ? authentication.getName() : null;
            if (eraser == null || !eraser.enabled()) {
                return ErasureReport.builder(subjectType, subject)
                        .tenantId(tenantId)
                        .requestedBy(operator)
                        .failure("erasure capability is not available (enterprise feature required)")
                        .build();
            }
            final ErasureReport report = eraser.erase(subjectType, subject, domains);
            final ErasureAuditRecorder recorder = recorderProvider.getIfAvailable();
            if (recorder != null && report.success()) {
                try {
                    recorder.record(report);
                } catch (RuntimeException e) {
                    // 留痕失败不回滚已执行的擦除（被遗忘权优先），记录到日志
                    log.error("Erasure audit record failed (erasure already applied): {}", report.summary(), e);
                }
            }
            return report;
        })
                .subscribeOn(Schedulers.boundedElastic())
                .map(RouterResponse::success)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Erasure failed: subjectType={} subject={}", subjectType, subject, e);
                    final ErasureReport failed = ErasureReport.builder(subjectType, subject)
                            .tenantId(tenantId)
                            .requestedBy(authentication != null ? authentication.getName() : null)
                            .failure("erasure failed: " + e.getMessage())
                            .build();
                    return Mono.just(ResponseEntity.ok(RouterResponse.success(failed)));
                });
    }

    /** 从认证上下文解析租户，绝不读请求参数。 */
    private static String resolveTenant(final Authentication authentication) {
        if (authentication == null) {
            return SingleTenantContext.DEFAULT_TENANT_ID;
        }
        if (authentication instanceof ApiKeyAuthentication apiKeyAuth) {
            final String t = apiKeyAuth.getTenantId();
            return t != null && !t.isBlank() ? t : SingleTenantContext.DEFAULT_TENANT_ID;
        }
        if (authentication.getDetails() instanceof JwtPrincipal principal) {
            return principal.getTenantId();
        }
        return SingleTenantContext.DEFAULT_TENANT_ID;
    }
}
