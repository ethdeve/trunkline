package org.unreal.modelrouter.router.ratelimit.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.ratelimit.RateLimitConfig;
import org.unreal.modelrouter.router.ratelimit.RateLimitContext;
import org.unreal.modelrouter.router.ratelimit.RateLimiter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 漏桶限流器实现
 */
public class LeakyBucketRateLimiter implements RateLimiter {
    private final RateLimitConfig config;
    private final AtomicLong water;
    private final AtomicLong lastLeak;
    
    @Autowired(required = false)
    private MetricsCollector metricsCollector;

    public LeakyBucketRateLimiter(final RateLimitConfig config) {
        this.config = config;
        this.water = new AtomicLong(0);
        this.lastLeak = new AtomicLong(System.nanoTime());
    }

    /**
     * B4 修复：由 ComponentFactory 在创建后注入指标采集器（限流器经 new 创建，
     * 自身 {@code @Autowired} 字段恒为 null）。
     */
    @Override
    public void setMetricsCollector(final MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * 尝试获取令牌
     * @param context 限流上下文
     * @return 是否获取成功
     */
    @Override
    public boolean tryAcquire(final RateLimitContext context) {
        leak();
        // 清单 M-18：CAS 失败只代表并发下水量被别的线程改过，不应直接判为限流。
        // 循环重试直到容量确实不足（真正拒绝）或 CAS 成功（放行）。
        boolean allowed = false;
        while (true) {
            long current = water.get();
            if (current + context.getTokens() > config.getCapacity()) {
                break; // 容量确实不足，拒绝
            }
            if (water.compareAndSet(current, current + context.getTokens())) {
                allowed = true;
                break;
            }
        }

        // 记录限流指标
        recordRateLimitMetrics(context, allowed);
        return allowed;
    }

    private void leak() {
        long now = System.nanoTime();
        long last = lastLeak.get();
        long passed = now - last;
        long toLeak = (passed * config.getRate()) / 1_000_000_000L;
        if (toLeak > 0 && lastLeak.compareAndSet(last, now)) {
            water.updateAndGet(v -> Math.max(0, v - toLeak));
        }
    }

    /**
     * 获取限流配置
     * @return 限流配置
     */
    @Override 
    public RateLimitConfig getConfig() { 
        return config; 
    }

    /**
     * 记录限流指标
     */
    private void recordRateLimitMetrics(final RateLimitContext context, final boolean allowed) {
        if (metricsCollector != null) {
            try {
                String serviceName = context.getServiceType() != null
                    ? context.getServiceType().name().toLowerCase() : "unknown";
                metricsCollector.recordRateLimit(serviceName, "leaky_bucket", allowed);
            } catch (Exception e) {
                // 静默处理指标记录异常，不影响业务逻辑
            }
        }
    }

    /**
     * 获取剩余容量
     */
    @Override
    public long getRemainingCapacity() {
        leak();
        return Math.max(0, config.getCapacity() - water.get());
    }

    /**
     * 获取容量使用率
     */
    @Override
    public double getUsageRatio() {
        leak();
        long capacity = config.getCapacity();
        if (capacity <= 0) {
            return 0;
        }
        return (double) water.get() / capacity;
    }
}
