package org.unreal.modelrouter.common.dto;

/**
 * SSO 登录请求体（O7-a）。
 *
 * <p>企业 IdP 回调把授权码回传给 {@code POST /api/auth/jwt/sso}；
 * 社区版该端点恒返回 {@code SSO_UNSUPPORTED}（{@code NoopSsoConnector.providerName()}
 * 为空串判别），请求体不会被消费。</p>
 *
 * @author Trunkline Team
 */
public final class SsoLoginRequest {
    private String code;

    public SsoLoginRequest() {
    }

    public String getCode() {
        return this.code;
    }

    public void setCode(final String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        // 授权码是短期凭证，禁止进日志
        return "SsoLoginRequest(code=***)";
    }
}
