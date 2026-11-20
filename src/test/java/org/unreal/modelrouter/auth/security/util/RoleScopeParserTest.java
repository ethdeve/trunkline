/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoleScopeParser 单元测试（P0-c 二维角色地基）。
 *
 * <p>核心不变量：
 * <ol>
 *   <li>社区版零变化——旧格式 ["ADMIN"] / ["ADMIN","USER"] 解析为 legacy=true、全部角色全局生效，
 *       any tenant 下 rolesForTenant 恒等于全部角色；</li>
 *   <li>新格式跨租户不同角色——[{t:t1,r:ADMIN},{t:t2,r:USER}] 按租户正确切分；</li>
 *   <li>全局通配符 t="*" 作为平台超管/AUDITOR，在任意租户生效；</li>
 *   <li>解析失败降级为空，不抛异常（登录不阻断）。</li>
 * </ol>
 *
 * @author Trunkline Team
 */
@DisplayName("RoleScopeParser 二维角色兼容解析测试")
class RoleScopeParserTest {

    @Test
    @DisplayName("旧格式单角色：legacy=true，全局=全部，任意租户生效")
    void legacySingleRole() {
        RoleScopeParser p = RoleScopeParser.parse("[\"ADMIN\"]");
        assertTrue(p.isLegacy());
        assertEquals(Set.of("ADMIN"), p.globalRoles());
        assertTrue(p.rolesByTenant().isEmpty());
        assertEquals(Set.of("ADMIN"), p.rolesForTenant("t1"));
        assertEquals(Set.of("ADMIN"), p.rolesForTenant("anything"));
        assertEquals(Set.of("ADMIN"), p.allRoles());
        assertFalse(p.hasTenantScopedBindings());
        assertTrue(p.authorizedTenants().isEmpty());
    }

    @Test
    @DisplayName("旧格式多角色：全部角色全局生效（社区版逐字不变）")
    void legacyMultipleRoles() {
        RoleScopeParser p = RoleScopeParser.parse("[\"ADMIN\",\"USER\",\"OPERATOR\"]");
        assertTrue(p.isLegacy());
        assertEquals(Set.of("ADMIN", "USER", "OPERATOR"), p.allRoles());
        assertEquals(p.allRoles(), p.rolesForTenant("t1"));
        assertEquals(p.allRoles(), p.rolesForTenant(null));
    }

    @Test
    @DisplayName("新格式：跨租户不同角色正确切分")
    void newFormatCrossTenantRoles() {
        RoleScopeParser p = RoleScopeParser.parse(
                "[{\"t\":\"t1\",\"r\":\"ADMIN\"},{\"t\":\"t2\",\"r\":\"USER\"}]");
        assertFalse(p.isLegacy());
        assertTrue(p.globalRoles().isEmpty());
        assertEquals(Set.of("ADMIN"), p.rolesForTenant("t1"));
        assertEquals(Set.of("USER"), p.rolesForTenant("t2"));
        // 未绑定租户：无全局角色时为空
        assertTrue(p.rolesForTenant("t3").isEmpty());
        // allRoles 为并集，供 URL 级 authority 使用
        assertEquals(Set.of("ADMIN", "USER"), p.allRoles());
        assertTrue(p.hasTenantScopedBindings());
        assertEquals(List.of("t1", "t2"), p.authorizedTenants());
    }

    @Test
    @DisplayName("新格式：t=* 全局通配符在任意租户生效（平台超管/AUDITOR）")
    void globalWildcard() {
        RoleScopeParser p = RoleScopeParser.parse(
                "[{\"t\":\"*\",\"r\":\"AUDITOR\"}]");
        assertFalse(p.isLegacy());
        assertEquals(Set.of("AUDITOR"), p.globalRoles());
        assertEquals(Set.of("AUDITOR"), p.rolesForTenant("t1"));
        assertEquals(Set.of("AUDITOR"), p.rolesForTenant("any"));
        // 全局-only 不产生租户级绑定，不签发 tenantRoles claim
        assertFalse(p.hasTenantScopedBindings());
    }

    @Test
    @DisplayName("新格式：全局 + 租户混合，作用域叠加")
    void mixedGlobalAndTenant() {
        RoleScopeParser p = RoleScopeParser.parse(
                "[{\"t\":\"*\",\"r\":\"ADMIN\"},{\"t\":\"t1\",\"r\":\"USER\"}]");
        assertEquals(Set.of("ADMIN"), p.rolesForTenant("t2"));
        assertEquals(Set.of("ADMIN", "USER"), p.rolesForTenant("t1"));
        assertEquals(Set.of("ADMIN", "USER"), p.allRoles());
    }

    @Test
    @DisplayName("大小写归一：租户小写、角色大写")
    void normalization() {
        RoleScopeParser p = RoleScopeParser.parse(
                "[{\"t\":\"ACME\",\"r\":\"admin\"}]");
        assertEquals(Set.of("ADMIN"), p.rolesForTenant("acme"));
        assertEquals(Set.of("ADMIN"), p.rolesForTenant("ACME"));
    }

    @Test
    @DisplayName("解析失败降级为空，不抛异常")
    void malformedFallsBackEmpty() {
        assertTrue(RoleScopeParser.parse("not-json").allRoles().isEmpty());
        assertTrue(RoleScopeParser.parse("{\"not\":\"a list\"}").allRoles().isEmpty());
        assertTrue(RoleScopeParser.parse(null).allRoles().isEmpty());
        assertTrue(RoleScopeParser.parse("").allRoles().isEmpty());
        assertTrue(RoleScopeParser.parse("[]").allRoles().isEmpty());
    }

    @Test
    @DisplayName("序列化确定性：全局在前，租户按 key 排序")
    void serializationDeterministic() {
        RoleScopeParser p = RoleScopeParser.parse(
                "[{\"t\":\"t2\",\"r\":\"USER\"},{\"t\":\"t1\",\"r\":\"ADMIN\"},{\"t\":\"*\",\"r\":\"AUDITOR\"}]");
        String json = p.toTenantRolesJson();
        assertTrue(json.indexOf("\"*\"") < json.indexOf("\"t1\""));
        assertTrue(json.indexOf("\"t1\"") < json.indexOf("\"t2\""));
        // 序列化后再解析，语义等价
        RoleScopeParser roundTrip = RoleScopeParser.parse(json);
        assertEquals(p.globalRoles(), roundTrip.globalRoles());
        assertEquals(p.rolesByTenant(), roundTrip.rolesByTenant());
    }

    @Test
    @DisplayName("空集：rolesForTenant 对 null 租户只返回全局角色")
    void nullTenantOnlyGlobal() {
        RoleScopeParser p = RoleScopeParser.parse(
                "[{\"t\":\"t1\",\"r\":\"ADMIN\"}]");
        assertTrue(p.rolesForTenant(null).isEmpty());
        assertEquals(Set.of("ADMIN"), p.rolesForTenant("t1"));
    }
}
