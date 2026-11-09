package org.unreal.modelrouter.auth.security.access;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.unreal.modelrouter.auth.controller.ApiKeyManagementController;
import org.unreal.modelrouter.auth.controller.JwtTokenController;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定角色授权管理器测试。
 *
 * <p>行为契约：命中矩阵按级别裁决；未登记且未豁免 fail-closed（仅 ADMIN）；
 * 豁免清单按「已认证」放行；API-Key 主体（ROLE_&lt;SERVICE&gt;）不满足任何管理级别。</p>
 */
@DisplayName("固定角色授权管理器")
class FixedRoleAuthorizationManagerTest {

    private FixedRoleAuthorizationManager manager;

    @BeforeEach
    void setUp() {
        manager = new FixedRoleAuthorizationManager(new FixedRoleAccessPolicy());
    }

    private AuthorizationDecision decide(final Authentication auth, final String method, final String path) {
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path));
        final org.springframework.security.web.server.authorization.AuthorizationContext context =
                new org.springframework.security.web.server.authorization.AuthorizationContext(exchange, null);
        final AuthorizationDecision decision = manager.check(Mono.just(auth), context).block();
        assertNotNull(decision);
        return decision;
    }

    private JwtAuthentication jwt(final String... roles) {
        final JwtAuthentication auth = new JwtAuthentication("user", "token", List.of(roles));
        auth.setAuthenticated(true);
        return auth;
    }

    @Nested
    @DisplayName("SELF 级：所有登录角色可用")
    class SelfLevel {

        @Test
        @DisplayName("USER 访问我的调用记录导出 → 放行")
        void userCanExportMine() {
            assertTrue(decide(jwt("USER"), "GET", "/api/call-history/mine/export").isGranted());
        }

        @Test
        @DisplayName("USER 访问我的账单 → 放行")
        void userCanReadMyBilling() {
            assertTrue(decide(jwt("USER"), "GET", "/api/billing/my").isGranted());
        }

        @Test
        @DisplayName("我的密钥用量自助端点 → 所有登录角色放行；全局用量端点仍仅 OPS")
        void myQuotaUsageIsSelf() {
            for (final String role : List.of("USER", "OPERATOR", "ADMIN")) {
                assertTrue(decide(jwt(role), "GET", "/api/monitoring/quota/mine/usage").isGranted(),
                        role + " 应能读我的密钥用量");
            }
            // 通用多维用量端点无属主校验，绝不能对员工放行
            assertFalse(decide(jwt("USER"), "GET", "/api/monitoring/quota/usage").isGranted());
        }
    }

    @Nested
    @DisplayName("OPS 级：运维与管理员可用，员工拒绝")
    class OpsLevel {

        @Test
        @DisplayName("USER 访问调用观测 → 拒绝")
        void userDeniedCallHistory() {
            assertFalse(decide(jwt("USER"), "GET", "/api/call-history?keyword=x").isGranted());
        }

        @Test
        @DisplayName("OPERATOR 访问调用观测 → 放行")
        void operatorAllowedCallHistory() {
            assertTrue(decide(jwt("OPERATOR"), "GET", "/api/call-history").isGranted());
        }

        @Test
        @DisplayName("OPERATOR 访问技术总览 → 放行；USER → 拒绝")
        void dashboardLevel() {
            assertTrue(decide(jwt("OPERATOR"), "GET", "/api/dashboard/summary").isGranted());
            assertFalse(decide(jwt("USER"), "GET", "/api/dashboard/summary").isGranted());
        }

        @Test
        @DisplayName("团队用量/部门下拉 → USER 拒绝，OPERATOR 与 ADMIN 放行（回归：URL 未登记时运维 403）")
        void billingTeamEndpointsAreOps() {
            for (final String path : List.of("/api/billing/team", "/api/billing/departments")) {
                assertFalse(decide(jwt("USER"), "GET", path).isGranted(), path);
                assertTrue(decide(jwt("OPERATOR"), "GET", path).isGranted(), path);
                assertTrue(decide(jwt("ADMIN"), "GET", path).isGranted(), path);
            }
        }

        @Test
        @DisplayName("全局密钥视图（全部密钥 / 配额告警 / 配额概览）→ USER 拒绝，OPERATOR 与 ADMIN 放行")
        void globalApiKeyViewsStayOpsOnly() {
            // 这三个接口返回全员密钥与用量（含他人 keyId、描述、用量百分比），
            // 属于「团队」分组；员工自助只能走 /mine 系列（服务端强制按属主过滤）。
            // 故意不豁免给 USER：顶栏徽标也只挂在 ops/admin 可见的菜单项上。
            for (final String path : List.of(
                    "/api/auth/api-keys",
                    "/api/auth/api-keys/quota/alerts",
                    "/api/auth/api-keys/quota/overview")) {
                assertFalse(decide(jwt("USER"), "GET", path).isGranted(), path);
                assertTrue(decide(jwt("OPERATOR"), "GET", path).isGranted(), path);
                assertTrue(decide(jwt("ADMIN"), "GET", path).isGranted(), path);
            }
        }
    }

    @Nested
    @DisplayName("ADMIN 级：仅管理员")
    class AdminLevel {

        @Test
        @DisplayName("OPERATOR 访问账户管理 → 拒绝；ADMIN → 放行")
        void accountManagement() {
            assertFalse(decide(jwt("OPERATOR"), "GET", "/api/security/jwt/accounts").isGranted());
            assertTrue(decide(jwt("ADMIN"), "GET", "/api/security/jwt/accounts").isGranted());
        }
    }

    @Nested
    @DisplayName("未登记端点：fail-closed 仅 ADMIN，豁免清单放行")
    class Unmatched {

        @Test
        @DisplayName("USER 访问未登记端点 → 拒绝；ADMIN → 放行")
        void failClosedForNonAdmin() {
            assertFalse(decide(jwt("USER"), "GET", "/api/unknown/resource").isGranted());
            assertTrue(decide(jwt("ADMIN"), "GET", "/api/unknown/resource").isGranted());
        }

        @Test
        @DisplayName("豁免端点（Token 用量上报）→ 已认证即放行")
        void exemptEndpointsAuthenticatedOnly() {
            assertTrue(decide(jwt("USER"), "POST", "/api/token-usage/record").isGranted());
        }

        @Test
        @DisplayName("内置帮助文档正文 → 所有已登录角色放行（回归：未登记时非 ADMIN 403）")
        void docsContentExemptForAllRoles() {
            assertTrue(decide(jwt("USER"), "GET", "/api/docs/content").isGranted());
            assertTrue(decide(jwt("OPERATOR"), "GET", "/api/docs/content").isGranted());
            assertTrue(decide(jwt("ADMIN"), "GET", "/api/docs/content").isGranted());
        }
    }

    @Nested
    @DisplayName("API-Key 主体（ROLE_<SERVICE>）")
    class ApiKeyPrincipal {

        @Test
        @DisplayName("API-Key 不满足任何管理级别（管理面拒绝）")
        void apiKeyDeniedOnManagementSurface() {
            final ApiKeyAuthentication auth =
                    new ApiKeyAuthentication("key-1", "sk-key-1", List.of("chat"));
            auth.setAuthenticated(true);
            assertFalse(decide(auth, "GET", "/api/call-history").isGranted());
        }

        @Test
        @DisplayName("API-Key 打豁免的上报端点 → 放行（摄取链路不破坏）")
        void apiKeyAllowedOnIngestSurface() {
            final ApiKeyAuthentication auth =
                    new ApiKeyAuthentication("key-1", "sk-key-1", List.of("chat"));
            auth.setAuthenticated(true);
            assertTrue(decide(auth, "POST", "/api/token-usage/record").isGranted());
        }
    }

    @Nested
    @DisplayName("异常输入")
    class EdgeCases {

        @Test
        @DisplayName("无认证主体 → 拒绝")
        void emptyAuthenticationDenied() {
            final MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/call-history/mine").build());
            final org.springframework.security.web.server.authorization.AuthorizationContext context =
                    new org.springframework.security.web.server.authorization.AuthorizationContext(exchange, null);
            final AuthorizationDecision decision = manager.check(Mono.empty(), context).block();
            assertNotNull(decision);
            assertFalse(decision.isGranted());
        }

        @Test
        @DisplayName("未认证的 JwtAuthentication → 拒绝")
        void unauthenticatedJwtDenied() {
            final JwtAuthentication auth = new JwtAuthentication("raw-token");
            assertFalse(decide(auth, "GET", "/api/call-history/mine").isGranted());
        }
    }

    /**
     * URL 矩阵放行不等于请求能成功：项目启用了方法级安全，控制器上的
     * {@code @PreAuthorize} 是第二道闸门。2026-11-09 的故障正是两道闸门口径不一致
     * （URL=OPS、方法=ADMIN）导致 OPERATOR 403。此处用反射把口径锁死。
     */
    @Nested
    @DisplayName("方法级 @PreAuthorize 与 URL 矩阵一致性")
    class MethodSecurityConsistency {

        @Test
        @DisplayName("全部密钥控制器：全局端点 OPERATOR+ADMIN、自助端点三角色，不得残留仅 ADMIN")
        void apiKeyControllerAnnotationsMatchMatrix() {
            int globalEndpoints = 0;
            int selfEndpoints = 0;
            for (final Method method : ApiKeyManagementController.class.getDeclaredMethods()) {
                final PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
                if (annotation == null) {
                    continue;
                }
                final boolean self = method.getName().equals("getMyApiKeys")
                        || method.getName().equals("revealMyApiKeyValue");
                if (self) {
                    assertEquals("hasAnyRole('USER','OPERATOR','ADMIN')", annotation.value(),
                            method.getName() + " 应维持 SELF");
                    selfEndpoints++;
                } else {
                    assertEquals("hasAnyRole('OPERATOR','ADMIN')", annotation.value(),
                            method.getName() + " 必须与 /api/auth/api-keys/** 的 OPS 级别一致，"
                                    + "否则运维在方法层仍会 403");
                    globalEndpoints++;
                }
            }
            assertEquals(2, selfEndpoints, "两个自助端点必须保留三角色放行");
            assertTrue(globalEndpoints >= 15, "全局密钥端点应全部有方法级注解，实际=" + globalEndpoints);
        }

        @Test
        @DisplayName("自助令牌列表的方法级注解不得把 OPERATOR 排除（豁免清单按已认证放行）")
        void tokenListAnnotationCoversOperator() {
            final Method getTokens = Arrays.stream(JwtTokenController.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("getTokens"))
                    .findFirst()
                    .orElseThrow();
            final String expr = getTokens.getAnnotation(PreAuthorize.class).value();
            assertTrue(expr.contains("OPERATOR"), "注解=" + expr);
        }
    }
}
