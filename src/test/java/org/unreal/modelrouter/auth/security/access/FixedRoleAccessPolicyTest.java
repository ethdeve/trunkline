/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定角色访问矩阵测试（社区版权权固定化）。
 *
 * <p>矩阵即产品契约：员工=「我的」分组、运维=「我的+团队/策略」、管理员=全部。
 * 本测试锁定关键路径的级别与规则顺序（首条命中优先），防止后续改动破坏菜单-后端一致性。</p>
 * @author Trunkline Team
 */
@DisplayName("固定角色访问矩阵")
class FixedRoleAccessPolicyTest {

    private final FixedRoleAccessPolicy policy = new FixedRoleAccessPolicy();

    private AccessLevel levelOf(final HttpMethod method, final String path) {
        final Optional<AccessRule> rule = policy.findRule(method, path);
        assertTrue(rule.isPresent(), "应命中规则: " + method + " " + path);
        return rule.get().level();
    }

    @Nested
    @DisplayName("SELF：员工自助端点（「我的」分组）")
    class SelfEndpoints {

        @Test
        @DisplayName("我的调用记录与导出为 SELF，且先于 call-history 通配规则命中")
        void mineEndpointsAreSelf() {
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/call-history/mine"));
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/call-history/mine/export"));
        }

        @Test
        @DisplayName("我的账单/额度/密钥/实例清单为 SELF")
        void selfEndpoints() {
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/billing/my"));
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/billing/my/recent"));
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/auth/api-keys/mine"));
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/instance/mine"));
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/gateway/info"));
            // 我的密钥「本月已用」自助端点：必须先于 /api/monitoring/quota/** 的 OPS 规则命中
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/monitoring/quota/mine/usage"));
        }
    }

    @Nested
    @DisplayName("OPS：团队/策略分组")
    class OpsEndpoints {

        @Test
        @DisplayName("内置帮助文档正文为 OPS——「只从豁免清单移除」会让 OPERATOR 落回 fail-closed 403")
        void docsContentIsRegisteredAsOps() {
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/docs/content"));
        }

        @Test
        @DisplayName("调用历史列表（调用观测）为 OPS——修正存量运维 403 不一致")
        void callHistoryIsOps() {
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/call-history"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/call-history/123"));
        }

        @Test
        @DisplayName("全部密钥管理为 OPS——修正存量运维 403 不一致")
        void apiKeyManageIsOps() {
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.POST, "/api/auth/api-keys"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.DELETE, "/api/auth/api-keys/key-1"));
        }

        @Test
        @DisplayName("团队用量/部门下拉为 OPS——修正方法级允许运维但 URL 未登记的 403")
        void billingTeamEndpointsAreOps() {
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/billing/team"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/billing/departments"));
            // 员工自助账单仍是 SELF，不能被上面的 OPS 规则覆盖
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/billing/my"));
        }

        @Test
        @DisplayName("dashboard/接入/监控/追踪查询为 OPS")
        void teamEndpointsAreOps() {
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/dashboard/summary"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/token-usage/list"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/config/instance/pool-1"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.PUT, "/api/config/instance/pool-1"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/monitoring/metrics/x"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/monitoring/quota/usage"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/monitoring/quota/status"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/tracing/query/trace-1"));
        }

        @Test
        @DisplayName("追踪配置写为 ADMIN（旧码语义保留）")
        void tracingConfigWriteIsAdmin() {
            assertEquals(AccessLevel.ADMIN, levelOf(HttpMethod.PUT, "/api/tracing/query/config"));
            assertEquals(AccessLevel.ADMIN, levelOf(HttpMethod.POST, "/api/tracing/actuator/toggle"));
        }
    }

    @Nested
    @DisplayName("ADMIN：系统分组与敏感面")
    class AdminEndpoints {

        @Test
        @DisplayName("账户/黑名单/脱敏/JWT 运维为 ADMIN")
        void adminEndpoints() {
            assertEquals(AccessLevel.ADMIN, levelOf(HttpMethod.GET, "/api/security/jwt/accounts"));
            assertEquals(AccessLevel.ADMIN, levelOf(HttpMethod.POST, "/api/security/blacklist/x"));
            assertEquals(AccessLevel.ADMIN, levelOf(HttpMethod.GET, "/api/config/sanitization/rules"));
            assertEquals(AccessLevel.ADMIN, levelOf(HttpMethod.GET, "/api/auth/jwt/blacklist/stats"));
            assertEquals(AccessLevel.ADMIN, levelOf(HttpMethod.GET, "/api/auth/jwt/tokens/t-1"));
        }
    }

    @Nested
    @DisplayName("未登记端点（fail-closed 兜底语义由授权管理器执行）")
    class UnmatchedEndpoints {

        @Test
        @DisplayName("未登记路径返回 empty（/api/billing/team 已登记为 OPS，不再属于未登记）")
        void unmatchedReturnsEmpty() {
            assertTrue(policy.findRule(HttpMethod.GET, "/api/billing/unknown-view").isEmpty());
            assertTrue(policy.findRule(HttpMethod.GET, "/api/unknown/resource").isEmpty());
        }

        @Test
        @DisplayName("方法不匹配不命中（GET-only 规则不匹配 POST）")
        void methodMustMatch() {
            assertTrue(policy.findRule(HttpMethod.POST, "/api/billing/my").isEmpty());
        }
    }
}
