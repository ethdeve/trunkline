/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.List;

/**
 * 出站内容防泄露检查扩展点（B2 · 出站 DLP，商业版功能）。
 *
 * <p>与 {@link ModerationProvider}（O7-c，违规内容审核）同一挂载语义：<b>在请求发往
 * 上游模型之前</b>、实例选择之前执行——被拦截的请求不消耗负载均衡状态与实例级限流令牌，
 * 上游零调用。本扩展点专注<b>敏感数据出站防护</b>（身份证 / 手机号 / 密钥等），
 * 判定语汇比审核多一级「脱敏替换后放行」。</p>
 *
 * <p><b>失败语义（与 O7-c 相反，B2 特有）</b>：{@code ModerationProvider} 约定实现不得抛异常；
 * 本扩展点<b>允许抛异常</b>——主干会捕获一切异常并按 <b>fail-closed</b> 拒绝出站
 * （{@code DLP_BLOCKED}）。防泄露的失败模式必须是「拒绝」而不是「放行」。
 * 实现内部也建议自行兜底返回 {@link #block}，构成双层保险。</p>
 *
 * <p><b>SPI 兼容性</b>：新接口不改变既有接口签名，{@link EnterpriseSpiVersion#VERSION}
 * 保持 3（同 {@code AuditChainListener} 的「v3 续 · 不推版本」口径）。社区版由
 * {@code NoopOutboundContentInspector} 恒注册放行，零行为影响。</p>
 *
 * <p><b>隐私纪律</b>：{@code reason} 会回显给客户端，<b>禁止包含待审原文或命中的敏感片段</b>；
 * {@code ruleId}（规则标识）允许回显。实现不得把待审文本写进日志或异常消息。</p>
 *
 * @author Trunkline Team
 */
public interface OutboundContentInspector {

    /**
     * 检查一次即将出站的 chat 消息列表。
     *
     * <p>主干运行在弹性线程池上，实现允许做本地规则计算（正则 / 关键词），
     * 不应做长阻塞外部 IO（远程引擎应在输入侧异步预热）。
     *
     * @param input 模型名与逐条消息文本（与请求消息顺序一一对应）
     * @return 判定；返回 {@code null} 由主干按 fail-closed 处理
     */
    InspectionDecision inspect(InspectionInput input);

    /** 判定语汇 */
    enum Verdict {
        /** 放行：原样出站 */
        ALLOW,
        /** 脱敏：按 {@code sanitizedMessages} 替换后出站 */
        SANITIZE,
        /** 阻断：拒绝出站，请求返回 {@code DLP_BLOCKED} */
        BLOCK
    }

    /**
     * 检查输入。
     *
     * @param model    模型名
     * @param messages 逐条消息文本列表（顺序与请求 {@code messages} 一一对应；
     *                 元素可为 {@code null}，表示该消息无文本内容）
     * @param username 请求方用户名（可为 null）
     * @param tenantId 租户 ID（可为 null；由 {@code TenantContextHolder} 解析）
     */
    record InspectionInput(String model, List<String> messages, String username, String tenantId) {
    }

    /**
     * 检查判定。
     *
     * @param verdict           判定语汇
     * @param sanitizedMessages 脱敏后的逐条消息文本；<b>仅 {@code SANITIZE} 时有效</b>，
     *                          且必须与输入 {@code messages} 等长一一对应——长度不符由主干
     *                          按 fail-closed 处理（视为实现契约违约，拒绝出站）
     * @param ruleId            命中规则标识（可回显给客户端；多个命中时取最严规则）
     * @param reason            拒绝 / 脱敏说明（回显给客户端，<b>禁止包含原文或敏感片段</b>）
     */
    record InspectionDecision(Verdict verdict, List<String> sanitizedMessages, String ruleId, String reason) {

        /** 放行（共享不可变单例语义，由调用方按需构造） */
        public static InspectionDecision allow() {
            return new InspectionDecision(Verdict.ALLOW, null, null, null);
        }

        /** 阻断出站 */
        public static InspectionDecision block(final String ruleId, final String reason) {
            return new InspectionDecision(Verdict.BLOCK, null, ruleId, reason);
        }

        /** 脱敏后放行；{@code sanitizedMessages} 必须与输入消息列表等长一一对应 */
        public static InspectionDecision sanitize(final List<String> sanitizedMessages,
                                                  final String ruleId, final String reason) {
            return new InspectionDecision(Verdict.SANITIZE, List.copyOf(sanitizedMessages), ruleId, reason);
        }
    }
}
