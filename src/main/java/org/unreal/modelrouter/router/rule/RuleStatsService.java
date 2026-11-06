package org.unreal.modelrouter.router.rule;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.config.core.MonitoringProperties;
import org.unreal.modelrouter.router.rule.model.RuleStat;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则命中统计服务
 * 命中计数挂载在决策生效点(ModelServiceRegistry.selectInstance),dry-run 与适配器二次求值不计数
 *
 * <p>两套口径：
 * <ul>
 *   <li><b>hits</b>：Micrometer 累计计数，进程生命周期内只增不减（重启归零）；</li>
 *   <li><b>monthHits</b>：进程内按自然月分桶计数，跨月自动归零，用于「本月命中」展示。</li>
 * </ul>
 * 两者都是内存态，与既有「基于内存计数」语义一致，不落库。
 */
@Service
public class RuleStatsService {

    public static final String METRIC_NAME_SUFFIX = "rule_hits_total";

    private final MeterRegistry meterRegistry;
    private final String metricPrefix;

    /** key = ruleId|actionType，value = 当前自然月的计数桶 */
    private final Map<String, MonthCounter> monthlyHits = new ConcurrentHashMap<>();

    public RuleStatsService(final MeterRegistry meterRegistry, final MonitoringProperties monitoringProperties) {
        this.meterRegistry = meterRegistry;
        String prefix = monitoringProperties.getPrefix();
        this.metricPrefix = (prefix != null && !prefix.isEmpty()) ? prefix + "_" : "";
    }

    /**
     * 记录规则命中
     */
    public void recordHit(final String ruleId, final String actionType) {
        Counter.builder(metricPrefix + METRIC_NAME_SUFFIX)
                .tag("ruleId", ruleId)
                .tag("actionType", actionType)
                .description("Number of requests matched by routing rule")
                .register(meterRegistry)
                .increment();

        final String month = currentYearMonth();
        monthlyHits.compute(ruleId + "|" + actionType, (key, holder) -> {
            MonthCounter counter = holder == null ? new MonthCounter(month) : holder;
            counter.rollIfExpired(month);
            counter.increment();
            return counter;
        });
    }

    /**
     * 聚合全部规则的命中计数(按 ruleId+actionType 系列)
     *
     * <p>每项同时带累计命中(hits)与本月命中(monthHits)。</p>
     */
    public List<RuleStat> getStats() {
        String metricName = metricPrefix + METRIC_NAME_SUFFIX;
        final String month = currentYearMonth();
        List<RuleStat> stats = new ArrayList<>();
        meterRegistry.find(metricName).counters().forEach(counter -> {
            String ruleId = counter.getId().getTag("ruleId");
            String actionType = counter.getId().getTag("actionType");
            if (ruleId != null) {
                stats.add(new RuleStat(ruleId, null, actionType, (long) counter.count(),
                        monthHitsOf(ruleId, actionType, month)));
            }
        });
        return stats;
    }

    /**
     * 取某规则(+动作类型)在指定自然月的命中数。
     *
     * <p>桶不存在或已过期(跨月)均返回 0。</p>
     */
    private long monthHitsOf(final String ruleId, final String actionType, final String month) {
        MonthCounter counter = monthlyHits.get(ruleId + "|" + actionType);
        if (counter == null) {
            return 0L;
        }
        counter.rollIfExpired(month);
        return counter.count();
    }

    private static String currentYearMonth() {
        return YearMonth.now(ZoneId.systemDefault()).toString();
    }

    /**
     * 自然月计数桶：跨月时归零重开。
     */
    private static final class MonthCounter {
        private volatile String yearMonth;
        private final AtomicLong hits = new AtomicLong();

        MonthCounter(final String yearMonth) {
            this.yearMonth = yearMonth;
        }

        void rollIfExpired(final String currentMonth) {
            if (!currentMonth.equals(this.yearMonth)) {
                synchronized (this) {
                    if (!currentMonth.equals(this.yearMonth)) {
                        this.yearMonth = currentMonth;
                        this.hits.set(0L);
                    }
                }
            }
        }

        void increment() {
            this.hits.incrementAndGet();
        }

        long count() {
            return this.hits.get();
        }
    }
}
