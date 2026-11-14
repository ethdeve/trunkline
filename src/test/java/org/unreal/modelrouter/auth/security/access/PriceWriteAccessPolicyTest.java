/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 / 方案 3.2 #10：单价写权限收紧到 ADMIN 的顺序回归。
 *
 * <p><b>为什么单独一个测试类</b>：矩阵是「首条命中优先」，把 {@code write} 改成 ADMIN 本身
 * 只有一行，但真正的风险是后续有人再加一条更宽泛、级别更低的写规则并登记在前面，
 * 或把 GET 与 write 的顺序写反导致读也被挡到 ADMIN。这里把方法集合、路径深度与
 * 规则登记顺序一起钉死。</p>
 *
 * <p>「改单价 = 改钱」：运维（OPERATOR）在接入管理「价格」tab 只能看，不能改；
 * 企业版 {@code ModelPriceController} 的 PUT / DELETE 由本矩阵裁决（控制器无方法级注解）。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("B4：单价端点权限矩阵")
class PriceWriteAccessPolicyTest {

    private static final String CATALOG = "/api/config/prices/catalog";
    private static final String SINGLE = "/api/config/prices/gpt-4o";

    private final FixedRoleAccessPolicy policy = new FixedRoleAccessPolicy();

    private AccessLevel levelOf(final HttpMethod method, final String path) {
        final Optional<AccessRule> rule = policy.findRule(method, path);
        assertTrue(rule.isPresent(), "应命中规则: " + method + " " + path);
        return rule.get().level();
    }

    @Test
    @DisplayName("读：价目表 / 目录 / 单行仍是 OPS")
    void readsRemainOps() {
        assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, "/api/config/prices"));
        assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, CATALOG));
        assertEquals(AccessLevel.OPS, levelOf(HttpMethod.GET, SINGLE));
    }

    @Test
    @DisplayName("写：POST / PUT / PATCH / DELETE 四种方法全部 ADMIN")
    void allWriteMethodsAreAdmin() {
        for (final HttpMethod method : List.of(
                HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            assertEquals(AccessLevel.ADMIN, levelOf(method, SINGLE),
                    method + " 改单价必须仅管理员可做");
            assertEquals(AccessLevel.ADMIN, levelOf(method, CATALOG));
        }
    }

    @Test
    @DisplayName("深层路径（租户维度议价）同样被 ADMIN 规则覆盖")
    void nestedPathsAreAdmin() {
        assertEquals(AccessLevel.ADMIN,
                levelOf(HttpMethod.PUT, "/api/config/prices/tenant/acme/gpt-4o"));
        assertEquals(AccessLevel.OPS,
                levelOf(HttpMethod.GET, "/api/config/prices/tenant/acme/gpt-4o"));
    }

    @Test
    @DisplayName("矩阵中不存在针对 prices 的 OPS 写规则（顺序实测，防死规则遮蔽）")
    void noOopsWriteRuleForPrices() {
        final List<AccessRule> rules = policy.getRules();
        int getIndex = -1;
        int writeIndex = -1;
        for (int i = 0; i < rules.size(); i++) {
            final AccessRule rule = rules.get(i);
            if (!"/api/config/prices/**".equals(rule.pathPattern())) {
                continue;
            }
            if (rule.methods().equals(java.util.Set.of(HttpMethod.GET))) {
                getIndex = i;
                assertEquals(AccessLevel.OPS, rule.level(), "GET 规则须保持 OPS");
            } else {
                writeIndex = i;
                assertEquals(AccessLevel.ADMIN, rule.level(),
                        "不允许残留 OPS 写规则，否则首条命中会让 ADMIN 规则成为死规则");
                assertTrue(rule.methods().contains(HttpMethod.PUT));
                assertTrue(rule.methods().contains(HttpMethod.DELETE));
            }
        }
        assertTrue(getIndex >= 0 && writeIndex >= 0, "应同时登记 GET 与 write 两条 prices 规则");
        assertTrue(getIndex < writeIndex, "GET 规则须先登记，保证读不被写规则影响");
        assertFalse(rules.subList(0, writeIndex).stream().anyMatch(r ->
                        r.matches(HttpMethod.PUT, SINGLE) && r.level() != AccessLevel.ADMIN),
                "更早登记的更宽泛写规则不得抢先命中（首条命中优先）");
    }

    @Test
    @DisplayName("相邻端点未被误伤：providers / quota 写仍是 OPS")
    void adjacentRulesAreUntouched() {
        assertEquals(AccessLevel.OPS, levelOf(HttpMethod.PUT, "/api/config/providers/openai"));
        assertEquals(AccessLevel.OPS, levelOf(HttpMethod.PUT, "/api/config/quota/gpt-4o"));
        assertEquals(AccessLevel.OPS, levelOf(HttpMethod.POST, "/api/config/instance/pool-1"));
    }
}
