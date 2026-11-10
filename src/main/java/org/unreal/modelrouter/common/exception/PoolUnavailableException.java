/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 池路由不可用异常（带 Retry-After 提示）
 *
 * <p>N-4：熔断打开 / 成员不健康导致的池感知 503 此前不带 {@code Retry-After} 头，
 * 而 fallback 降级路径带 {@code Retry-After: 60} —— 客户端无法用统一策略退避。
 * 本异常携带 {@code retryAfterSeconds}，由异常处理器在写响应时补上
 * {@code Retry-After} 头，使两类 503 的退避语义一致。</p>
 *
 * <p><b>继承 {@link ResponseStatusException} 的原因（2026-10-22 回归修复）：</b>
 * 池不可用此前抛的是 {@code ResponseStatusException}，既有集成测试
 * （{@code ModelServiceRegistryRuleIntegrationTest#testPool_allUnhealthy_throws}）
 * 断言的就是这个类型。若改为继承 {@code RuntimeException}，该契约会被破坏——
 * 捕获 {@code ResponseStatusException} 的调用方将漏掉本异常，异常直接冒泡成 500。
 * 继承它可同时保留「503 状态语义」与「带 Retry-After 的额外信息」。</p>
 * @author Trunkline Team
 */
public class PoolUnavailableException extends ResponseStatusException {

    private static final long serialVersionUID = 1L;

    /** 客户端应等待的秒数（建议退避时间） */
    private final int retryAfterSeconds;

    public PoolUnavailableException(final String message, final int retryAfterSeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return HttpStatus.SERVICE_UNAVAILABLE;
    }
}
