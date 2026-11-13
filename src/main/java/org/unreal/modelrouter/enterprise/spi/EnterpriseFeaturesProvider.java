/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.Map;

/**
 * 企业能力探测（O6）——前端特性开关的唯一数据源。
 *
 * <p><b>背景</b>：商业版复用同一套开源 SPA，靠「后端增强 API + 前端条件渲染」区分能力。
 * 前端需要知道当前部署具备哪些企业能力（SSO / 多租户 / 审计哈希链 / DLP 出境策略等），
 * 才能决定显示企业屏还是「升级企业版」占位。本 SPI 是该探测的<b>唯一权威源</b>。</p>
 *
 * <p><b>社区版兜底</b>：{@code CommunityFeaturesProvider} 返回空能力集
 * （{@code GET /api/v1/enterprise/features} → {@code {}}）；
 * 商业版 {@code trunkline-enterprise.jar} 的 AutoConfiguration 注册自己的
 * {@code EnterpriseFeaturesProvider} Bean（如 {@code {sso:true, tenant:true, ...}}），
 * 本兜底随 {@code @ConditionalOnMissingBean} 自动退位——与
 * {@code NoopBillingProvider} 等五个扩展点同一覆盖模式。</p>
 *
 * <p><b>契约约束</b>：键名使用稳定的小写能力标识（{@code sso} / {@code tenant} /
 * {@code auditChain} / {@code dlp} / {@code billing}），值为 {@code true} 表示可用；
 * 前端把「缺键或 false」一律按不可用渲染，不得假设键恒在。
 * 破坏性变更遵循 {@link EnterpriseSpiVersion} 版本纪律。</p>
 *
 * @author Trunkline Team
 */
public interface EnterpriseFeaturesProvider {

    /**
     * 返回当前部署可用的企业能力集。
     *
     * @return 能力键 → 是否可用；不可变 Map，社区版恒为空
     */
    Map<String, Object> getFeatures();
}
