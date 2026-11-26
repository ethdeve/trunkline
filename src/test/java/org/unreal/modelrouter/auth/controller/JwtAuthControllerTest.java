package org.unreal.modelrouter.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.unreal.modelrouter.auth.security.config.properties.JwtConfig;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.service.AccountManager;
import org.unreal.modelrouter.auth.security.service.JwtPersistenceService;
import org.unreal.modelrouter.auth.security.service.JwtTokenRefreshService;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.dto.JwtTokenInfo;
import org.unreal.modelrouter.common.dto.LoginRequest;
import org.unreal.modelrouter.common.dto.LoginResponse;
import org.unreal.modelrouter.common.dto.SsoLoginRequest;
import org.unreal.modelrouter.common.dto.TokenRefreshRequest;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.enterprise.spi.SsoConnector;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JwtAuthController 单元测试
 * 测试认证相关功能（登录、刷新令牌）
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthControllerTest {

    @Mock
    private JwtTokenRefreshService tokenRefreshService;

    @Mock
    private AccountManager accountManager;

    @Mock
    private SecurityProperties securityProperties;

    @Mock
    private JwtPersistenceService jwtPersistenceService;

    @Mock
    private SsoConnector ssoConnector;

    @InjectMocks
    private JwtAuthController controller;

    private LoginRequest testLoginRequest;
    private TokenRefreshRequest testRefreshRequest;

    @BeforeEach
    void setUp() {
        // Mockito 对带 @RequiredArgsConstructor 的控制器走构造器注入后不再做字段注入
        // （jwtPersistenceService 的 null 即证据），SSO 字段需显式反射注入。
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "ssoConnector", ssoConnector);

        testLoginRequest = new LoginRequest();
        testLoginRequest.setUsername("testuser");
        testLoginRequest.setPassword("password");

        testRefreshRequest = new TokenRefreshRequest();
        testRefreshRequest.setToken("test-token");

        JwtConfig jwtConfig = mock(JwtConfig.class);
        lenient().when(jwtConfig.getExpirationMinutes()).thenReturn(60L);
        lenient().when(securityProperties.getJwt()).thenReturn(jwtConfig);
    }

    // ==================== 登录测试 ====================

    @Nested
    @DisplayName("POST /api/auth/jwt/login - 用户登录测试")
    class LoginTests {

        @Test
        @DisplayName("登录成功")
        void testLogin_success() {
            // Given
            lenient().when(accountManager.authenticateAndGenerateToken(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(Mono.just("test-jwt-token"));

            // When
            Mono<RouterResponse<LoginResponse>> result = controller.login(testLoginRequest, null);

            // Then
            StepVerifier.create(result)
                    .expectNextMatches(response -> response.isSuccess()
                            && response.getData().getToken().equals("test-jwt-token"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("登录失败")
        void testLogin_failed() {
            // Given
            lenient().when(accountManager.authenticateAndGenerateToken(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(Mono.error(new RuntimeException("Invalid credentials")));

            // When
            Mono<RouterResponse<LoginResponse>> result = controller.login(testLoginRequest, null);

            // Then
            // issue #94: 登录失败以 ApiException 结束链路 -> 401（LOGIN_FAILED）
            StepVerifier.create(result)
                    .expectErrorSatisfies(ex -> {
                        assertInstanceOf(ApiException.class, ex);
                        ApiException apiEx = (ApiException) ex;
                        assertEquals("LOGIN_FAILED", apiEx.getErrorCode());
                        assertEquals(HttpStatus.UNAUTHORIZED, apiEx.getStatus());
                    })
                    .verify();
        }
    }

    // ==================== 刷新令牌测试 ====================

    @Nested
    @DisplayName("POST /api/auth/jwt/refresh - 令牌刷新测试")
    class RefreshTokenTests {

        /**
         * P0-L 归属校验接入后的测试装配：刷新端点要求「认证主体 + 待刷新令牌 subject 一致」，
         * 因此必须传认证主体并打桩 subjectOf 返回同一主体（此前缺桩导致 mock 返回 null NPE）。
         */
        private Authentication mockAuthentication() {
            Authentication auth = mock(Authentication.class);
            lenient().when(auth.getName()).thenReturn("testuser");
            return auth;
        }

        @Test
        @DisplayName("刷新成功")
        void testRefreshToken_success() {
            // Given
            lenient().when(tokenRefreshService.subjectOf(anyString()))
                    .thenReturn(Mono.just("testuser"));
            lenient().when(tokenRefreshService.refreshToken(anyString(), any(), any()))
                    .thenReturn(Mono.just("new-jwt-token"));
            lenient().when(tokenRefreshService.saveTokenOnRefreshWithContext(
                            anyString(), anyString(), any(), any(), any()))
                    .thenReturn(Mono.empty());

            // When
            Mono<RouterResponse<JwtTokenInfo>> result =
                    controller.refreshToken(testRefreshRequest, mockAuthentication(), null);

            // Then
            StepVerifier.create(result)
                    .expectNextMatches(response -> response.isSuccess()
                            && response.getData().getToken().equals("new-jwt-token"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("刷新失败")
        void testRefreshToken_failed() {
            // Given
            lenient().when(tokenRefreshService.subjectOf(anyString()))
                    .thenReturn(Mono.just("testuser"));
            lenient().when(tokenRefreshService.refreshToken(anyString(), any(), any()))
                    .thenReturn(Mono.error(new RuntimeException("Token expired")));

            // When
            Mono<RouterResponse<JwtTokenInfo>> result =
                    controller.refreshToken(testRefreshRequest, mockAuthentication(), null);

            // Then
            // issue #94: 刷新失败以 ApiException 结束链路（TOKEN_REFRESH_FAILED 未命中语义码规则 -> 500）
            StepVerifier.create(result)
                    .expectErrorSatisfies(ex -> {
                        assertInstanceOf(ApiException.class, ex);
                        ApiException apiEx = (ApiException) ex;
                        assertEquals("TOKEN_REFRESH_FAILED", apiEx.getErrorCode());
                        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, apiEx.getStatus());
                    })
                    .verify();
        }
    }

    @Nested
    @DisplayName("SSO 登录（O7-a）")
    class SsoLoginTests {

        private static final String STATE = "state-issued-by-authorize";

        private SsoLoginRequest requestWithCode() {
            final SsoLoginRequest request = new SsoLoginRequest();
            request.setCode("auth-code");
            request.setState(STATE);
            return request;
        }

        /** R-A2-1 后 /sso 无条件要求 state cookie，业务分支用例必须走合法 front-channel 形态。 */
        private MockServerWebExchange exchangeWithValidState() {
            return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/jwt/sso")
                    .cookie(new HttpCookie("trunkline_sso_state", STATE))
                    .build());
        }

        @Test
        @DisplayName("Noop（providerName 空串）→ SSO_UNSUPPORTED，不以 null 判别")
        void noopConnectorReturnsUnsupported() {
            lenient().when(ssoConnector.providerName()).thenReturn("");
            lenient().when(ssoConnector.exchange(anyString())).thenReturn(Optional.empty());

            StepVerifier.create(controller.ssoLogin(requestWithCode(), exchangeWithValidState()))
                    .expectErrorSatisfies(ex -> {
                        assertInstanceOf(ApiException.class, ex);
                        assertEquals("SSO_UNSUPPORTED", ((ApiException) ex).getErrorCode());
                    })
                    .verify();
            verifyNoInteractions(accountManager);
        }

        @Test
        @DisplayName("企业连接器返回 empty（无效授权码）→ SSO_FAILED，不签 token")
        void invalidCodeReturnsSsoFailed() {
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");
            lenient().when(ssoConnector.exchange(anyString())).thenReturn(Optional.empty());

            StepVerifier.create(controller.ssoLogin(requestWithCode(), exchangeWithValidState()))
                    .expectErrorSatisfies(ex -> {
                        assertInstanceOf(ApiException.class, ex);
                        assertEquals("SSO_FAILED", ((ApiException) ex).getErrorCode());
                    })
                    .verify();
            verifyNoInteractions(accountManager);
        }

        @Test
        @DisplayName("企业连接器命中 → 映射本地账号并签发 JWT")
        void validCodeIssuesToken() {
            final SsoConnector.EnterpriseUser user =
                    new SsoConnector.EnterpriseUser("ext-1", "张三", "zhangsan@corp.example", "IT");
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");
            lenient().when(ssoConnector.exchange(anyString())).thenReturn(Optional.of(user));
            lenient().when(accountManager.authenticateExternal(any(), any(), any()))
                    .thenReturn(Mono.just("sso-jwt-token"));

            StepVerifier.create(controller.ssoLogin(requestWithCode(), exchangeWithValidState()))
                    .expectNextMatches(response -> response.isSuccess()
                            && response.getData().getToken().equals("sso-jwt-token"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/auth/jwt/sso/authorize（A2 补链）")
    class SsoAuthorizeTests {

        private MockServerWebExchange exchange() {
            return MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/auth/jwt/sso/authorize").build());
        }

        @Test
        @DisplayName("Noop（providerName 空串）→ SSO_UNSUPPORTED")
        void noopReturnsUnsupported() {
            lenient().when(ssoConnector.providerName()).thenReturn("");

            StepVerifier.create(controller.ssoAuthorize(exchange()))
                    .expectErrorSatisfies(ex -> {
                        assertInstanceOf(ApiException.class, ex);
                        assertEquals("SSO_UNSUPPORTED", ((ApiException) ex).getErrorCode());
                    })
                    .verify();
        }

        @Test
        @DisplayName("企业连接器返回 empty → SSO_UNCONFIGURED（fail-closed，不裸跳）")
        void emptyRedirectReturnsUnconfigured() {
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");
            lenient().when(ssoConnector.authorizationRedirect(anyString())).thenReturn(Optional.empty());

            StepVerifier.create(controller.ssoAuthorize(exchange()))
                    .expectErrorSatisfies(ex -> {
                        assertInstanceOf(ApiException.class, ex);
                        assertEquals("SSO_UNCONFIGURED", ((ApiException) ex).getErrorCode());
                    })
                    .verify();
        }

        @Test
        @DisplayName("javascript: / 相对路径 → 拒绝（防 open redirect）")
        void unsafeRedirectsAreRejected() {
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");
            lenient().when(ssoConnector.authorizationRedirect(anyString()))
                    .thenReturn(Optional.of("javascript:alert(1)"), Optional.of("/evil"));

            for (int i = 0; i < 2; i++) {
                StepVerifier.create(controller.ssoAuthorize(exchange()))
                        .expectErrorSatisfies(ex -> {
                            assertInstanceOf(ApiException.class, ex);
                            assertEquals("SSO_UNCONFIGURED", ((ApiException) ex).getErrorCode());
                        })
                        .verify();
            }
        }

        @Test
        @DisplayName("合法 https URL → 302 + HttpOnly/SameSite state cookie")
        void safeRedirectReturns302WithStateCookie() {
            final String idp = "https://idp.example.com/authorize?client_id=abc&state=STATE";
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");
            lenient().when(ssoConnector.authorizationRedirect(anyString())).thenReturn(Optional.of(idp));

            final MockServerWebExchange ex = exchange();
            StepVerifier.create(controller.ssoAuthorize(ex)).verifyComplete();

            assertEquals(HttpStatus.FOUND, ex.getResponse().getStatusCode());
            assertEquals(URI.create(idp), ex.getResponse().getHeaders().getLocation());
            final ResponseCookie cookie = ex.getResponse().getCookies().getFirst("trunkline_sso_state");
            assertNotNull(cookie);
            assertTrue(cookie.isHttpOnly());
            assertEquals("Lax", cookie.getSameSite());
            assertTrue(cookie.getValue() != null && !cookie.getValue().isBlank());
        }

        @Test
        @DisplayName("SPI 抛异常 → SSO_UNCONFIGURED，不透 500/堆栈")
        void spiExceptionIsFailClosed() {
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");
            lenient().when(ssoConnector.authorizationRedirect(anyString()))
                    .thenThrow(new IllegalStateException("bad config"));

            StepVerifier.create(controller.ssoAuthorize(exchange()))
                    .expectErrorSatisfies(ex -> {
                        assertInstanceOf(ApiException.class, ex);
                        assertEquals("SSO_UNCONFIGURED", ((ApiException) ex).getErrorCode());
                    })
                    .verify();
        }
    }

    @Nested
    @DisplayName("SSO state CSRF 校验（A2 补链）")
    class SsoStateTests {

        private SsoLoginRequest requestWith(final String state) {
            final SsoLoginRequest request = new SsoLoginRequest();
            request.setCode("auth-code");
            request.setState(state);
            return request;
        }

        private MockServerWebExchange exchangeWithState(final String cookieState) {
            return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/jwt/sso")
                    .cookie(new HttpCookie("trunkline_sso_state", cookieState))
                    .build());
        }

        @Test
        @DisplayName("cookie 与回传 state 不一致 → SSO_STATE_MISMATCH，不换 token")
        void mismatchIsRejected() {
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");

            StepVerifier.create(controller.ssoLogin(requestWith("tampered"), exchangeWithState("expected")))
                    .expectErrorSatisfies(ex -> {
                        assertInstanceOf(ApiException.class, ex);
                        assertEquals("SSO_STATE_MISMATCH", ((ApiException) ex).getErrorCode());
                    })
                    .verify();
            verifyNoInteractions(accountManager);
        }

        @Test
        @DisplayName("state 一致 → 正常签发 JWT，并清除 cookie")
        void matchingStateIssuesToken() {
            final SsoConnector.EnterpriseUser user =
                    new SsoConnector.EnterpriseUser("ext-1", "张三", "zhangsan@corp.example", "IT");
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");
            lenient().when(ssoConnector.exchange(anyString())).thenReturn(Optional.of(user));
            lenient().when(accountManager.authenticateExternal(any(), any(), any()))
                    .thenReturn(Mono.just("sso-jwt-token"));

            final MockServerWebExchange ex = exchangeWithState("expected");
            StepVerifier.create(controller.ssoLogin(requestWith("expected"), ex))
                    .expectNextMatches(response -> response.isSuccess()
                            && response.getData().getToken().equals("sso-jwt-token"))
                    .verifyComplete();
            final ResponseCookie cleared = ex.getResponse().getCookies().getFirst("trunkline_sso_state");
            assertNotNull(cleared);
            assertEquals(java.time.Duration.ZERO, cleared.getMaxAge());
        }

        @Test
        @DisplayName("R-A2-1：无 state cookie（未走 front-channel）→ 401 SSO_STATE_MISSING，绝不换 token")
        void noCookieIsRejected() {
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");

            final MockServerWebExchange ex = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/auth/jwt/sso").build());
            StepVerifier.create(controller.ssoLogin(requestWith(null), ex))
                    .expectErrorSatisfies(err -> {
                        assertInstanceOf(ApiException.class, err);
                        assertEquals("SSO_STATE_MISSING", ((ApiException) err).getErrorCode());
                        assertEquals(HttpStatus.UNAUTHORIZED, ((ApiException) err).getStatus());
                    })
                    .verify();
            verifyNoInteractions(accountManager);
            verify(ssoConnector, never()).exchange(anyString());
        }

        @Test
        @DisplayName("R-A2-1：exchange 为 null（服务端直调 back-channel）→ 401 SSO_STATE_MISSING")
        void nullExchangeIsRejected() {
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");

            StepVerifier.create(controller.ssoLogin(requestWith("any-state"), null))
                    .expectErrorSatisfies(err -> {
                        assertInstanceOf(ApiException.class, err);
                        assertEquals("SSO_STATE_MISSING", ((ApiException) err).getErrorCode());
                    })
                    .verify();
            verifyNoInteractions(accountManager);
        }

        @Test
        @DisplayName("R-A2-1：有 cookie 但回传 state 为空 → 401 SSO_STATE_MISMATCH")
        void missingSubmittedStateIsRejected() {
            lenient().when(ssoConnector.providerName()).thenReturn("oidc");

            StepVerifier.create(controller.ssoLogin(requestWith(null), exchangeWithState("expected")))
                    .expectErrorSatisfies(err -> {
                        assertInstanceOf(ApiException.class, err);
                        assertEquals("SSO_STATE_MISMATCH", ((ApiException) err).getErrorCode());
                    })
                    .verify();
            verifyNoInteractions(accountManager);
        }
    }
}
