package org.unreal.modelrouter.console;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A5 稳定 deptId + 组→角色映射（落「账号管理」菜单的配置块）。
 *
 * <p>背景：JwtAccountEntity.department 目前是自由文本（部门名），缺稳定部门 ID
 * 与「部门/组 → 允许角色白名单」映射，卡住成本归因（deptId）与部门预算。
 * 为遵守零存量单一基线红线，本控制器不改 jwt_accounts 表结构，改用两个 configKey 维护：
 * <ul>
 *   <li>{@code console.dept-registry}：部门名 ↔ 稳定 deptId 注册表；</li>
 *   <li>{@code console.group-role-mappings}：deptId → 允许角色列表。</li>
 * </ul>
 * 前端账号管理页在分配角色时读这份白名单，禁止 IdP 角色透传（只允许白名单内角色）。</p>
 */
@RestController
@RequestMapping("/api/security/group-roles")
@Tag(name = "组角色映射", description = "稳定 deptId 注册表与部门→角色白名单")
public class GroupRoleMappingController {

    static final String DEPT_KEY = "console.dept-registry";
    static final String MAP_KEY = "console.group-role-mappings";

    /** 允许落入白名单的角色，与 OSS TrunklineRoles 白名单口径一致（禁 IdP 角色透传）。 */
    private static final List<String> ALLOWED_ROLES =
            List.of("PLATFORM_ADMIN", "TENANT_ADMIN", "ADMIN", "OPERATOR", "USER");

    private final ConsoleExtensionStore store;

    public GroupRoleMappingController(final ConsoleExtensionStore store) {
        this.store = store;
    }

    @GetMapping("/departments")
    @Operation(summary = "列出稳定部门注册表")
    public ResponseEntity<RouterResponse<List<Map<String, Object>>>> departments() {
        return ResponseEntity.ok(RouterResponse.success(plainList(store.list(DEPT_KEY))));
    }

    /** 登记/更新部门，生成稳定 deptId（同部门名幂等）。 */
    @PostMapping("/departments")
    @Operation(summary = "登记部门（生成稳定 deptId）")
    public ResponseEntity<RouterResponse<Map<String, Object>>> registerDepartment(
            @RequestBody final Map<String, Object> body) {
        final String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        final List<Map<String, Object>> existing = store.list(DEPT_KEY);
        for (final Map<String, Object> d : existing) {
            if (name.equals(d.get("name"))) return ResponseEntity.ok(RouterResponse.success(store.plain(d)));
        }
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("deptId", "DEPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        record.put("name", name);
        record.put("createdAt", java.time.Instant.now().toString());
        store.upsert(DEPT_KEY, "deptId", record);
        return ResponseEntity.ok(RouterResponse.success(store.plain(record)));
    }

    @GetMapping("/mappings")
    @Operation(summary = "列出部门→角色白名单映射")
    public ResponseEntity<RouterResponse<List<Map<String, Object>>>> mappings() {
        return ResponseEntity.ok(RouterResponse.success(plainList(store.list(MAP_KEY))));
    }

    /** 设置某部门的允许角色（仅白名单内角色生效）。 */
    @PostMapping("/mappings")
    @Operation(summary = "设置部门→角色白名单")
    public ResponseEntity<RouterResponse<Map<String, Object>>> setMapping(
            @RequestBody final Map<String, Object> body) {
        final String deptId = body.get("deptId") == null ? "" : String.valueOf(body.get("deptId")).trim();
        if (deptId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deptId is required");
        final List<String> roles = new java.util.ArrayList<>();
        if (body.get("roles") instanceof List<?> raw) {
            for (final Object r : raw) {
                final String role = String.valueOf(r);
                if (ALLOWED_ROLES.contains(role) && !roles.contains(role)) roles.add(role);
            }
        }
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("deptId", deptId);
        record.put("roles", roles);
        record.put("updatedAt", java.time.Instant.now().toString());
        store.upsert(MAP_KEY, "deptId", record);
        return ResponseEntity.ok(RouterResponse.success(store.plain(record)));
    }

    /** 查询某部门可用角色（供账号管理页下拉用）。 */
    @GetMapping("/mappings/{deptId}/roles")
    @Operation(summary = "查询某部门允许的角色")
    public ResponseEntity<RouterResponse<List<String>>> rolesFor(@PathVariable final String deptId) {
        for (final Map<String, Object> m : store.list(MAP_KEY)) {
            if (deptId.equals(m.get("deptId"))) {
                final List<String> roles = new java.util.ArrayList<>();
                if (m.get("roles") instanceof List<?> raw) {
                    for (final Object r : raw) roles.add(String.valueOf(r));
                }
                return ResponseEntity.ok(RouterResponse.success(roles));
            }
        }
        // 未配置映射时回落到全局白名单中的基础角色，避免账号完全无法分配
        return ResponseEntity.ok(RouterResponse.success(List.of("USER")));
    }

    @DeleteMapping("/mappings/{deptId}")
    @Operation(summary = "删除部门→角色映射")
    public ResponseEntity<RouterResponse<Void>> deleteMapping(@PathVariable final String deptId) {
        store.delete(MAP_KEY, "deptId", deptId);
        return ResponseEntity.ok(RouterResponse.success(null));
    }

    private List<Map<String, Object>> plainList(final List<Map<String, Object>> source) {
        final List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (final Map<String, Object> m : source) result.add(store.plain(m));
        return result;
    }
}
