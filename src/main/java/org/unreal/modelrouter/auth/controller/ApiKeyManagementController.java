package org.unreal.modelrouter.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchExportVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchImportRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchImportResult;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreationVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreateRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyListVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyUpdateRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyWithModelScopeVO;
import org.unreal.modelrouter.auth.security.service.ApiKeyNotFoundException;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.auth.security.service.ApiKeyQuotaService;
import org.unreal.modelrouter.auth.security.service.ApiKeyWhitelistMismatchException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.common.exception.ApiExceptions;

/**
 * API 密钥管理控制器
 * 提供 API 密钥的增删改查等管理功能的 REST API
 *
 * 安全改进：
 * 1. 使用强类型 DTO/VO，不使用 Map
 * 2. keyValue 使用 SHA-256 哈希存储
 * 3. 仅在创建时返回原始 keyValue
 * 4. 支持 IP 白名单和每日请求限制
 * 5. 支持密钥轮换机制和创建者信息记录
 *
 * <p><b>授权分层（2026-11-09 一致性修正）</b>：URL 层由
 * {@code FixedRoleAccessPolicy} 把 {@code /api/auth/api-keys/**} 定为 OPS
 * （「全部密钥」菜单对运维可见）。此前本类所有全局端点的方法级注解仍是
 * {@code hasRole('ADMIN')}，导致 OPERATOR 在 URL 层放行后又被方法层拦下 403，
 * 菜单承诺与真实权限不一致。现统一为 {@code hasAnyRole('OPERATOR','ADMIN')}，
 * 与 URL 矩阵、前端菜单、权限码 {@code security:apikeys:manage} → ops 对齐。</p>
 *
 * <p>自助端点 {@code /mine}、{@code /mine/{keyId}/reveal} 仍为 SELF
 * （三角色皆可用），归属由服务端从 principal 强制解析，且必须登记在 OPS 通配规则之前。</p>
 */
@Slf4j
@RestController
@RequestMapping("api/auth/api-keys")
@RequiredArgsConstructor
@Tag(name = "API Key Management", description = "API密钥管理接口")
public class ApiKeyManagementController {

    private final ApiKeyService apiKeyService;
    private final ApiKeyQuotaService apiKeyQuotaService;

    /**
     * 获取所有 API 密钥列表
     * 返回不包含 keyValue 的安全副本
     */
    @GetMapping
    @Operation(summary = "获取所有API密钥", description = "获取系统中所有API密钥的信息（不包含实际密钥值）")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyListVO>> getAllApiKeys() {
        return apiKeyService.getAllApiKeysVO()
                .map(list -> RouterResponse.success(list, "获取API密钥列表成功"))
                .onErrorResume(e -> {
                    log.error("获取API密钥列表失败", e);
                    return Mono.error(ApiException.of("INTERNAL_ERROR", "获取API密钥列表失败"));
                });
    }

    /**
     * 「我的密钥」列表（v3.5 Trunkline UI 改造，规格 §4.2）。
     *
     * <p>仅返回当前登录用户名下（{@code createdBy == principal.name}）的密钥，
     * 归属由服务端从 {@link Authentication#getName()} 解析，不信任前端传参，避免越权。</p>
     *
     * <p>可见性：普通用户与运维（{@code hasAnyRole('USER','OPERATOR','ADMIN')}）——
     * 「我的密钥」对 user/ops 可见（用户决策 #3）。</p>
     *
     * <p><b>P0-L 越权泄漏收敛</b>：响应额外携带<b>分组扩展信息</b>
     * （{@link ApiKeyWithModelScopeVO#getModelScope()}）—— 分组名 / 友好名 / 服务类型 /
     * 加权均价，且<b>只含该用户自己白名单内</b>的分组。这样前端不再需要调用
     * {@code GET /api/config/pools/list}（全公司资源池，含成员 instanceId / weight），
     * 即可渲染「可用虚拟模型」与金额估算 —— 收紧 {@code config:pools:read} 后功能保持完整。</p>
     */
    @GetMapping("/mine")
    @Operation(summary = "获取我的API密钥", description = "获取当前登录用户名下的所有API密钥（不包含实际密钥值），并附带可见分组的展示信息")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyWithModelScopeVO>> getMyApiKeys(final Authentication authentication) {
        final String owner = authentication != null ? authentication.getName() : null;
        return apiKeyService.getMineApiKeysWithModelScope(owner)
                .map(list -> RouterResponse.success(list, "获取我的API密钥成功"))
                .onErrorResume(e -> {
                    log.error("获取我的API密钥列表失败", e);
                    return Mono.error(ApiException.of("INTERNAL_ERROR", "获取我的API密钥列表失败"));
                });
    }

    /**
     * 员工取回自己的密钥明文（「我的密钥」页复制按钮的数据源，2026-11 用户决策）。
     *
     * <p>用 POST 而非 GET：密钥明文是一次性敏感数据，避免进入浏览器历史 / 代理日志 /
     * CDN 缓存。归属校验在服务层（{@code holder/createdBy} 必须等于当前登录名），
     * 存量未加密密钥返回明确错误引导轮换。</p>
     */
    @PostMapping("/mine/{keyId}/reveal")
    @Operation(summary = "取回我的API密钥明文", description = "解密并返回当前登录用户名下指定密钥的明文（仅限本人），用于「我的密钥」页复制功能")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Mono<RouterResponse<Map<String, String>>> revealMyApiKeyValue(
            final Authentication authentication,
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId) {
        final String owner = authentication != null ? authentication.getName() : null;
        return Mono.fromCallable(() -> apiKeyService.revealMineApiKeyValue(owner, keyId))
                .map(kv -> RouterResponse.success(Map.of("keyValue", kv), "取回密钥明文成功"))
                .onErrorResume(e -> {
                    log.warn("取回密钥明文失败: keyId={}, owner={}, reason={}", keyId, owner, e.getMessage());
                    return Mono.error(ApiException.of("REVEAL_FAILED", e.getMessage()));
                });
    }

    /**
     * 根据ID获取 API 密钥详情
     * 返回不包含 keyValue 的安全副本
     */
    @GetMapping("/{keyId}")
    @Operation(summary = "获取指定API密钥信息", description = "根据API密钥ID获取详细信息（不包含实际的密钥值）")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyVO>> getApiKeyById(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId) {
        return apiKeyService.getApiKeyByIdVO(keyId)
                .map(vo -> RouterResponse.success(vo, "获取API密钥信息成功"))
                .onErrorResume(e -> {
                    log.error("获取API密钥信息失败: {}", keyId, e);
                    return Mono.error(ApiException.of("NOT_FOUND", "API密钥不存在"));
                });
    }

    /**
     * 创建新的 API 密钥
     * 仅在创建时返回原始 keyValue，请前端弹窗提示用户保存
     */
    @PostMapping
    @Operation(summary = "创建新的API密钥",
               description = "创建一个新的API密钥，返回的密钥值仅此一次显示，请妥善保存")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyCreationVO>> createApiKey(
            @Parameter(description = "API密钥创建请求")
            @Valid @RequestBody final ApiKeyCreateRequest request,
            final Authentication authentication) {
        final String by = authentication != null ? authentication.getName() : "system";
        return apiKeyService.createApiKey(request, by, null)
                .map(vo -> RouterResponse.success(vo,
                        "创建API密钥成功，请妥善保存密钥值，此密钥值仅此一次显示"))
                .onErrorResume(e -> {
                    log.error("创建API密钥失败", e);
                    return Mono.error(ApiExceptions.wrap(e, "创建API密钥失败"));
                });
    }

    /**
     * 更新 API 密钥信息
     * 注意：keyValue 不可更新
     *
     * <p>2026-10-24：此前此处的 {@code onErrorResume} 把<b>所有</b>异常都压成
     * NOT_FOUND(404)，导致白名单与权限分类不一致的校验失败也返回 404，
     * 前端拿不到真实原因。</p>
     *
     * <p><b>为什么用单个 {@code onErrorResume} 而不是链式多个：</b>
     * Reactor 的 {@code onErrorResume} 是「下游 catch-all 兜底」结构——
     * 上游算子用 {@code Mono.error(newEx)} 换出的新异常信号<b>会继续流向下游算子</b>。
     * 链式写法 {@code onErrorResume(A).onErrorResume(B).onErrorResume(catchAll)}
     * 里，catchAll 会把 A/B 已经转换好的语义异常再改写一遍，
     * 于是 {@code INVALID_REQUEST} 又被压回 {@code NOT_FOUND}。
     * 因此这里只保留一个算子，在其内部按类型显式分派。</p>
     */
    @PutMapping("/{keyId}")
    @Operation(summary = "更新API密钥",
               description = "更新指定API密钥的信息（不包含密钥值，密钥值不可更新）")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyVO>> updateApiKey(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId,
            @Parameter(description = "API密钥更新请求")
            @Valid @RequestBody final ApiKeyUpdateRequest request) {
        return apiKeyService.updateApiKey(keyId, request)
                .map(vo -> RouterResponse.success(vo, "更新API密钥成功"))
                .onErrorResume(e -> {
                    if (e instanceof ApiException) {
                        return Mono.error(e);
                    }
                    if (e instanceof ApiKeyNotFoundException) {
                        // 资源不存在 → 404
                        return Mono.error(ApiException.of("NOT_FOUND", e.getMessage()));
                    }
                    if (e instanceof ApiKeyWhitelistMismatchException) {
                        // 白名单与权限的模型分类不一致 → 客户端输入违规 → 400
                        return Mono.error(ApiException.of("INVALID_REQUEST", e.getMessage()));
                    }
                    if (e instanceof IllegalArgumentException) {
                        // 其余参数问题（含配额阈值越界）→ 400
                        return Mono.error(ApiException.of("INVALID_REQUEST", e.getMessage()));
                    }
                    log.error("更新API密钥失败: {}", keyId, e);
                    return Mono.error(ApiException.of("NOT_FOUND", "API密钥不存在"));
                });
    }

    /**
     * 删除 API 密钥
     */
    @DeleteMapping("/{keyId}")
    @Operation(summary = "删除API密钥", description = "删除指定的API密钥")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<Void>> deleteApiKey(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId) {
        return apiKeyService.deleteApiKey(keyId)
                .then(Mono.just(RouterResponse.<Void>success(null, "删除API密钥成功")))
                .onErrorResume(e -> {
                    log.error("删除API密钥失败: {}", keyId, e);
                    // 保留业务异常自身的错误码与状态码（如 409 依赖冲突），不要一律吞成 404
                    if (e instanceof ApiException apiException) {
                        return Mono.error(apiException);
                    }
                    return Mono.error(ApiException.of("NOT_FOUND", "API密钥不存在"));
                });
    }

    /**
     * 禁用 API 密钥
     */
    @PatchMapping("/{keyId}/disable")
    @Operation(summary = "禁用API密钥", description = "禁用指定的API密钥")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyVO>> disableApiKey(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId) {
        return apiKeyService.disableApiKey(keyId)
                .map(vo -> RouterResponse.success(vo, "禁用API密钥成功"))
                .onErrorResume(e -> {
                    log.error("禁用API密钥失败: {}", keyId, e);
                    return Mono.error(ApiException.of("NOT_FOUND", "API密钥不存在"));
                });
    }

    /**
     * 启用 API 密钥
     */
    @PatchMapping("/{keyId}/enable")
    @Operation(summary = "启用API密钥", description = "启用指定的API密钥")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyVO>> enableApiKey(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId) {
        return apiKeyService.enableApiKey(keyId)
                .map(vo -> RouterResponse.success(vo, "启用API密钥成功"))
                .onErrorResume(e -> {
                    log.error("启用API密钥失败: {}", keyId, e);
                    return Mono.error(ApiException.of("NOT_FOUND", "API密钥不存在"));
                });
    }

    /**
     * 重置 API 密钥（生成新的 keyValue）
     * 旧的密钥值将失效，新的密钥值仅显示一次
     */
    @PostMapping("/{keyId}/reset")
    @Operation(summary = "重置API密钥",
               description = "重置API密钥值，旧的密钥值将失效，新的密钥值仅此一次显示")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyCreationVO>> resetApiKey(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId,
            final Authentication authentication) {
        final String by = authentication != null ? authentication.getName() : "system";
        // 先删除旧的密钥，然后创建新的
        return apiKeyService.getApiKeyByIdVO(keyId)
                .flatMap(oldKey -> {
                    // 创建新的密钥请求，保留原有属性
                    // 注意：allowedGroups / holder 必须一并带过去，否则重置会静默清空白名单与持有人
                    ApiKeyCreateRequest newRequest = ApiKeyCreateRequest.builder()
                            .keyId(keyId)  // 保持相同的 keyId
                            .description(oldKey.getDescription())
                            .holder(oldKey.getHolder())
                            .permissions(oldKey.getPermissions())
                            .allowedGroups(oldKey.getAllowedGroups())
                            .allowedIpAddresses(oldKey.getAllowedIpAddresses())
                            .enabled(oldKey.isEnabled())
                            .expiresAt(oldKey.getExpiresAt())
                            .rotationPeriodDays(oldKey.getRotationPeriodDays())
                            .dailyRequestLimit(oldKey.getDailyRequestLimit())
                            .dailyTokenLimit(oldKey.getDailyTokenLimit())
                            .hourlyRequestLimit(oldKey.getHourlyRequestLimit())
                            .hourlyTokenLimit(oldKey.getHourlyTokenLimit())
                            .monthlyRequestLimit(oldKey.getMonthlyRequestLimit())
                            .monthlyTokenLimit(oldKey.getMonthlyTokenLimit())
                            .rateLimitPerMinute(oldKey.getRateLimitPerMinute())
                            .quotaAlertThreshold(oldKey.getQuotaAlertThreshold())
                            .build();

                    // 先删除旧密钥
                    return apiKeyService.deleteApiKey(keyId)
                            .then(apiKeyService.createApiKey(newRequest, by, null));
                })
                .map(vo -> RouterResponse.success(vo,
                        "重置API密钥成功，持有人可在「我的密钥」中查看并复制新的密钥值"))
                .onErrorResume(e -> {
                    log.error("重置API密钥失败: {}", keyId, e);
                    return Mono.error(ApiExceptions.wrap(e, "重置API密钥失败"));
                });
    }

    /**
     * 强制轮换 API 密钥
     * 生成新的 keyValue 并更新 lastRotatedAt 时间戳
     * 旧的密钥值将失效，新的密钥值仅显示一次
     * 与 reset 不同，rotate 保留原有 keyId 和所有属性，仅更新 keyValue 和 lastRotatedAt
     */
    @PostMapping("/{keyId}/rotate")
    @Operation(summary = "强制轮换API密钥",
               description = "强制轮换API密钥值，旧的密钥值将失效，新的密钥值仅此一次显示")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyCreationVO>> forceRotateApiKey(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId,
            final Authentication authentication) {
        // 运维/管理员都可轮换，审计里的操作人必须取真实登录主体，不能硬编码 "admin"
        final String by = authentication != null ? authentication.getName() : "system";
        return apiKeyService.forceRotateKey(keyId, by)
                .map(vo -> RouterResponse.success(vo,
                        "密钥轮换成功，持有人可在「我的密钥」中查看并复制新的密钥值"))
                .onErrorResume(e -> {
                    log.error("强制轮换API密钥失败: {}", keyId, e);
                    return Mono.error(ApiExceptions.wrap(e, "密钥轮换失败"));
                });
    }

    /**
     * 批量导出 API 密钥配置
     * 导出的数据不包含 keyValue 和 keyHash，仅包含可恢复的配置信息
     * 可用于备份或迁移到其他系统
     */
    @GetMapping("/export")
    @Operation(summary = "批量导出API密钥",
               description = "导出所有API密钥的配置信息（不包含密钥值），可用于备份或迁移")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyBatchExportVO>> exportApiKeys() {
        return apiKeyService.exportApiKeys()
                .map(vo -> RouterResponse.success(vo, "导出API密钥配置成功"))
                .onErrorResume(e -> {
                    log.error("导出API密钥失败", e);
                    return Mono.error(ApiExceptions.wrap(e, "导出API密钥失败"));
                });
    }

    /**
     * 批量导入 API 密钥
     * 导入时会为每个密钥生成新的 keyValue
     * MERGE 模式：保留现有密钥，仅添加新密钥
     * REPLACE 模式：删除所有现有密钥，导入新密钥
     */
    @PostMapping("/import")
    @Operation(summary = "批量导入API密钥",
               description = "批量导入API密钥配置，导入时会为每个密钥生成新的密钥值")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyBatchImportResult>> importApiKeys(
            @Parameter(description = "API密钥批量导入请求")
            @Valid @RequestBody final ApiKeyBatchImportRequest request,
            final Authentication authentication) {
        // 同上：审计操作人取真实登录主体
        final String by = authentication != null ? authentication.getName() : "system";
        return apiKeyService.importApiKeys(request, by, null)
                .map(result -> {
                    String message = String.format("批量导入完成：成功 %d，失败 %d",
                            result.getSuccessCount(), result.getFailureCount());
                    return RouterResponse.success(result, message);
                })
                .onErrorResume(e -> {
                    log.error("批量导入API密钥失败", e);
                    return Mono.error(ApiExceptions.wrap(e, "批量导入API密钥失败"));
                });
    }

    // ===== 配额管理端点 =====

    /**
     * 获取指定 API Key 的配额使用详情
     */
    @GetMapping("/{keyId}/quota")
    @Operation(summary = "获取API密钥配额详情",
               description = "获取指定API密钥的配额使用情况，包括请求/Token使用量和速率")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyQuotaService.QuotaUsageDetail>> getApiKeyQuota(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId) {
        return Mono.fromCallable(() -> apiKeyQuotaService.getQuotaUsage(keyId))
                .flatMap(opt -> opt.map(detail -> Mono.just(RouterResponse.success(detail, "获取配额详情成功")))
                        .orElseGet(() -> Mono.error(ApiException.of("NOT_FOUND", "API密钥不存在"))))
                .onErrorResume(e -> {
                    log.error("获取API密钥配额详情失败: {}", keyId, e);
                    return Mono.error(ApiExceptions.wrap(e, "获取配额详情失败"));
                });
    }

    /**
     * 重置指定 API Key 的每日配额计数器
     */
    @PostMapping("/{keyId}/quota/reset")
    @Operation(summary = "重置API密钥每日配额",
               description = "重置指定API密钥的每日请求和Token使用计数器")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<Void>> resetApiKeyQuota(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId) {
        // 配额服务内部会 block()（管理面同步语义），必须把执行切到 boundedElastic：
        // Mono.fromRunnable 默认在订阅线程上执行，而订阅线程是 Reactor 非阻塞线程
        // （实测为 parallel-*），在那里 block 会抛 IllegalStateException。
        return Mono.fromRunnable(() -> apiKeyQuotaService.resetDailyQuota(keyId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(RouterResponse.<Void>success(null, "重置每日配额成功")))
                .onErrorResume(e -> {
                    log.error("重置API密钥每日配额失败: {}", keyId, e);
                    return Mono.error(ApiExceptions.wrap(e, "重置配额失败"));
                });
    }

    /**
     * 仅更新 API Key 配额字段（v3.2.1）
     */
    @PutMapping("/{keyId}/quota")
    @Operation(summary = "更新API密钥配额",
               description = "仅更新每日请求/Token上限、每分钟速率与告警阈值；0 表示不限制")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<ApiKeyQuotaService.QuotaUsageDetail>> updateApiKeyQuota(
            @Parameter(description = "API密钥ID") @PathVariable("keyId") final String keyId,
            @RequestBody final org.unreal.modelrouter.auth.security.dto.ApiKeyUpdateRequest request) {
        // 同上：updateQuotaLimits 内部会 block()，必须离开订阅线程（非阻塞线程无法 block）
        return Mono.fromCallable(() -> apiKeyQuotaService.updateQuotaLimits(keyId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(detail -> RouterResponse.success(detail, "配额已更新"))
                .onErrorResume(IllegalArgumentException.class, e ->
                        Mono.error(ApiException.of("INVALID_REQUEST", e.getMessage())))
                .onErrorResume(e -> {
                    log.error("更新API密钥配额失败: {}", keyId, e);
                    return Mono.error(ApiExceptions.wrap(e, "更新配额失败"));
                });
    }

    /**
     * 批量重置指定 API Key 的每日配额（v3.2.1）
     */
    @PostMapping("/quota/batch-reset")
    @Operation(summary = "批量重置API密钥配额",
               description = "按 keyIds 批量重置每日配额与速率计数；不存在的 Key 会被跳过")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<Map<String, Object>>> batchResetQuota(
            @RequestBody final Map<String, List<String>> body) {
        final List<String> keyIds = body == null ? null : body.get("keyIds");
        // 同上：批量重置内部逐个 block()，必须离开订阅线程
        return Mono.fromCallable(() -> {
            final int reset = apiKeyQuotaService.resetDailyQuotas(keyIds);
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("requested", keyIds == null ? 0 : keyIds.size());
            data.put("reset", reset);
            return RouterResponse.success(data, "批量重置配额完成");
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.error("批量重置API密钥配额失败", e);
            return Mono.error(ApiExceptions.wrap(e, "批量重置失败"));
        });
    }

    /**
     * 重置全部 API Key 每日配额（v3.2.1）
     */
    @PostMapping("/quota/reset-all")
    @Operation(summary = "重置全部API密钥配额",
               description = "重置所有 API Key 的每日请求/Token 计数与速率限制")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<Void>> resetAllQuota() {
        // 同上：resetAllDailyQuotas 内部逐 key block()，必须离开订阅线程
        return Mono.fromRunnable(() -> apiKeyQuotaService.resetAllDailyQuotas())
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(RouterResponse.<Void>success(null, "已重置全部配额")))
                .onErrorResume(e -> {
                    log.error("重置全部API密钥配额失败", e);
                    return Mono.error(ApiExceptions.wrap(e, "重置全部配额失败"));
                });
    }

    /**
     * 获取所有触发配额告警的 API Key 列表
     */
    @GetMapping("/quota/alerts")
    @Operation(summary = "获取配额告警列表",
               description = "获取所有触发了配额告警阈值的API密钥")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<List<ApiKeyQuotaService.QuotaAlertInfo>>> getQuotaAlerts() {
        return Mono.fromCallable(apiKeyQuotaService::getAlerts)
                .map(alerts -> RouterResponse.success(alerts, "获取配额告警列表成功"))
                .onErrorResume(e -> {
                    log.error("获取配额告警列表失败", e);
                    return Mono.error(ApiExceptions.wrap(e, "获取告警列表失败"));
                });
    }

    /**
     * 获取所有 API Key 的配额使用概览
     */
    @GetMapping("/quota/overview")
    @Operation(summary = "获取配额使用概览",
               description = "获取所有API密钥的配额使用情况概览")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<List<ApiKeyQuotaService.QuotaUsageDetail>>> getQuotaOverview() {
        return Mono.fromCallable(apiKeyQuotaService::getAllQuotaUsage)
                .map(overview -> RouterResponse.success(overview, "获取配额概览成功"))
                .onErrorResume(e -> {
                    log.error("获取配额概览失败", e);
                    return Mono.error(ApiExceptions.wrap(e, "获取配额概览失败"));
                });
    }
}