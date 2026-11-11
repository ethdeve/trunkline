package org.unreal.modelrouter.router.fallback;

/**
 * 降级策略接口
 */
public interface FallbackStrategy<T> {
    /**
     * 执行降级逻辑
     * @param cause 引起降级的异常或原因
     * @return 降级返回的结果
     */
    T fallback(Exception cause);

    /**
     * 按请求维度执行降级逻辑（M-24：消除缓存降级的跨请求串扰）。
     *
     * <p>带请求标识的实现（如 {@code CacheFallbackStrategy}）必须只返回<b>本次请求自己</b>
     * 的缓存结果；找不到对应条目时返回"无可用降级"，绝不回退到"任意取一条缓存"——
     * 否则会把 A 用户的模型输出当作 B 用户的降级响应返回，造成数据串扰与越权泄漏。</p>
     *
     * <p>默认实现忽略请求键、委托给 {@link #fallback(Exception)}，保持所有既有无状态策略
     * （如 {@code DefaultFallbackStrategy}）的二进制与行为兼容。</p>
     *
     * @param cause     引起降级的异常
     * @param requestKey 本次请求的稳定降级键；可为 null（无法定位请求维度时按无状态降级处理）
     * @return 降级返回的结果
     */
    default T fallback(final Exception cause, final String requestKey) {
        return fallback(cause);
    }
}
