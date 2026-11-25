/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.List;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;

/**
 * 密级 × 地域出境策略扩展点（#9 数据分级出境）。
 *
 * <p>在路由决策点（{@code ModelServiceRegistry.selectInstance} 标签过滤后、池解析前）
 * 对候选实例按「请求密级 × 实例地域」过滤或拒绝。社区版默认实现恒 ALLOW——
 * <b>零策略时行为完全不变</b>；企业版覆盖实现读出境策略表并返回实际判定。</p>
 *
 * <p><b>违规动作</b>：9-C 建议口径「默认 DENY fail-closed + 可选 REROUTE」。
 * {@link Verdict#DENY} 为主干拒绝（403）；{@link Verdict#REROUTE} 由策略直接给出
 * 允许的实例子集（切境内/私有实例），主干按该子集继续路由。<b>log-only 灰度</b>
 * 由策略返回 {@link Verdict#ALLOW} 但在 {@code reason} 中记「仅记录」实现——灰度期
 * 不拦截、只留痕，企业侧据此写审计。</p>
 *
 * <p><b>失败语义（硬约束）</b>：主干对「返回 null / 抛异常 / 决策含 null」一律
 * <b>fail-closed 拒绝</b>——防泄露的默认姿态必须是"拒绝"而非"放行"。</p>
 *
 * <p><b>SPI 兼容性</b>：新接口不改变既有接口签名，{@link EnterpriseSpiVersion#VERSION}
 * 保持 3。社区版由 {@code CommunityClassificationRoutingPolicy} 恒 ALLOW 兜底。</p>
 *
 * @author Trunkline Team
 */
public interface ClassificationRoutingPolicy {

    /**
     * 对候选实例做出境策略判定。
     *
     * @param classification 请求密级（服务端注入，非客户端自报）
     * @param candidates     当前候选实例（已经过规则动作与标签过滤）
     * @param resolver       地域解析器，用于逐个取实例地域视图
     * @return 策略决策；返回 {@code null} 由主干按 fail-closed 处理
     */
    PolicyDecision decide(DataClassification classification, List<ModelInstance> candidates,
                          DataResidencyResolver resolver);

    /** 违规动作语汇 */
    enum Verdict {
        /** 放行：全部候选可用 */
        ALLOW,
        /** 拒绝：整个请求 DENY（403，fail-closed） */
        DENY,
        /** 重路由：仅用 {@code allowedInstances} 给出的实例子集继续 */
        REROUTE
    }

    /**
     * 策略决策。
     *
     * @param verdict          判定语汇
     * @param allowedInstances 仅 REROUTE 时有效：允许继续的实例子集（不可为 null/空）
     * @param policyId         命中的策略标识（可回显 / 留痕）
     * @param reason           说明（回显给客户端，禁止包含 prompt 原文）
     */
    record PolicyDecision(Verdict verdict, List<ModelInstance> allowedInstances,
                          String policyId, String reason) {

        /** 放行全部（共享语义） */
        public static PolicyDecision allow() {
            return new PolicyDecision(Verdict.ALLOW, null, null, null);
        }

        /** 拒绝 */
        public static PolicyDecision deny(final String policyId, final String reason) {
            return new PolicyDecision(Verdict.DENY, null, policyId, reason);
        }

        /** 重路由到指定实例子集 */
        public static PolicyDecision reroute(final List<ModelInstance> allowed,
                                             final String policyId, final String reason) {
            return new PolicyDecision(Verdict.REROUTE, allowed, policyId, reason);
        }

        /** 决策自洽性校验：REROUTE 必须有非空子集，其余动作不得带子集 */
        public boolean isValid() {
            if (verdict == null) {
                return false;
            }
            if (verdict == Verdict.REROUTE) {
                return allowedInstances != null && !allowedInstances.isEmpty();
            }
            return true;
        }
    }
}
