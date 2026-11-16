/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

/**
 * 企业扩展点（SPI）契约版本号（O11）。
 *
 * <p>企业仓以 {@code provided} 编译期绑定主干 SPI；主干是 Apache-2.0 开源，随时可能改接口
 * 签名且企业仓无感知——升级时会在客户现场炸 {@code NoSuchMethodError}。本常量类把
 * 「SPI 是否发生破坏性变更」变成可编程检查：</p>
 *
 * <ul>
 *   <li><b>主干侧约定</b>：对任何 SPI 接口/record 做<b>破坏性变更</b>（删字段、改签名、
 *       改语义），必须 {@code VERSION + 1} 并在本类注释登记变更记录；仅新增方法/字段
 *       （带默认实现或兼容构造器）不算破坏性。</li>
 *   <li><b>企业侧约定</b>：编译目标版本以常量 {@code EXPECTED_SPI_VERSION} 固化在企业仓，
 *       启动时比对——不一致即 fail-fast 并给出可读错误（企业版
 *       {@code EnterpriseAutoConfiguration#spiVersionGate}）。</li>
 *   <li><b>业务规则</b>：主干改 SPI = 破坏性变更，必须同步在企业仓发版，CHANGELOG 标注
 *       {@code [BREAKING-SPI]}。</li>
 * </ul>
 *
 * <p><b>版本记录</b>：</p>
 * <ul>
 *   <li>{@code v1}：初始五接口（{@code SsoConnector} / {@code BillingProvider} /
 *       {@code ModerationProvider} / {@code LicenseVerifier} / {@code TenantContext}）。</li>
 *   <li>{@code v2}（O7-c）：{@code ModerationInput} 新增 {@code tenantId} 组件——多租户
 *       「按租户配置审核策略」需要；保留三参兼容构造器（tenantId=null），存量编译不受影响，
 *       但企业实现应改用四参构造以参与多租户隔离判定。</li>
 *   <li>{@code v3}（B2，{@code [BREAKING-SPI]}）：{@code BillingProvider.BillingEntry} 新增
 *       {@code traceId} 首组件作为真实账本幂等键（一次调用一条，重放不重复扣费）；保留五参
 *       兼容构造器，但 record 组件变化破坏了二进制兼容。同批新增 {@code precheck(BillingPrecheck)}
 *       default 方法与两个新 record（属兼容扩展，不额外推版本）。企业仓
 *       {@code EXPECTED_SPI_VERSION} 必须同步改为 3，两仓同批发版。</li>
 *   <li>{@code v3 续}（B1，兼容扩展不推版本）：新增 {@code AuditChainListener} 接口
 *       （审计哈希链订阅出口，全方法 default + 嵌套 record）——新接口不改变既有接口签名，
 *       社区版无 Bean 时零开销；{@code VERSION} 保持 3。</li>
 *   <li>{@code v3 续}（B2，兼容扩展不推版本）：新增 {@code OutboundContentInspector} 接口
 *       （出站 DLP 检查扩展点，嵌套 record / Verdict）——同 B1 口径，新接口不改既有签名，
 *       社区版 Noop 恒注册放行；{@code VERSION} 保持 3。失败语义与 O7-c 相反：
 *       实现可抛异常，主干兜底 fail-closed 拒绝出站。</li>
 *   <li>{@code v3 续}（M4，兼容扩展不推版本）：新增 {@code ToolGovernanceProvider} 接口
 *       （Agent 工具治理扩展点，嵌套 record / Verdict）——同 B1/B2 口径，新接口不改既有
 *       签名，社区版 Noop 恒注册放行；{@code VERSION} 保持 3。失败语义同 B2：实现可抛
 *       异常，主干兜底 fail-closed 拒绝出站（{@code TOOL_BLOCKED}）。</li>
 * </ul>
 *
 * @author Trunkline Team
 */
public final class EnterpriseSpiVersion {

    /** 当前 SPI 契约版本。破坏性变更必须 +1 并在类注释登记。 */
    public static final int VERSION = 3;

    private EnterpriseSpiVersion() {
    }
}
