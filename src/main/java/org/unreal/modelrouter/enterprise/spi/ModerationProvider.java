/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.Optional;

/**
 * 内容审核扩展点（商业版功能，S2 任务 32）。
 *
 * <p>在请求转发到上游模型前对企业敏感内容做审核；社区版由
 * {@code NoopModerationProvider} 放行全部请求。
 *
 * <p><b>B5-out（输出侧 / 流式增量审核）</b>：由 {@link #openOutputSession} 提供可选的
 * 会话式扩展点。该方法是 {@code default}，返回空表示「不启用输出侧审核」——
 * 按 {@link EnterpriseSpiVersion} 约定，新增带默认实现的方法不构成破坏性变更，
 * 社区版 Noop 与已发布的旧企业 jar 无需重编即可安全关闭该入口。
 * @author Trunkline Team
 */
public interface ModerationProvider {

    /**
     * 审核一条即将发往上游的输入或输出内容。
     *
     * @param input 模型名与待审文本
     * @return 放行 / 拦截判定；实现不得抛出异常阻断请求，拦截与否只看返回值
     */
    ModerationDecision check(ModerationInput input);

    /**
     * 开启一次「输出侧流式增量审核」会话（B5-out）。
     *
     * <p>默认返回 {@link Optional#empty()}：主干检测到空会话即原样返回原始 chunk 流，
     * <b>零解析、零拷贝、零行为变化</b>。企业侧实现按功能位 / 配置决定是否返回会话。
     * 会话对象与单次订阅绑定，不要求线程安全（Reactor 保证 onNext 串行）。
     *
     * @param input 本次请求的模型 / 调用者 / 租户信息（供企业侧按租户选审核策略）
     * @return 输出侧审核会话；空表示不启用
     */
    default Optional<OutputSession> openOutputSession(ModerationInput input) {
        return Optional.empty();
    }

    /**
     * 输出侧增量审核会话。
     *
     * <p><b>先审后发</b>：主干把一个 SSE 数据帧交给 {@link #checkChunk} 后，按判定决定
     * 「立即下发 / 暂存等待更多文本 / 拦截终止」。实现必须<b>本地、无阻塞、无外部 IO</b>
     * （运行在 Netty event loop 上），且不得把待审文本写进日志或异常消息。
     * 远程审核引擎应自行异步预热规则，或在输入侧 {@link #check} 中使用。
     */
    interface OutputSession {

        /**
         * 审核一个增量数据帧。
         *
         * @param chunk 该帧提取到的增量文本与终止标记；{@link OutputChunk#delta()} 为
         *              {@code null} 表示此帧不含可审文本（usage / event 行等）
         * @return 判定；返回 {@code null} 由主干按 fail-closed 处理
         */
        OutputDecision checkChunk(OutputChunk chunk);

        /**
         * 上游正常结束时冲刷尾部残留文本（被 {@code HOLD} 的帧尚未下发）。
         *
         * @return ALLOW 表示冲刷暂存帧；BLOCK 表示丢弃暂存帧并终止
         */
        OutputDecision finish();
    }

    /**
     * 单个 SSE 数据帧的审核输入。
     *
     * @param delta    增量文本；{@code null} 表示该帧不含可审文本
     * @param terminal 是否为协议终止标记（{@code [DONE]} / {@code finish_reason} / message_stop）
     */
    record OutputChunk(String delta, boolean terminal) {

        /** 普通文本增量帧 */
        public static OutputChunk of(final String delta) {
            return new OutputChunk(delta, false);
        }

        /** 终止帧（无文本） */
        public static OutputChunk terminalFrame() {
            return new OutputChunk(null, true);
        }
    }

    /** 输出侧增量判定语汇 */
    enum OutputVerdict {
        /** 放行：冲刷此前暂存的帧并下发当前帧 */
        ALLOW,
        /** 暂存：当前帧先不发，等待更多增量文本拼够再判定（防跨块切词漏放） */
        HOLD,
        /** 拦截：丢弃暂存帧并终止流 */
        BLOCK
    }

    /**
     * 输出侧判定。
     *
     * @param verdict 判定语汇
     * @param reason  拦截原因（<b>禁止包含待审原文或命中的敏感词</b>，会回显给客户端）
     */
    record OutputDecision(OutputVerdict verdict, String reason) {

        public static OutputDecision allow() {
            return new OutputDecision(OutputVerdict.ALLOW, null);
        }

        public static OutputDecision hold() {
            return new OutputDecision(OutputVerdict.HOLD, null);
        }

        public static OutputDecision block(String reason) {
            return new OutputDecision(OutputVerdict.BLOCK, reason);
        }
    }

    /**
     * 审核输入。
     *
     * <p>canonical 四参构造（O7-c / SPI v2）：{@code tenantId} 供企业侧"按租户配置审核策略"。
     * 保留三参兼容构造（tenantId=null），主干与存量企业测试无需同步改签名。</p>
     *
     * @param model    模型名
     * @param content  待审文本
     * @param username 请求方用户名（可为 null）
     * @param tenantId 租户 ID（可为 null；多租户下由 {@code TenantContextHolder} 解析）
     */
    record ModerationInput(String model, String content, String username, String tenantId) {

        /** 三参兼容构造（SPI v1 形态；tenantId = null）。 */
        public ModerationInput(final String model, final String content, final String username) {
            this(model, content, username, null);
        }
    }

    /** 审核判定 */
    record ModerationDecision(boolean allowed, String reason) {

        public static ModerationDecision allow() {
            return new ModerationDecision(true, null);
        }

        public static ModerationDecision block(String reason) {
            return new ModerationDecision(false, reason);
        }
    }
}
