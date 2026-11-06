package org.unreal.modelrouter.router.ratelimit;

import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;

/**
 * 限流器基础接口
 */
public interface RateLimiter {
    /**
     * 尝试获取令牌
     * @param context 限流上下文
     * @return true表示允许，false表示拒绝
     */
    boolean tryAcquire(RateLimitContext context);

    /**
     * 获取限流配置
     * @return 限流配置
     */
    RateLimitConfig getConfig();

    /**
     * 获取剩余容量（令牌数/请求数）
     * 用于监控和指标导出
     * @return 剩余容量，-1 表示不支持或未知
     */
    default long getRemainingCapacity() {
        return -1;
    }

    /**
     * 获取容量使用率（0.0 ~ 1.0）
     * 用于监控和指标导出
     * @return 使用率，-1 表示不支持或未知
     */
    default double getUsageRatio() {
        return -1;
    }

    /**
     * B4 修复：限流器由 {@code ComponentFactory} 经 {@code new} 创建，
     * 其上的 {@code @Autowired} 字段在工厂路径上恒为 null（与 R2 同源）。
     * 通过此 setter 由工厂在创建后注入 {@link MetricsCollector}，
     * 使限流指标不再静默丢失。默认实现为空操作（如 {@link ScopedRateLimiterWrapper}
     * 委托内部限流器记录，无需自身持有）。
     *
     * @param metricsCollector 指标采集器，可为 null（无指标场景）
     */
    default void setMetricsCollector(final MetricsCollector metricsCollector) {
        // no-op by default
    }
}
