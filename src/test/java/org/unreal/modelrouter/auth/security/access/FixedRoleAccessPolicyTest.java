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
            // 企业能力探测（O6）：任意已登录角色都要能做特性探测，
            // 未登记 fail-closed 仅 ADMIN 会把普通用户挡在企业 UI 显隐判断之外
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/v1/enterprise/features"));
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
        @DisplayName("模型能力档案（v0.2.0）为 OPS——未登记会让 OPERATOR fail-closed 403")
        void modelCapabilitiesAreOps() {
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/config/model-capabilities/list"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/config/model-capabilities/gpt-4o"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/config/model-capabilities/facets"));
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
    @DisplayName("AUDIT：企业跨租户只读汇总（ADMIN + AUDITOR）")
    class AuditEndpoints {

        @Test
        @DisplayName("企业用量总览显式登记为 AUDIT——不能落 fail-closed 把审计员挡在门外")
        void enterpriseUsageOverviewIsAuditLevel() {
            assertEquals(AccessLevel.AUDIT,
                    levelOf(HttpMethod.GET, "/api/v1/enterprise/usage/overview"));
        }
    }

    @Nested
    @DisplayName("2026-11-22 整改新增：4 个 Controller 的 URL 矩阵兜底")
    class NewlyRegisteredControllerEndpoints {

        @Test
        @DisplayName("提示词模板全方法为 OPS——含根路径与各子路径")
        void promptTemplatesAreOps() {
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/config/prompt-templates"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.POST, "/api/config/prompt-templates"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/config/prompt-templates/t1/versions"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.POST, "/api/config/prompt-templates/compose"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.DELETE, "/api/config/prompt-templates/t1"));
        }

        @Test
        @DisplayName("密钥申请：submit/mine 为 SELF，all/decide 为 OPS——USER 绝不能审批")
        void apiKeyRequestsAreCorrectlyScoped() {
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.POST, "/api/auth/api-key-requests"));
            assertEquals(AccessLevel.SELF, levelOf(HttpMethod.GET, "/api/auth/api-key-requests/mine"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/auth/api-key-requests"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.POST, "/api/auth/api-key-requests/r1/decide"));
            assertTrue(AccessLevel.SELF.isSatisfiedBy(java.util.List.of("ROLE_USER")),
                    "USER 应能提交申请/查自己申请");
            assertTrue(!AccessLevel.OPS.isSatisfiedBy(java.util.List.of("ROLE_USER")),
                    "USER 绝不能越权审批");
        }

        @Test
        @DisplayName("组织映射：rolesFor 为 SELF，其余为 OPS")
        void groupRolesAreCorrectlyScoped() {
            assertEquals(AccessLevel.SELF,
                    levelOf(HttpMethod.GET, "/api/security/group-roles/mappings/d1/roles"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/security/group-roles/mappings"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.POST, "/api/security/group-roles/mappings"));
            assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/security/group-roles/departments"));
        }
    }

    @Nested
    @DisplayName("我的可见租户（P2：SELF 供切换器使用）")
    class MyTenantsEndpoints {

        @Test
        @DisplayName("GET /api/v1/enterprise/tenants/mine 登记为 SELF——不能落 fail-closed 把 USER 挡在门外")
        void myTenantsIsSelfLevel() {
            assertEquals(AccessLevel.SELF,
                    levelOf(HttpMethod.GET, "/api/v1/enterprise/tenants/mine"));
        }

        @Test
        @DisplayName("管理端租户开通/停用显式登记 PLATFORM（仅全局 ADMIN），不与 /mine 的 SELF 混淆")
        void managementEndpointsArePlatformLevel() {
            // A 口径收紧：平台级端点不再落 fail-closed 兜底，而是显式 PLATFORM 级别，
            // 由 FixedRoleAuthorizationManager 读 tenantRoles claim 区分全局/租户级 ADMIN。
            assertEquals(AccessLevel.PLATFORM,
                    levelOf(HttpMethod.GET, "/api/v1/enterprise/tenants"));
            assertEquals(AccessLevel.PLATFORM,
                    levelOf(HttpMethod.POST, "/api/v1/enterprise/tenants"));
            assertEquals(AccessLevel.PLATFORM,
                    levelOf(HttpMethod.DELETE, "/api/v1/enterprise/tenants/t2"));
            // /mine 必须仍是 SELF——首条命中优先，不能被 PLATFORM 通配吞掉
            assertEquals(AccessLevel.SELF,
                    levelOf(HttpMethod.GET, "/api/v1/enterprise/tenants/mine"));
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
