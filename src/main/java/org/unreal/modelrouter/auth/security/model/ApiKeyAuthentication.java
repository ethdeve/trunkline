package org.unreal.modelrouter.auth.security.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * API Key认证对象
 * 实现Spring Security的Authentication接口
 */
@Getter
@Setter
public final class ApiKeyAuthentication implements Authentication {
    
    private final String principal;
    private final String credentials;
    private final Collection<? extends GrantedAuthority> authorities;
    private boolean authenticated = false;
    private Object details;
    private final List<String> allowedGroups;

    /**
     * 【issue #693】发起该请求的真实客户端 IP，由 DefaultAuthenticationConverter 在提取
     * API Key 时用 ClientIpResolver 推导后写入，认证链据此校验 ApiKey 的 IP 白名单。
     * 未认证对象也携带该值；无法判定时为 "unknown"（不是 null），白名单非空时自然拒绝。
     */
    private String clientIp;

    /**
     * 构造函数 - 用于未认证的请求
     */
    public ApiKeyAuthentication(final String apiKey) {
        this.principal = null;
        this.credentials = apiKey;
        this.authorities = List.of();
        this.allowedGroups = null;
    }

    /**
     * 构造函数 - 用于已认证的请求（不携带分组白名单，兼容旧调用方）
     */
    public ApiKeyAuthentication(final String keyId, final String apiKey, final List<String> permissions) {
        this(keyId, apiKey, permissions, null);
    }

    /**
     * 构造函数 - 用于已认证的请求（携带模型分组白名单）
     *
     * <p><b>v3.5 治理收口：</b>原先并存的 {@code allowedModels}（已接入模型 / 实例名白名单）
     * 已移除——对外调用的唯一入口是模型分组（虚拟模型名），实例不再能被直接调用。</p>
     *
     * @param allowedGroups  可访问的模型分组名白名单（null 表示不限制）
     */
    public ApiKeyAuthentication(final String keyId, final String apiKey, final List<String> permissions,
                               final List<String> allowedGroups) {
        this.principal = keyId;
        this.credentials = apiKey;
        this.authorities = permissions != null
                ? permissions.stream()
                    .map(permission -> new SimpleGrantedAuthority("ROLE_" + permission.toUpperCase()))
                    .collect(Collectors.toList())
                : List.of();
        this.allowedGroups = allowedGroups;
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
        return "ApiKeyAuthentication{" 
        + "principal='" + principal + '\''
        + ", authenticated=" + authenticated
                + ", authorities=" + authorities
                + '}';
    }
}