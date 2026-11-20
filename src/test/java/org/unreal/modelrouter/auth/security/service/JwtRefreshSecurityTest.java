/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 刷新链路安全修复的单元测试（纯逻辑，不依赖 Spring 容器，不使用 Mockito）。
 *
 * <p>覆盖三个实测坐实的漏洞：
 * <ol>
 *   <li>刷新时沿用旧 jti → 新令牌一签发就被拉黑（下次请求 401）；</li>
 *   <li>刷新时沿用旧角色声明 → 角色被调整后的用户刷新即复活旧角色
 *       （历史版本还包括 permissions claim；固定权限矩阵落地后令牌只携带角色，
 *       旧 permissions 声明刷新时被剔除、不再写入）；</li>
 *   <li>刷新时不校验令牌归属 → 任意已认证用户可刷出他人（含 ADMIN）的新令牌。</li>
 * </ol>
 * @author Trunkline Team
 */
@DisplayName("JWT 刷新链路安全修复")
class JwtRefreshSecurityTest {

    @Nested
    @DisplayName("漏洞 1：刷新不得沿用旧 jti")
    class JtiRegeneration {

        @Test
        @DisplayName("复制自定义声明时剔除 jti（否则新令牌继承旧令牌 jti）")
        void copyClaimsMustDropJti() {
            final Map<String, Object> old = Map.of(
                    "jti", "old-jti-value",
                    "roles", List.of("USER"),
                    "permissions", List.of("a:read"),
                    "userId", "u1",
                    "custom", "keep-me");

            final Map<String, Object> copied = JwtRefreshClaimResolver.copyClaimsForRefresh(old);

            assertFalse(copied.containsKey("jti"), "jti 必须被剔除");
            assertFalse(copied.containsKey("roles"), "roles 必须被剔除（要重算）");
            assertFalse(copied.containsKey("permissions"), "permissions 必须被剔除（不再写入）");
            assertEquals("keep-me", copied.get("custom"), "无关自定义声明应保留");
            assertEquals("u1", copied.get("userId"));
        }

        @Test
        @DisplayName("标准声明同样不沿用")
        void standardClaimsAreNotCopied() {
            final Map<String, Object> old = Map.of(
                    "sub", "user",
                    "iss", "trunkline",
                    "exp", 123L,
                    "iat", 100L,
                    "nbf", 100L);

            final Map<String, Object> copied = JwtRefreshClaimResolver.copyClaimsForRefresh(old);

            assertTrue(copied.isEmpty(), "标准声明应由签发方重建：" + copied);
        }

        @Test
        @DisplayName("buildRefreshClaims 写入重算后的 roles，不写 permissions")
        void buildRefreshClaimsWritesRecomputedValues() {
            final Map<String, Object> old = Map.of("jti", "old", "permissions", List.of("stale:read"));

            final Map<String, Object> built = JwtRefreshClaimResolver.buildRefreshClaims(
                    old, List.of("USER"));

            assertFalse(built.containsKey("jti"));
            assertEquals(List.of("USER"), built.get("roles"));
            assertFalse(built.containsKey("permissions"), "固定权限矩阵下不再写入 permissions claim");
        }

        @Test
        @DisplayName("null 输入不抛异常")
        void nullClaimsAreTolerated() {
            assertNotNull(JwtRefreshClaimResolver.copyClaimsForRefresh(null));
            assertTrue(JwtRefreshClaimResolver.copyClaimsForRefresh(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("漏洞 2：刷新必须重算角色（旧角色不复活）")
    class RoleRecalculation {

        @Test
        @DisplayName("重算得到的角色覆盖旧声明")
        void staleRolesDoNotSurviveRefresh() {
            // 旧令牌带着已调整的角色
            final Map<String, Object> old = Map.of(
                    "roles", List.of("ADMIN"));

            // 权威来源重算后，角色已降级
            final Map<String, Object> built = JwtRefreshClaimResolver.buildRefreshClaims(
                    old, List.of("USER"));

            assertEquals(List.of("USER"), built.get("roles"),
                    "已调整的角色不得出现在新令牌中");
        }

        @Test
        @DisplayName("旧令牌的 permissions 声明刷新后剔除")
        void legacyPermissionsClaimDropped() {
            final Map<String, Object> built = JwtRefreshClaimResolver.buildRefreshClaims(
                    Map.of("permissions", List.of("config:instances:read")), List.of("USER"));

            assertFalse(built.containsKey("permissions"));
        }

        @Test
        @DisplayName("重算失败必须 fail-closed（不得回退旧角色）")
        void nullRolesMustFailClosed() {
            final JwtRefreshIdentityException ex = assertThrows(
                    JwtRefreshIdentityException.class,
                    () -> JwtRefreshClaimResolver.buildRefreshClaims(
                            Map.of("roles", List.of("ADMIN")),
                            null));
            assertTrue(ex.getMessage().contains("拒绝签发新令牌"), ex.getMessage());
        }

        @Test
        @DisplayName("身份重算器：来源返回 null 时抛异常")
        void resolverFailsClosedOnNull() {
            final JwtRefreshIdentityResolver resolver = new JwtRefreshIdentityResolver(
                    subject -> null);

            assertThrows(JwtRefreshIdentityException.class, () -> resolver.resolve("user"));
        }

        @Test
        @DisplayName("身份重算器：来源抛异常时包装为 fail-closed 异常")
        void resolverFailsClosedOnError() {
            final JwtRefreshIdentityResolver resolver = new JwtRefreshIdentityResolver(
                    subject -> {
                        throw new IllegalStateException("db down");
                    });

            final JwtRefreshIdentityException ex =
                    assertThrows(JwtRefreshIdentityException.class, () -> resolver.resolve("user"));
            assertTrue(ex.getMessage().contains("db down"), ex.getMessage());
        }

        @Test
        @DisplayName("身份重算器：主体为空直接拒绝")
        void resolverRejectsBlankSubject() {
            final JwtRefreshIdentityResolver resolver = new JwtRefreshIdentityResolver(
                    subject -> List.of("USER"));

            assertThrows(JwtRefreshIdentityException.class, () -> resolver.resolve("  "));
        }

        @Test
        @DisplayName("身份重算器：正常路径返回角色")
        void resolverReturnsIdentity() {
            final JwtRefreshIdentityResolver resolver = new JwtRefreshIdentityResolver(
                    subject -> List.of("USER", "OPERATOR"));

            final JwtRefreshIdentityResolver.RefreshIdentity identity = resolver.resolve("user");

            assertEquals(List.of("USER", "OPERATOR"), identity.getRoles());
        }
    }

    @Nested
    @DisplayName("漏洞 3：刷新必须校验令牌归属")
    class TokenOwnership {

        @Test
        @DisplayName("主体一致 → 允许")
        void sameSubjectAllowed() {
            assertEquals(JwtTokenOwnershipValidator.Result.ALLOWED,
                    JwtTokenOwnershipValidator.check("user", "user"));
        }

        @Test
        @DisplayName("主体不一致 → 拒绝（FORBIDDEN）")
        void differentSubjectForbidden() {
            assertEquals(JwtTokenOwnershipValidator.Result.FORBIDDEN,
                    JwtTokenOwnershipValidator.check("admin", "user"));
        }

        @Test
        @DisplayName("ADMIN 也不能刷新他人令牌（刷新不是代签发）")
        void adminCannotRefreshOthersToken() {
            assertEquals(JwtTokenOwnershipValidator.Result.FORBIDDEN,
                    JwtTokenOwnershipValidator.check("user", "admin"));
        }

        @Test
        @DisplayName("无认证主体 → UNAUTHENTICATED")
        void noAuthenticationRejected() {
            assertEquals(JwtTokenOwnershipValidator.Result.UNAUTHENTICATED,
                    JwtTokenOwnershipValidator.check("user", null));
            assertEquals(JwtTokenOwnershipValidator.Result.UNAUTHENTICATED,
                    JwtTokenOwnershipValidator.check("user", "   "));
        }

        @Test
        @DisplayName("待刷新令牌无 subject → INVALID_TOKEN")
        void blankTokenSubjectInvalid() {
            assertEquals(JwtTokenOwnershipValidator.Result.INVALID_TOKEN,
                    JwtTokenOwnershipValidator.check("", "user"));
            assertEquals(JwtTokenOwnershipValidator.Result.INVALID_TOKEN,
                    JwtTokenOwnershipValidator.check(null, "user"));
        }

        @Test
        @DisplayName("首尾空白不参与比较")
        void whitespaceTolerated() {
            assertEquals(JwtTokenOwnershipValidator.Result.ALLOWED,
                    JwtTokenOwnershipValidator.check(" user ", "user"));
        }
    }

    @Nested
    @DisplayName("声明分类常量")
    class ClaimConstants {

        @Test
        @DisplayName("jti/roles/permissions/tenantRoles 属于必须重算/剔除的声明")
        void recomputedClaimsClassification() {
            assertTrue(JwtRefreshClaimResolver.isRecomputedClaim("jti"));
            assertTrue(JwtRefreshClaimResolver.isRecomputedClaim("roles"));
            assertTrue(JwtRefreshClaimResolver.isRecomputedClaim("permissions"));
            assertTrue(JwtRefreshClaimResolver.isRecomputedClaim("tenantRoles"));
            assertFalse(JwtRefreshClaimResolver.isRecomputedClaim("userId"));
            assertFalse(JwtRefreshClaimResolver.isRecomputedClaim(null));
        }

        @Test
        @DisplayName("刷新剔除旧 tenantRoles，防租户角色撤销后复活（P0-c）")
        void refreshDropsOldTenantRoles() {
            Map<String, Object> old = new java.util.HashMap<>();
            old.put("tenantRoles", java.util.List.of(
                    java.util.Map.of("t", "t1", "r", "ADMIN")));
            old.put("roles", java.util.List.of("ADMIN"));
            old.put("customKeep", "value");
            Map<String, Object> copied = JwtRefreshClaimResolver.copyClaimsForRefresh(old);
            assertFalse(copied.containsKey("tenantRoles"));
            assertFalse(copied.containsKey("roles"));
            assertEquals("value", copied.get("customKeep"));
        }
    }
}
