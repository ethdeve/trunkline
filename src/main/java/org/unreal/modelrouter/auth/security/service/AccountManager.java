package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.config.properties.JwtAccountProperties;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.config.properties.SsoConfig;
import org.unreal.modelrouter.auth.security.audit.ExtendedSecurityAuditService;
import org.unreal.modelrouter.auth.security.dto.ApiKeyVO;
import org.unreal.modelrouter.auth.security.util.RoleScopeParser;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.enterprise.spi.SsoConnector;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trunkline.security.enabled", havingValue = "true")
public class AccountManager implements UserDetailsService {

    private final SecurityProperties securityProperties;
    private final PasswordEncoder passwordEncoder;
    private final JwtAccountRepository jwtAccountRepository;
    private final ObjectMapper objectMapper;

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("=== AccountManager initialized ===");
        log.info("JWT enabled: {}", securityProperties.getJwt().isEnabled());
        log.info("JWT accounts count: {}", 
            securityProperties.getJwt().getAccounts() != null ? securityProperties.getJwt().getAccounts().size() : 0);
    }

    // 审计服务（用于记录认证操作）
    @Autowired(required = false)
    private ExtendedSecurityAuditService auditService;

    /**
     * 账户状态守卫（可选依赖）：停用账号后立即失效其 JWT 校验缓存，
     * 使已签发令牌在 {@code AccountStatusGuard} 的 30s TTL 窗口内即时失效。
     * 反向依赖 {@code AccountManager}，用 {@link Lazy} 打破初始化环。
     */
    @Autowired(required = false)
    @Lazy
    private AccountStatusGuard accountStatusGuard;

    /**
     * API Key 服务（可选依赖）：停用账号时吊销其持有的全部密钥。
     * 反向依赖链经 {@code JwtAccountService}，用 {@link Lazy} 打破初始化环。
     */
    @Autowired(required = false)
    @Lazy
    private ApiKeyService apiKeyService;

    /**
     * 验证用户凭据
     *
     * @param username 用户名
     * @param password 密码
     * @return 如果凭据有效返回true，否则返回false
     */
    public boolean validateCredentials(final String username, final String password) {
        if (!securityProperties.getJwt().isEnabled()) {
            log.warn("JWT认证功能未启用");
            return false;
        }

        // 1. YAML 静态账户优先，行为与旧版本完全一致
        List<JwtAccountProperties> accounts = securityProperties.getJwt().getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            log.warn("未配置YAML JWT账户，尝试数据库账户");
        } else {
            log.info("验证用户凭据: username={}, accountsCount={}", username, accounts.size());
            Optional<JwtAccountProperties> yamlAccount = accounts.stream()
                    .filter(JwtAccountProperties::isEnabled)
                    .filter(account -> account.getUsername().equals(username))
                    .findFirst();
            if (yamlAccount.isPresent()) {
                boolean matches = passwordEncoder.matches(password, yamlAccount.get().getPassword());
                log.info("YAML账户密码匹配结果: username={}, matches={}", username, matches);
                return matches;
            }
            log.info("YAML账户未命中，尝试数据库账户: username={}", username);
        }

        // 2. DB 账户兜底（Web 账户管理 API 创建的账户）
        Optional<JwtAccountEntity> dbAccount = jwtAccountRepository.findByUsername(username);
        if (dbAccount.isEmpty()) {
            log.info("数据库账户未找到: username={}", username);
            return false;
        }
        JwtAccountEntity entity = dbAccount.get();
        if (Boolean.FALSE.equals(entity.getEnabled())) {
            log.warn("数据库账户已禁用: username={}", username);
            return false;
        }
        boolean matches = passwordEncoder.matches(password, entity.getPassword());
        log.info("数据库账户密码匹配结果: username={}, matches={}", username, matches);
        return matches;
    }

    /**
     * 根据用户名查找账户信息
     *
     * @param username 用户名
     * @return 账户信息Optional
     */
    public Optional<JwtAccountProperties> findAccountByUsername(final String username) {
        if (!securityProperties.getJwt().isEnabled()) {
            log.warn("JWT认证功能未启用");
            return Optional.empty();
        }

        List<JwtAccountProperties> accounts = securityProperties.getJwt().getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            return Optional.empty();
        }

        return accounts.stream()
                .filter(JwtAccountProperties::isEnabled)
                .filter(account -> account.getUsername().equals(username))
                .findFirst();
    }

    /**
     * 验证用户名和密码并生成JWT令牌
     */
    public Mono<String> authenticateAndGenerateToken(
            final String username, final String password,
            final SecurityProperties securityProperties) {
        return authenticateAndGenerateToken(username, password, securityProperties, null, null);
    }

    /**
     * 验证用户名和密码并生成JWT令牌（带上下文信息用于审计）
     */
    public Mono<String> authenticateAndGenerateToken(
            final String username, final String password,
            final SecurityProperties securityProperties,
            final String ipAddress, final String userAgent) {
        // 验证配置中的用户凭据
        return Mono.just(validateCredentials(username, password))
                .filter(valid -> valid)
                .switchIfEmpty(Mono.defer(() -> {
                    // 记录认证失败审计
                    if (auditService != null) {
                        auditService.auditSecurityEvent("AUTHENTICATION_FAILED",
                            "用户名或密码错误: " + username, username, ipAddress)
                            .onErrorResume(ex -> {
                                log.warn("记录认证失败审计失败: {}", ex.getMessage());
                                return Mono.empty();
                            })
                            .subscribe();
                    }
                    return Mono.error(new RuntimeException("用户名或密码错误"));
                }))
                .map(valid -> generateJwtToken(username, securityProperties));
    }

    /**
     * 外部身份认证并签发 JWT（O7-a 调用点，A2 增加 JIT 自动建号）。
     *
     * <p>映射规则：外部身份先尝试映射到「已存在且启用」的本地账号
     * （{@code externalId} → {@code email} → {@code email 本地前缀}）；无映射时，若
     * {@code trunkline.security.sso.auto-provision=true}（默认），则 JIT 创建本地账号并签发，
     * 否则拒绝（{@code EXTERNAL_ACCOUNT_NOT_MAPPED}，须管理员预置）。
     * <b>安全约束</b>：签发角色完全来自本地账号配置（JIT 用 {@code default-role}），
     * 禁止从 IdP 返回的角色字符串透传。</p>
     *
     * @param externalUser IdP 交换出的企业用户身份
     * @param ipAddress    客户端 IP（审计）
     * @param userAgent    客户端 UA（审计）
     * @return 平台 JWT
     */
    public Mono<String> authenticateExternal(final SsoConnector.EnterpriseUser externalUser,
                                             final String ipAddress, final String userAgent) {
        // 注意：不能用 Mono.fromCallable(() -> resolve...返回 null)，Reactor 下 callable 返回 null
        // 会空完成（onComplete）而非把 null 传入 flatMap，导致「无映射→拒绝」分支变成死代码。
        // 故用 Mono.defer 显式处理 null → EXTERNAL_ACCOUNT_NOT_MAPPED。
        return Mono.defer(() -> {
            final String username = resolveOrProvisionLocalUsername(externalUser);
            if (username == null) {
                log.warn("外部身份无映射本地账号: externalId={}, email={}",
                        externalUser.externalId(), externalUser.email());
                if (auditService != null) {
                    auditService.auditSecurityEvent("AUTHENTICATION_FAILED",
                                    "外部身份无映射本地账号: " + externalUser.externalId(),
                                    externalUser.email(), ipAddress)
                            .onErrorResume(ex -> Mono.empty())
                            .subscribe();
                }
                return Mono.error(ApiException.of("EXTERNAL_ACCOUNT_NOT_MAPPED",
                        "外部账号未映射到本地账号，请联系管理员开通",
                        org.springframework.http.HttpStatus.UNAUTHORIZED));
            }
            log.info("外部身份已映射/开通本地账号并签发令牌: username={}, ip={}", username, ipAddress);
            return Mono.just(generateJwtToken(username, securityProperties));
        });
    }

    /**
     * 外部身份 → 本地用户名：先尝试映射到已存在且启用的账号，无映射时按策略 JIT 自动建号。
     * 返回 null 表示既无映射又不开通（调用方据此拒绝）。
     */
    private String resolveOrProvisionLocalUsername(final SsoConnector.EnterpriseUser user) {
        final String existing = resolveExternalLocalUsername(user);
        if (existing != null) {
            return existing;
        }
        final SsoConfig sso = securityProperties.getSso();
        if (sso == null || !sso.isAutoProvision()) {
            return null; // 沿用现状：须管理员预置
        }
        final String username = deriveSsoUsername(user);
        if (username == null) {
            return null; // 既无 email 也无 externalId，无法派生用户名
        }
        // 同名但被禁用的账号不自动启用（避免把禁用账号复活）
        final Optional<JwtAccountEntity> collision = jwtAccountRepository.findByUsername(username);
        if (collision.isPresent() && Boolean.FALSE.equals(collision.get().getEnabled())) {
            log.warn("SSO JIT 跳过：本地账号 {} 已存在但被禁用，不自动启用", username);
            return null;
        }
        if (collision.isPresent()) {
            return username; // 已存在且启用（兜底，正常不会到这）
        }
        provisionLocalAccount(user, username, sso.getDefaultRole());
        return username;
    }

    /**
     * 派生 JIT 本地用户名：优先 email 本地前缀（{@code alice@corp.com} → {@code alice}），
     * 其次 externalId；两者皆空返回 null。
     */
    private String deriveSsoUsername(final SsoConnector.EnterpriseUser user) {
        if (user.email() != null && !user.email().isBlank()) {
            final int at = user.email().indexOf('@');
            if (at > 0) {
                return user.email().substring(0, at);
            }
            return user.email(); // 无 @ 的退化情况
        }
        if (user.externalId() != null && !user.externalId().isBlank()) {
            return user.externalId();
        }
        return null;
    }

    /**
     * JIT 创建本地账号：随机密码（密码登录不可用）、角色取 default-role、启用、填名称/部门、
     * 归属默认租户。绝不持久化 IdP 令牌或原始声明。
     */
    private void provisionLocalAccount(final SsoConnector.EnterpriseUser user,
                                      final String username, final String defaultRole) {
        final JwtAccountEntity entity = JwtAccountEntity.builder()
                .username(username)
                .externalId(user.externalId())
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .roles(toRolesJson(defaultRole))
                .name(user.displayName())
                .department(user.department())
                .enabled(true)
                .build();
        jwtAccountRepository.save(entity);
        log.info("SSO JIT 首次登录自动建号: username={}, email={}, defaultRole={}",
                username, user.email(), defaultRole);
        if (auditService != null) {
            auditService.auditSecurityEvent("ACCOUNT_PROVISIONED",
                            "SSO 自动建号: " + username, username, null)
                    .onErrorResume(ex -> Mono.empty())
                    .subscribe();
        }
    }

    // ============================================================
    // A4 最小回收闭环（闸门② P0 红线）
    // 「IdP 移除/禁用 → 本地账号禁用 + JWT 失效 + API Key 吊销 + 组成员清空 + 审计」
    // 的复用入口。所有方法幂等可复跑：账号不存在/已禁用时安全跳过。
    // ============================================================

    /**
     * 按外部身份停用本地账号（离职回收单账号入口）。
     *
     * <p>定位策略直接查 {@code jwtAccountRepository}（优先 {@code externalId} 列，
     * 兜底 email / email 前缀），而非复用 {@code resolveExternalLocalUsername}——
     * 后者只扫 YAML 账户、对 DB 类 JIT 账号不可靠。</p>
     *
     * @param externalUser IdP 交换出的企业用户身份（取 {@code externalId} / {@code email}）
     * @return 完成信号（账号不存在或已停用时为空完成，幂等）
     */
    public Mono<Void> disableExternalAccount(final SsoConnector.EnterpriseUser externalUser) {
        return Mono.defer(() -> {
            final String username = resolveLocalUsernameForDeprovisioning(
                    externalUser.externalId(), externalUser.email());
            if (username == null) {
                log.info("A4 停用：外部身份无对应本地账号，跳过: externalId={}, email={}",
                        externalUser.externalId(), externalUser.email());
                return Mono.empty();
            }
            return disableLocalAccount(username);
        });
    }

    /**
     * 按外部身份标识停用（供 reconcile / 管理端点调用，无需完整 {@code EnterpriseUser}）。
     */
    public Mono<Void> disableExternalAccountByIdentity(final String externalId, final String email) {
        return Mono.defer(() -> {
            final String username = resolveLocalUsernameForDeprovisioning(externalId, email);
            if (username == null) {
                log.info("A4 停用：外部身份无对应本地账号，跳过: externalId={}, email={}",
                        externalId, email);
                return Mono.empty();
            }
            return disableLocalAccount(username);
        });
    }

    /**
     * 全量对账（reconcile）：给定【当前仍活跃】的外部身份标识集合，
     * 停用所有「带 {@code externalId} 但不在活跃集合内」的本地 SSO 账号。
     *
     * <p>本地/YAML 静态账户 {@code external_id} 为 {@code null}，不会被本方法触碰（避免误伤）。
     * 返回的汇总包含被停用账号清单与受影响密钥数，供脚本/端点留痕。</p>
     *
     * @param activeExternalIds 当前仍活跃的外部身份标识集合（来自 IdP/HR feed）
     * @return 对账结果汇总
     */
    public Mono<DeprovisioningSummary> reconcileExternalAccounts(
            final java.util.Set<String> activeExternalIds) {
        return Mono.defer(() -> {
            final java.util.Set<String> active =
                    activeExternalIds == null ? java.util.Set.of() : activeExternalIds;
            final java.util.List<JwtAccountEntity> ssoAccounts =
                    jwtAccountRepository.findAllByExternalIdIsNotNull();
            final java.util.List<String> toDisable = new java.util.ArrayList<>();
            for (final JwtAccountEntity e : ssoAccounts) {
                final String ext = e.getExternalId();
                if (ext != null && !ext.isBlank() && !active.contains(ext)) {
                    final String username = resolveLocalUsernameForDeprovisioning(ext, null);
                    if (username != null) {
                        toDisable.add(username);
                    }
                }
            }
            final java.util.concurrent.atomic.AtomicInteger revokedKeys =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            Mono<Void> chain = Mono.empty();
            for (final String username : toDisable) {
                chain = chain.then(disableLocalAccount(username, revokedKeys));
            }
            final int count = toDisable.size();
            return chain.thenReturn(new DeprovisioningSummary(count, toDisable, revokedKeys.get()));
        });
    }

    private Mono<Void> disableLocalAccount(final String username) {
        return disableLocalAccount(username, new java.util.concurrent.atomic.AtomicInteger(0));
    }

    private Mono<Void> disableLocalAccount(final String username,
                                           final java.util.concurrent.atomic.AtomicInteger revokedKeys) {
        final JwtAccountEntity entity = jwtAccountRepository.findByUsername(username).orElse(null);
        if (entity == null) {
            return Mono.empty();
        }
        // 幂等：已禁用账号重复停用不会抛错，仅确保状态/缓存/密钥一致。
        entity.setEnabled(false);
        entity.setRoles("[]"); // 组成员清空：吊销陈旧角色/组归属，避免恢复后残留
        jwtAccountRepository.save(entity);
        log.info("A4 停用：本地账号已禁用并清空组成员: username={}", username);
        auditDeprovisioning(username);
        return invalidateAndComplete(username).then(revokeApiKeysFor(username, revokedKeys));
    }

    private Mono<Void> invalidateAndComplete(final String username) {
        if (accountStatusGuard != null) {
            accountStatusGuard.invalidate(username);
        }
        return Mono.empty();
    }

    /**
     * 吊销该本地用户持有的全部 API Key（按有效持有人过滤）。
     */
    private Mono<Void> revokeApiKeysFor(final String username,
                                        final java.util.concurrent.atomic.AtomicInteger revokedKeys) {
        if (apiKeyService == null) {
            return Mono.empty();
        }
        return apiKeyService.getMineApiKeysVO(username)
                .flatMapMany(vo -> Flux.fromIterable(vo.getItems()))
                .map(ApiKeyVO::getKeyId)
                .filter(kid -> kid != null && !kid.isBlank())
                .flatMap(kid -> apiKeyService.deleteApiKey(kid, "deprovisioning")
                        .doOnSuccess(ignored -> {
                            revokedKeys.incrementAndGet();
                            log.info("A4 停用：API Key 已吊销: kid={}, holder={}", kid, username);
                        })
                        .onErrorResume(ex -> {
                            log.warn("A4 停用：吊销 API Key 失败 kid={}, holder={}: {}",
                                    kid, username, ex.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    /**
     * 停用本地账号时写入审计（失败不阻断主流程）。
     */
    private void auditDeprovisioning(final String username) {
        if (auditService != null) {
            auditService.auditSecurityEvent("ACCOUNT_DEPROVISIONED",
                            "SSO 账号停用（离职回收）: " + username, username, null)
                    .onErrorResume(ex -> Mono.empty())
                    .subscribe();
        }
    }

    /**
     * 停用专用账号定位：直接查库（优先 externalId，兜底 email / email 前缀），
     * 不依赖只扫 YAML 的 {@code resolveExternalLocalUsername}。
     */
    private String resolveLocalUsernameForDeprovisioning(final String externalId, final String email) {
        if (externalId != null && !externalId.isBlank()) {
            final JwtAccountEntity byExt = jwtAccountRepository.findByExternalId(externalId).orElse(null);
            if (byExt != null) {
                return byExt.getUsername();
            }
        }
        if (email != null && !email.isBlank()) {
            final JwtAccountEntity byEmail = jwtAccountRepository.findByUsername(email).orElse(null);
            if (byEmail != null) {
                return byEmail.getUsername();
            }
            final int at = email.indexOf('@');
            if (at > 0) {
                final JwtAccountEntity byPrefix =
                        jwtAccountRepository.findByUsername(email.substring(0, at)).orElse(null);
                if (byPrefix != null) {
                    return byPrefix.getUsername();
                }
            }
        }
        return null;
    }

    private String toRolesJson(final String role) {
        try {
            return objectMapper.writeValueAsString(List.of(role));
        } catch (final JsonProcessingException e) {
            log.warn("序列化 SSO default-role 失败，降级为空角色: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 外部身份 → 本地用户名映射；无映射返回 null。
     * 依次尝试 externalId / email / email 本地前缀，且目标账号必须存在并启用。
     */
    private String resolveExternalLocalUsername(final SsoConnector.EnterpriseUser user) {
        if (user == null) {
            return null;
        }
        if (user.externalId() != null && !user.externalId().isBlank()
                && findAccountByUsername(user.externalId()).isPresent()) {
            return user.externalId();
        }
        if (user.email() != null && !user.email().isBlank()) {
            if (findAccountByUsername(user.email()).isPresent()) {
                return user.email();
            }
            final int at = user.email().indexOf('@');
            if (at > 0) {
                final String localPart = user.email().substring(0, at);
                if (findAccountByUsername(localPart).isPresent()) {
                    return localPart;
                }
            }
        }
        return null;
    }

    /**
     * 生成JWT令牌
     */
    private String generateJwtToken(final String username, final SecurityProperties securityProperties) {
        // 获取用户详情
        UserDetails userDetails = loadUserByUsername(username);

        // 获取用户角色
        List<String> roles = userDetails.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        // 设置过期时间
        Date now = new Date();
        Date expiration = new Date(now.getTime()
        + securityProperties.getJwt().getExpirationMinutes() * 60 * 1000L);

        // 创建签名密钥（使用JJWT 0.12.x API）
        SecretKey signingKey = Keys.hmacShaKeyFor(
                securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));

        // 构建JWT令牌（使用JJWT 0.12.x API）
        // 社区版已移除权限码体系：令牌只携带角色，URL 级授权由固定角色矩阵按角色裁决
        final var builder = Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("roles", roles)
                .issuer(securityProperties.getJwt().getIssuer())
                .issuedAt(now)
                .expiration(expiration)
                .id(UUID.randomUUID().toString()); // 设置JTI用于黑名单管理

        // 多租户二维角色地基（P0-c）：仅当账户存储的是新格式 [{t,r}] 时，额外签发
        // tenantRoles claim（绑定结构），供企业侧 P2 做数据级作用域收窄。旧格式/社区版
        // 【不签发此 claim】，令牌内容与改造前逐字一致 —— 社区版零变化。
        final RoleScopeParser scope = resolveRoleScope(username);
        if (scope != null && !scope.isLegacy() && scope.hasTenantScopedBindings()) {
            try {
                builder.claim("tenantRoles", objectMapper.readValue(
                        scope.toTenantRolesJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {}));
            } catch (JsonProcessingException e) {
                log.warn("签发 tenantRoles claim 失败，按旧令牌处理: {}", e.getMessage());
            }
        }

        return builder.signWith(signingKey).compact();
    }

    /**
     * 解析账户的原始角色作用域（用于签发 tenantRoles claim）。
     *
     * <p>DB 账户读 roles 列原始 JSON；YAML 静态账户的角色恒为旧格式全局，返回 null（不签发）。
     * 私有方法，失败降级 null，不影响主签发链路。</p>
     */
    private RoleScopeParser resolveRoleScope(final String username) {
        try {
            final java.util.Optional<JwtAccountEntity> entity = jwtAccountRepository.findByUsername(username);
            if (entity.isPresent() && entity.get().getRoles() != null) {
                return RoleScopeParser.parse(entity.get().getRoles());
            }
        } catch (Exception e) {
            log.debug("查询账户角色作用域失败，跳过 tenantRoles claim: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public UserDetails loadUserByUsername(final String username) throws UsernameNotFoundException {
        if (!securityProperties.getJwt().isEnabled()) {
            throw new UsernameNotFoundException("JWT认证功能未启用");
        }

        // 1. YAML 静态账户优先，行为与旧版本完全一致
        List<JwtAccountProperties> accounts = securityProperties.getJwt().getAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            Optional<JwtAccountProperties> yamlAccount = accounts.stream()
                    .filter(account -> account.getUsername().equals(username) && account.isEnabled())
                    .findFirst();
            if (yamlAccount.isPresent()) {
                JwtAccountProperties account = yamlAccount.get();
                return User.builder()
                        .username(account.getUsername())
                        .password(account.getPassword())
                        .authorities(buildAuthorities(account.getRoles()))
                        .build();
            }
        }

        // 2. DB 账户兜底（Web 账户管理 API 创建的账户）
        JwtAccountEntity entity = jwtAccountRepository.findByUsername(username)
                .filter(dbAccount -> dbAccount.getEnabled() == null || dbAccount.getEnabled())
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在或已被禁用: " + username));

        return User.builder()
                .username(entity.getUsername())
                .password(entity.getPassword())
                .authorities(buildAuthorities(parseRoles(entity.getRoles())))
                .build();
    }

    /**
     * 解析数据库账户的角色 JSON 字符串（如 ["ADMIN", "USER"]）
     *
     * <p>解析失败时降级为空列表，避免因角色数据异常导致登录失败。
     *
     * @param rolesJson 角色 JSON 字符串
     * @return 角色列表（可能为空）
     */
    private List<String> parseRoles(final String rolesJson) {
        if (rolesJson == null || rolesJson.isBlank()) {
            return List.of();
        }
        // 多租户二维角色地基（P0-c）：RoleScopeParser 同时兼容旧格式 ["ADMIN"] 与新格式
        // [{t,r}]。allRoles() 返回全部角色并集，用于生成 URL 级 authority——旧格式时
        // 结果与改造前逐字一致（全部角色），社区版零变化；新格式时租户级角色也进 authority，
        // 保证"t1 的租户管理员"持有 ROLE_ADMIN 能进管理页（数据级收窄在企业侧 P2 叠加）。
        final RoleScopeParser parsed = RoleScopeParser.parse(rolesJson);
        if (parsed.isLegacy()) {
            // 旧格式走与改造前完全一致的解析路径（readerForListOf），行为零变化。
            try {
                return objectMapper.readerForListOf(String.class).readValue(rolesJson);
            } catch (JsonProcessingException e) {
                log.warn("解析数据库账户角色失败，按无角色处理: {}", e.getMessage());
                return List.of();
            }
        }
        return new java.util.ArrayList<>(parsed.allRoles());
    }

    /**
     * 将角色列表转为大写并封装为授权对象（与 YAML 账户逻辑一致）
     *
     * <p>已废弃角色 VIEWER（P0-2）在此归一化为替代角色 USER：
     * YAML 静态账户不在 {@code jwt_accounts} 表内，{@link JwtAccountRoleMigration}
     * 迁不到它；若不在签发前降级，这类账户会拿到空权限集合（角色已从
     * {@code role_permissions} 删除）而处处 403。归一化只影响「废弃角色」，
     * 其余角色按原逻辑大写化。</p>
     *
     * @param roles 角色列表（可为空）
     * @return 授权列表
     */
    private List<SimpleGrantedAuthority> buildAuthorities(final List<String> roles) {
        return roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(DeprecatedRoles::normalizeRole)
                .filter(role -> !role.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}