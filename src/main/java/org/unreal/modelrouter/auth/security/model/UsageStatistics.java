package org.unreal.modelrouter.auth.security.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.unreal.modelrouter.auth.security.quota.QuotaWindow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API Key使用统计数据模型
 *
 * <p><b>多窗口用量口径（P0-A）</b>：本类同时维护「日 / 小时 / 月」三个自然窗口的用量桶，
 * 分别对应 {@code dailyXxx}、{@code hourlyXxx}、{@code monthlyXxx} 系列限额字段，
 * 供 {@code ApiKey} 侧的限额判定使用。
 *
 * <p><b>窗口口径统一走 {@link QuotaWindow}</b>：小时桶键取
 * {@link QuotaWindow#HOUR} 的窗口起点（{@code 2026-11-02T17:00}），
 * 月桶键取 {@link QuotaWindow#MONTH} 的窗口起点（{@code 2026-11-01T00:00}），
 * 与配额账本（{@code QuotaLedgerService}）的窗口定义<b>完全一致</b>，
 * 避免「内存统计口径」与「账本口径」出现同一天算两次的偏差。</p>
 *
 * <p><b>一致性局限（多副本部署）</b>：本统计是<b>单副本内存计数 + 持久化</b>的近似口径。
 * 多副本部署时各副本各自计数，实际用量会被<b>低估约 N 倍</b>（N = 副本数），
 * 因此基于本统计的拦截在多副本下是「尽力而为」的软拦截，并非全局精确配额。
 * 需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）；
 * 本类解决的是「账本关闭（默认）时配了额度完全不生效」这一更严重的问题。</p>
 */
@Data
@Builder
@NoArgsConstructor   // 这个很关键
@AllArgsConstructor
public class UsageStatistics {

    /**
     * 小时桶保留数（保留最近 48 个小时 ≈ 2 天），防止持久化 JSON 无限增长。
     */
    private static final int HOURLY_BUCKET_RETENTION = 48;

    /**
     * 月桶保留数（保留最近 12 个月）。
     */
    private static final int MONTHLY_BUCKET_RETENTION = 12;

    /**
     * 总请求次数
     */
    private long totalRequests;

    /**
     * 成功请求次数
     */
    private long successfulRequests;

    /**
     * 失败请求次数
     */
    private long failedRequests;

    /**
     * 最后使用时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime lastUsedAt;

    /**
     * 每日使用统计（日期 -> 使用次数）
     */
    private Map<String, Long> dailyUsage;

    /**
     * 每日 Token 使用统计（日期 -> Token 总数）
     */
    private Map<String, Long> dailyTokenUsage;

    /**
     * 每小时使用统计（小时窗口起点 -> 使用次数），自然小时窗口。
     *
     * <p>键为 {@link QuotaWindow#HOUR} 对齐后的窗口起点（形如 {@code 2026-11-02T17:00}）。
     * 写入时按「有界窗口」裁剪，详见 {@link #pruneBuckets(Map, int)}。</p>
     */
    private Map<String, Long> hourlyUsage;

    /**
     * 每小时 Token 使用统计（小时窗口起点 -> Token 总数），自然小时窗口。
     */
    private Map<String, Long> hourlyTokenUsage;

    /**
     * 每月使用统计（月窗口起点 -> 使用次数），自然月窗口。
     *
     * <p>键为 {@link QuotaWindow#MONTH} 对齐后的窗口起点（形如 {@code 2026-11-01T00:00}）。</p>
     */
    private Map<String, Long> monthlyUsage;

    /**
     * 每月 Token 使用统计（月窗口起点 -> Token 总数），自然月窗口。
     */
    private Map<String, Long> monthlyTokenUsage;

    /**
     * 当前小时窗口的键（与 {@link QuotaWindow#HOUR} 同口径）。
     *
     * @return 形如 {@code 2026-11-02T17:00} 的窗口标识
     */
    public static String currentHourKey() {
        return QuotaWindow.HOUR.windowStart(LocalDateTime.now()).toString();
    }

    /**
     * 当前月窗口的键（与 {@link QuotaWindow#MONTH} 同口径）。
     *
     * @return 形如 {@code 2026-11-01T00:00} 的窗口标识
     */
    public static String currentMonthKey() {
        return QuotaWindow.MONTH.windowStart(LocalDateTime.now()).toString();
    }

    /**
     * 获取成功率
     * @return 成功率（0-1之间的小数）
     */
    // #811/#715：派生值不参与 JSON 往返——带 getter 无 setter 的派生属性序列化后
    // 读回是未知字段（裸 mapper 下直接炸掉单 Key 回写），且其值可由
    // successfulRequests/totalRequests 复原，落库纯属冗余。
    @JsonIgnore
    public double getSuccessRate() {
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) successfulRequests / totalRequests;
    }

    /**
     * 增加请求统计.
     *
     * <p>N-6: {@code dailyUsage}(当日用量,日配额判定口径)仅对成功请求累加——
     * 被拒请求(配额/限流/权限不足等)不计入当日用量,避免「被拒也占额度」导致
     * limit=N 实际只能成功不足 N 次;{@code totalRequests}/{@code failedRequests}
     * 仍记录全部请求(含被拒),保持审计口径完整。</p>
     *
     * <p>P0-A：小时 / 月窗口桶与日桶同口径——同样只对成功请求累加，
     * 因此「被配额拒绝的请求」不会反过来把用量顶上去。</p>
     *
     * @param success 请求是否成功
     */
    public synchronized void incrementRequest(final boolean success) {
        totalRequests++;
        if (success) {
            successfulRequests++;
        } else {
            failedRequests++;
        }
        lastUsedAt = LocalDateTime.now();
        if (success) {
            final LocalDateTime now = LocalDateTime.now();
            final String today = now.toLocalDate().toString();
            if (dailyUsage == null) {
                dailyUsage = new HashMap<>();
            }
            dailyUsage.put(today, dailyUsage.getOrDefault(today, 0L) + 1);

            // P0-A：小时 / 月窗口桶——多窗口配额的判定口径。
            final String hour = QuotaWindow.HOUR.windowStart(now).toString();
            if (hourlyUsage == null) {
                hourlyUsage = new HashMap<>();
            }
            hourlyUsage.put(hour, hourlyUsage.getOrDefault(hour, 0L) + 1);
            pruneBuckets(hourlyUsage, HOURLY_BUCKET_RETENTION);

            final String month = QuotaWindow.MONTH.windowStart(now).toString();
            if (monthlyUsage == null) {
                monthlyUsage = new HashMap<>();
            }
            monthlyUsage.put(month, monthlyUsage.getOrDefault(month, 0L) + 1);
            pruneBuckets(monthlyUsage, MONTHLY_BUCKET_RETENTION);
        }
    }

    /**
     * 增加 Token 使用量
     *
     * <p>P0-A：同时累加小时 / 月窗口的 Token 桶（与日桶同口径）。</p>
     *
     * @param tokens 本次请求消耗的 Token 数
     */
    public synchronized void incrementTokens(final long tokens) {
        if (tokens <= 0) {
            return;
        }
        final LocalDateTime now = LocalDateTime.now();
        final String today = now.toLocalDate().toString();
        if (dailyTokenUsage == null) {
            dailyTokenUsage = new HashMap<>();
        }
        dailyTokenUsage.put(today, dailyTokenUsage.getOrDefault(today, 0L) + tokens);

        final String hour = QuotaWindow.HOUR.windowStart(now).toString();
        if (hourlyTokenUsage == null) {
            hourlyTokenUsage = new HashMap<>();
        }
        hourlyTokenUsage.put(hour, hourlyTokenUsage.getOrDefault(hour, 0L) + tokens);
        pruneBuckets(hourlyTokenUsage, HOURLY_BUCKET_RETENTION);

        final String month = QuotaWindow.MONTH.windowStart(now).toString();
        if (monthlyTokenUsage == null) {
            monthlyTokenUsage = new HashMap<>();
        }
        monthlyTokenUsage.put(month, monthlyTokenUsage.getOrDefault(month, 0L) + tokens);
        pruneBuckets(monthlyTokenUsage, MONTHLY_BUCKET_RETENTION);
    }

    /**
     * 裁剪窗口桶，只保留最近 {@code keep} 个窗口。
     *
     * <p>窗口键是 ISO 局部日期时间（{@code 2026-11-02T17:00}），<b>字典序即时间序</b>，
     * 因此按字典序排序后从头丢弃即可。</p>
     *
     * <p>为什么需要裁剪：本对象整体序列化进持久化存储（{@code config_data}），
     * 若小时桶不裁剪，每小时一条会无限增长。</p>
     *
     * @param buckets 目标桶，可为 {@code null}
     * @param keep    保留的窗口数
     */
    private static void pruneBuckets(final Map<String, Long> buckets, final int keep) {
        if (buckets == null || buckets.size() <= keep) {
            return;
        }
        final List<String> keys = new ArrayList<>(buckets.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size() - keep; i++) {
            buckets.remove(keys.get(i));
        }
    }

    /**
     * 重置统计信息
     */
    public void reset() {
        totalRequests = 0L;
        successfulRequests = 0L;
        failedRequests = 0L;
        lastUsedAt = null;
        if (dailyUsage != null) {
            dailyUsage.clear();
        }
        if (dailyTokenUsage != null) {
            dailyTokenUsage.clear();
        }
        if (hourlyUsage != null) {
            hourlyUsage.clear();
        }
        if (hourlyTokenUsage != null) {
            hourlyTokenUsage.clear();
        }
        if (monthlyUsage != null) {
            monthlyUsage.clear();
        }
        if (monthlyTokenUsage != null) {
            monthlyTokenUsage.clear();
        }
    }

    /**
     * 重置每日统计（请求计数和 Token 计数）
     *
     * <p>P0-A：<b>只清日桶</b>。小时 / 月桶属于各自独立的配额窗口，
     * 「重置每日配额」不应顺带清掉小时 / 月的用量（否则用户可借重置日配额绕过小时 / 月配额）。
     * 小时 / 月窗口随自然周期自动翻篇，无需人工重置。</p>
     */
    public void resetDaily() {
        if (dailyUsage != null) {
            dailyUsage.clear();
        }
        if (dailyTokenUsage != null) {
            dailyTokenUsage.clear();
        }
    }

    /**
     * 获取失败率
     * @return 失败率（0-1之间的小数）
     */
    // #811/#715：派生值不参与 JSON 往返（同 getSuccessRate）。
    @JsonIgnore
    public double getFailureRate() {
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) failedRequests / totalRequests;
    }
}
