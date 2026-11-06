package org.unreal.modelrouter.auth.security.authentication.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import org.unreal.modelrouter.common.util.TokenHashUtils;
import org.unreal.modelrouter.auth.security.authentication.JwtTokenValidator;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtPrincipal;
import org.unreal.modelrouter.auth.security.service.AccountManager;
import org.unreal.modelrouter.auth.security.service.EnhancedJwtBlacklistService;
import org.unreal.modelrouter.auth.security.service.JwtRefreshClaimResolver;
import org.unreal.modelrouter.auth.security.service.JwtRefreshIdentityException;
import org.unreal.modelrouter.auth.security.service.JwtRefreshIdentityResolver;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JWT令牌验证器默认实现
 * 提供JWT令牌的验证、解析、刷新和黑名单管理功能
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trunkline.security.jwt.enabled", havingValue = "true")
public class DefaultJwtTokenValidator implements JwtTokenValidator {

    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:jti:";
    private static final String ROLES_CLAIM = "roles";
    private static final String USER_ID_CLAIM = "userId";

    /** 黑名单存储不可用后的短路窗口默认值（issue #76）。 */
    private static final long DEFAULT_DEGRADED_RETRY_INTERVAL_MILLIS = 30_000L;

    private final SecurityProperties securityProperties;
    private final ReactiveStringRedisTemplate redisTemplate;

    /** 降级告警是否已打过（issue #76：只打一次，避免每请求刷屏）。 */
    private final AtomicBoolean legacyDegradationLogged = new AtomicBoolean(false);

    /** 降级短路窗口截止时间戳；0 表示未处于降级状态。 */
    private volatile long legacyRedisRetryAfterMillis = 0L;

    // 使用增强的黑名单服务（可选依赖）
    @Autowired(required = false)
    private EnhancedJwtBlacklistService enhancedBlacklistService;

    /**
     * 账户服务（可选依赖）：刷新时用于重新加载用户当前角色。
     *
     * <p>与 {@code AccountManager} 形成单向依赖（validator → accountManager → rolePermissionService），
     * 不存在循环引用；AccountManager 不依赖 validator。
     */
    @Autowired(required = false)
    private AccountManager accountManager;

    @Override
    public Mono<Authentication> validateToken(final String token) {
        // 检查令牌是否在黑名单中
        return isTokenBlacklisted(token)
            .flatMap(isBlacklisted -> {
                if (isBlacklisted) {
                    return Mono.error(new AuthenticationException("JWT令牌已被列入黑名单", "JWT_BLACKLISTED"));
                }

                try {
                    // 解析和验证JWT令牌
                    Claims claims = parseToken(token);

                    // 检查令牌是否过期
                    if (claims.getExpiration().before(new Date())) {
                        return Mono.error(new AuthenticationException("JWT令牌已过期", "JWT_EXPIRED"));
                    }

                    // 提取用户信息
                    String subject = claims.getSubject();
                    String issuer = claims.getIssuer();
                    List<String> roles = extractRoles(claims);

                    LocalDateTime issuedAt = convertToLocalDateTime(claims.getIssuedAt());
                    LocalDateTime expiresAt = convertToLocalDateTime(claims.getExpiration());

                    // 创建JWT主体对象
                    JwtPrincipal principal = new JwtPrincipal(
                        subject,
                        issuer,
                        roles,
                        issuedAt,
                        expiresAt,
                        new HashMap<>(claims)
                    );

                    // 创建认证对象（角色→ROLE_ 前缀 authority；URL 级授权由固定角色矩阵裁决）
                    JwtAuthentication authentication =
                            new JwtAuthentication(subject, token, roles);
                    authentication.setAuthenticated(true);
                    authentication.setDetails(principal);

                    log.debug("JWT令牌验证成功: subject={}, roles={}",
                            subject, roles);
                    return Mono.just((Authentication) authentication);

                } catch (JwtException e) {
                    log.warn("JWT令牌验证失败: {}", e.getMessage());
                    return Mono.error(new AuthenticationException("JWT令牌验证失败: " + e.getMessage(), "JWT_INVALID"));
                } catch (Exception e) {
                    log.error("JWT令牌验证过程中发生错误", e);
                    return Mono.error(new AuthenticationException("JWT令牌验证过程中发生错误", "JWT_VALIDATION_ERROR"));
                }
            });
    }

    @Override
    public Mono<String> refreshToken(final String token) {
        try {
            // 解析当前令牌
            Claims claims = parseToken(token);

            // 检查是否可以刷新（通常检查是否在刷新窗口内）
            Date now = new Date();
            Date expiration = claims.getExpiration();
            long refreshWindowMs = Duration.ofDays(securityProperties.getJwt().getRefreshExpirationDays()).toMillis();

            if (now.getTime() - expiration.getTime() > refreshWindowMs) {
                return Mono.error(new AuthenticationException("JWT令牌超出刷新窗口", "JWT_REFRESH_EXPIRED"));
            }

            // 将旧令牌加入黑名单
            return blacklistToken(token)
                .then(Mono.fromCallable(() -> {
                    // 创建新令牌
                    final String subject = claims.getSubject();

                    // P0-L 安全修复（漏洞 2）：刷新时**重算**角色，绝不沿用旧声明。
                    //
                    // 旧实现把旧令牌的 roles/permissions claim 原样拷进新令牌，导致角色被调整后，
                    // 持有旧令牌的用户只要刷新一次就把旧角色带回来。这里从权威来源
                    // （账户服务）重新取值。社区版已移除权限码体系，令牌只携带角色。
                    //
                    // fail-closed：重算失败一律抛异常让刷新失败 —— 宁可让用户重新登录，
                    // 也不能签发一个仍带陈旧角色的令牌。
                    final JwtRefreshIdentityResolver.RefreshIdentity identity =
                            resolveRefreshIdentity(subject);

                    // P0-L 安全修复（漏洞 1）：复制自定义声明时剔除 jti/roles/permissions。
                    //
                    // 旧实现只排除了 sub/iss/exp/iat/nbf，jti 被一并拷进 additionalClaims，
                    // 而 generateToken 里 additionalClaims 是最后应用的（覆盖 .id() 设的新 jti），
                    // 于是新令牌 jti == 旧令牌 jti；而旧令牌在刷新瞬间已被拉黑 →
                    // 新令牌一签发就是黑名单里的，下次请求立刻 401
                    // （实测：前端每 30 分钟自动刷新一次 → 用户每 30 分钟被踢出登录）。
                    final Map<String, Object> additionalClaims =
                            JwtRefreshClaimResolver.buildRefreshClaims(
                                    new HashMap<>(claims),
                                    identity.getRoles());

                    return generateToken(subject, identity.getRoles(), additionalClaims);
                }));

        } catch (JwtException e) {
            log.warn("JWT令牌刷新失败: {}", e.getMessage());
            return Mono.error(new AuthenticationException("JWT令牌刷新失败: " + e.getMessage(), "JWT_REFRESH_FAILED"));
        } catch (Exception e) {
            log.error("JWT令牌刷新过程中发生错误", e);
            return Mono.error(new AuthenticationException("JWT令牌刷新过程中发生错误", "JWT_REFRESH_ERROR"));
        }
    }

    /**
     * 刷新令牌时重算身份（角色）。
     *
     * <p><b>权威来源</b>：{@link AccountManager#loadUserByUsername(String)} 返回的
     * authorities（YAML 静态账户优先，DB 账户兜底）。</p>
     *
     * <p><b>fail-closed</b>：来源不可用（bean 缺失 / 查库失败 / 返回 null）都抛
     * {@link JwtRefreshIdentityException}，由 {@link #refreshToken(String)} 转为刷新失败。
     * 绝不回退到旧令牌里的角色声明 —— 那正是漏洞 2 的根因。</p>
     *
     * @param subject 令牌主体（用户名）
     * @return 重算出的角色
     * @throws JwtRefreshIdentityException 重算失败
     */
    private JwtRefreshIdentityResolver.RefreshIdentity resolveRefreshIdentity(final String subject) {
        if (accountManager == null) {
            throw new JwtRefreshIdentityException(
                    "刷新令牌所需的账户服务不可用，拒绝签发新令牌");
        }
        final JwtRefreshIdentityResolver resolver = new JwtRefreshIdentityResolver(
                this::loadCurrentRoles);
        return resolver.resolve(subject);
    }

    /**
     * 读取用户当前生效的角色列表。
     *
     * <p>取 {@code UserDetails.getAuthorities()} 并去掉 {@code ROLE_} 前缀
     * （{@link JwtAuthentication} 用的是无前缀的 {@code ROLE_} + 角色名形式，
     * 而 {@link AccountManager} 存的是裸角色名，此处统一成裸角色名）。
     *
     * @param subject 用户名
     * @return 角色列表（不可为 null）
     */
    private List<String> loadCurrentRoles(final String subject) {
        final org.springframework.security.core.userdetails.UserDetails userDetails =
                accountManager.loadUserByUsername(subject);
        if (userDetails == null || userDetails.getAuthorities() == null) {
            return null;
        }
        return userDetails.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && !authority.trim().isEmpty())
                // ROLE_USER → USER；裸角色名（AccountManager 存的形态）保持原样
                .map(authority -> authority.trim().toUpperCase(java.util.Locale.ROOT)
                        .startsWith("ROLE_")
                        ? authority.trim().toUpperCase(java.util.Locale.ROOT).substring("ROLE_".length())
                        : authority.trim().toUpperCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
    }

    @Override
    public Mono<Boolean> isTokenBlacklisted(final String token) {
        if (!securityProperties.getJwt().isBlacklistEnabled()) {
            return Mono.just(false);
        }

        try {
            // 优先使用增强的黑名单服务
            if (enhancedBlacklistService != null) {
                String tokenId = extractTokenId(token);
                // C1: 鉴权侧 jti 键与管理台撤销侧 tokenHash 键都查。
                // 管理台撤销（revokeTokenByHash → RedisBlacklistOperations）写的是
                // jwt:blacklist:entry:<tokenHash>（hex），只查 jti 键会漏拦。
                return enhancedBlacklistService.isBlacklisted(tokenId)
                    .flatMap(isBlacklisted -> isBlacklisted
                            ? Mono.just(true)
                            : enhancedBlacklistService.isBlacklisted(TokenHashUtils.hashToken(token)))
                    .doOnNext(isBlacklisted -> {
                        if (isBlacklisted) {
                            log.warn("令牌在增强黑名单中被发现: tokenId={}", tokenId);
                        }
                    });
            }

            // 降级到原有的Redis检查
            Claims claims = parseToken(token);
            String jti = claims.getId();

            if (jti == null) {
                // 如果没有JTI，使用令牌的哈希值
                jti = calculateTokenHash(token);
            }

            final String finalJti = jti; // 创建final变量供lambda使用
            String blacklistKey = BLACKLIST_KEY_PREFIX + finalJti;

            // 存储已判定不可用时短路，避免每个请求都付一次连接超时（issue #76）
            if (System.currentTimeMillis() < legacyRedisRetryAfterMillis) {
                boolean failClosed = isBlacklistFailClosedWhenUnavailable();
                log.debug("黑名单存储处于降级短路窗口，跳过 Redis 查询: jti={}, failClosed={}",
                        finalJti, failClosed);
                // failClosed=false：历史行为，短路窗口内放行；true：按黑名单拒绝（issue #118）
                return Mono.just(failClosed);
            }

            return redisTemplate.hasKey(blacklistKey)
                .doOnNext(isBlacklisted -> {
                    if (isBlacklisted) {
                        log.warn("令牌在Redis黑名单中被发现: jti={}", finalJti);
                    }
                })
                // 查询成功即证明存储可用：清除降级状态，恢复后重新生效
                .doOnNext(ignored -> clearLegacyDegradation())
                .onErrorResume(ex -> {
                    legacyRedisRetryAfterMillis =
                            System.currentTimeMillis() + DEFAULT_DEGRADED_RETRY_INTERVAL_MILLIS;
                    boolean failClosed = isBlacklistFailClosedWhenUnavailable();
                    // 降级语义必须让运维可见，但只在首次明确告警，其后降为 DEBUG
                    // （原实现每个请求打两条 ERROR，会掩盖真正的鉴权失败——issue #76）
                    if (legacyDegradationLogged.compareAndSet(false, true)) {
                        log.error("JWT 黑名单校验已降级：黑名单存储不可达，failClosed={}，"
                                + "撤销/登出的令牌在存储恢复前{}，存在安全风险。error={}, jti={}",
                                failClosed,
                                failClosed ? "会被拦截（当前按黑名单拒绝）" : "不会被拦截",
                                ex.getMessage(), finalJti);
                    } else {
                        log.debug("黑名单存储仍不可用，继续降级放行: jti={}, failClosed={}, error={}",
                                finalJti, failClosed, ex.getMessage());
                    }
                    // 默认允许（failClosed=false，降级已在首次告警中说明）；
                    // failClosed=true 时按黑名单拒绝（issue #118）
                    return Mono.just(failClosed);
                });

        } catch (Exception e) {
            log.error("检查JWT令牌黑名单状态时发生严重错误: {}", e.getMessage(), e);
            return Mono.just(false); // 出错时默认不在黑名单中，但记录错误
        }
    }

    /**
     * 黑名单存储恢复可用时清除降级状态（幂等）.
     */
    private void clearLegacyDegradation() {
        legacyRedisRetryAfterMillis = 0L;
        if (legacyDegradationLogged.compareAndSet(true, false)) {
            log.info("JWT 黑名单存储已恢复，Redis 黑名单校验重新生效");
        }
    }

    @Override
    public Mono<Void> blacklistToken(final String token) {
        if (!securityProperties.getJwt().isBlacklistEnabled()) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            try {
                Claims claims = parseToken(token);

                // 计算令牌剩余有效期
                Date expiration = claims.getExpiration();
                long ttlSeconds = Math.max(0, (expiration.getTime() - System.currentTimeMillis()) / 1000);

                if (ttlSeconds <= 0) {
                    log.debug("令牌已过期，无需加入黑名单");
                    return Mono.<Void>empty();
                }

                // 优先使用增强的黑名单服务
                if (enhancedBlacklistService != null) {
                    String tokenId = extractTokenId(token);
                    return enhancedBlacklistService.addToBlacklist(tokenId, ttlSeconds)
                        .doOnNext(success -> {
                            if (success) {
                                log.info("令牌已通过增强服务加入黑名单: tokenId={}, ttl={}s", tokenId, ttlSeconds);
                            } else {
                                log.error("通过增强服务加入黑名单失败: tokenId={}", tokenId);
                            }
                        })
                        .then();
                }

                // 降级到原有的Redis方式
                String jti = claims.getId();
                if (jti == null) {
                    jti = calculateTokenHash(token);
                }

                final String finalJti = jti; // 创建final变量供lambda使用
                String blacklistKey = BLACKLIST_KEY_PREFIX + finalJti;

                return redisTemplate.opsForValue()
                    .set(blacklistKey, "blacklisted", Duration.ofSeconds(ttlSeconds))
                    .doOnNext(success -> {
                        if (success) {
                            log.info("令牌已通过Redis加入黑名单: jti={}, ttl={}s", finalJti, ttlSeconds);
                        } else {
                            log.error("通过Redis加入黑名单失败: jti={}", finalJti);
                        }
                    })
                    .then();

            } catch (Exception e) {
                log.error("将JWT令牌加入黑名单时发生错误: {}", e.getMessage(), e);
                return Mono.<Void>empty(); // 出错时静默失败
            }
        }).flatMap(mono -> mono);
    }

    @Override
    public Mono<String> extractUserId(final String token) {
        return Mono.fromCallable(() -> {
            try {
                Claims claims = parseToken(token);
                String userId = claims.get(USER_ID_CLAIM, String.class);

                if (userId == null) {
                    userId = claims.getSubject(); // 如果没有userId声明，使用subject
                }

                return userId;

            } catch (JwtException e) {
                throw new AuthenticationException(
                        "无法从JWT令牌中提取用户ID: " + e.getMessage(),
                        "JWT_USER_ID_EXTRACTION_FAILED");
            }
        });
    }

/**
     * 生成新的JWT令牌（使用JJWT 0.12.x API）
     *
     * <p><b>声明应用顺序（P0-L 漏洞 1）</b>：{@code additionalClaims} 必须**先**应用，
     * {@code .id()} 必须**后**应用。JJWT 的 {@code claim("jti", x)} 会覆盖 {@code id()}，
     * 若 additionalClaims 里残留旧 jti（刷新时从旧令牌拷贝而来），新令牌就会继承旧 jti；
     * 而旧令牌在刷新瞬间已被拉黑 → 新令牌一签发即失效，下次请求 401。
     * 这里让 {@code .id()} 最后生效，从根上保证每个令牌都有唯一 jti。
     */
    public String generateToken(
            final String subject, final List<String> roles,
            final Map<String, Object> additionalClaims) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() 
        + Duration.ofMinutes(securityProperties.getJwt().getExpirationMinutes()).toMillis());

        JwtBuilder builder = Jwts.builder()
                .subject(subject)
                .issuer(securityProperties.getJwt().getIssuer())
                .issuedAt(now)
                .expiration(expiration)
                .claim(ROLES_CLAIM, roles);

        // 添加额外的声明（先于 .id()，避免旧 jti 覆盖新 jti）
        if (additionalClaims != null) {
            additionalClaims.forEach((key, value) -> {
                // jti 由本方法统一生成，忽略调用方传入的值
                if (!JwtRefreshClaimResolver.CLAIM_JTI.equals(key)) {
                    builder.claim(key, value);
                }
            });
        }

        // JTI 最后设置 —— 保证唯一（黑名单以 jti 为键）
        builder.id(UUID.randomUUID().toString());

        return builder.signWith(getSigningKey()).compact();
    }

    /**
     * 解析JWT令牌
     */
    private Claims parseToken(final String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            // 签发端始终写入 iss（见 generateToken / AccountManager）；解析端强制校验（issue #117）
            .requireIssuer(securityProperties.getJwt().getIssuer())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /**
     * 黑名单存储不可用时是否拒绝令牌（fail-closed）。默认 false 保持历史放行语义（issue #118）。
     */
    private boolean isBlacklistFailClosedWhenUnavailable() {
        return securityProperties.getJwt().isBlacklistFailClosedWhenUnavailable();
    }

    /**
     * 获取签名密钥
     */
    private SecretKey getSigningKey() {
        String secret = securityProperties.getJwt().getSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("JWT签名密钥未配置");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 从Claims中提取角色列表
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRoles(final Claims claims) {
        Object rolesObj = claims.get(ROLES_CLAIM);

        if (rolesObj instanceof List) {
            return (List<String>) rolesObj;
        } else if (rolesObj instanceof String) {
            return Arrays.asList(((String) rolesObj).split(","));
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * 将Date转换为LocalDateTime
     */
    private LocalDateTime convertToLocalDateTime(final Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * 提取令牌ID（用于黑名单）
     */
    private String extractTokenId(final String token) {
        try {
            Claims claims = parseToken(token);
            String jti = claims.getId();

            if (jti != null && !jti.trim().isEmpty()) {
                return jti.trim();
            }

            // 如果没有JTI，使用令牌的哈希值
            return calculateTokenHash(token);

        } catch (Exception e) {
            log.warn("提取令牌ID失败，使用哈希值: {}", e.getMessage());
            return calculateTokenHash(token);
        }
    }

    /**
     * 计算令牌哈希值
     */
    private String calculateTokenHash(final String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception ex) {
            log.warn("计算令牌SHA-256哈希失败，使用简单哈希: {}", ex.getMessage());
            return String.valueOf(token.hashCode());
        }
    }
}