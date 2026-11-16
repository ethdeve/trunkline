/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具治理扩展点（M4 · Agent 工具网关，商业版功能）。
 *
 * <p>挂在出站 DLP（{@link OutboundContentInspector}）之后、实例选择之前：对 Anthropic
 * {@code /v1/messages} 透传链路上的<b>工具定义（{@code tools[]}）与工具结果回传
 * （{@code role:"tool"} wire 消息）</b>做治理——白名单校验、租户/角色授权、敏感内容复检。
 * 被 BLOCK 的请求上游零调用；STRIP 后请求按普通文本对话继续（负载均衡、计费、配额
 * 链路完全不受影响）。</p>
 *
 * <p><b>失败语义（fail-closed，与 B2 同款）</b>：实现抛异常、返回 {@code null}、
 * STRIP 时 {@code allowedTools} 形状非法（{@code null} 列表元素等契约违约），
 * 主干一律按 {@code TOOL_BLOCKED} 拒绝出站。工具治理的失败模式必须是「拒绝」
 * 而不是「放行」。实现内部也建议自行兜底，构成双层保险。</p>
 *
 * <p><b>STRIP 语义（主干执行，实现只出判定）</b>：{@code allowedTools} 为空或缺失
 * → 主干清空 {@code tools}/{@code tool_choice}/{@code wire_messages}，请求降级为
 * 纯文本对话（{@code messages()} 文本视图回落，下游请求体与无工具请求逐字节一致）；
 * 非空 → 主干按给定列表替换 {@code tools}，{@code tool_choice} 与 {@code wire_messages}
 * 保留（历史工具回放由实现自行权衡，见企业侧口径登记）。</p>
 *
 * <p><b>SPI 兼容性</b>：新接口不改变既有接口签名，{@link EnterpriseSpiVersion#VERSION}
 * 保持 3（「v3 续 · 不推版本」口径，先例 {@code OutboundContentInspector}）。社区版由
 * {@code NoopToolGovernanceProvider} 恒注册放行，零行为影响。</p>
 *
 * <p><b>隐私纪律</b>：{@code reason} 会回显给客户端，<b>禁止包含工具入参原文或
 * 命中的敏感片段</b>；{@code ruleId}（规则 / 工具标识）允许回显。</p>
 *
 * @author Trunkline Team
 */
public interface ToolGovernanceProvider {

    /**
     * 治理一次即将出站的 Agent 工具调用请求。
     *
     * <p>主干运行在弹性线程池上，实现允许做本地规则计算（注册表查询 / 正则复检），
     * 不应做长阻塞外部 IO。</p>
     *
     * @param input 模型名、工具定义列表（OpenAI wire 形状）、工具结果回传文本、用户与租户
     * @return 判定；返回 {@code null} 由主干按 fail-closed 处理
     */
    GovernanceDecision govern(GovernanceInput input);

    /** 判定语汇 */
    enum Verdict {
        /** 放行：工具定义与历史原样出站 */
        ALLOW,
        /** 剥离：按 {@code allowedTools} 替换工具定义后出站（见接口 javadoc 的语义分支） */
        STRIP,
        /** 阻断：拒绝出站，请求返回 {@code TOOL_BLOCKED} */
        BLOCK
    }

    /**
     * 治理输入。
     *
     * @param model            模型名
     * @param tools            工具定义列表（OpenAI wire 形状
     *                         {@code [{type:"function",function:{name,description,parameters}}]}）；
     *                         无工具请求时为空列表
     * @param toolResultTexts  工具结果回传文本（按 {@code wire_messages} 中
     *                         {@code role:"tool"} 消息顺序提取）；无回传时为空列表
     * @param toolNames        与 {@code tools} 顺序一一对应的工具名（主干预提取，
     *                         免实现重复解析；解析失败的位置为 {@code null}）
     * @param username         请求方用户名（可为 null）
     * @param tenantId         租户 ID（可为 null；由 {@code TenantContextHolder} 解析）
     */
    record GovernanceInput(String model, List<Map<String, Object>> tools,
                           List<String> toolResultTexts, List<String> toolNames,
                           String username, String tenantId) {
    }

    /**
     * 治理判定。
     *
     * @param verdict      判定语汇
     * @param allowedTools <b>仅 {@code STRIP} 时有效</b>：替换出站的工具定义列表；
     *                     空列表 / 缺失表示全剥离（请求降级纯文本对话）。
     *                     ALLOW / BLOCK 时必须为 {@code null}
     * @param ruleId       命中规则或工具标识（可回显给客户端）
     * @param reason       阻断 / 剥离说明（回显给客户端，<b>禁止包含工具入参原文或敏感片段</b>）
     */
    record GovernanceDecision(Verdict verdict, List<Map<String, Object>> allowedTools,
                              String ruleId, String reason) {

        /** 放行 */
        public static GovernanceDecision allow() {
            return new GovernanceDecision(Verdict.ALLOW, null, null, null);
        }

        /** 全剥离：请求降级为纯文本对话 */
        public static GovernanceDecision stripAll(final String ruleId, final String reason) {
            return new GovernanceDecision(Verdict.STRIP, List.of(), ruleId, reason);
        }

        /** 部分剥离：仅保留给定工具定义出站 */
        public static GovernanceDecision strip(final List<Map<String, Object>> allowedTools,
                                               final String ruleId, final String reason) {
            return new GovernanceDecision(Verdict.STRIP, List.copyOf(allowedTools), ruleId, reason);
        }

        /** 阻断出站 */
        public static GovernanceDecision block(final String ruleId, final String reason) {
            return new GovernanceDecision(Verdict.BLOCK, null, ruleId, reason);
        }
    }
}
