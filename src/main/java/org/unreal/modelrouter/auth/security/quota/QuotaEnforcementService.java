package org.unreal.modelrouter.auth.security.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配额限额判定与预留服务（v3.1 PR-2）。
 *
 * <p>把 PR-1 的旁路账本接入请求主链路：在实例选择之前判定“账本窗口用量 + 本次估算 token”
 * 是否越过既有 {@link ApiKey} 限额，未超限则记账预留（{@link QuotaLedgerService#reserve(QuotaRequest)}），
 * 结算由链路末端按实际用量冲正。</p>
 *
 * <p><b>限额映射</b>（限额来源是既有 API Key 配置，不新增配额字段）：</p>
 * <ul>
 *   <li>{@link QuotaWindow#DAY} 窗口 → {@code dailyRequestLimit}（请求数）与
 *       {@code dailyTokenLimit}（token 数）；</li>
 *   <li>{@link QuotaWindow#MINUTE} 窗口 → {@code rateLimitPerMinute}（请求数）。</li>
 * </ul>
 * <p>{@code 0} 表示不限制（与 {@link ApiKey} 既有语义一致）。判定顺序为
 * 日请求数 → 日 token 数 → 每分钟请求数，命中即返回（一次只报告一项，避免响应头自相矛盾）。</p>
 *
 * <p><b>维度取舍</b>：限额本身是 API Key 级配置，因此请求侧账本维度取 API Key 粒度
 * （{@link QuotaDimension#ofApiKey(String)}，其余维度留空），使 {@code usage(dimension, window)}
 * 既能命中账本内存热路径（不引入每请求一次聚合查询），又能按 Key 正确聚合窗口用量；
 * 服务类型 / 模型级细分留待后续 PR。</p>
 *
 * <p><b>不变量</b>：</p>
 * <ul>
 *   <li>账本未启用（{@code jairouter.quota.enabled=false}，默认）时本服务全部入口立即返回、
 *       不读写账本、不创建预留凭据——调用方行为与 v3.0.x 完全一致；</li>
 *   <li>判定 / 记账过程中的任何异常都按 fail-open 放行并打告警日志，绝不抛给调用方，
 *       因此配额链路不会产生 5xx；</li>
 *   <li><b>读不到用量不等于零用量</b>：窗口用量用 {@link QuotaLedgerService#usageStrict(QuotaDimension, QuotaWindow)}
 *       读取（读取失败直接抛异常），因此账本不可用时不会拿“默认 0 用量”去判定限额——那既可能凭空
 *       构造超限（错杀请求），也可能放过真实超限。此时统一按 fail-open 放行、不写任何 429 响应头；</li>
 *   <li>不调用也不修改既有限流组件（{@code RateLimitManager}），MINUTE 窗口判定是账本口径的
 *       独立视角，不存在双扣；</li>
 *   <li>限额对应的窗口未在 {@code jairouter.quota.windows} 中启用时，该项限额无法度量，
 *       按 fail-open 跳过（只记录 debug 日志）。</li>
 * </ul>
 *
 * @author JAiRouter Team
 * @since 3.1.0
 */
@Slf4j
@Service
public class QuotaEnforcementService {

    private final QuotaLedgerService ledgerService;
    private final QuotaProperties properties;
    private final ApiKeyService apiKeyService;
    private final Clock clock;

    /** 分组（模型分组 / 池）维度限额解析器（未装配时为 {@code null}，该维度按无限制放行） */
    @Autowired(required = false)
    @Qualifier("group")
    private QuotaLimitResolver groupLimitResolver;

    /** 模型（已接入模型 / 实例）维度限额解析器（未装配时为 {@code null}，该维度按无限制放行） */
    @Autowired(required = false)
    @Qualifier("model")
    private QuotaLimitResolver modelLimitResolver;

    /**
     * 单进程内按 API Key 串行化「判定 + 预留」，消除 check-then-act 双花窗口。
     * 多实例分布式计数仍依赖预留后二次核对 + 回滚（见 {@link #tryReserve}）。
     */
    private final ConcurrentHashMap<String, Object> reserveLocks = new ConcurrentHashMap<>();

    /**
     * Spring 使用的主构造器。
     *
     * @param ledgerService 账本服务
     * @param properties    账本配置（读取启用的窗口）
     * @param apiKeyService API Key 服务（读取限额配置，未装配时为 {@code null}，此时按无限制放行）
     */
    @Autowired
    public QuotaEnforcementService(final QuotaLedgerService ledgerService,
                                   final QuotaProperties properties,
                                   @Autowired(required = false) final ApiKeyService apiKeyService) {
        this(ledgerService, properties, apiKeyService, Clock.systemDefaultZone());
    }

    /**
     * 可注入时钟的构造器，便于测试固定窗口边界与 {@code Retry-After}。
     *
     * @param ledgerService 账本服务
     * @param properties    账本配置
     * @param apiKeyService API Key 服务，可为 {@code null}
     * @param clock         时钟
     */
    public QuotaEnforcementService(final QuotaLedgerService ledgerService,
                                   final QuotaProperties properties,
                                   final ApiKeyService apiKeyService,
                                   final Clock clock) {
        this.ledgerService = ledgerService;
        this.properties = properties;
        this.apiKeyService = apiKeyService;
        this.clock = clock;
    }

    /**
     * 配额能力是否启用（透传账本开关）。
     *
     * @return {@code jairouter.quota.enabled} 的值，默认 {@code false}
     */
    public boolean isEnabled() {
        return ledgerService.isEnabled();
    }

    /**
     * 判定本次请求是否已超限（只读，不记账、不挂载凭据）。
     *
     * <p>判定标准是 {@code 窗口已用量 + 本次估算 token > 限额}（严格大于：恰好用满不算超限）。
     * 窗口用量经 {@link QuotaLedgerService#usageStrict(QuotaDimension, QuotaWindow)} 读取，
     * 读不到用量（账本不可用）时不把缺失当成 0，而是直接降级放行。</p>
     *
     * @param apiKeyId        API Key ID，{@code null} 或空串时直接放行
     * @param estimatedTokens 本次请求估算 token 数（负数按 0 处理）
     * @return 超限时返回被命中的限额；未超限、账本未启用、Key 未知、账本读不到用量或判定异常
     *         （fail-open）时返回 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> evaluate(final String apiKeyId, final long estimatedTokens) {
        if (!isEnabled() || apiKeyId == null || apiKeyId.isEmpty()) {
            return Optional.empty();
        }
        try {
            final ApiKey apiKey = resolveApiKey(apiKeyId);
            if (apiKey == null) {
                log.debug("配额判定跳过：未找到 API Key 配置，按无限制放行: apiKeyId={}", apiKeyId);
                return Optional.empty();
            }
            final LocalDateTime now = LocalDateTime.now(clock);
            final List<QuotaWindow> windows = properties.enabledWindows();
            final QuotaDimension dimension = QuotaDimension.ofApiKey(apiKeyId);
            final long tokens = Math.max(0L, estimatedTokens);

            // 判定顺序：HOUR → DAY → MONTH → MINUTE（对齐上游 3.4.0 的优先级说明）
            Optional<QuotaLimitViolation> violation = evaluateWindow(apiKey, dimension, windows,
                QuotaWindow.HOUR, apiKey.getHourlyRequestLimit(), apiKey.getHourlyTokenLimit(),
                QuotaLimitViolation.METRIC_HOURLY_REQUESTS, QuotaLimitViolation.METRIC_HOURLY_TOKENS,
                tokens, now);
            if (violation.isPresent()) {
                return violation;
            }
            violation = evaluateWindow(apiKey, dimension, windows,
                QuotaWindow.DAY, apiKey.getDailyRequestLimit(), apiKey.getDailyTokenLimit(),
                QuotaLimitViolation.METRIC_DAILY_REQUESTS, QuotaLimitViolation.METRIC_DAILY_TOKENS,
                tokens, now);
            if (violation.isPresent()) {
                return violation;
            }
            violation = evaluateWindow(apiKey, dimension, windows,
                QuotaWindow.MONTH, apiKey.getMonthlyRequestLimit(), apiKey.getMonthlyTokenLimit(),
                QuotaLimitViolation.METRIC_MONTHLY_REQUESTS, QuotaLimitViolation.METRIC_MONTHLY_TOKENS,
                tokens, now);
            if (violation.isPresent()) {
                return violation;
            }
            return evaluateWindow(apiKey, dimension, windows,
                QuotaWindow.MINUTE, apiKey.getRateLimitPerMinute(), 0L,
                QuotaLimitViolation.METRIC_RATE_PER_MINUTE, null, 0L, now);
        } catch (Exception e) {
            log.warn("配额限额判定异常，按 fail-open 放行: apiKeyId={}, error={}", apiKeyId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * N-2: 账本未启用时的日 Token 配额请求前预估兜底判定.
     *
     * <p>账本关闭({@code jairouter.quota.enabled=false},默认)时,主链路退化为
     * 「认证时按历史 {@code dailyTokenUsage} 校验 + 响应后入账」——单次请求的消耗
     * 在放行时点尚不可知,一次大请求即可把当日额度撑爆(实测:limit=224/已用 214,
     * 本次消耗 154 仍放行,累计 368 超限 65%,仅下一次才被拒)。</p>
     *
     * <p>本方法在请求转发前按 {@link QuotaTokenEstimator#estimateUpperBound(Object)}
     * （prompt 估算 + {@code max_tokens} 输出上限）判定:
     * {@code 当日已用 + 本次估算 > dailyTokenLimit} 即 429。不触碰账本、不创建预留,
     * 与启用态判定结果形态一致({@link QuotaLimitViolation} + Retry-After 到日窗口重置)。</p>
     *
     * <p>只覆盖 Token 维度:请求数({@code dailyRequestLimit})由认证链路实时判定,
     * 每分钟限流由 {@code RateLimitManager} 实时判定,均无「事后入账」时延;
     * N-6 修复后被拒请求也不再计入当日用量,请求数口径已闭合。</p>
     *
     * @param apiKeyId   API Key ID
     * @param requestDto 原始请求 DTO（用于估算上限），可为 {@code null}
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> checkEstimatedTokenQuota(final String apiKeyId,
                                                                  final Object requestDto) {
        if (apiKeyId == null || apiKeyId.isEmpty()) {
            return Optional.empty();
        }
        final long estimatedUpperBound = QuotaTokenEstimator.estimateUpperBound(requestDto);
        if (estimatedUpperBound <= 0L) {
            // 无估算信息(非 chat DTO / 空 messages / 无 max_tokens):无法预判,放行
            return Optional.empty();
        }
        try {
            final ApiKey apiKey = resolveApiKey(apiKeyId);
            if (apiKey == null || apiKey.getDailyTokenLimit() <= 0L) {
                return Optional.empty();
            }
            final String today = LocalDateTime.now(clock).toLocalDate().toString();
            long todayUsed = 0L;
            if (apiKey.getUsage() != null && apiKey.getUsage().getDailyTokenUsage() != null) {
                final Long used = apiKey.getUsage().getDailyTokenUsage().get(today);
                todayUsed = used != null ? used : 0L;
            }
            if (todayUsed + estimatedUpperBound > apiKey.getDailyTokenLimit()) {
                final LocalDateTime now = LocalDateTime.now(clock);
                final LocalDateTime nextDay = QuotaWindow.DAY.windowStart(now).plusDays(1L);
                final long millis = Math.max(0L, Duration.between(now, nextDay).toMillis());
                final long retryAfter = Math.max(1L, (millis + 999L) / 1000L);
                log.warn("N-2 预估 Token 配额超限，拒绝请求: apiKeyId={}, used={}, estimated={}, limit={}",
                        apiKeyId, todayUsed, estimatedUpperBound, apiKey.getDailyTokenLimit());
                return Optional.of(new QuotaLimitViolation(
                        QuotaLimitViolation.METRIC_DAILY_TOKENS,
                        QuotaWindow.DAY,
                        apiKey.getDailyTokenLimit(),
                        todayUsed,
                        retryAfter));
            }
        } catch (Exception e) {
            log.warn("N-2 Token 配额预估校验异常，按放行处理: apiKeyId={}, error={}", apiKeyId, e.toString());
        }
        return Optional.empty();
    }

    /**
     * 判定并在放行时完成预留（仅 API Key 维度，兼容既有调用点）。
     *
     * <p>等价于 {@link #tryReserve(ServerHttpRequest, String, String, String, long)} 且分组 / 模型
     * 维度均为空——即只受 Key 总闸约束。</p>
     *
     * @param request         请求（用于挂载结算凭据），可为 {@code null}
     * @param apiKeyId        API Key ID（总闸）
     * @param estimatedTokens 本次请求估算 token 数
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> tryReserve(final ServerHttpRequest request,
                                                    final String apiKeyId,
                                                    final long estimatedTokens) {
        return tryReserve(request, apiKeyId, "", "", estimatedTokens);
    }

    /**
     * 判定并在放行时完成预留（多维度判定 + 记账 + 挂载结算凭据）。
     *
     * <p>三维度（Key 总闸 / 分组 / 模型）各自独立预算，任一维度超限即返回 429，并回滚本请求已在
     * 其它维度成功预留的增量（照搬既有“多窗口回滚”思路，按维度逐条冲正）。Key 维度恒生效；
     * 分组与模型维度互斥（一次请求只走其一），对应解析器未装配时该维度按无限制放行。</p>
     *
     * <p>返回空表示“放行且已完成预留”（或账本未启用 / 判定异常降级放行）；返回超限结果表示
     * 调用方应拒绝本次请求（429），本方法此时不记账。</p>
     *
     * @param request         请求（用于挂载结算凭据），可为 {@code null}
     * @param apiKeyId        API Key ID（总闸）
     * @param groupId         分组（模型分组 / 池）名称，无则传空串
     * @param model           模型（已接入模型 / 实例）名称，无则传空串
     * @param estimatedTokens 本次请求估算 token 数
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    public Optional<QuotaLimitViolation> tryReserve(final ServerHttpRequest request,
                                                    final String apiKeyId,
                                                    final String groupId,
                                                    final String model,
                                                    final long estimatedTokens) {
        if (!isEnabled() || apiKeyId == null || apiKeyId.isEmpty()) {
            return Optional.empty();
        }
        final Object lock = reserveLocks.computeIfAbsent(apiKeyId, key -> new Object());
        synchronized (lock) {
            try {
                final long tokens = Math.max(0L, estimatedTokens);
                // 组装三维度与各自限额：Key 恒生效；分组 / 模型按需（互斥）
                final List<QuotaDimension> dimensions = new ArrayList<>();
                final List<QuotaLimitSet> limits = new ArrayList<>();
                dimensions.add(QuotaDimension.ofApiKey(apiKeyId));
                limits.add(limitsOf(resolveApiKey(apiKeyId)));
                if (groupId != null && !groupId.isEmpty()) {
                    dimensions.add(QuotaDimension.ofGroup(groupId));
                    limits.add(resolveGroupLimit(groupId));
                }
                if (model != null && !model.isEmpty()) {
                    dimensions.add(QuotaDimension.ofModel(model));
                    limits.add(resolveModelLimit(model));
                }

                // 逐维度预留；超限则回滚已成功维度（避免少扣 / 多扣）
                final List<QuotaDimension> committed = new ArrayList<>();
                for (int i = 0; i < dimensions.size(); i++) {
                    final QuotaDimension dim = dimensions.get(i);
                    final QuotaLimitSet limit = limits.get(i);
                    if (limit == null || limit.equals(QuotaLimitSet.UNLIMITED)) {
                        // 该维度无有效限额：仅记账（不判定），保证计数连续
                        ledgerService.reserve(QuotaRequest.of(dim, tokens));
                        committed.add(dim);
                        continue;
                    }
                    final Optional<QuotaLimitViolation> violation = ledgerService.reserveWithLimits(
                            QuotaRequest.of(dim, tokens), limit);
                    if (violation.isPresent()) {
                        rollbackDimensions(committed, tokens);
                        log.warn("配额超限，拒绝请求: apiKeyId={}, scope={}, {}", apiKeyId, dim, violation.get().describe());
                        return violation;
                    }
                    committed.add(dim);
                }
                QuotaReservation.attach(request, new QuotaReservation(ledgerService, dimensions, tokens));
                return Optional.empty();
            } catch (Exception e) {
                log.warn("配额预留异常，按 fail-open 放行: apiKeyId={}, error={}", apiKeyId, e.toString());
                return Optional.empty();
            }
        }
    }

    /**
     * 回滚已成功预留的多个维度（按维度逐条冲正，失败只告警）。
     *
     * @param committed 已成功预留的维度列表
     * @param tokens    预留的估算 token 数
     */
    private void rollbackDimensions(final List<QuotaDimension> committed, final long tokens) {
        for (final QuotaDimension dim : committed) {
            try {
                ledgerService.settle(new QuotaSettlement(dim, tokens, tokens, true));
            } catch (Exception e) {
                log.warn("配额多维度预留回滚失败（残量留待窗口过期回收）: scope={}, error={}", dim, e.toString());
            }
        }
    }

    /**
     * 解析分组（模型分组 / 池）维度的限额集合。
     *
     * @param groupId 分组（池）名称
     * @return 限额集合；解析器缺失或解析异常时返回 {@code null}（按无限制放行）
     */
    private QuotaLimitSet resolveGroupLimit(final String groupId) {
        if (groupLimitResolver == null) {
            return null;
        }
        try {
            return groupLimitResolver.resolve(groupId).orElse(null);
        } catch (Exception e) {
            log.warn("解析分组配额限额失败，按无限制放行: groupId={}, error={}", groupId, e.toString());
            return null;
        }
    }

    /**
     * 解析模型（已接入模型 / 实例）维度的限额集合。
     *
     * @param model 模型（实例）名称
     * @return 限额集合；解析器缺失或解析异常时返回 {@code null}（按无限制放行）
     */
    private QuotaLimitSet resolveModelLimit(final String model) {
        if (modelLimitResolver == null) {
            return null;
        }
        try {
            return modelLimitResolver.resolve(model).orElse(null);
        } catch (Exception e) {
            log.warn("解析模型配额限额失败，按无限制放行: model={}, error={}", model, e.toString());
            return null;
        }
    }

    /**
     * 预留后的限额复核（用量已包含本笔预留）。
     *
     * <p>判定改为「已用量 &gt; 限额」（不再 +1），避免把刚预留的那一笔重复计算。</p>
     *
     * @param apiKeyId API Key ID
     * @return 超限结果；未超限、配置缺失或读取失败（fail-open）时为空
     */
    private Optional<QuotaLimitViolation> evaluateAfterReserve(final String apiKeyId) {
        try {
            final ApiKey apiKey = resolveApiKey(apiKeyId);
            if (apiKey == null) {
                return Optional.empty();
            }
            final LocalDateTime now = LocalDateTime.now(clock);
            final List<QuotaWindow> windows = properties.enabledWindows();
            final QuotaDimension dimension = QuotaDimension.ofApiKey(apiKeyId);

            // 复核顺序与 evaluate 一致：HOUR → DAY → MONTH → MINUTE；
            // 语义为「已用量（含本笔预留）> 限额」，与预留时的「+1 > 限额」严格大于口径等价
            for (final QuotaWindow window : new QuotaWindow[] {QuotaWindow.HOUR, QuotaWindow.DAY, QuotaWindow.MONTH}) {
                if (!windows.contains(window)) {
                    continue;
                }
                final long maxRequests = limitsOf(apiKey).maxRequests(window);
                final long maxTokens = limitsOf(apiKey).maxTokens(window);
                if (maxRequests <= 0L && maxTokens <= 0L) {
                    continue;
                }
                final Optional<QuotaUsage> usage = ledgerService.usageStrict(dimension, window);
                final long requests = usage.map(QuotaUsage::requestCount).orElse(0L);
                final long tokens = usage.map(QuotaUsage::tokenCount).orElse(0L);
                if (maxRequests > 0L && requests > maxRequests) {
                    return Optional.of(violation(requestMetricFor(window), window,
                            maxRequests, requests - 1L, now));
                }
                if (maxTokens > 0L && tokens > maxTokens) {
                    return Optional.of(violation(tokenMetricFor(window), window,
                            maxTokens, tokens, now));
                }
            }
            if (windows.contains(QuotaWindow.MINUTE) && apiKey.getRateLimitPerMinute() > 0) {
                final long requests = ledgerService.usageStrict(dimension, QuotaWindow.MINUTE)
                        .map(QuotaUsage::requestCount).orElse(0L);
                if (requests > apiKey.getRateLimitPerMinute()) {
                    return Optional.of(violation(QuotaLimitViolation.METRIC_RATE_PER_MINUTE, QuotaWindow.MINUTE,
                            apiKey.getRateLimitPerMinute(), requests - 1L, now));
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debug("配额预留后复核失败，按 fail-open 放行: apiKeyId={}, error={}", apiKeyId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 单个窗口的限额判定（请求数 + token）。
     *
     * <p>四个窗口（HOUR / DAY / MONTH / MINUTE）共用这一份逻辑，避免各写一遍后出现口径漂移：
     * 窗口未启用则跳过（无法度量 ⇒ fail-open）；两项限额都 {@code <= 0} 时不读账本直接放行；
     * 命中条件为 {@code 已用量 + 本笔 > 限额}（严格大于：恰好用满不算超限）。</p>
     *
     * @param apiKey            限额配置
     * @param dimension         账本维度
     * @param windows           启用的窗口
     * @param window            当前判定的窗口
     * @param maxRequests       该窗口请求数上限（{@code <= 0} 不限）
     * @param maxTokens         该窗口 token 上限（{@code <= 0} 不限）
     * @param requestMetricName 请求数超限时的指标名
     * @param tokenMetricName   token 超限时的指标名（无 token 限额的窗口传 {@code null}）
     * @param estimatedTokens   本次估算 token 数（MINUTE 窗口传 0）
     * @param now               当前时间
     * @return 超限结果，未超限或窗口未启用时为空
     * @throws IllegalStateException 账本读不到该窗口用量（由 {@link #evaluate} 统一按 fail-open 处理）
     */
    private Optional<QuotaLimitViolation> evaluateWindow(final ApiKey apiKey,
                                                         final QuotaDimension dimension,
                                                         final List<QuotaWindow> windows,
                                                         final QuotaWindow window,
                                                         final long maxRequests,
                                                         final long maxTokens,
                                                         final String requestMetricName,
                                                         final String tokenMetricName,
                                                         final long estimatedTokens,
                                                         final LocalDateTime now) {
        if (!windows.contains(window)) {
            log.debug("{} 窗口未启用，跳过限额判定: apiKeyId={}", window, apiKey.getKeyId());
            return Optional.empty();
        }
        if (maxRequests <= 0L && maxTokens <= 0L) {
            return Optional.empty();
        }
        final Optional<QuotaUsage> usage = ledgerService.usageStrict(dimension, window);
        final long requests = usage.map(QuotaUsage::requestCount).orElse(0L);
        final long tokens = usage.map(QuotaUsage::tokenCount).orElse(0L);

        if (maxRequests > 0L && requests + 1L > maxRequests) {
            return Optional.of(violation(requestMetricName, window, maxRequests, requests, now));
        }
        if (tokenMetricName != null && maxTokens > 0L && tokens + estimatedTokens > maxTokens) {
            return Optional.of(violation(tokenMetricName, window, maxTokens, tokens, now));
        }
        return Optional.empty();
    }

    /** 取窗口对应的请求数限额指标名（供预留后复核使用）。 */
    private static String requestMetricFor(final QuotaWindow window) {
        return switch (window) {
            case HOUR -> QuotaLimitViolation.METRIC_HOURLY_REQUESTS;
            case DAY -> QuotaLimitViolation.METRIC_DAILY_REQUESTS;
            case MONTH -> QuotaLimitViolation.METRIC_MONTHLY_REQUESTS;
            case MINUTE -> QuotaLimitViolation.METRIC_RATE_PER_MINUTE;
        };
    }

    /** 取窗口对应的 token 限额指标名（MINUTE 窗口无 token 限额，返回请求数指标名兜底，不会命中）。 */
    private static String tokenMetricFor(final QuotaWindow window) {
        return switch (window) {
            case HOUR -> QuotaLimitViolation.METRIC_HOURLY_TOKENS;
            case DAY -> QuotaLimitViolation.METRIC_DAILY_TOKENS;
            case MONTH -> QuotaLimitViolation.METRIC_MONTHLY_TOKENS;
            case MINUTE -> QuotaLimitViolation.METRIC_RATE_PER_MINUTE;
        };
    }

    /**
     * 从 API Key 配置打包限额集合（供 CAS 预留与判定共用，对齐上游 3.4.0）。
     *
     * <p>配置缺失（{@code apiKey == null}）时返回 {@link QuotaLimitSet#UNLIMITED}：
     * Key 维度仍然只记账、不判定，其余维度（分组 / 模型）的限额照常生效。</p>
     *
     * @param apiKey 限额配置，可为 {@code null}
     * @return 限额集合（各窗口 {@code <= 0} 表示不限）
     */
    private QuotaLimitSet limitsOf(final ApiKey apiKey) {
        if (apiKey == null) {
            return QuotaLimitSet.UNLIMITED;
        }
        return new QuotaLimitSet(
                apiKey.getRateLimitPerMinute(),
                apiKey.getHourlyRequestLimit(),
                apiKey.getHourlyTokenLimit(),
                apiKey.getDailyRequestLimit(),
                apiKey.getDailyTokenLimit(),
                apiKey.getMonthlyRequestLimit(),
                apiKey.getMonthlyTokenLimit());
    }

    /**
     * 构造超限结果（含距下一窗口边界的秒数）。
     *
     * @param metric 限额指标名
     * @param window 窗口
     * @param limit  限额配置值
     * @param used   已用额度
     * @param now    当前时间
     * @return 超限结果
     */
    private QuotaLimitViolation violation(final String metric,
                                          final QuotaWindow window,
                                          final long limit,
                                          final long used,
                                          final LocalDateTime now) {
        return new QuotaLimitViolation(metric, window, limit, used, retryAfterSeconds(window, now));
    }

    /**
     * 计算距窗口重置的秒数（向上取整，最小 1 秒）。
     *
     * @param window 窗口
     * @param now    当前时间
     * @return 距下一窗口边界的秒数
     */
    private long retryAfterSeconds(final QuotaWindow window, final LocalDateTime now) {
        final LocalDateTime start = window.windowStart(now);
        final LocalDateTime next;
        switch (window) {
            case MINUTE:
                next = start.plusMinutes(1L);
                break;
            case HOUR:
                next = start.plusHours(1L);
                break;
            case MONTH:
                next = start.plusMonths(1L);
                break;
            default:
                next = start.plusDays(1L);
                break;
        }
        final long millis = Math.max(0L, Duration.between(now, next).toMillis());
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    /**
     * 按 API Key ID 解析限额配置（沿用管理侧 {@code ApiKeyQuotaService} 的索引方式）。
     *
     * @param apiKeyId API Key ID
     * @return 限额配置；索引缺失或读取异常时返回 {@code null}（调用方按无限制放行）
     */
    private ApiKey resolveApiKey(final String apiKeyId) {
        if (apiKeyService == null) {
            return null;
        }
        try {
            final String keyHash = apiKeyService.getKeyIdIndex().get(apiKeyId);
            if (keyHash == null) {
                return null;
            }
            return apiKeyService.getApiKeyCache().get(keyHash);
        } catch (Exception e) {
            log.warn("读取 API Key 限额配置失败，按无限制放行: apiKeyId={}, error={}", apiKeyId, e.toString());
            return null;
        }
    }
}
