/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 无可用实例异常（#1010：503 明细契约）.
 *
 * <p>普通路由在「全候选被限流 / 全不健康 / 全熔断」时抛出，携带结构化明细
 * {@code data.members}（逐实例 name / target(host:port 脱敏) / status / health /
 * circuitBreaker / failureCount / lastFailureTime(ISO)），由异常处理器写入响应体：
 * {@code /api/**} 面 {@code RouterResponse.data}，{@code /v1/**} 面
 * {@code error.details}（默认关闭，由 {@code trunkline.error-details.expose-topology} 开启）。</p>
 *
 * <p><b>继承 {@link ResponseStatusException} 的原因：</b>与 {@code PoolUnavailableException}
 * 同理——既有调用方与集成测试按 {@code ResponseStatusException} 捕获/断言
 * （如 {@code ModelServiceRegistryRuleIntegrationTest}），改为继承 {@code RuntimeException}
 * 会把 503 变成 500。继承它可同时保留「503 状态语义」与「带 Retry-After + 明细」的额外信息。</p>
 *
 * <p>N-4 对齐：{@code Retry-After} 默认 60s，与池路由
 * {@code POOL_UNAVAILABLE_RETRY_AFTER_SECONDS} 及 fallback 降级路径的退避语义一致。</p>
 *
 * @author Trunkline Team
 */
public class NoAvailableInstanceException extends ResponseStatusException {

    private static final long serialVersionUID = 1L;

    /** 客户端应等待的秒数（建议退避时间，与池路由一致为 60s） */
    private final int retryAfterSeconds;

    /** 结构化明细：members 逐实例诊断信息（transient：异常序列化场景不携带） */
    private final transient Map<String, Object> data;

    public NoAvailableInstanceException(final String message, final Map<String, Object> data) {
        this(message, data, 60);
    }

    public NoAvailableInstanceException(final String message, final Map<String, Object> data,
                                        final int retryAfterSeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
        this.data = data;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return HttpStatus.SERVICE_UNAVAILABLE;
    }
}
