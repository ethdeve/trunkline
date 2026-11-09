package org.unreal.modelrouter.monitor.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.auth.security.quota.QuotaCounterMetrics;
import org.unreal.modelrouter.auth.security.quota.QuotaDimension;
import org.unreal.modelrouter.auth.security.quota.QuotaLedgerService;
import org.unreal.modelrouter.auth.security.quota.QuotaProperties;
import org.unreal.modelrouter.auth.security.quota.QuotaUsage;
import org.unreal.modelrouter.auth.security.quota.QuotaWindow;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 配额观测面控制器.
 *
 * <p>提供只读的配额运行状态与用量查询接口，绝不触发写入或结算。</p>
 *
 * @author JAiRouter Team
 * @since 3.1.0
 * @modified Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/quota")
@Tag(name = "配额监控", description = "配额账本运行状态与用量查询（只读）")
public class QuotaMonitoringController {

    /** 行类型：分组聚合（分组总用量，只统计分组桶行） */
    public static final String SCOPE_GROUP = "GROUP";

    /** 行类型：组内明细（按实例拆分的用量分布） */
    public static final String SCOPE_MEMBER = "MEMBER";

    /** 行类型：密钥维度（API Key 总闸用量） */
    public static final String SCOPE_KEY = "KEY";

    /** 行类型：厂商模型维度（已接入模型 / 实例的额度与限速用量） */
    public static final String SCOPE_MODEL = "MODEL";

    private final QuotaLedgerService ledgerService;
    private final QuotaProperties quotaProperties;
    private final QuotaCounterMetrics metrics;
    private final ApiKeyService apiKeyService;

    /**
     * 构造配额监控控制器.
     *
     * @param ledgerService   配额账本服务
     * @param quotaProperties 配额配置
     * @param metrics         配额计数指标
     * @param apiKeyService   API Key 归属查询（仅「我的密钥」自助端点使用）
     */
    public QuotaMonitoringController(final QuotaLedgerService ledgerService,
                                     final QuotaProperties quotaProperties,
                                     final QuotaCounterMetrics metrics,
                                     final ApiKeyService apiKeyService) {
        this.ledgerService = ledgerService;
        this.quotaProperties = quotaProperties;
        this.metrics = metrics;
        this.apiKeyService = apiKeyService;
    }

    /**
     * 查询配额运行状态.
     *
     * <p>返回后端类型、是否降级、降级原因、窗口启用情况、fail-open 模式等运行时信息。
     * Redis 模式下尝试连通性探针，失败时不抛 5xx，返回 status=degraded + 说明。</p>
     *
     * @return 配额运行状态
     */
    @GetMapping("/status")
    @Operation(summary = "查询配额运行状态",
            description = "返回 backendName/degraded/degradedReason/enabled/failOpen/windows"
                    + " 与 Redis 连通性信息（只读，不触发写入）")
    public ResponseEntity<RouterResponse<Map<String, Object>>> getStatus() {
        final Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", ledgerService.isEnabled());
        status.put("backendName", ledgerService.backendName());
        status.put("degraded", ledgerService.isDegraded());
        status.put("degradedReason", ledgerService.degradedReason());
        status.put("failOpen", quotaProperties.isFailOpen());

        final List<String> windowNames = new ArrayList<>();
        for (final QuotaWindow w : quotaProperties.enabledWindows()) {
            windowNames.add(w.name());
        }
        status.put("windows", windowNames);

        final Map<String, Object> distributed = new LinkedHashMap<>();
        distributed.put("enabled", quotaProperties.distributedEnabled());
        distributed.put("keyPrefix", quotaProperties.distributedKeyPrefix());
        distributed.put("timeoutMs", quotaProperties.distributedTimeout().toMillis());
        distributed.put("degradeToLocal", quotaProperties.distributedDegradeToLocal());
        status.put("distributed", distributed);

        if (quotaProperties.distributedEnabled()) {
            final Map<String, Object> redisProbe = probeRedis();
            status.put("redisProbe", redisProbe);

            final Map<String, Object> counterMetrics = new LinkedHashMap<>();
            counterMetrics.put("degradationCount",
                    metrics.degradationCount(QuotaLedgerService.REASON_REDIS_UNAVAILABLE)
                            + metrics.degradationCount(QuotaLedgerService.REASON_REDIS_TIMEOUT)
                            + metrics.degradationCount(QuotaLedgerService.REASON_REDIS_NOT_CONFIGURED));
            status.put("counterMetrics", counterMetrics);
        }

        return ResponseEntity.ok(RouterResponse.success(status));
    }

    /**
     * 查询配额用量.
     *
     * <p>支持按维度筛选（tenantId / apiKeyId / userId / serviceType / model / groupId）与窗口类型。
     * 缺省维度使用空串哨兵语义（{@code QuotaDimension.SENTINEL}）。
     * 无参数时返回空维度 + 全部已启用窗口的聚合（按 API Key 聚合视图，即空维度哨兵值）。</p>
     *
     * <p><b>分组视图（v3.5 治理收口）</b>：指定 {@code groupId} 时返回<b>分组含组内明细</b>——</p>
     * <ul>
     *   <li>未指定 {@code model}：同时返回该分组的<b>聚合行</b>（{@code scope=GROUP}，
     *       即该分组的总用量，只统计分组桶行）与<b>组内明细行</b>（{@code scope=MEMBER}，
     *       按实例拆分）；</li>
     *   <li>指定 {@code model}：只返回该实例的明细行（{@code scope=MEMBER}）。</li>
     * </ul>
     * <p>聚合行与明细行是<b>两套互斥口径</b>：一次请求写入预留期的分组桶行与选实例后的成员桶行，
     * 二者不可相加（相加会让分组用量翻倍）。响应中的 {@code scope} 字段标识行类型。</p>
     *
     * <p>本端点严格只读，不会触发任何写入或结算操作。</p>
     *
     * <p><b>线程语义（issue #105）</b>：分布式模式下 {@code ledgerService.usage(...)} 会同步阻塞读取
     * Redis / 数据库，而 WebFlux 的同步签名端点运行在事件循环线程上（本仓库未装配
     * {@code BlockingExecutionConfigurer}），因此这里改为返回 {@code Mono} 并把读数显式切到
     * {@link Schedulers#boundedElastic()}；响应体与状态码与同步实现完全一致。</p>
     *
     * @param tenantId    租户 ID（可选，缺省为空串）
     * @param apiKeyId    API Key ID（可选，缺省为空串）
     * @param userId      用户 ID（可选，缺省为空串）
     * @param serviceType 服务类型（可选，缺省为空串）
     * @param model       模型名称（可选，缺省为空串）
     * @param groupId     分组（模型分组 / 池）名称（可选，缺省为空串）
     * @param window      窗口类型（可选，如 MINUTE / HOUR / DAY / MONTH；缺省返回所有已启用窗口）
     * @return 用量列表
     */
    @GetMapping("/usage")
    @Operation(summary = "查询配额用量",
            description = "按维度与窗口筛选配额用量（只读），"
                    + "返回 dimensions/window/windowStart/requestCount/tokenCount/scope；"
                    + "指定 groupId 时返回「分组含组内明细」")
    public Mono<ResponseEntity<RouterResponse<List<Map<String, Object>>>>> getUsage(
            @RequestParam(required = false) final String tenantId,
            @RequestParam(required = false) final String apiKeyId,
            @RequestParam(required = false) final String userId,
            @RequestParam(required = false) final String serviceType,
            @RequestParam(required = false) final String model,
            @RequestParam(required = false) final String groupId,
            @RequestParam(required = false) final String window) {
        return Mono.fromCallable(() ->
                        doGetUsage(tenantId, apiKeyId, userId, serviceType, model, groupId, window))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 「我的密钥」自助用量查询（员工侧「本月已用」）。
     *
     * <p>通用 {@code /usage} 端点支持任意维度组合、不做属主校验，属于运维/管理员的
     * 观测面（权限矩阵 OPS）；员工页面若直接调用会 403。本端点只接受
     * {@code apiKeyId + window}，并在服务端校验该密钥的有效持有人
     * （{@code holder || createdBy}）必须等于当前登录用户名，其余维度一律置空串哨兵，
     * 无法借它查询跨员工聚合、分组或他人用量。</p>
     *
     * <p>查询逻辑复用 {@link #doGetUsage}，响应契约与全局端点完全一致（只读）。</p>
     *
     * @param apiKeyId       本人名下 API Key ID（必填）
     * @param window         窗口类型（可选，如 MONTH；缺省返回所有已启用窗口）
     * @param authentication 当前登录主体（由 Spring Security 注入，不信任前端传参）
     * @return 用量列表；非本人密钥返回 403
     */
    @GetMapping("/mine/usage")
    @Operation(summary = "查询我的密钥用量",
            description = "仅可查询当前登录用户名下的 API Key 用量（只读），按属主强制校验；"
                    + "响应契约与 /usage 一致")
    public Mono<ResponseEntity<RouterResponse<List<Map<String, Object>>>>> getMyUsage(
            @RequestParam final String apiKeyId,
            @RequestParam(required = false) final String window,
            final Authentication authentication) {
        return Mono.fromCallable(() -> doGetMyUsage(apiKeyId, window, authentication))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 自助用量查询的实际实现：先做属主校验，再复用精确维度查数。
     */
    private ResponseEntity<RouterResponse<List<Map<String, Object>>>> doGetMyUsage(
            final String apiKeyId,
            final String window,
            final Authentication authentication) {
        final String owner = authentication != null ? authentication.getName() : null;
        if (owner == null || owner.isBlank()
                || apiKeyService == null || !apiKeyService.isOwnedBy(owner, apiKeyId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(RouterResponse.error("无权查询该密钥的用量", "FORBIDDEN"));
        }
        // 只放行 apiKeyId 一个维度：其余维度置空，避免自助端点退化成跨员工观测面
        return doGetUsage(null, apiKeyId, null, null, null, null, window);
    }

    /**
     * 用量查询的实际实现（同步读取账本，必须运行在可阻塞线程上）.
     *
     * @param tenantId    租户 ID
     * @param apiKeyId    API Key ID
     * @param userId      用户 ID
     * @param serviceType 服务类型
     * @param model       模型名称
     * @param groupId     分组（模型分组 / 池）名称
     * @param window      窗口类型
     * @return 用量响应
     */
    private ResponseEntity<RouterResponse<List<Map<String, Object>>>> doGetUsage(
            final String tenantId,
            final String apiKeyId,
            final String userId,
            final String serviceType,
            final String model,
            final String groupId,
            final String window) {
        if (!ledgerService.isEnabled()) {
            return ResponseEntity.ok(RouterResponse.success(List.of(), "配额账本未启用"));
        }

        final List<QuotaWindow> targetWindows;
        if (window != null && !window.isBlank()) {
            final Optional<QuotaWindow> parsed = QuotaWindow.parse(window);
            if (parsed.isEmpty()) {
                return ResponseEntity.badRequest().body(RouterResponse.error(
                        "无效的窗口类型: " + window
                                + "（有效值: MINUTE, HOUR, DAY, MONTH）",
                        "INVALID_REQUEST"));
            }
            targetWindows = List.of(parsed.get());
        } else {
            targetWindows = quotaProperties.enabledWindows();
        }

        final List<Map<String, Object>> results = new ArrayList<>();

        // 分组视图：返回「分组含组内明细」，聚合行与明细行口径互斥
        if (groupId != null && !groupId.isBlank()) {
            if (model != null && !model.isBlank()) {
                // 指定模型：只返回该实例的组内明细行
                for (final QuotaUsage usage : ledgerService.usageByGroupMembers(groupId)) {
                    if (targetWindows.contains(usage.window()) && model.equals(usage.dimension().model())) {
                        results.add(usageToMap(usage, SCOPE_MEMBER));
                    }
                }
            } else {
                // 未指定模型：聚合行（分组总用量）+ 组内明细行（按实例拆分）
                for (final QuotaUsage usage : ledgerService.usageByGroupAggregate(groupId)) {
                    if (targetWindows.contains(usage.window())) {
                        results.add(usageToMap(usage, SCOPE_GROUP));
                    }
                }
                for (final QuotaUsage usage : ledgerService.usageByGroupMembers(groupId)) {
                    if (targetWindows.contains(usage.window())) {
                        results.add(usageToMap(usage, SCOPE_MEMBER));
                    }
                }
            }
            return ResponseEntity.ok(RouterResponse.success(results));
        }

        // 非分组视图：精确维度查数（与既有语义一致）
        final QuotaDimension dimension = new QuotaDimension(
                tenantId, apiKeyId, userId, serviceType, model, groupId);
        for (final QuotaWindow w : targetWindows) {
            final Optional<QuotaUsage> usage = ledgerService.usage(dimension, w);
            usage.ifPresent(u -> results.add(usageToMap(u, scopeOf(u.dimension()))));
        }

        return ResponseEntity.ok(RouterResponse.success(results));
    }

    /**
     * 由维度推断行类型（{@code scope}）.
     *
     * @param dimension 账本维度
     * @return 行类型标记
     */
    private static String scopeOf(final QuotaDimension dimension) {
        if (dimension == null) {
            return SCOPE_KEY;
        }
        if (!dimension.groupId().isEmpty()) {
            return dimension.model().isEmpty() ? SCOPE_GROUP : SCOPE_MEMBER;
        }
        // 无分组但有模型 = 厂商模型维度（额度与限速按厂商模型配置）
        if (!dimension.model().isEmpty()) {
            return SCOPE_MODEL;
        }
        return SCOPE_KEY;
    }

    /**
     * Redis 连通性探针（不抛异常）.
     *
     * @return 探针结果 map
     */
    private Map<String, Object> probeRedis() {
        final Map<String, Object> probe = new LinkedHashMap<>();
        try {
            final boolean degraded = ledgerService.isDegraded();
            probe.put("reachable", !degraded);
            if (degraded) {
                probe.put("reason", ledgerService.degradedReason());
                probe.put("status", "degraded");
            } else {
                probe.put("status", "healthy");
            }
        } catch (Exception e) {
            log.debug("Redis 连通性探针异常: {}", e.toString());
            probe.put("reachable", false);
            probe.put("status", "error");
            probe.put("reason", e.getMessage());
        }
        return probe;
    }

    /**
     * 用量记录转 Map.
     *
     * @param usage 用量
     * @param scope 行类型标记（{@code GROUP} 分组聚合 / {@code MEMBER} 组内明细 /
     *                {@code MODEL} 厂商模型维度 / {@code KEY} 密钥维度）
     * @return map
     */
    private static Map<String, Object> usageToMap(final QuotaUsage usage, final String scope) {
        final Map<String, Object> map = new LinkedHashMap<>();
        final Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("tenantId", usage.dimension().tenantId());
        dimensions.put("apiKeyId", usage.dimension().apiKeyId());
        dimensions.put("userId", usage.dimension().userId());
        dimensions.put("serviceType", usage.dimension().serviceType());
        dimensions.put("model", usage.dimension().model());
        dimensions.put("groupId", usage.dimension().groupId());
        map.put("dimensions", dimensions);
        map.put("scope", scope);
        map.put("window", usage.window().name());
        map.put("windowStart", usage.windowStart().toString());
        map.put("requestCount", usage.requestCount());
        map.put("tokenCount", usage.tokenCount());
        // 限额对照：仅当维度只关心单个 API Key 时，由前端/调用方结合 /auth/api-keys/quota/overview 填充；
        // 此处预留字段，保证响应契约稳定。
        map.put("dailyRequestLimit", null);
        map.put("dailyTokenLimit", null);
        map.put("rateLimitPerMinute", null);
        return map;
    }
}
