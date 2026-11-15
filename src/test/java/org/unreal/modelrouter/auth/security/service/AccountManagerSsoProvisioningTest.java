package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.enterprise.spi.SsoConnector.EnterpriseUser;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AccountManager SSO JIT 自动建号测试（A2）。
 *
 * <p>覆盖：① 首次登录无映射 → 自动建号并签发令牌；② auto-provision=false → 拒绝；
 * ③ 同名已被禁用账号 → 不自动启用、拒绝；④ 既无 email 也无 externalId → 拒绝。</p>
 */
@DisplayName("AccountManager SSO JIT 自动建号")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountManagerSsoProvisioningTest {

    private static final String TEST_SECRET = "test-secret-key-for-jwt-validation-min-32-chars";
    private static final String TEST_ISSUER = "trunkline-test";

    @Mock
    private JwtAccountRepository jwtAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecurityProperties securityProperties;
    private AccountManager accountManager;
    private SecretKey signingKey;
    private final Map<String, JwtAccountEntity> store = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        securityProperties.getJwt().setEnabled(true);
        securityProperties.getJwt().setSecret(TEST_SECRET);
        securityProperties.getJwt().setIssuer(TEST_ISSUER);
        securityProperties.getJwt().setExpirationMinutes(30L);
        // 默认开启 JIT
        securityProperties.getSso().setAutoProvision(true);
        securityProperties.getSso().setDefaultRole("USER");

        signingKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}encodedRandom");

        // 内存 store 行为：save 落库、findByUsername/existsByUsername 反映落库结果
        when(jwtAccountRepository.save(any(JwtAccountEntity.class))).thenAnswer(inv -> {
            final JwtAccountEntity e = inv.getArgument(0);
            store.put(e.getUsername(), e);
            return e;
        });
        when(jwtAccountRepository.findByUsername(anyString())).thenAnswer(
                inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(jwtAccountRepository.existsByUsername(anyString())).thenAnswer(
                inv -> store.containsKey(inv.getArgument(0)));

        accountManager = new AccountManager(securityProperties, passwordEncoder,
                jwtAccountRepository, objectMapper);
    }

    private static EnterpriseUser user(final String sub, final String name,
                                      final String email, final String dept) {
        return new EnterpriseUser(sub, name, email, dept);
    }

    @Nested
    @DisplayName("JIT 自动建号")
    class JitProvisionTests {

        @Test
        @DisplayName("首次 SSO 登录无映射 → 自动建号并签发令牌，角色取 default-role")
        void provisionsAndIssuesToken() {
            final EnterpriseUser external = user("sub-123", "Alice", "alice@corp.com", "Eng");

            final Mono<String> tokenMono = accountManager.authenticateExternal(external, "1.2.3.4", "ua");

            StepVerifier.create(tokenMono)
                    .assertNext(token -> {
                        final Claims claims = Jwts.parser().verifyWith(signingKey).build()
                                .parseSignedClaims(token).getPayload();
                        assertThat(claims.getSubject()).isEqualTo("alice");
                        assertThat(claims.get("roles", List.class)).containsExactly("USER");
                    })
                    .verifyComplete();

            verify(jwtAccountRepository).save(any(JwtAccountEntity.class));
            final JwtAccountEntity saved = store.get("alice");
            assertThat(saved).isNotNull();
            assertThat(saved.getUsername()).isEqualTo("alice");
            assertThat(saved.getRoles()).isEqualTo("[\"USER\"]");
            assertThat(saved.getEnabled()).isTrue();
            assertThat(saved.getName()).isEqualTo("Alice");
            assertThat(saved.getDepartment()).isEqualTo("Eng");
        }

        @Test
        @DisplayName("auto-provision=false → 无映射即拒绝，不建号")
        void autoProvisionDisabledRejects() {
            securityProperties.getSso().setAutoProvision(false);
            final EnterpriseUser external = user("sub-9", "Bob", "bob@corp.com", null);

            StepVerifier.create(accountManager.authenticateExternal(external, "1.2.3.4", "ua"))
                    .expectErrorMatches(t -> t instanceof org.unreal.modelrouter.common.exception.ApiException
                            && ((org.unreal.modelrouter.common.exception.ApiException) t)
                                .getErrorCode().equals("EXTERNAL_ACCOUNT_NOT_MAPPED"))
                    .verify();

            verify(jwtAccountRepository, never()).save(any(JwtAccountEntity.class));
        }

        @Test
        @DisplayName("同名本地账号已被禁用 → 不自动启用、拒绝")
        void disabledCollisionNotReactivated() {
            store.put("alice", JwtAccountEntity.builder()
                    .username("alice").password("{bcrypt}x").roles("[\"USER\"]")
                    .enabled(false).build());
            final EnterpriseUser external = user("sub-1", "Alice", "alice@corp.com", "Eng");

            StepVerifier.create(accountManager.authenticateExternal(external, "1.2.3.4", "ua"))
                    .expectErrorMatches(t -> t instanceof org.unreal.modelrouter.common.exception.ApiException
                            && ((org.unreal.modelrouter.common.exception.ApiException) t)
                                .getErrorCode().equals("EXTERNAL_ACCOUNT_NOT_MAPPED"))
                    .verify();

            verify(jwtAccountRepository, never()).save(any(JwtAccountEntity.class));
        }

        @Test
        @DisplayName("既无 email 也无 externalId → 无法派生用户名，拒绝")
        void noIdentifierRejects() {
            final EnterpriseUser external = user(null, "Ghost", null, null);

            StepVerifier.create(accountManager.authenticateExternal(external, "1.2.3.4", "ua"))
                    .expectErrorMatches(t -> t instanceof org.unreal.modelrouter.common.exception.ApiException
                            && ((org.unreal.modelrouter.common.exception.ApiException) t)
                                .getErrorCode().equals("EXTERNAL_ACCOUNT_NOT_MAPPED"))
                    .verify();

            verify(jwtAccountRepository, never()).save(any(JwtAccountEntity.class));
        }
    }
}
