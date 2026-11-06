package org.unreal.modelrouter.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
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
import org.unreal.modelrouter.common.dto.TokenRefreshRequest;
import org.unreal.modelrouter.common.exception.ApiException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @InjectMocks
    private JwtAuthController controller;

    private LoginRequest testLoginRequest;
    private TokenRefreshRequest testRefreshRequest;

    @BeforeEach
    void setUp() {
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
}
