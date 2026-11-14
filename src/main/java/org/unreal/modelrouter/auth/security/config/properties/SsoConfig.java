package org.unreal.modelrouter.auth.security.config.properties;

/**
 * SSO 本地账号开通策略（A2，与 IdP 连接配置分离）。
 *
 * <p>IdP 连接配置（client-id/secret/端点）在商业版 {@code EnterpriseProperties.sso}；
 * 本配置只管「SSO 登录成功后本地平台账号如何处理」，因为本地账号的创建逻辑在核心
 * {@code AccountManager}，连接器只负责 IdP → {@code EnterpriseUser} 映射、不携带角色。</p>
 *
 * @author Trunkline Team
 */
public class SsoConfig {

    /**
     * 首次 SSO 登录且无对应本地账号时，是否自动建号（JIT）。
     * true：按 email 本地前缀（兜底 externalId）创建启用账号并赋予 {@link #defaultRole}；
     * false：沿用旧行为——无映射即拒绝（EXTERNAL_ACCOUNT_NOT_MAPPED），须管理员预置。
     */
    private boolean autoProvision = true;

    /**
     * JIT 自动建号时赋予的角色（固定角色矩阵按角色裁决 URL 级权限）。默认 USER。
     */
    private String defaultRole = "USER";

    public boolean isAutoProvision() {
        return autoProvision;
    }

    public void setAutoProvision(final boolean autoProvision) {
        this.autoProvision = autoProvision;
    }

    public String getDefaultRole() {
        return defaultRole;
    }

    public void setDefaultRole(final String defaultRole) {
        this.defaultRole = defaultRole;
    }
}
