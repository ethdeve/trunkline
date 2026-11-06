package org.unreal.modelrouter.router.pool.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源池定义
 * 一组同服务类型实例的命名集合;请求 model 使用 poolName(如 company-chat)时触发池路由
 */
public final class PoolDefinition {

    /** 池名 = 虚拟模型名(请求 model 用它触发池路由;必须显式创建,没有隐式默认池) */
    private String poolName;
    /** 显示名 */
    private String name;
    /** 单服务类型(chat/embedding/...) */
    private String serviceType;
    /** 是否启用 */
    private boolean enabled = true;
    /** 池级选择策略:weighted-random / round-robin / least-connections / ip-hash */
    private String strategy = "weighted-random";
    /** 描述 */
    private String description;
    /** 池成员(引用实例) */
    private List<PoolMember> members = new ArrayList<>();

    /**
     * 每分钟请求速率上限（0 表示无限制）
     */
    private int rateLimitPerMinute = 0;

    /**
     * 每小时请求上限（0 表示无限制）
     */
    private long hourlyRequestLimit = 0L;

    /**
     * 每小时 Token 使用上限（0 表示无限制）
     */
    private long hourlyTokenLimit = 0L;

    /**
     * 每日请求上限（0 表示无限制）
     */
    private long dailyRequestLimit = 0L;

    /**
     * 每日 Token 使用上限（0 表示无限制）
     */
    private long dailyTokenLimit = 0L;

    /**
     * 每月请求上限（0 表示无限制）
     */
    private long monthlyRequestLimit = 0L;

    /**
     * 每月 Token 使用上限（0 表示无限制）
     */
    private long monthlyTokenLimit = 0L;

    /**
     * 配额告警阈值（0.0-1.0），达到此比例时触发告警，默认 0.8
     */
    private double quotaAlertThreshold = 0.8;

    public String getPoolName() {
        return poolName;
    }

    public void setPoolName(final String poolName) {
        this.poolName = poolName;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(final String serviceType) {
        this.serviceType = serviceType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(final String strategy) {
        this.strategy = strategy;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public List<PoolMember> getMembers() {
        return members;
    }

    public void setMembers(final List<PoolMember> members) {
        this.members = members;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(final int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public long getHourlyRequestLimit() {
        return hourlyRequestLimit;
    }

    public void setHourlyRequestLimit(final long hourlyRequestLimit) {
        this.hourlyRequestLimit = hourlyRequestLimit;
    }

    public long getHourlyTokenLimit() {
        return hourlyTokenLimit;
    }

    public void setHourlyTokenLimit(final long hourlyTokenLimit) {
        this.hourlyTokenLimit = hourlyTokenLimit;
    }

    public long getDailyRequestLimit() {
        return dailyRequestLimit;
    }

    public void setDailyRequestLimit(final long dailyRequestLimit) {
        this.dailyRequestLimit = dailyRequestLimit;
    }

    public long getDailyTokenLimit() {
        return dailyTokenLimit;
    }

    public void setDailyTokenLimit(final long dailyTokenLimit) {
        this.dailyTokenLimit = dailyTokenLimit;
    }

    public long getMonthlyRequestLimit() {
        return monthlyRequestLimit;
    }

    public void setMonthlyRequestLimit(final long monthlyRequestLimit) {
        this.monthlyRequestLimit = monthlyRequestLimit;
    }

    public long getMonthlyTokenLimit() {
        return monthlyTokenLimit;
    }

    public void setMonthlyTokenLimit(final long monthlyTokenLimit) {
        this.monthlyTokenLimit = monthlyTokenLimit;
    }

    public double getQuotaAlertThreshold() {
        return quotaAlertThreshold;
    }

    public void setQuotaAlertThreshold(final double quotaAlertThreshold) {
        this.quotaAlertThreshold = quotaAlertThreshold;
    }

    /**
     * 把本池的配额字段打包为限额集合（供配额执行层判定使用）。
     *
     * @return 限额集合（各窗口 {@code <= 0} 表示不限制）
     */
    public org.unreal.modelrouter.auth.security.quota.QuotaLimitSet toQuotaLimitSet() {
        return new org.unreal.modelrouter.auth.security.quota.QuotaLimitSet(
                rateLimitPerMinute,
                hourlyRequestLimit,
                hourlyTokenLimit,
                dailyRequestLimit,
                dailyTokenLimit,
                monthlyRequestLimit,
                monthlyTokenLimit);
    }
}
