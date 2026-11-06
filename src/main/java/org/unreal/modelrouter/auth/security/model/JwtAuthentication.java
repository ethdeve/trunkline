package org.unreal.modelrouter.auth.security.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JWT令牌认证对象
 * 实现Spring Security的Authentication接口
 *
 * <p>authority 只含角色（{@code ROLE_} 前缀）。社区版已移除可配置 RBAC 权限码体系，
 * URL 级授权由固定角色矩阵（auth.security.access 包）基于角色裁决。</p>
 */
@Getter
@Setter
public final class JwtAuthentication implements Authentication {

    private final String principal;
    private final String credentials;
    private final Collection<? extends GrantedAuthority> authorities;
    private boolean authenticated = false;
    private Object details;

    /**
     * 构造函数 - 用于未认证的请求
     */
    public JwtAuthentication(final String token) {
        this.principal = null;
        this.credentials = token;
        this.authorities = List.of();
    }

    /**
     * 构造函数 - 用于已认证的请求
     *
     * <p>角色映射为 {@code ROLE_} 前缀的 authority（与 API-Key 的 ROLE_* 语义一致），
     * 供固定角色矩阵（FixedRoleAuthorizationManager）与方法级 hasRole 裁决。
     *
     * @param subject 用户名
     * @param token   原始 JWT
     * @param roles   角色列表（写入 ROLE_ 前缀 authority）
     */
    public JwtAuthentication(final String subject, final String token, final List<String> roles) {
        this.principal = subject;
        this.credentials = token;
        this.authorities = buildAuthorities(roles);
    }

    /**
     * 构建 authority 集合：角色加 ROLE_ 前缀
     */
    private static Collection<? extends GrantedAuthority> buildAuthorities(final List<String> roles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roles != null) {
            roles.stream()
                    .filter(role -> role != null && !role.trim().isEmpty())
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase()))
                    .forEach(authorities::add);
        }
        return authorities;
    }

    @Override
    public String getName() {
        return principal != null ? principal : "anonymous";
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getDetails() {
        return details;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public void setAuthenticated(final boolean isAuthenticated) throws IllegalArgumentException {
        this.authenticated = isAuthenticated;
    }

    @Override
    public String toString() {
        return "JwtAuthentication{"
        + "principal='" + principal + '\''
        + ", authenticated=" + authenticated
                + ", authorities=" + authorities
                + '}';
    }
}
