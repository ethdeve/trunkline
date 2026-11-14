/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.Optional;

/**
 * 单点登录扩展点（商业版功能，S2 任务 32）。
 *
 * <p>企业 IdP（OIDC / LDAP / 飞书钉钉扫码等）接入入口；社区版由
 * {@code NoopSsoConnector} 返回不支持，登录仍走本地账号密码。
 * @author Trunkline Team
 */
public interface SsoConnector {

    /** IdP 标识（登录页展示用，如 {@code feishu}、{@code oidc}） */
    String providerName();

    /**
     * 用授权码换取企业用户身份。
     *
     * @param authorizationCode IdP 回传的授权码
     * @return 命中则返回企业用户；空表示授权码无效或未配置
     */
    Optional<EnterpriseUser> exchange(String authorizationCode);

    /**
     * 构造 IdP 授权端点的 front-channel 重定向 URL（授权码流程第一步，A2 补链）。
     *
     * <p>开源主干 {@code GET /api/auth/jwt/sso/authorize} 生成 {@code state} 后调用本方法，
     * 拿到 URL 即 302 跳转；回调携带 {@code code} 落到 {@code POST /api/auth/jwt/sso}
     * 完成 {@link #exchange}。企业实现按 provider 拼装 {@code client_id/redirect_uri/
     * scope/state} 等参数，<b>绝不可把 client_secret 拼进 URL</b>（front-channel 明文）。</p>
     *
     * <p><b>默认实现返回 empty</b>：社区版 {@code NoopSsoConnector} 与旧版企业 jar（未重新编译）
     * 都因此安全地关闭跳转入口，不抛 {@code AbstractMethodError}。这是 O11 契约允许的
     * 「仅新增带默认实现的方法」，不算破坏性变更，{@code EnterpriseSpiVersion.VERSION} 保持 v2。</p>
     *
     * @param state 主干生成的一次性随机值（CSRF），实现必须原样拼入 URL 的 state 参数
     * @return 绝对 http/https 授权 URL；empty 表示未启用或配置不完整（fail-closed，端点返回
     *         {@code SSO_UNCONFIGURED}）
     */
    default Optional<String> authorizationRedirect(final String state) {
        return Optional.empty();
    }

    /** 企业用户身份（映射为本地 {@code jwt_accounts} 账号后签发平台 JWT） */
    record EnterpriseUser(String externalId, String displayName, String email, String department) {
    }
}
