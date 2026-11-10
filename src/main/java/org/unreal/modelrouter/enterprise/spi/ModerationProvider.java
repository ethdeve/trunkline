/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

/**
 * 内容审核扩展点（商业版功能，S2 任务 32）。
 *
 * <p>在请求转发到上游模型前对企业敏感内容做审核；社区版由
 * {@code NoopModerationProvider} 放行全部请求。
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

    /** 审核输入 */
    record ModerationInput(String model, String content, String username) {
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
