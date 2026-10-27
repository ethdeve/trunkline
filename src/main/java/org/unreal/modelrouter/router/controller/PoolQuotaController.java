package org.unreal.modelrouter.router.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.quota.QuotaDimension;
import org.unreal.modelrouter.auth.security.quota.QuotaLedgerService;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitSet;
import org.unreal.modelrouter.auth.security.quota.QuotaUsage;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.router.pool.PoolSelector;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分组（模型分组 / 池）维度配额管理控制器（v3.4 配额多维度改造 · 一期）。
 *
 * <p>与 API Key 维度的配额管理平行：</p>
 * <ul>
 *   <li>{@code GET  /api/config/pools/{poolName}/quota/usage} — 查询该分组在各窗口的用量与限额；</li>
 *   <li>{@code POST /api/config/pools/{poolName}/quota/reset} — 重置该分组的全部窗口计数。</li>
 * </ul>
 *
 * <p><b>分组维度的口径</b>：分组配额是<b>跨 API Key 的共享预算</b>——账本按
 * {@code group_id = 池名} 聚合（{@link QuotaDimension#ofGroup(String)}），该分组下所有密钥
 * 的请求共同消耗这一份额度，任一请求超出即 429。这与「API Key 维度 = 单个密钥的总闸」
 * 是两个独立的预算，互不替代。</p>
 *
 * <p><b>线程语义</b>：账本读 / 写会同步阻塞（数据库快照），而 WebFlux 端点运行在事件循环线程上，
 * 因此两个端点都显式切到 {@link Schedulers#boundedElastic()}（与
 * {@code ApiKeyManagementController} 的配额端点一致）。</p>
 *
 * @author JAiRouter Team
 * @since 3.4.0
 */
@Slf4j
@RestController
@RequestMapping("/api/config/pools")
@CrossOrigin(origins = "*")
@Tag(name = "分组配额管理", description = "模型分组(池)维度的配额用量查询与重置")
public class PoolQuotaController {

    private final PoolSelector poolSelector;
    private final QuotaLedgerService ledgerService;

    /**
     * 构造分组配额控制器.
     *
     * @param poolSelector  池选择器（提供生效池快照）
     * @param ledgerService 配额账本服务
     */
    public PoolQuotaController(final PoolSelector poolSelector,
                               final QuotaLedgerService ledgerService) {
        this.poolSelector = poolSelector;
        this.ledgerService = ledgerService;
    }

    /**
     * 查询分组维度的配额用量与限额.
     *
     * <p>返回该分组在各已启用窗口的请求数 / Token 数，以及当前配置的限额
     * （各窗口 {@code <= 0} 表示不限制）。</p>
     *
     * <p><b>用量口径（v3.5 治理收口）</b>：一次请求会写入两族账本行——预留期的<b>分组桶</b>
     * （{@code group_id = 分组名, model = ""}）与选实例后补记的<b>成员桶</b>
     * （{@code group_id = 分组名, model = 实例名}）。本端点按<b>分组聚合口径</b>返回
     * {@code usage}（只统计分组桶行，与限额判定读的是同一份计数，因此页面显示的用量
     * 与“是否触发限额”始终一致），并在 {@code members} 中附带<b>组内明细</b>
     * （按实例拆分）。两个口径<b>互斥、不可相加</b>——相加会让分组用量翻倍。</p>
     *
     * @param poolName 分组（池）名
     * @return 用量与限额
     */
    @GetMapping("/{poolName}/quota/usage")
    @Operation(summary = "查询分组配额用量",
            description = "按分组（池）维度返回各窗口的请求数/Token 数与配置的限额；"
                    + "usage 为分组聚合口径，members 为组内按实例拆分的明细")
    public Mono<RouterResponse<Map<String, Object>>> getPoolQuotaUsage(
            @PathVariable("poolName") final String poolName) {
        return Mono.fromCallable(() -> {
            final PoolDefinition pool = requirePool(poolName);
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("poolName", poolName);
            result.put("limits", limitsToMap(pool.toQuotaLimitSet()));

            final List<Map<String, Object>> usages = new ArrayList<>();
            final List<Map<String, Object>> members = new ArrayList<>();
            if (ledgerService.isEnabled()) {
                // 分组聚合口径：只统计分组桶行（model 为空），与分组级限额判定口径一致
                for (final QuotaUsage usage : ledgerService.usageByGroupAggregate(poolName)) {
                    final Map<String, Object> row = new LinkedHashMap<>();
                    row.put("window", usage.window().name());
                    row.put("windowStart", usage.windowStart().toString());
                    row.put("requestCount", usage.requestCount());
                    row.put("tokenCount", usage.tokenCount());
                    usages.add(row);
                }
                // 组内明细口径：只统计成员桶行（model = 实例名），与聚合口径互斥
                for (final QuotaUsage usage : ledgerService.usageByGroupMembers(poolName)) {
                    final Map<String, Object> row = new LinkedHashMap<>();
                    row.put("model", usage.dimension().model());
                    row.put("window", usage.window().name());
                    row.put("windowStart", usage.windowStart().toString());
                    row.put("requestCount", usage.requestCount());
                    row.put("tokenCount", usage.tokenCount());
                    members.add(row);
                }
            }
            result.put("usage", usages);
            result.put("members", members);
            return RouterResponse.success(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 重置分组的全部配额计数.
     *
     * <p>清理该分组的全部窗口（比「仅重置当日」更彻底），覆盖本地计数槽位、
     * 数据库快照与（开启分布式计数时的）Redis 计数桶。异常只记录日志不抛出。</p>
     *
     * @param poolName 分组（池）名
     * @return 清理条数
     */
    @PostMapping("/{poolName}/quota/reset")
    @Operation(summary = "重置分组配额",
            description = "重置指定分组（池）在全部窗口的请求数/Token 计数")
    public Mono<RouterResponse<Map<String, Object>>> resetPoolQuota(
            @PathVariable("poolName") final String poolName) {
        return Mono.fromCallable(() -> {
            requirePool(poolName);
            final int cleared = ledgerService.resetByDimension(QuotaDimension.ofGroup(poolName));
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("poolName", poolName);
            result.put("cleared", cleared);
            return RouterResponse.success(result, "分组配额已重置");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 校验分组存在（不存在直接 404，避免对不存在的分组做无意义的重置）。
     *
     * @param poolName 分组（池）名
     * @return 生效池定义
     */
    private PoolDefinition requirePool(final String poolName) {
        final List<PoolDefinition> pools = poolSelector.getAllPools();
        if (pools != null) {
            for (final PoolDefinition pool : pools) {
                if (pool != null && poolName.equals(pool.getPoolName())) {
                    return pool;
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pool not found: " + poolName);
    }

    /**
     * 限额集合转可读 map（便于前端直接展示限额口径）.
     *
     * @param limits 限额集合
     * @return 限额 map
     */
    private static Map<String, Object> limitsToMap(final QuotaLimitSet limits) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("rateLimitPerMinute", limits.minuteRequests());
        map.put("hourlyRequestLimit", limits.hourRequests());
        map.put("hourlyTokenLimit", limits.hourTokens());
        map.put("dailyRequestLimit", limits.dayRequests());
        map.put("dailyTokenLimit", limits.dayTokens());
        map.put("monthlyRequestLimit", limits.monthRequests());
        map.put("monthlyTokenLimit", limits.monthTokens());
        return map;
    }
}
