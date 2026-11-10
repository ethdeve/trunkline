package org.unreal.modelrouter.auth.security.quota;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.persistence.jpa.entity.QuotaLedgerEntity;
import org.unreal.modelrouter.persistence.jpa.repository.QuotaLedgerRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 配额账本服务（v3.1 PR-1 内存账本 + PR-3 Redis 分布式计数）。
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li><b>热路径纯内存 / Redis</b>：{@link #reserve(QuotaRequest)} / {@link #settle(QuotaSettlement)}
 *       只操作计数后端（{@link LocalCounterBackend} 内存 {@code LongAdder} 或
 *       {@link RedisCounterBackend} 的 Lua 原子累加），不做任何数据库写入，避免给请求链路增加延迟。</li>
 *   <li><b>数据库仅作快照</b>：{@link #flush()} 定时（默认 60s）与 {@link #shutdown()}
 *       （{@code @PreDestroy}）时把本地计数增量落库。落库按“增量累加 + 行不存在则插入绝对值”实现，
 *       失败时保留增量待下一轮重试，不会重复计数。Redis 模式下快照<b>仍然写</b>，记录的是本节点
 *       贡献量：它既是重启 / 审计所需的旁路证据，也是降级期间本地视图的基线来源；集群权威计数值
 *       以 Redis 为准（见 {@link #usageStrict(QuotaDimension, QuotaWindow)}）。</li>
 *   <li><b>冷启动续算</b>：本地槽位首次被访问时从数据库读取基线（该维度的历史累计值），
 *       使重启后用量连续；读取失败会抛异常，由 {@link #reserve(QuotaRequest)} 捕获并按 fail-open 处理。</li>
 *   <li><b>分布式计数与降级</b>（v3.1 PR-3）：{@code trunkline.quota.distributed.enabled=true} 且
 *       装配了 {@link QuotaCounterBackend} 时，Redis 为权威计数，本地后端退化为“镜像 + 降级兜底”：
 *       <ul>
 *         <li>写入路径：先记本地镜像（保证降级视图与快照完整），再以 Lua 原子累加写 Redis；
 *             Redis 失败时把增量登记为“待补写”，下一次成功写入时合并补写，保证降级期间计数不丢；</li>
 *         <li>读取路径：优先读 Redis（跨实例一致），Redis 不可用时按
 *             {@code trunkline.quota.distributed.degrade-to-local} 决定是否回退到本地视图 / 数据库快照；</li>
 *         <li>任何 Redis 异常都不会抛给调用方：只打告警日志、累加降级指标并标记
 *             {@link #isDegraded() degraded}，放行/拒绝仍由 {@code trunkline.quota.fail-open} 决定；</li>
 *         <li>超时属“结果未知”（命令可能已生效）：不重试同一增量，宁可少计也不重复计——
 *             少计偏向放行，与默认 {@code fail-open=true} 的安全取向一致。</li>
 *       </ul>
 *   </li>
 *   <li><b>同步阻塞的代价与边界</b>（issue #105 更新）：PR-2 把 {@code reserve}/{@code settle}
 *       钉在同步签名上，Redis 调用因此使用 {@code Mono.block(timeout)}（默认 50ms），
 *       热路径最坏阻塞 {@code distributed.timeout}，代价换来“零签名变更 + 确定性降级”。
 *       但 {@code Mono.block()} 在 Reactor 非阻塞线程（Netty 事件循环
 *       {@code reactor-http-nio-*}）上会被直接拒绝并抛 {@link IllegalStateException}，
 *       因此阻塞调用必须留在可以阻塞的线程上：
 *       <ul>
 *         <li>{@code reserve} 路径：唯一生产入口是
 *             {@code ServiceRequestHandler}（{@code Mono.defer(...).subscribeOn(Schedulers.boundedElastic())}），
 *             已在 {@code boundedElastic} 上执行，保持同步阻塞语义不变；</li>
 *         <li>{@code settle} 路径：调用方位于 {@code doOnError}/{@code doOnCancel}/
 *             {@code doOnComplete} 等 Netty 信号回调，{@link #settle(QuotaSettlement)} 检测到
 *             非阻塞线程时把工作整体交给 {@code boundedElastic}（fire-and-forget，见该方法注释），
 *             事件循环不再等待 Redis，冲正量也不会再被静默丢弃；</li>
 *         <li>观测面 {@code QuotaMonitoringController#getUsage}：改为返回 {@code Mono} 并把读数
 *             切到 {@code boundedElastic}，与 {@code reserve} 属同一类“显式移交”。</li>
 *       </ul>
 *       仍属同步阻塞的只有 {@code reserve} / {@code reserveWithLimits} / {@code usageStrict} 本身，
 *       它们要求调用方已在可阻塞线程上（生产入口均满足）。</li>
 *   <li><b>绝不抛出</b>：{@link #reserve(QuotaRequest)} / {@link #settle(QuotaSettlement)} /
 *       查询 / 清理接口都不会把异常抛给调用方；账本不可用时按
 *       {@code trunkline.quota.fail-open}（默认 true）放行并标记 {@code degraded=true}。
 *       唯一例外是 {@link #usageStrict(QuotaDimension, QuotaWindow)}（v3.1 PR-2 新增，供限额判定
 *       区分“零用量”与“读不到用量”），它把读取异常抛给调用方，由调用方按 fail-open 处理。</li>
 *   <li><b>多实例语义</b>：默认（{@code distributed.enabled=false}）仅保证单实例内计数精确；
 *       开启分布式计数后额度由 Redis 共享，本地计数仅在降级期间作为兜底视图。</li>
 * </ul>
 *
 * <p>{@code trunkline.quota.enabled=false}（默认）时所有方法立即返回、不访问数据库与 Redis。</p>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Slf4j
@Service
public class QuotaLedgerService {

    /** 过期行清理调度表达式：每小时第 23 分钟执行（避开整点高峰） */
    private static final String CLEANUP_CRON = "0 23 * * * ?";

    /** 结算 Redis 补写重试队列容量（超出后丢弃并计 dropped 指标；pending 仍保留在本地后端） */
    private static final int SETTLE_RETRY_QUEUE_CAPACITY = 4096;

    /** 单次重试调度最多处理的键数（避免单次任务阻塞过久） */
    private static final int SETTLE_RETRY_BATCH = 512;

    /** Redis 计数后端不可用（连接失败 / 命令错误），已降级为本地计数 */
    public static final String REASON_REDIS_UNAVAILABLE = "quota-counter-redis-unavailable";

    /** Redis 计数命令超时（结果未知），已降级为本地计数 */
    public static final String REASON_REDIS_TIMEOUT = "quota-counter-redis-timeout";

    /** 开启分布式计数但未装配计数后端 Bean（配置错误），已降级为本地计数 */
    public static final String REASON_REDIS_NOT_CONFIGURED = "quota-counter-redis-not-configured";

    private final QuotaLedgerRepository repository;
    private final QuotaProperties properties;
    private final Clock clock;

    /** 本地计数后端：始终存在（本地模式为唯一后端，分布式模式下为镜像 + 降级兜底） */
    private final LocalCounterBackend localBackend;

    /** 分布式计数后端；{@code null} 表示未启用或未装配 */
    private final QuotaCounterBackend distributedBackend;

    /** 是否使用分布式后端作为权威计数 */
    private final boolean distributed;

    private final QuotaCounterMetrics metrics;

    /** 是否处于降级状态（最近一次分布式操作失败） */
    private volatile boolean degraded;

    /** 降级原因，未降级时为空串 */
    private volatile String degradedReason = "";

    /**
     * 结算 Redis 补写重试队列：存放“结算时 Redis 补写失败”的计数键。
     * 本地增量已写入本地后端，失败的 Redis 增量也已转入该键的 pending，由后台调度重试补写，
     * 避免 fire-and-forget 的 settlement 在 Redis 抖动后静默丢失跨实例权威计数。
     */
    private final BlockingQueue<QuotaCounterKey> settleRetryQueue =
            new LinkedBlockingQueue<>(SETTLE_RETRY_QUEUE_CAPACITY);

    /**
     * Spring 使用的主构造器。
     *
     * @param repository         账本仓库
     * @param properties         账本配置
     * @param metrics            配额计数指标（记录后端类型 / 降级次数 / Redis 命令耗时）
     * @param distributedBackends 分布式计数后端提供者（仅在
     *                            {@code trunkline.quota.distributed.enabled=true} 时存在 Bean）
     */
    @Autowired
    public QuotaLedgerService(final QuotaLedgerRepository repository,
                              final QuotaProperties properties,
                              final QuotaCounterMetrics metrics,
                              final ObjectProvider<QuotaCounterBackend> distributedBackends) {
        this(repository, properties, Clock.systemDefaultZone(),
            distributedBackends == null ? null : distributedBackends.getIfAvailable(), metrics);
    }

    /**
     * 全量构造器（测试与显式装配使用）。
     *
     * @param repository          账本仓库
     * @param properties          账本配置
     * @param clock               时钟
     * @param distributedBackend  分布式计数后端，{@code null} 表示只用本地后端
     * @param metrics             指标收集器，{@code null} 时使用空实现
     */
    public QuotaLedgerService(final QuotaLedgerRepository repository,
                              final QuotaProperties properties,
                              final Clock clock,
                              final QuotaCounterBackend distributedBackend,
                              final QuotaCounterMetrics metrics) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics == null ? QuotaCounterMetrics.noop() : metrics;
        this.localBackend = new LocalCounterBackend(this::loadBaseline);
        this.distributedBackend = distributedBackend;
        this.distributed = properties.isEnabled() && properties.distributedEnabled() && distributedBackend != null;
        if (properties.isEnabled() && properties.distributedEnabled() && distributedBackend == null) {
            this.degraded = true;
            this.degradedReason = REASON_REDIS_NOT_CONFIGURED;
            this.metrics.recordDegradation(REASON_REDIS_NOT_CONFIGURED);
            log.warn("已开启配额分布式计数但未装配 QuotaCounterBackend，账本保持本地模式: reason={}",
                REASON_REDIS_NOT_CONFIGURED);
        }
        this.metrics.recordBackend(this.distributed);
        this.metrics.registerSettleRetryQueueGauge(settleRetryQueue::size);
        log.info("配额账本初始化: enabled={}, backend={}, failOpen={}, windows={}", properties.isEnabled(),
            backendName(), properties.isFailOpen(), properties.enabledWindows());
    }

    /**
     * 可注入时钟的构造器（本地模式），便于测试固定时间窗边界。
     *
     * @param repository 账本仓库
     * @param properties 账本配置
     * @param clock      时钟
     */
    public QuotaLedgerService(final QuotaLedgerRepository repository,
                              final QuotaProperties properties,
                              final Clock clock) {
        this(repository, properties, clock, null, null);
    }

    /**
     * 本地模式构造器（使用系统时钟）。
     *
     * @param repository 账本仓库
     * @param properties 账本配置
     */
    public QuotaLedgerService(final QuotaLedgerRepository repository, final QuotaProperties properties) {
        this(repository, properties, Clock.systemDefaultZone(), null, null);
    }

    /**
     * 账本是否启用。
     *
     * @return {@code trunkline.quota.enabled} 的值，默认 {@code false}
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 当前是否使用分布式（Redis）计数后端作为权威计数。
     *
     * @return 启用且已装配分布式后端时返回 {@code true}
     */
    public boolean isDistributed() {
        return distributed;
    }

    /**
     * 当前权威计数后端名称。
     *
     * @return {@code redis}（分布式模式）或 {@code local}
     */
    public String backendName() {
        return distributed ? distributedBackend.name() : LocalCounterBackend.NAME;
    }

    /**
     * 是否处于降级状态（最近一次分布式计数操作失败，或开启分布式计数但未装配后端）。
     *
     * @return 降级时返回 {@code true}
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * 降级原因。
     *
     * @return 降级原因，未降级时为空串
     */
    public String degradedReason() {
        return degradedReason;
    }

    /**
     * 预留一次配额：对全部已启用窗口原子累加请求数（+1）与预估 token。
     *
     * <p>本方法不会抛出异常：账本未启用时直接返回“未启用”决策；账本读写异常时按
     * {@code trunkline.quota.fail-open} 返回放行（默认）或拒绝（fail-closed）并标记 {@code degraded=true}；
     * 分布式计数不可用时降级为本地计数，原因见 {@link #degradedReason()}。</p>
     *
     * @param request 预留请求，可为 {@code null}
     * @return 预留决策
     */
    public QuotaDecision reserve(final QuotaRequest request) {
        if (!isEnabled()) {
            return QuotaDecision.disabled();
        }
        if (request == null) {
            return degrade(QuotaDecision.REASON_INVALID_REQUEST);
        }
        try {
            final List<QuotaWindow> windows = properties.enabledWindows();
            if (windows.isEmpty()) {
                // 启用但未配置任何窗口：等同于未启用，不产生任何记账
                return QuotaDecision.disabled();
            }
            final LocalDateTime now = LocalDateTime.now(clock);
            final QuotaDimension dimension = request.dimension();
            final long tokens = request.estimatedTokens();
            if (!distributed) {
                for (final QuotaWindow window : windows) {
                    localBackend.incrementLocal(keyOf(dimension, window, now), 1L, tokens);
                }
                return QuotaDecision.allow();
            }
            String failure = null;
            for (final QuotaWindow window : windows) {
                final QuotaCounterKey key = keyOf(dimension, window, now);
                if (properties.isFailOpen()) {
                    // fail-open：本次请求一定放行，先记本地镜像（降级读数、快照与补偿记账都依赖它）
                    localBackend.incrementLocal(key, 1L, tokens);
                }
                if (failure != null) {
                    // 本轮已确认 Redis 不可用：剩余窗口不再逐条尝试，避免超时叠加放大请求延迟；
                    // 增量登记为待补写，Redis 恢复后合并补写（不丢计数）
                    if (properties.isFailOpen()) {
                        localBackend.addPending(key, 1L, tokens);
                    }
                    continue;
                }
                try {
                    publish(key, 1L, tokens, properties.isFailOpen());
                } catch (Exception e) {
                    failure = classify(e);
                    if (degraded) {
                        log.debug("Redis 计数写入持续失败: apiKeyId={}, window={}, reason={}, error={}",
                            dimension.apiKeyId(), window, failure, e.toString());
                    } else {
                        log.warn("Redis 计数写入失败，降级为本地计数: apiKeyId={}, window={}, reason={}, error={}",
                            dimension.apiKeyId(), window, failure, e.toString());
                    }
                    continue;
                }
                if (!properties.isFailOpen()) {
                    // fail-closed：只有权威计数写入成功才记本地账，保证“被拒绝的请求不计账”
                    localBackend.incrementLocal(key, 1L, tokens);
                }
            }
            if (failure != null) {
                metrics.recordDegradation(failure);
                markDegraded(failure);
                return degrade(failure);
            }
            clearDegraded();
            return QuotaDecision.allow();
        } catch (Exception e) {
            log.warn("配额账本 reserve 异常，按 fail-open={} 处理: apiKeyId={}, error={}",
                properties.isFailOpen(), request.dimension().apiKeyId(), e.toString());
            return degrade(QuotaDecision.REASON_LEDGER_UNAVAILABLE);
        }
    }

    /**
     * 限额 CAS 预留：对每个启用窗口原子「检查 + 累加」，任一窗口超限则返回该窗口的违规结果。
     *
     * <p>本地模式使用 {@link LocalCounterBackend#incrementLocalWithLimit}（槽位锁）；
     * 分布式模式使用 Redis Lua {@link RedisCounterBackend#INCREMENT_WITH_LIMIT_SCRIPT}
     * （超限由脚本回滚，返回空结果）。Redis 失败时按 fail-open 降级为普通预留。</p>
     *
     * <p><b>多窗口原子性（issue #120）</b>：窗口之间无法做跨键原子提交（Redis 计数键按维度分散在
     * 不同 hash slot，单脚本多键提交不兼容集群部署），因此采用「逐窗口 CAS + 超限时冲正」——
     * 后续窗口被拒时，对前面已提交的窗口逐个撤销本请求的增量（本地 {@code -1/-tokens} +
     * 分布式 {@code publish(-1/-tokens)}，含待补写增量的抵消）。每个窗口的准入判定仍是原子的，
     * 并发突发不会超放；被拒请求不留残量。Redis 命令超时属“结果未知”，该窗口不做冲正
     * （避免误减他人计数导致超放），残量留到窗口过期回收。</p>
     *
     * @param request 预留请求
     * @param limits  限额集合（各窗口请求数 / token 上限，{@code <= 0} 表示该窗口不限制；
     *                对齐上游 3.4.0：HOUR / DAY / MONTH / MINUTE 四级窗口均参与判定）
     * @return 超限结果；成功预留或账本未启用时为空
     */
    public Optional<QuotaLimitViolation> reserveWithLimits(final QuotaRequest request,
                                                           final QuotaLimitSet limits) {
        if (!isEnabled() || request == null || limits == null) {
            return Optional.empty();
        }
        try {
            final List<QuotaWindow> windows = properties.enabledWindows();
            if (windows.isEmpty()) {
                return Optional.empty();
            }
            final LocalDateTime now = LocalDateTime.now(clock);
            final QuotaDimension dimension = request.dimension();
            final long tokens = Math.max(0L, request.estimatedTokens());
            final List<ReserveCommit> committed = new ArrayList<>();
            for (final QuotaWindow window : windows) {
                final long maxRequests = limits.maxRequests(window);
                final long maxTokens = limits.maxTokens(window);
                if (maxRequests <= 0L && maxTokens <= 0L) {
                    // 该窗口无有效限额：普通累加，保证计数连续
                    final QuotaCounterKey key = keyOf(dimension, window, now);
                    if (!distributed) {
                        localBackend.incrementLocal(key, 1L, tokens);
                        committed.add(new ReserveCommit(key, true, false));
                    } else {
                        try {
                            publish(key, 1L, tokens, true);
                            committed.add(new ReserveCommit(key, false, true));
                        } catch (Exception e) {
                            markDegraded(classify(e));
                            // 明确失败时增量已放回待补写（冲正可与之抵消）；超时结果未知，不冲正
                            if (!isTimeout(e)) {
                                committed.add(new ReserveCommit(key, false, true));
                            }
                        }
                    }
                    continue;
                }
                final QuotaCounterKey key = keyOf(dimension, window, now);
                if (!distributed) {
                    final long[] totals =
                            localBackend.incrementLocalWithLimit(key, 1L, tokens, maxRequests, maxTokens);
                    if (totals == null) {
                        rollbackCommitted(committed, tokens);
                        return buildLimitViolation(window, maxRequests, maxTokens, key, now);
                    }
                    committed.add(new ReserveCommit(key, true, false));
                    continue;
                }
                try {
                    final long[] result = executeRedis(() ->
                            distributedBackend.incrementWithLimit(key, 1L, tokens, maxRequests, maxTokens));
                    if (result == null) {
                        // Lua 已回滚
                        rollbackCommitted(committed, tokens);
                        return buildLimitViolation(window, maxRequests, maxTokens, key, now);
                    }
                    localBackend.incrementLocal(key, 1L, tokens);
                    committed.add(new ReserveCommit(key, true, true));
                    clearDegraded();
                } catch (Exception e) {
                    final String reason = classify(e);
                    markDegraded(reason);
                    metrics.recordDegradation(reason);
                    if (properties.isFailOpen()) {
                        // 降级：回退普通预留，避免 Redis 故障时误杀
                        localBackend.incrementLocal(key, 1L, tokens);
                        localBackend.addPending(key, 1L, tokens);
                        // 明确失败：待补写增量由冲正的 publish(-1) 抵消；超时结果未知，不冲正
                        committed.add(new ReserveCommit(key, true, !isTimeout(e)));
                    } else {
                        rollbackCommitted(committed, tokens);
                        return buildLimitViolation(window, maxRequests, maxTokens, key, now);
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("配额 CAS reserveWithLimits 异常，按 fail-open 处理: apiKeyId={}, error={}",
                    request.dimension().apiKeyId(), e.toString());
            return Optional.empty();
        }
    }


    /**
     * 多窗口预留中一笔已提交的累加（issue #120 冲正用）。
     *
     * @param key              计数键
     * @param localIncremented 本地累计是否已写入（冲正时用 {@code incrementLocalIfPresent(-1, -tokens)} 撤销）
     * @param redisRollback    是否需要向分布式后端冲正（{@code publish(-1, -tokens)}，
     *                         含“放回待补写增量”的抵消；结果未知的超时写入不冲正，避免误减他人计数）
     */
    private record ReserveCommit(QuotaCounterKey key, boolean localIncremented, boolean redisRollback) {
    }

    /**
     * 冲正多窗口预留中已提交的累加（issue #120：后续窗口被拒时撤销前面窗口的本笔增量）。
     *
     * <p>只撤销本请求真正写入的部分：本地写过才做 {@code -1/-tokens}，分布式冲正走
     * {@link #publish}（把待补写增量与 {@code -1} 合并后一次写出，或互相抵消为零不发命令）。
     * 单个窗口冲正失败只记日志，不阻断其余窗口的撤销——账本是旁路能力。</p>
     *
     * @param committed 已提交窗口列表（按提交顺序，冲正按逆序执行）
     * @param tokens    预留的估算 token 数
     */
    private void rollbackCommitted(final List<ReserveCommit> committed, final long tokens) {
        for (int index = committed.size() - 1; index >= 0; index--) {
            final ReserveCommit entry = committed.get(index);
            try {
                if (entry.localIncremented()) {
                    localBackend.incrementLocalIfPresent(entry.key(), -1L, -tokens);
                }
                if (entry.redisRollback()) {
                    publish(entry.key(), -1L, -tokens, true);
                }
            } catch (Exception e) {
                log.warn("配额多窗口预留冲正失败（残量留待窗口过期回收）: window={}, windowStart={}, error={}",
                    entry.key().window(), entry.key().windowStart(), e.toString());
            }
        }
    }

    private Optional<QuotaLimitViolation> buildLimitViolation(final QuotaWindow window,
                                                              final long maxRequests,
                                                              final long maxTokens,
                                                              final QuotaCounterKey key,
                                                              final LocalDateTime now) {
        // CAS 拒绝时计数未包含本笔，used 即当前窗口累计值
        final long[] usage = localBackend.totals(key);
        if (maxRequests > 0L) {
            final long used = usage == null ? maxRequests : usage[0];
            final String metric = requestMetricFor(window);
            return Optional.of(new QuotaLimitViolation(metric, window, maxRequests,
                    Math.max(0L, used), retryAfterSeconds(window, now)));
        }
        final long usedTokens = usage == null ? maxTokens : usage[1];
        return Optional.of(new QuotaLimitViolation(tokenMetricFor(window),
                window, maxTokens, Math.max(0L, usedTokens), retryAfterSeconds(window, now)));
    }

    /** 取窗口对应的请求数限额指标名（对齐上游 3.4.0 四级窗口）。 */
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

    private long retryAfterSeconds(final QuotaWindow window, final LocalDateTime now) {
        final LocalDateTime start = window.windowStart(now);
        final LocalDateTime next = switch (window) {
            case MINUTE -> start.plusMinutes(1L);
            case HOUR -> start.plusHours(1L);
            case MONTH -> start.plusMonths(1L);
            default -> start.plusDays(1L);
        };
        final long millis = Math.max(0L, java.time.Duration.between(now, next).toMillis());
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    /**
     * 结算一次配额：按 {@code actual - estimated} 冲正 token，调用失败时回滚整笔预留。
     *
     * <p>只冲正已存在的本地槽位（不会为结算新建槽位、不访问数据库）；异常被吞掉并记录日志，
     * 不会影响调用方结果。分布式模式下同时把冲正量补写到 Redis（失败仅告警 + 降级标记）。</p>
     *
     * <p><b>线程语义（issue #105）</b>：本方法本身就是 fire-and-forget——签名为 {@code void}、
     * 吞掉所有异常、返回值不含任何结算结果，因此当调用方位于 Reactor 非阻塞线程
     * （Netty 事件循环上的 {@code doOnError} / {@code doOnCancel} / {@code doOnComplete} 回调）时，
     * 把工作整体交给 {@link Schedulers#boundedElastic()} 执行后立即返回。</p>
     *
     * <p>移交不改变调用方可见的语义：内联执行的返回值与异常都是“无”与“无”，异步执行同样是
     * “无”与“无”（异常在 {@link #settleBlocking(QuotaSettlement)} 内部已消化）。不移交则有实际损失：
     * 事件循环上 {@code Mono.block()} 会抛 {@link IllegalStateException}，Redis 冲正<b>永久静默丢失</b>，
     * 分布式计数只增不减地漂移。非 Reactor 线程（普通调用线程、{@code boundedElastic}）仍内联执行，
     * 保证“方法返回时冲正已写入”，既有测试依赖这一确定性。</p>
     *
     * @param settlement 结算信息，可为 {@code null}（忽略）
     */
    public void settle(final QuotaSettlement settlement) {
        if (!isEnabled() || settlement == null) {
            return;
        }
        if (Schedulers.isInNonBlockingThread()) {
            Schedulers.boundedElastic().schedule(() -> settleBlocking(settlement));
            return;
        }
        settleBlocking(settlement);
    }

    /**
     * 结算的实际实现（同步阻塞：分布式模式下会以 {@code Mono.block(timeout)} 补写 Redis）.
     *
     * <p>必须运行在可阻塞线程上；由 {@link #settle(QuotaSettlement)} 负责选择执行线程。</p>
     *
     * @param settlement 结算信息，可为 {@code null}（忽略）
     */
    private void settleBlocking(final QuotaSettlement settlement) {
        if (!isEnabled() || settlement == null) {
            return;
        }
        try {
            final LocalDateTime now = LocalDateTime.now(clock);
            final QuotaDimension dimension = settlement.dimension();
            final long requestDelta = settlement.requestDelta();
            final long tokenDelta = settlement.tokenDelta();
            if (requestDelta == 0L && tokenDelta == 0L) {
                return;
            }
            String failure = null;
            for (final QuotaWindow window : properties.enabledWindows()) {
                final QuotaCounterKey key = keyOf(dimension, window, now);
                if (localBackend.incrementLocalIfPresent(key, requestDelta, tokenDelta) == null) {
                    continue;
                }
                if (!distributed) {
                    continue;
                }
                try {
                    publish(key, requestDelta, tokenDelta, true);
                } catch (Exception e) {
                    failure = classify(e);
                    log.warn("Redis 计数结算补写失败，降级为本地计数: apiKeyId={}, window={}, reason={}, error={}",
                        dimension.apiKeyId(), window, failure, e.toString());
                    // 本地增量已写入；失败的 Redis 增量已转入该键 pending，入重试队列后台补写
                    metrics.recordSettlePublishFailure();
                    enqueueSettleRetry(key);
                }
            }
            if (failure != null) {
                metrics.recordDegradation(failure);
                markDegraded(failure);
            }
        } catch (Exception e) {
            log.warn("配额账本 settle 异常（忽略，不影响请求结果）: apiKeyId={}, error={}",
                settlement.dimension().apiKeyId(), e.toString());
        }
    }

    /**
     * 把结算补写失败的计数键加入重试队列（队列满则丢弃并计 dropped 指标；
     * 失败的 Redis 增量仍保留在该键的本地 pending，待下次流量或重试补写）。
     *
     * @param key 失败的计数键
     */
    private void enqueueSettleRetry(final QuotaCounterKey key) {
        if (!settleRetryQueue.offer(key)) {
            metrics.recordSettleRetryDropped();
            log.warn("配额结算重试队列已满，丢弃重试（pending 仍保留在本地后端）: apiKeyId={}, window={}",
                key.dimension().apiKeyId(), key.window());
        }
    }

    /**
     * 结算补写后台重试（定时调度，仅分布式模式生效）。
     *
     * <p>只做 Redis 补写：取出失败的计数键，按 {@code publish(key, 0, 0, false)} 把该键在本地后端
     * 累积的 pending（含此前失败的结算增量）一次性刷到 Redis；本地增量在结算时已写入，这里<b>不</b>
     * 重复累加。仍失败则重新入队（受队列容量约束），直到 Redis 恢复或队列溢出。</p>
     *
     * @return 本轮成功补写的键数
     */
    @Scheduled(fixedDelayString = "${trunkline.quota.settle-retry-interval-seconds:5}", timeUnit = TimeUnit.SECONDS)
    public int retrySettlements() {
        if (!isEnabled() || !distributed) {
            return 0;
        }
        int processed = 0;
        // 失败键先收集、批次结束后统一回插：避免“出队即重入队”在同一批次内自我循环（Redis 持续不可用时会打转刷爆日志）
        final List<QuotaCounterKey> failed = new ArrayList<>();
        QuotaCounterKey key;
        while (processed < SETTLE_RETRY_BATCH && (key = settleRetryQueue.poll()) != null) {
            try {
                publish(key, 0L, 0L, false);
                processed++;
            } catch (Exception e) {
                metrics.recordSettleRetryFailure();
                log.warn("配额结算重试仍失败（批次结束后重新入队）: apiKeyId={}, window={}, error={}",
                    key.dimension().apiKeyId(), key.window(), e.toString());
                failed.add(key);
            }
        }
        for (final QuotaCounterKey retryKey : failed) {
            enqueueSettleRetry(retryKey);
        }
        return processed;
    }

    /**
     * 应用关闭前尽力把重试队列中的 pending 补写一遍（不阻塞过久：队列为空或无 pending 时立即返回）。
     */
    private void drainSettleRetries() {
        if (!distributed) {
            settleRetryQueue.clear();
            return;
        }
        QuotaCounterKey key;
        while ((key = settleRetryQueue.poll()) != null) {
            try {
                publish(key, 0L, 0L, false);
            } catch (Exception e) {
                log.warn("配额结算关闭前补写失败（pending 随进程退出丢弃）: apiKeyId={}, window={}, error={}",
                    key.dimension().apiKeyId(), key.window(), e.toString());
            }
        }
    }

    /**
     * 查询某一维度在当前窗口内的用量。
     *
     * @param dimension 维度，{@code null} 时返回 {@link Optional#empty()}
     * @param window    窗口类型，{@code null} 时返回 {@link Optional#empty()}
     * @return 用量；账本未启用、均无数据或读取失败时返回 {@link Optional#empty()}（不会抛出异常）
     */
    public Optional<QuotaUsage> usage(final QuotaDimension dimension, final QuotaWindow window) {
        if (!isEnabled() || dimension == null || window == null) {
            return Optional.empty();
        }
        try {
            return usageStrict(dimension, window);
        } catch (Exception e) {
            log.warn("配额账本 usage 查询失败: apiKeyId={}, error={}", dimension.apiKeyId(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * 查询某一维度在当前窗口内的用量，读取失败时抛出异常（v3.1 PR-2 限额判定专用）。
     *
     * <p>与 {@link #usage(QuotaDimension, QuotaWindow)} 的唯一差异是故障语义：后者把“账本不可用”
     * 折叠成 {@link Optional#empty()}，调用方无法区分“窗口内确实零用量”与“读不到用量”。限额判定
     * 必须区分这两者——把读取失败当成零用量会凭空得出“仍有额度”的结论（甚至反过来误判超限），
     * 因此这里把异常抛给调用方，由 {@code QuotaEnforcementService} 统一按 fail-open 放行。</p>
     *
     * <p>分布式模式下优先读 Redis（跨实例权威值，并加上本节点尚未补写的增量）；Redis 无该桶时
     * 回退数据库快照；Redis 读取失败时按 {@code degrade-to-local} 回退本地视图或数据库快照，
     * 不降级（{@code false}）时抛出异常。</p>
     *
     * @param dimension 维度
     * @param window    窗口类型
     * @return 用量；无记录时返回 {@link Optional#empty()}
     * @throws IllegalStateException 账本未启用，或参数缺失，或内存 / Redis / 数据库读取失败
     */
    public Optional<QuotaUsage> usageStrict(final QuotaDimension dimension, final QuotaWindow window) {
        if (!isEnabled()) {
            throw new IllegalStateException("配额账本未启用: " + QuotaDecision.REASON_DISABLED);
        }
        if (dimension == null || window == null) {
            throw new IllegalStateException("配额账本查询缺少维度或窗口: " + QuotaDecision.REASON_INVALID_REQUEST);
        }
        final LocalDateTime windowStart = window.windowStart(LocalDateTime.now(clock));
        final QuotaCounterKey key = new QuotaCounterKey(dimension, window, windowStart);
        if (!distributed) {
            return usageFromLocal(key);
        }
        try {
            final Optional<long[]> remote = executeRedis(() -> distributedBackend.read(key));
            clearDegraded();
            if (remote.isPresent()) {
                return Optional.of(toUsage(key, withPending(key, remote.get())));
            }
            // Redis 无该桶（未记账 / 已过期 / 数据被清）→ 与本地模式一致回退数据库快照
            return usageFromDatabase(key);
        } catch (Exception e) {
            final String reason = classify(e);
            metrics.recordDegradation(reason);
            if (degraded) {
                log.debug("Redis 计数读取持续失败: apiKeyId={}, window={}, reason={}, error={}",
                    dimension.apiKeyId(), window, reason, e.toString());
            } else {
                log.warn("Redis 计数读取失败，降级读数: apiKeyId={}, window={}, windowStart={}, reason={},"
                    + " degradeToLocal={}, error={}", dimension.apiKeyId(), window, windowStart, reason,
                    properties.distributedDegradeToLocal(), e.toString());
            }
            markDegraded(reason);
            if (!properties.distributedDegradeToLocal()) {
                throw new IllegalStateException("配额分布式计数读取失败（degrade-to-local=false）: " + reason, e);
            }
            return usageFromLocal(key);
        }
    }

    /**
     * 查询某个 API Key 的全部账本用量（所有维度、所有窗口）。
     *
     * <p><b>数据来源取舍</b>：这是管理侧 / 审计视图，需要一次列出某个 Key 的所有窗口与维度，
     * 而在 Redis 中按维度枚举需要全量 {@code SCAN}（成本不可控），因此分布式模式下仍以
     * “本地镜像 + 数据库快照”为数据源；需要跨实例权威值时使用
     * {@link #usage(QuotaDimension, QuotaWindow)}（单窗口精确读 Redis）。</p>
     *
     * <p>内存态优先：与本地槽位同键的数据库快照会被跳过，避免同一窗口重复计数。
     * 数据库读取失败时降级为“仅返回内存态”并记录日志，不抛出异常。</p>
     *
     * @param apiKeyId API Key ID，{@code null} 或空串、账本未启用时返回空列表
     * @return 用量列表（按窗口类型、窗口起点降序排序）
     */
    public List<QuotaUsage> usageAll(final String apiKeyId) {
        if (!isEnabled() || apiKeyId == null || apiKeyId.isEmpty()) {
            return List.of();
        }
        final List<QuotaUsage> result = usageAllFromMemory(apiKeyId);
        final Set<QuotaCounterKey> inMemoryKeys = new LinkedHashSet<>();
        for (final QuotaUsage usage : result) {
            inMemoryKeys.add(new QuotaCounterKey(usage.dimension(), usage.window(), usage.windowStart()));
        }
        try {
            for (final QuotaLedgerEntity row : repository.findByApiKeyId(apiKeyId)) {
                if (row.getWindowStart() == null) {
                    continue;
                }
                final Optional<QuotaWindow> window = QuotaWindow.parse(row.getWindowType());
                if (window.isEmpty()) {
                    continue;
                }
                final QuotaCounterKey key = new QuotaCounterKey(dimensionOf(row), window.get(), row.getWindowStart());
                if (inMemoryKeys.contains(key)) {
                    continue;
                }
                result.add(toUsage(key, row));
            }
        } catch (Exception e) {
            log.warn("配额账本 usageAll 读库失败，降级为仅返回内存态: apiKeyId={}, error={}", apiKeyId, e.toString());
        }
        result.sort((left, right) -> {
            final int byWindow = Integer.compare(left.window().ordinal(), right.window().ordinal());
            if (byWindow != 0) {
                return byWindow;
            }
            return right.windowStart().compareTo(left.windowStart());
        });
        return result;
    }

    /**
     * 仅从本地内存计数聚合用量（不查库）.
     *
     * <p>管理台告警/概览使用：避免 Redis/H2 异常时同步读库拖死 HTTP 线程。</p>
     *
     * @param apiKeyId API Key ID
     * @return 内存态用量列表
     */
    public List<QuotaUsage> usageAllFromMemory(final String apiKeyId) {
        if (!isEnabled() || apiKeyId == null || apiKeyId.isEmpty()) {
            return List.of();
        }
        final List<QuotaUsage> result = new ArrayList<>();
        for (final QuotaCounterKey key : localBackend.keys()) {
            if (!apiKeyId.equals(key.dimension().apiKeyId())) {
                continue;
            }
            final long[] totals = localBackend.totals(key);
            if (totals == null) {
                continue;
            }
            result.add(toUsage(key, totals));
        }
        return result;
    }

    /**
     * 重置某个 API Key 的账本（本地槽位 + 数据库快照 + 分布式计数），用于配额重置。
     *
     * <p>清理会覆盖该 Key 的全部窗口（比“仅重置当日”更彻底），异常只记录日志不抛出；
     * 账本未启用时为无操作。</p>
     *
     * @param apiKeyId API Key ID，{@code null}、空串或账本未启用时不做任何处理
     * @return 清理条数（本地槽位 + 数据库行 + 分布式计数桶）
     */
    public int reset(final String apiKeyId) {
        if (!isEnabled() || apiKeyId == null || apiKeyId.isEmpty()) {
            return 0;
        }
        int cleared = localBackend.resetLocal(apiKeyId);
        try {
            cleared += repository.deleteByApiKeyId(apiKeyId);
        } catch (Exception e) {
            log.warn("配额账本 reset 删除数据库快照失败（本地槽位已清空）: apiKeyId={}, error={}",
                apiKeyId, e.toString());
        }
        if (distributed) {
            try {
                cleared += (int) nullToZero(executeRedis(() -> distributedBackend.reset(apiKeyId)));
            } catch (Exception e) {
                final String reason = classify(e);
                metrics.recordDegradation(reason);
                markDegraded(reason);
                log.warn("配额账本 reset 删除分布式计数失败（本地槽位与快照已清空）: apiKeyId={}, reason={}, error={}",
                    apiKeyId, reason, e.toString());
            }
        }
        log.info("配额账本已重置: apiKeyId={}, 清理条数={}, backend={}", apiKeyId, cleared, backendName());
        return cleared;
    }

    /**
     * 按指定维度重置账本（本地槽位 + 数据库快照 + 分布式计数），用于分组 / 模型 / Key 维度的配额重置。
     *
     * <p>分组与模型维度互斥（一次请求只走其一），因此按非空维度选择重置目标；三者皆空时视为无操作。</p>
     *
     * <p>命名带 {@code ByDimension} 后缀，避免与 {@link #reset(String)} 形成重载歧义
     * （{@code reset(null)} 会因两个引用类型重载而无法编译）。</p>
     *
     * @param scope 维度（按其非空前缀维度重置），{@code null} 或账本未启用时不做任何处理
     * @return 清理条数（本地槽位 + 数据库行 + 分布式计数桶）
     */
    public int resetByDimension(final QuotaDimension scope) {
        if (!isEnabled() || scope == null) {
            return 0;
        }
        if (scope.groupId() != null && !scope.groupId().isEmpty()) {
            return resetByGroupId(scope.groupId());
        }
        if (scope.model() != null && !scope.model().isEmpty()) {
            return resetByModel(scope.model());
        }
        if (scope.apiKeyId() != null && !scope.apiKeyId().isEmpty()) {
            return reset(scope.apiKeyId());
        }
        return 0;
    }

    /**
     * 重置某个分组（模型分组 / 池）的全部账本。
     *
     * @param groupId 分组（池）名称
     * @return 清理条数
     */
    private int resetByGroupId(final String groupId) {
        int cleared = localBackend.resetLocalByGroupId(groupId);
        try {
            cleared += repository.deleteByGroupId(groupId);
        } catch (Exception e) {
            log.warn("配额账本 reset(分组) 删除数据库快照失败（本地槽位已清空）: groupId={}, error={}", groupId, e.toString());
        }
        if (distributed) {
            try {
                cleared += (int) nullToZero(executeRedis(() -> distributedBackend.resetByGroup(groupId)));
            } catch (Exception e) {
                final String reason = classify(e);
                metrics.recordDegradation(reason);
                markDegraded(reason);
                log.warn("配额账本 reset(分组) 删除分布式计数失败（本地槽位与快照已清空）: groupId={}, reason={}, error={}",
                    groupId, reason, e.toString());
            }
        }
        log.info("配额账本已重置(分组): groupId={}, 清理条数={}, backend={}", groupId, cleared, backendName());
        return cleared;
    }

    /**
     * 重置某个模型（已接入模型 / 实例）的全部账本。
     *
     * @param model 模型（实例）名称
     * @return 清理条数
     */
    private int resetByModel(final String model) {
        int cleared = localBackend.resetLocalByModel(model);
        try {
            cleared += repository.deleteByModel(model);
        } catch (Exception e) {
            log.warn("配额账本 reset(模型) 删除数据库快照失败（本地槽位已清空）: model={}, error={}", model, e.toString());
        }
        if (distributed) {
            try {
                cleared += (int) nullToZero(executeRedis(() -> distributedBackend.resetByModel(model)));
            } catch (Exception e) {
                final String reason = classify(e);
                metrics.recordDegradation(reason);
                markDegraded(reason);
                log.warn("配额账本 reset(模型) 删除分布式计数失败（本地槽位与快照已清空）: model={}, reason={}, error={}",
                    model, reason, e.toString());
            }
        }
        log.info("配额账本已重置(模型): model={}, 清理条数={}, backend={}", model, cleared, backendName());
        return cleared;
    }

    /**
     * 按指定维度查询用量（管理台 / 审计视图）。
     *
     * <p><b>分组维度的通配口径</b>（v3.5 治理收口）：{@code scope} 指定了 {@code groupId} 而未指定
     * {@code model} 时，<b>只匹配分组桶行</b>（{@code model} 为空的那一行），不匹配组内成员明细行
     * （{@code model} = 实例名）。这样「按分组聚合」得到的就是该分组的真实用量，不会与成员明细
     * 重复计数。需要组内明细时请改用 {@link #usageByGroupMembers(String)}。</p>
     *
     * <p>内存态优先：与本地槽位同键的数据库快照会被跳过，避免同一窗口重复计数。
     * 数据库读取失败时降级为“仅返回内存态”并记录日志，不抛出异常。</p>
     *
     * @param scope 维度（按其非空前缀维度过滤），{@code null} 或账本未启用时返回空列表
     * @return 用量列表
     */
    public List<QuotaUsage> usageByDimension(final QuotaDimension scope) {
        if (!isEnabled() || scope == null) {
            return List.of();
        }
        return usageByScope(scope, dimension -> matchesScope(dimension, scope));
    }

    /**
     * 查询某分组的<b>聚合</b>用量（只统计分组桶行：{@code groupId} 匹配且 {@code model} 为空）。
     *
     * <p><b>口径定义</b>（v3.5 治理收口）：一次请求会写入两族账本行——预留期写<b>分组桶</b>
     * （{@code group_id = 分组名, model = ""}），选实例后补记<b>成员桶</b>
     * （{@code group_id = 分组名, model = 实例名}）。两者是<b>两行</b>而非同一行，各自独立累加。
     * 因此：</p>
     * <ul>
     *   <li><b>分组聚合</b> = 只取分组桶行（{@link #usageByGroupAggregate(String)}）；</li>
     *   <li><b>组内明细</b> = 只取成员桶行（{@link #usageByGroupMembers(String)}）。</li>
     * </ul>
     * <p>两个口径<b>互斥、不可相加</b>：分组桶已经包含了该分组内所有请求，成员桶只是同一批请求
     * 按实例下钻后的分布。把两者混在一起求和会让分组用量<b>翻倍</b>。</p>
     *
     * <p>本口径与限额判定链路一致：分组级限额读的就是分组桶行（精确 key），
     * 因此页面展示的“分组用量”与“是否触发限额”始终对得上。</p>
     *
     * @param groupId 分组（模型分组 / 池）名称，{@code null} 或空串时返回空列表
     * @return 用量列表（按窗口类型、窗口起点降序排序）
     */
    public List<QuotaUsage> usageByGroupAggregate(final String groupId) {
        if (!isEnabled() || groupId == null || groupId.isEmpty()) {
            return List.of();
        }
        return usageByScope(QuotaDimension.ofGroup(groupId),
            dimension -> groupId.equals(dimension.groupId()) && dimension.model().isEmpty());
    }

    /**
     * 查询某分组的<b>组内明细</b>用量（只统计成员桶行：{@code groupId} 匹配且 {@code model} 非空）。
     *
     * <p>与 {@link #usageByGroupAggregate(String)} <b>互斥</b>：本方法给出该分组内按实例拆分的
     * 用量分布（观测明细），聚合方法给出该分组的总量（治理口径）。两者不可相加，
     * 否则同一批请求会被计两次。</p>
     *
     * @param groupId 分组（模型分组 / 池）名称，{@code null} 或空串时返回空列表
     * @return 用量列表（按窗口类型、窗口起点降序排序）
     */
    public List<QuotaUsage> usageByGroupMembers(final String groupId) {
        if (!isEnabled() || groupId == null || groupId.isEmpty()) {
            return List.of();
        }
        return usageByScope(QuotaDimension.ofGroup(groupId),
            dimension -> groupId.equals(dimension.groupId()) && !dimension.model().isEmpty());
    }

    /**
     * 按过滤器查询用量（{@link #usageByDimension(String)} / {@link #usageByGroupAggregate(String)} /
     * {@link #usageByGroupMembers(String)} 的公共实现）。
     *
     * <p>内存态与数据库快照走<b>同一个过滤器</b>，保证两侧口径一致（否则同一行在内存里被过滤掉、
     * 却从数据库侧漏进来，聚合结果会随部署形态漂移）。</p>
     *
     * @param scope   查询维度（决定数据库侧的取数范围）
     * @param filter  维度过滤器
     * @return 用量列表（按窗口类型、窗口起点降序排序）
     */
    private List<QuotaUsage> usageByScope(final QuotaDimension scope,
                                          final java.util.function.Predicate<QuotaDimension> filter) {
        final List<QuotaUsage> result = new ArrayList<>();
        final Set<QuotaCounterKey> inMemoryKeys = new LinkedHashSet<>();
        for (final QuotaCounterKey key : localBackend.keys()) {
            if (!filter.test(key.dimension())) {
                continue;
            }
            final long[] totals = localBackend.totals(key);
            if (totals == null) {
                continue;
            }
            inMemoryKeys.add(key);
            result.add(toUsage(key, totals));
        }
        try {
            final List<QuotaLedgerEntity> rows = loadRows(scope);
            for (final QuotaLedgerEntity row : rows) {
                if (row.getWindowStart() == null) {
                    continue;
                }
                final Optional<QuotaWindow> window = QuotaWindow.parse(row.getWindowType());
                if (window.isEmpty()) {
                    continue;
                }
                final QuotaCounterKey key = new QuotaCounterKey(dimensionOf(row), window.get(), row.getWindowStart());
                if (inMemoryKeys.contains(key)) {
                    continue;
                }
                if (!filter.test(key.dimension())) {
                    continue;
                }
                result.add(toUsage(key, row));
            }
        } catch (Exception e) {
            log.warn("配额账本 usageByDimension 读库失败，降级为仅返回内存态: scope={}, error={}", scope, e.toString());
        }
        result.sort((left, right) -> {
            final int byWindow = Integer.compare(left.window().ordinal(), right.window().ordinal());
            if (byWindow != 0) {
                return byWindow;
            }
            return right.windowStart().compareTo(left.windowStart());
        });
        return result;
    }

    /**
     * 按查询维度选择数据库侧的取数范围。
     *
     * <p>维度优先级：分组 &gt; 模型 &gt; API Key；三者都为空时取全表（管理台“全量视图”）。</p>
     *
     * @param scope 查询维度
     * @return 账本行列表
     */
    private List<QuotaLedgerEntity> loadRows(final QuotaDimension scope) {
        if (scope.groupId() != null && !scope.groupId().isEmpty()) {
            return repository.findByGroupId(scope.groupId());
        }
        if (scope.model() != null && !scope.model().isEmpty()) {
            return repository.findByModel(scope.model());
        }
        if (scope.apiKeyId() != null && !scope.apiKeyId().isEmpty()) {
            return repository.findByApiKeyId(scope.apiKeyId());
        }
        return repository.findAll();
    }

    /**
     * 判断账本维度是否匹配指定的查询维度。
     *
     * <p><b>分组维度的特殊处理</b>（v3.5 治理收口）：一次请求会写入两族账本行——预留期的
     * <b>分组桶</b>（{@code group_id = 分组名, model = ""}）与选实例后补记的<b>成员桶</b>
     * （{@code group_id = 分组名, model = 实例名}）。因此当查询只指定 {@code groupId} 而未指定
     * {@code model} 时，只匹配<b>分组桶行</b>：否则成员明细会被一并计入，使分组聚合翻倍。
     * 需要组内明细时，调用方应显式指定 {@code model} 或改用
     * {@link #usageByGroupMembers(String)}。</p>
     *
     * <p>其余维度仍按“空值即通配”的前缀语义匹配。</p>
     *
     * @param dimension 账本维度
     * @param scope     查询维度（非空字段参与匹配）
     * @return 全部非空字段均相等时返回 {@code true}
     */
    private static boolean matchesScope(final QuotaDimension dimension, final QuotaDimension scope) {
        if (!scope.apiKeyId().isEmpty() && !scope.apiKeyId().equals(dimension.apiKeyId())) {
            return false;
        }
        if (!scope.groupId().isEmpty() && !scope.groupId().equals(dimension.groupId())) {
            return false;
        }
        if (!scope.model().isEmpty()) {
            return scope.model().equals(dimension.model());
        }
        // 指定了分组但未指定模型：只命中分组桶行（model 为空），排除组内成员明细行
        return scope.groupId().isEmpty() || dimension.model().isEmpty();
    }

    /**
     * 按各级保留期清理过期账本行与计数（默认 minute=1d / hour=2d / day=35d / month=13mo）。
     *
     * <p>同时清理已过期且增量已全部落库（且无待补写 Redis 增量）的本地计数槽位，避免计数无限
     * 增长；仍持有未落库增量的槽位会留到下一轮 {@link #flush()} 之后再清理。Redis 模式下额外
     * 删除过期的 Redis 计数桶。异常只记录日志不抛出。</p>
     *
     * @return 清理的数据库行数（计数清理数量见日志）
     */
    @Scheduled(cron = CLEANUP_CRON)
    public int cleanupExpired() {
        if (!isEnabled()) {
            return 0;
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        // 已启用窗口 + 仍存在计数槽位的窗口（配置变更后可能残留），保证不与 PR-1 的清理范围退化
        final Set<QuotaWindow> windows = new LinkedHashSet<>(properties.enabledWindows());
        for (final QuotaCounterKey key : localBackend.keys()) {
            windows.add(key.window());
        }
        int deleted = 0;
        long purged = 0L;
        for (final QuotaWindow window : windows) {
            deleted += deleteExpiredRows(window, now);
            purged += purgeCounters(window, now);
        }
        if (deleted > 0 || purged > 0) {
            log.info("配额账本保留期清理完成: 数据库行={}, 计数槽位={}, backend={}", deleted, purged, backendName());
        }
        return deleted;
    }

    /**
     * 把本地计数增量落库（定时快照，默认每 60 秒一次）。
     *
     * <p>每个槽位独立短事务：先按增量原子累加，影响行数为 0 时说明行不存在，改为插入绝对值快照；
     * 单个槽位失败只影响该槽位，下一轮 flush 会重试（增量保留在内存中）。</p>
     *
     * @return 成功落库的槽位数
     */
    @Scheduled(fixedDelayString = "${trunkline.quota.flush-interval-seconds:60}", timeUnit = TimeUnit.SECONDS)
    public int flush() {
        if (!isEnabled()) {
            return 0;
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        int written = 0;
        for (final QuotaCounterKey key : localBackend.keys()) {
            if (writeSnapshot(key, now)) {
                written++;
            }
        }
        if (written > 0) {
            log.debug("配额账本快照落库完成: 槽位数={}", written);
        }
        return written;
    }

    /**
     * 应用关闭前最后一次落库，尽量不丢失内存中的增量。
     */
    @PreDestroy
    public void shutdown() {
        if (!isEnabled()) {
            return;
        }
        try {
            final int written = flush();
            log.info("配额账本关闭前落库完成: 槽位数={}", written);
        } catch (Exception e) {
            log.warn("配额账本关闭前落库失败: {}", e.toString());
        }
        drainSettleRetries();
        localBackend.clear();
    }

    /**
     * 读取本地计数（内存镜像优先），无本地计数时回退数据库快照。
     *
     * @param key 计数键
     * @return 用量；无数据时返回 {@link Optional#empty()}
     */
    private Optional<QuotaUsage> usageFromLocal(final QuotaCounterKey key) {
        final long[] totals = localBackend.totals(key);
        if (totals != null) {
            return Optional.of(toUsage(key, totals));
        }
        return usageFromDatabase(key);
    }

    /**
     * 读取数据库快照（读库异常原样抛出，由调用方按 fail-open 处理）。
     *
     * @param key 计数键
     * @return 用量；无记录时返回 {@link Optional#empty()}
     */
    private Optional<QuotaUsage> usageFromDatabase(final QuotaCounterKey key) {
        final QuotaDimension dimension = key.dimension();
        return repository.findByTenantIdAndApiKeyIdAndUserIdAndServiceTypeAndModelAndGroupIdAndWindowTypeAndWindowStart(
                dimension.tenantId(), dimension.apiKeyId(), dimension.userId(), dimension.serviceType(),
                dimension.model(), dimension.groupId(), key.window().name(), key.windowStart())
            .map(row -> toUsage(key, row));
    }

    /**
     * 计算窗口起点与计数键。
     *
     * @param dimension 维度
     * @param window    窗口类型
     * @param now       当前时间
     * @return 计数键
     */
    private static QuotaCounterKey keyOf(final QuotaDimension dimension,
                                         final QuotaWindow window,
                                         final LocalDateTime now) {
        return new QuotaCounterKey(dimension, window, window.windowStart(now));
    }

    /**
     * 把增量（含降级期间累积的待补写增量）发布到分布式后端。
     *
     * <p>成功即返回；失败时向上抛异常由调用方按 fail-open 降级，并恢复此前取出的待补写增量：
     * 明确失败（连接失败 / 脚本错误）时连同本次增量一起放回待补写队列；超时属“结果未知”，
     * 整批放弃以避免重复计数。</p>
     *
     * @param key             计数键
     * @param requests        本次请求数增量
     * @param tokens          本次 token 增量
     * @param registerPending 失败时是否把本次增量登记为待补写（fail-closed 拒绝的请求不计账时为 false）
     */
    private void publish(final QuotaCounterKey key,
                         final long requests,
                         final long tokens,
                         final boolean registerPending) {
        final long[] pending = localBackend.takePending(key);
        final long requestDelta = requests + (pending == null ? 0L : pending[0]);
        final long tokenDelta = tokens + (pending == null ? 0L : pending[1]);
        if (requestDelta == 0L && tokenDelta == 0L) {
            return;
        }
        try {
            executeRedis(() -> distributedBackend.increment(key, requestDelta, tokenDelta));
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                log.warn("Redis 计数命令超时（结果未知），放弃补写该增量以避免重复计数: window={}, windowStart={},"
                    + " requests={}, tokens={}", key.window(), key.windowStart(), requestDelta, tokenDelta);
            } else {
                final long restoreRequests = pending == null ? 0L : pending[0];
                final long restoreTokens = pending == null ? 0L : pending[1];
                localBackend.addPending(key,
                    restoreRequests + (registerPending ? requests : 0L),
                    restoreTokens + (registerPending ? tokens : 0L));
            }
            throw e;
        }
    }

    /**
     * 阻塞执行一次 Redis 命令并统计耗时（超时由 {@code distributed.timeout} 控制）。
     *
     * <p>本方法只能运行在可阻塞线程上：Reactor 会在非阻塞线程上直接拒绝 {@code Mono.block()}，
     * 其异常信息只说明“block 不被支持”，不含任何账本上下文（issue #105 中表现为 Redis 冲正静默丢失）。
     * 因此这里先做显式守卫，抛出可定位的异常——异常信息带上当前线程名与后端名，调用方
     * （{@code reserve} / {@code usageStrict} / {@code reset} / {@code cleanupExpired}）的
     * {@code classify} 会把它归类为 {@link #REASON_REDIS_UNAVAILABLE} 并打出降级告警，
     * 使“误在事件循环上调用”与“Redis 真的不可用”至少在日志里可区分。</p>
     *
     * @param command 命令工厂
     * @param <T>     结果类型
     * @return 命令结果
     * @throws IllegalStateException 当前线程为 Reactor 非阻塞线程（事件循环无法阻塞等待 Redis）
     */
    private <T> T executeRedis(final Supplier<Mono<T>> command) {
        final long start = System.nanoTime();
        boolean success = false;
        try {
            if (Schedulers.isInNonBlockingThread()) {
                throw new IllegalStateException("配额账本 Redis 命令不能在 Reactor 非阻塞线程上同步阻塞执行"
                    + "（否则 block() 会被拒绝且冲正量静默丢失）: thread=" + Thread.currentThread().getName()
                    + ", backend=" + backendName());
            }
            final T value = command.get().block(properties.distributedTimeout());
            success = true;
            return value;
        } finally {
            metrics.recordRedisCommand(Duration.ofNanos(System.nanoTime() - start), success);
        }
    }

    /**
     * 把 Redis 权威读数与本节点尚未补写的增量合并（避免降级期间低估用量）。
     *
     * @param key    计数键
     * @param remote Redis 读到的 {@code [requests, tokens]}
     * @return 合并后的 {@code [requests, tokens]}
     */
    private long[] withPending(final QuotaCounterKey key, final long[] remote) {
        final long[] pending = localBackend.peekPending(key);
        if (pending == null) {
            return remote;
        }
        return new long[]{remote[0] + pending[0], remote[1] + pending[1]};
    }

    /**
     * 初始化本地计数槽位基线（数据库历史累计值）。
     *
     * @param key 计数键
     * @return {@code [requests, tokens]} 基线
     * @throws RuntimeException 读取数据库失败时（由 {@link #reserve(QuotaRequest)} 按 fail-open 处理）
     */
    private long[] loadBaseline(final QuotaCounterKey key) {
        final QuotaDimension dimension = key.dimension();
        final Optional<QuotaLedgerEntity> row =
            repository.findByTenantIdAndApiKeyIdAndUserIdAndServiceTypeAndModelAndGroupIdAndWindowTypeAndWindowStart(
                dimension.tenantId(), dimension.apiKeyId(), dimension.userId(), dimension.serviceType(),
                dimension.model(), dimension.groupId(), key.window().name(), key.windowStart());
        final long baselineRequests = row.isPresent() ? nullToZero(row.get().getRequestCount()) : 0L;
        final long baselineTokens = row.isPresent() ? nullToZero(row.get().getTokenCount()) : 0L;
        return new long[]{baselineRequests, baselineTokens};
    }

    /**
     * 写入单个槽位的增量快照（累计值与增量来自同一次读取，避免重复落库）。
     *
     * @param key 计数键
     * @param now 当前时间
     * @return 是否成功落库
     */
    private boolean writeSnapshot(final QuotaCounterKey key, final LocalDateTime now) {
        final long[] snapshot = localBackend.snapshotDelta(key);
        if (snapshot == null) {
            return false;
        }
        final QuotaDimension dimension = key.dimension();
        try {
            final int updated = repository.accumulate(
                dimension.tenantId(), dimension.apiKeyId(), dimension.userId(), dimension.serviceType(),
                dimension.model(), dimension.groupId(), key.window().name(), key.windowStart(),
                snapshot[2], snapshot[3], now);
            final boolean persisted = updated > 0 || insertRow(key, snapshot[0], snapshot[1], now);
            if (!persisted) {
                return false;
            }
            localBackend.markPublished(key, snapshot[0], snapshot[1]);
            return true;
        } catch (Exception e) {
            log.warn("配额账本快照落库失败（增量保留待下轮重试）: apiKeyId={}, window={}, windowStart={}, error={}",
                dimension.apiKeyId(), key.window(), key.windowStart(), e.toString());
            return false;
        }
    }

    /**
     * 插入账本行（首次落库写入绝对值）。
     *
     * @param key      计数键
     * @param requests 累计请求数
     * @param tokens   累计 token 数
     * @param now      当前时间
     * @return 是否插入成功
     */
    private boolean insertRow(final QuotaCounterKey key,
                              final long requests,
                              final long tokens,
                              final LocalDateTime now) {
        final QuotaDimension dimension = key.dimension();
        try {
            repository.save(QuotaLedgerEntity.builder()
                .tenantId(dimension.tenantId())
                .apiKeyId(dimension.apiKeyId())
                .userId(dimension.userId())
                .serviceType(dimension.serviceType())
                .model(dimension.model())
                .groupId(dimension.groupId())
                .windowType(key.window().name())
                .windowStart(key.windowStart())
                .requestCount(requests)
                .tokenCount(tokens)
                .updatedAt(now)
                .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("配额账本行已存在（并发插入），下轮以累加方式落库: apiKeyId={}", dimension.apiKeyId());
            return false;
        } catch (Exception e) {
            log.warn("配额账本行插入失败: apiKeyId={}, window={}, error={}",
                dimension.apiKeyId(), key.window(), e.toString());
            return false;
        }
    }

    /**
     * 删除指定窗口的过期数据库行。
     *
     * @param window 窗口类型
     * @param now    当前时间
     * @return 删除行数
     */
    private int deleteExpiredRows(final QuotaWindow window, final LocalDateTime now) {
        try {
            return repository.deleteExpired(window.name(), properties.retentionCutoff(window, now));
        } catch (Exception e) {
            log.warn("配额账本过期行清理失败: window={}, error={}", window, e.toString());
            return 0;
        }
    }

    /**
     * 清理指定窗口的过期计数（Redis 桶 + 本地槽位），失败只告警不抛出。
     *
     * @param window 窗口类型
     * @param now    当前时间
     * @return 清理的计数数量
     */
    private long purgeCounters(final QuotaWindow window, final LocalDateTime now) {
        final LocalDateTime cutoff = properties.retentionCutoff(window, now);
        long purged = 0L;
        if (distributed) {
            try {
                purged += nullToZero(executeRedis(() -> distributedBackend.deleteExpired(window, cutoff)));
            } catch (Exception e) {
                final String reason = classify(e);
                metrics.recordDegradation(reason);
                markDegraded(reason);
                log.warn("Redis 过期计数清理失败（忽略，下轮重试）: window={}, reason={}, error={}",
                    window, reason, e.toString());
            }
        }
        return purged + localBackend.deleteExpiredLocal(window, cutoff);
    }

    /**
     * 标记降级状态。
     *
     * @param reason 降级原因
     */
    private void markDegraded(final String reason) {
        this.degradedReason = reason;
        this.degraded = true;
    }

    /**
     * 清除降级状态（一次成功的分布式操作即视为恢复）。
     */
    private void clearDegraded() {
        if (this.degraded) {
            log.info("配额分布式计数已恢复: backend={}", backendName());
        }
        this.degraded = false;
        this.degradedReason = "";
    }

    /**
     * 按配置决定降级决策的放行 / 拒绝形态。
     *
     * @param reason 降级原因
     * @return fail-open 时放行、否则拒绝的降级决策
     */
    private QuotaDecision degrade(final String reason) {
        if (properties.isFailOpen()) {
            return QuotaDecision.failOpen(reason);
        }
        return QuotaDecision.failClosed(reason);
    }

    /**
     * 判断异常是否为超时（Reactor {@code block(timeout)} 把超时包装成
     * {@link IllegalStateException}，真实超时在因果链中）。
     *
     * @param error 异常
     * @return 因果链中存在 {@link TimeoutException} 时返回 {@code true}
     */
    private static boolean isTimeout(final Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 把异常归类为降级原因。
     *
     * @param error 异常
     * @return {@link #REASON_REDIS_TIMEOUT} 或 {@link #REASON_REDIS_UNAVAILABLE}
     */
    private static String classify(final Throwable error) {
        return isTimeout(error) ? REASON_REDIS_TIMEOUT : REASON_REDIS_UNAVAILABLE;
    }

    /**
     * 计数键 → 用量读数（非负钳制）。
     *
     * @param key    计数键
     * @param totals 计数值
     * @return 用量读数
     */
    private static QuotaUsage toUsage(final QuotaCounterKey key, final long[] totals) {
        return new QuotaUsage(key.dimension(), key.window(), key.windowStart(),
            Math.max(0L, totals[0]), Math.max(0L, totals[1]));
    }

    /**
     * 数据库行 → 用量读数。
     *
     * @param key 计数键
     * @param row 数据库行
     * @return 用量读数
     */
    private static QuotaUsage toUsage(final QuotaCounterKey key, final QuotaLedgerEntity row) {
        return new QuotaUsage(key.dimension(), key.window(), key.windowStart(),
            Math.max(0L, nullToZero(row.getRequestCount())), Math.max(0L, nullToZero(row.getTokenCount())));
    }

    /**
     * 数据库行 → 维度（空值折叠为空串哨兵值）。
     *
     * @param row 数据库行
     * @return 维度
     */
    private static QuotaDimension dimensionOf(final QuotaLedgerEntity row) {
        return new QuotaDimension(row.getTenantId(), row.getApiKeyId(), row.getUserId(),
            row.getServiceType(), row.getModel(), row.getGroupId());
    }

    /**
     * {@code Long} → {@code long} 空值兜底。
     *
     * @param value 可能为 {@code null} 的计数
     * @return 非 {@code null} 的计数
     */
    private static long nullToZero(final Long value) {
        return value == null ? 0L : value;
    }
}
