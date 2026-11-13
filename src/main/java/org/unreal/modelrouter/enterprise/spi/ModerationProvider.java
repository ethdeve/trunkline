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
