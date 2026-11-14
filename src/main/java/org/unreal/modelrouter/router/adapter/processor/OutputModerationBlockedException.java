/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.processor;

/**
 * 输出侧（流式增量）审核判定为拦截时，用于终止响应体的内部信号。
 *
 * <p><b>为什么走异常而不是正常结束</b>：流式主链的 {@code doOnComplete} 会写流式缓存、
 * 记成功指标、把响应体落进调用历史。若审核拦截后仍走 {@code onComplete}，违规内容会被
 * 缓存并落库，且被记为一次成功调用。以异常终止可复用既有 {@code onErrorResume} /
 * {@link StreamingErrorTermination} 的「先补 error 帧、再以失败信号结束」语义：
 * 不写缓存、不记成功、不补 {@code [DONE]}（避免客户端把拦截读成正常结束）。</p>
 *
 * <p><b>脱敏约束</b>：{@code reason} 只放固定文案，绝不放待审原文或命中的敏感词——
 * 它会经 error 帧回显给客户端，也可能进日志。</p>
 */
final class OutputModerationBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 默认回显文案（不泄露规则细节） */
    static final String DEFAULT_REASON = "输出内容未通过审核";

    private final String reason;

    OutputModerationBlockedException(final String reason) {
        super(sanitize(reason));
        this.reason = sanitize(reason);
    }

    String reason() {
        return reason;
    }

    /** 空 / 过长 / 含控制字符的原因一律回落固定文案，防止实现方误把原文塞进来。 */
    private static String sanitize(final String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return DEFAULT_REASON;
        }
        final String trimmed = candidate.trim();
        if (trimmed.length() > 120) {
            return DEFAULT_REASON;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                return DEFAULT_REASON;
            }
        }
        return trimmed;
    }
}
