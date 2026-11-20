/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色作用域兼容解析器（多租户二维角色地基，P0-c）。
 *
 * <p>职责：把 {@code jwt_accounts.roles} 列（JSON）与 JWT 的 {@code roles}/{@code tenantRoles}
 * claim 统一解析成"角色绑定"结构，同时<b>完整兼容旧格式</b>，保证社区版行为零变化。</p>
 *
 * <p>两种存储格式：</p>
 * <ul>
 *   <li><b>旧格式（社区版 / 存量数据）</b>：{@code ["ADMIN","USER"]} —— 所有角色作用于全部租户，
 *       等价 {@code t="*"}。解析结果中全局角色集合=全部，租户映射为空 → 任何租户下生效角色=全部，
 *       与改造前逐字一致。</li>
 *   <li><b>新格式（企业版二维）</b>：{@code [{"t":"t1","r":"ADMIN"},{"t":"t2","r":"USER"}]} ——
 *       每条绑定含租户与角色；{@code t="*"} 表示全局（平台超管 / AUDITOR）。</li>
 * </ul>
 *
 * <p><b>关键不变量</b>：{@link #globalRoles()} 返回"在任何租户下都生效"的角色（旧格式全部角色 +
 * 新格式中 {@code t="*"} 的角色）。URL 级固定角色矩阵继续用这个集合生成 authority —— 因此社区版
 * （只有旧格式）的判定链完全不变。租户级角色的作用域收窄在企业侧 P2 叠加，本类只提供数据。</p>
 *
 * @author Trunkline Team
 */
public final class RoleScopeParser {

    /** 全局作用域通配符：仅用于角色绑定内部表达，永不作为 X-Tenant-Id 下发（撞 ID 正则）。 */
    public static final String GLOBAL_TENANT = "*";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};

    private final Set<String> globalRoles;
    private final Map<String, Set<String>> rolesByTenant;
    private final boolean legacy;

    private RoleScopeParser(final Set<String> globalRoles,
                            final Map<String, Set<String>> rolesByTenant,
                            final boolean legacy) {
        this.globalRoles = globalRoles;
        this.rolesByTenant = rolesByTenant;
        this.legacy = legacy;
    }

    /** 空解析结果（无角色 / 解析失败降级）。 */
    public static RoleScopeParser empty() {
        return new RoleScopeParser(Collections.emptySet(), Collections.emptyMap(), true);
    }

    /**
     * 解析 roles 存储字符串（jwt_accounts.roles 列）。自动识别旧/新格式。
     *
     * @param rolesJson JSON 字符串；null/空白/非法 → 空结果（降级，不阻断登录）
     */
    public static RoleScopeParser parse(final String rolesJson) {
        if (rolesJson == null || rolesJson.isBlank()) {
            return empty();
        }
        try {
            final Object value = MAPPER.readValue(rolesJson, Object.class);
            if (value instanceof List<?> list) {
                return fromList(list);
            }
            return empty();
        } catch (JsonProcessingException e) {
            return empty();
        }
    }

    /**
     * 解析已经反序列化好的列表（JWT claim 场景：roles 是 List&lt;String&gt;，
     * tenantRoles 是 List&lt;Map&gt;）。
     */
    public static RoleScopeParser fromList(final List<?> list) {
        if (list == null || list.isEmpty()) {
            return empty();
        }
        final boolean legacy = list.stream().allMatch(item -> item instanceof String);
        final Set<String> global = new LinkedHashSet<>();
        final Map<String, Set<String>> byTenant = new LinkedHashMap<>();
        if (legacy) {
            // 旧格式：全部角色视为全局，任何租户下都生效 → 社区版判定链逐字不变
            for (final Object item : list) {
                addRole(global, (String) item);
            }
        } else {
            // 新格式：[{t, r}, ...]
            for (final Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                final Object t = map.get("t");
                final Object r = map.get("r");
                if (t == null || r == null) {
                    continue;
                }
                final String tenant = String.valueOf(t).trim().toLowerCase();
                final String role = String.valueOf(r).trim().toUpperCase();
                if (tenant.isEmpty() || role.isEmpty()) {
                    continue;
                }
                if (GLOBAL_TENANT.equals(tenant)) {
                    addRole(global, role);
                } else {
                    byTenant.computeIfAbsent(tenant, k -> new LinkedHashSet<>()).add(role);
                }
            }
        }
        return new RoleScopeParser(
                Collections.unmodifiableSet(global),
                Collections.unmodifiableMap(byTenant),
                legacy);
    }

    /** 合并两个来源（如旧 roles claim + 新 tenantRoles claim），全局角色取并集。 */
    public static RoleScopeParser merge(final RoleScopeParser a, final RoleScopeParser b) {
        final Set<String> global = new LinkedHashSet<>(a.globalRoles);
        global.addAll(b.globalRoles);
        final Map<String, Set<String>> byTenant = new LinkedHashMap<>(a.rolesByTenant);
        b.rolesByTenant.forEach((tenant, roles) ->
                byTenant.computeIfAbsent(tenant, k -> new LinkedHashSet<>()).addAll(roles));
        return new RoleScopeParser(
                Collections.unmodifiableSet(global),
                Collections.unmodifiableMap(byTenant),
                a.legacy && b.legacy);
    }

    private static void addRole(final Set<String> target, final String role) {
        if (role != null && !role.isBlank()) {
            target.add(role.trim().toUpperCase());
        }
    }

    /**
     * 全部角色的并集（全局 ∪ 所有租户级）。
     *
     * <p>P0-c 阶段 URL 级固定角色矩阵用这个集合生成 authority —— 保证"t1 的租户管理员"持有
     * ROLE_ADMIN、能进入管理页面（URL 判定是全局的，不带租户上下文）。数据级作用域收窄
     * （限制其只能操作本租户数据）在企业侧 P2 叠加。旧格式时此集合=全部角色，社区版零变化。</p>
     */
    public Set<String> allRoles() {
        final Set<String> result = new LinkedHashSet<>(globalRoles);
        rolesByTenant.values().forEach(result::addAll);
        return result;
    }

    /** 全局角色集合（任何租户下都生效；旧格式=全部角色）。 */
    public Set<String> globalRoles() {
        return globalRoles;
    }

    /** 租户→角色映射（仅新格式的非全局绑定）。 */
    public Map<String, Set<String>> rolesByTenant() {
        return rolesByTenant;
    }

    /** 是否为旧格式（社区版 / 存量数据标志）。 */
    public boolean isLegacy() {
        return legacy;
    }

    /**
     * 给定租户下生效的全部角色 = 全局角色 ∪ 该租户的绑定角色。
     * 旧格式时 tenant 参数被忽略，恒返回全部角色（社区版零变化）。
     */
    public Set<String> rolesForTenant(final String tenantId) {
        final Set<String> result = new LinkedHashSet<>(globalRoles);
        if (tenantId != null) {
            final Set<String> scoped = rolesByTenant.get(tenantId.trim().toLowerCase());
            if (scoped != null) {
                result.addAll(scoped);
            }
        }
        return result;
    }

    /** 用户有权的租户集合（新格式中显式绑定的租户 + 全局标记；旧格式返回空集表示"全部")。 */
    public List<String> authorizedTenants() {
        return new ArrayList<>(rolesByTenant.keySet());
    }

    /** 是否有非全局的租户级绑定（用于决定是否签发 tenantRoles claim）。 */
    public boolean hasTenantScopedBindings() {
        return !rolesByTenant.isEmpty();
    }

    /**
     * 序列化成新格式绑定数组（供 JWT tenantRoles claim / 存储回写）。
     * 输出顺序：全局绑定在前（t="*"），租户绑定按 key 排序，保证确定性与可测试性。
     *
     * @return 形如 {@code [{"t":"*","r":"ADMIN"},{"t":"t1","r":"USER"}]}；无绑定时返回空数组
     */
    public String toTenantRolesJson() {
        final List<Map<String, String>> bindings = new ArrayList<>();
        for (final String role : globalRoles) {
            bindings.add(Map.of("t", GLOBAL_TENANT, "r", role));
        }
        rolesByTenant.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    for (final String role : entry.getValue()) {
                        bindings.add(Map.of("t", entry.getKey(), "r", role));
                    }
                });
        try {
            return MAPPER.writeValueAsString(bindings);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
