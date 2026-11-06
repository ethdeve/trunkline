package org.unreal.modelrouter.auth.security.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JWT令牌主体对象
 * 实现Java Security的Principal接口
 * 包含JWT令牌的详细信息
 */
@Getter
@RequiredArgsConstructor
public final class JwtPrincipal implements Principal {
    
    private final String subject;
    private final String issuer;
    private final List<String> roles;
    private final LocalDateTime issuedAt;
    private final LocalDateTime expiresAt;
    private final Map<String, Object> claims;
    
    @Override
    public String getName() {
        return subject;
    }
    
    /**
     * 检查是否具有指定角色
     */
    public boolean hasRole(final String role) {
        return roles != null && roles.contains(role);
    }
    
    /**
     * 检查是否具有管理员角色
     */
    public boolean isAdmin() {
        return hasRole("admin") || hasRole("ADMIN");
    }
    
    /**
     * 检查是否具有用户角色
     */
    public boolean isUser() {
        return hasRole("user") || hasRole("USER");
    }
    
    /**
     * 检查令牌是否已过期
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * 获取指定的声明值
     */
    public Object getClaim(final String claimName) {
        return claims != null ? claims.get(claimName) : null;
    }
    
    /**
     * 获取字符串类型的声明值
     */
    public String getStringClaim(final String claimName) {
        Object claim = getClaim(claimName);
        return claim != null ? claim.toString() : null;
    }

    /**
     * 获取令牌归属租户（多租户预埋，S2 任务 40）。
     *
     * <p>老令牌无 {@code tenantId} claim，缺省视为 {@code default} 租户，
     * 不强制任何人重新登录。社区版恒为 {@code default}。
     */
    public String getTenantId() {
        final String tenantId = getStringClaim("tenantId");
        return tenantId == null || tenantId.isBlank()
                ? org.unreal.modelrouter.enterprise.noop.SingleTenantContext.DEFAULT_TENANT_ID
                : tenantId;
    }

    /**
     * 是否平台超管（角色预留，S2 任务 39；社区版不会签发该角色）
     */
    public boolean isPlatformAdmin() {
        return hasRole("PLATFORM_ADMIN");
    }

    /**
     * 是否租户管理员（角色预留，S2 任务 39；社区版不会签发该角色）
     */
    public boolean isTenantAdmin() {
        return hasRole("TENANT_ADMIN");
    }
    
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JwtPrincipal that = (JwtPrincipal) o;
        return Objects.equals(subject, that.subject)
        && Objects.equals(issuer, that.issuer);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(subject, issuer);
    }
    
    @Override
    public String toString() {
        return "JwtPrincipal{" 
        + "subject='" + subject + '\''
        + ", issuer='" + issuer + '\''
                + ", roles=" + roles
                + ", expiresAt=" + expiresAt
                + '}';
    }
}