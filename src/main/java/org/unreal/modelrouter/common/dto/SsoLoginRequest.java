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

    /** SSO 授权流程的 state（CSRF 校验用）；由 /sso/authorize 生成并种入 cookie，回调时原样回传 */
    private String state;

    public SsoLoginRequest() {
    }

    public String getCode() {
        return this.code;
    }

    public void setCode(final String code) {
        this.code = code;
    }

    public String getState() {
        return this.state;
    }

    public void setState(final String state) {
        this.state = state;
    }

    @Override
    public String toString() {
        // 授权码与 state 都是短期凭证，禁止进日志
        return "SsoLoginRequest(code=***, state=***)";
    }
}
