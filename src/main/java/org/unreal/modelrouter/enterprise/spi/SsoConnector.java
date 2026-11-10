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

    /** 企业用户身份（映射为本地 {@code jwt_accounts} 账号后签发平台 JWT） */
    record EnterpriseUser(String externalId, String displayName, String email, String department) {
    }
}
