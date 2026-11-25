package org.unreal.modelrouter.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台扩展功能（提示词模板 / 适配器生命周期 / API Key 审批 / 组角色映射）
 * 的纯单元测试：用内存 StoreManager 替身验证存储与状态机，不启 Spring 上下文。
 */
@DisplayName("控制台扩展功能单元测试")
class ConsoleExtensionsTest {

    private ConsoleExtensionStore store;

    @BeforeEach
    void setUp() {
        store = new ConsoleExtensionStore(new InMemoryStoreManager(), new ObjectMapper());
    }

    // ---------- 存储底座 ----------
    @Test
    @DisplayName("STORE-1: upsert 幂等，同 id 覆盖不新增")
    void upsertIdempotent() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", "a");
        first.put("v", 1);
        assertTrue(store.upsert("k", "id", first));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", "a");
        second.put("v", 2);
        assertFalse(store.upsert("k", "id", second));
        assertEquals(1, store.list("k").size());
        assertEquals(2, store.list("k").get(0).get("v"));
    }

    @Test
    @DisplayName("STORE-2: delete 幂等")
    void deleteIdempotent() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "a");
        store.upsert("k", "id", item);
        store.delete("k", "id", "a");
        store.delete("k", "id", "a");
        assertTrue(store.list("k").isEmpty());
    }

    // ---------- 提示词模板 ----------
    @Test
    @DisplayName("TPL-1: 保存生成 id，二次保存保留版本历史")
    void promptTemplateVersions() {
        PromptTemplateController controller = new PromptTemplateController(store);
        Map<String, Object> created = controller.save(Map.of("name", "系统提示词", "content", "v1")).getBody().getData();
        String id = String.valueOf(created.get("templateId"));
        assertNotNull(id);
        Map<String, Object> updated = new LinkedHashMap<>();
        updated.put("templateId", id);
        updated.put("name", "系统提示词");
        updated.put("content", "v2");
        controller.save(updated);
        assertEquals(1, controller.versions(id).getBody().getData().size());
    }

    @Test
    @DisplayName("TPL-2: 编排按 id 顺序拼接")
    void promptTemplateCompose() {
        PromptTemplateController controller = new PromptTemplateController(store);
        String a = String.valueOf(controller.save(Map.of("name", "A", "content", "AAA")).getBody().getData().get("templateId"));
        String b = String.valueOf(controller.save(Map.of("name", "B", "content", "BBB")).getBody().getData().get("templateId"));
        Map<String, Object> result = controller.compose(Map.of("templateIds", List.of(b, a))).getBody().getData();
        assertEquals("BBB\nAAA", result.get("content"));
        assertEquals(2, result.get("steps"));
    }

    @Test
    @DisplayName("TPL-3: 缺 name 返回 400")
    void promptTemplateMissingName() {
        PromptTemplateController controller = new PromptTemplateController(store);
        assertThrows(ResponseStatusException.class, () -> controller.save(Map.of("content", "x")));
    }

    // ---------- API Key 审批 ----------
    @Test
    @DisplayName("APR-1: 提交→审批为 APPROVED，重复审批 409")
    void approvalStateMachine() {
        ApiKeyApprovalController controller = new ApiKeyApprovalController(store, null);
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
        Map<String, Object> req = controller.submit(Map.of("purpose", "测试用途"), alice).getBody().getData();
        String rid = String.valueOf(req.get("requestId"));
        assertEquals("PENDING", req.get("status"));
        Authentication admin = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
        Map<String, Object> decided = controller.decide(rid, Map.of("decision", "APPROVED"), admin).getBody().getData();
        assertEquals("APPROVED", decided.get("status"));
        assertEquals("admin", decided.get("decidedBy"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.decide(rid, Map.of("decision", "REJECTED"), admin));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    @DisplayName("APR-2: 申请人由 principal 解析，不信前端传参")
    void approvalApplicantFromPrincipal() {
        ApiKeyApprovalController controller = new ApiKeyApprovalController(store, null);
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
        Map<String, Object> req = controller.submit(
                Map.of("purpose", "用途", "applicant", "bob"), alice).getBody().getData();
        assertEquals("alice", req.get("applicant"));
    }

    @Test
    @DisplayName("APR-3: 非法 decision 返回 400")
    void approvalBadDecision() {
        ApiKeyApprovalController controller = new ApiKeyApprovalController(store, null);
        Authentication admin = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
        Map<String, Object> req = controller.submit(Map.of("purpose", "用途"), admin).getBody().getData();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.decide(String.valueOf(req.get("requestId")), Map.of("decision", "MAYBE"), admin));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("APR-4: 申请单密级默认 PUBLIC，非法值回落 PUBLIC，合法值大小写归一")
    void approvalDesiredClassificationNormalized() {
        ApiKeyApprovalController controller = new ApiKeyApprovalController(store, null);
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());

        Map<String, Object> defaultReq = controller.submit(Map.of("purpose", "默认密级"), alice).getBody().getData();
        assertEquals("PUBLIC", defaultReq.get("desiredClassification"));

        Map<String, Object> upper = controller.submit(
                Map.of("purpose", "归一", "desiredClassification", " internal "), alice).getBody().getData();
        assertEquals("INTERNAL", upper.get("desiredClassification"));

        Map<String, Object> illegal = controller.submit(
                Map.of("purpose", "非法", "desiredClassification", "TOP_SECRET"), alice).getBody().getData();
        assertEquals("PUBLIC", illegal.get("desiredClassification"));
    }

    @Test
    @DisplayName("APR-5: 审批通过未指定 finalClassification → 沿用申请人期望密级")
    void approvalFinalClassificationInheritsDesired() {
        ApiKeyApprovalController controller = new ApiKeyApprovalController(store, null);
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
        Authentication admin = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
        Map<String, Object> req = controller.submit(
                Map.of("purpose", "机密用途", "desiredClassification", "CONFIDENTIAL"), alice).getBody().getData();
        String rid = String.valueOf(req.get("requestId"));

        Map<String, Object> decided = controller.decide(rid, Map.of("decision", "APPROVED"), admin).getBody().getData();
        assertEquals("CONFIDENTIAL", decided.get("finalClassification"));
    }

    @Test
    @DisplayName("APR-6: 审批显式 finalClassification 覆盖期望密级，拒绝单不写最终密级")
    void approvalFinalClassificationOverrideAndReject() {
        ApiKeyApprovalController controller = new ApiKeyApprovalController(store, null);
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
        Authentication admin = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
        Map<String, Object> req = controller.submit(
                Map.of("purpose", "降级审批", "desiredClassification", "CONFIDENTIAL"), alice).getBody().getData();
        String rid = String.valueOf(req.get("requestId"));

        Map<String, Object> decided = controller.decide(rid,
                Map.of("decision", "APPROVED", "finalClassification", " bogus "), admin).getBody().getData();
        assertEquals("PUBLIC", decided.get("finalClassification"), "非法值回落 PUBLIC");

        Map<String, Object> rejectedReq = controller.submit(Map.of("purpose", "拒绝单"), alice).getBody().getData();
        Map<String, Object> rejected = controller.decide(String.valueOf(rejectedReq.get("requestId")),
                Map.of("decision", "REJECTED", "finalClassification", "INTERNAL"), admin).getBody().getData();
        assertEquals("REJECTED", rejected.get("status"));
        assertFalse(rejected.containsKey("finalClassification"), "拒绝单不写最终密级");
    }

    // ---------- 组角色映射 ----------
    @Test
    @DisplayName("MAP-1: 部门登记幂等且生成稳定 deptId")
    void deptRegisterIdempotent() {
        GroupRoleMappingController controller = new GroupRoleMappingController(store);
        Map<String, Object> d1 = controller.registerDepartment(Map.of("name", "研发部")).getBody().getData();
        Map<String, Object> d2 = controller.registerDepartment(Map.of("name", "研发部")).getBody().getData();
        assertEquals(d1.get("deptId"), d2.get("deptId"));
        assertTrue(String.valueOf(d1.get("deptId")).startsWith("DEPT-"));
    }

    @Test
    @DisplayName("MAP-2: 角色白名单过滤 IdP 透传角色")
    void roleWhitelistFilter() {
        GroupRoleMappingController controller = new GroupRoleMappingController(store);
        Map<String, Object> mapping = controller.setMapping(Map.of(
                "deptId", "DEPT-1",
                "roles", List.of("USER", "SUPER_ADMIN_FROM_IDP", "OPERATOR"))).getBody().getData();
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) mapping.get("roles");
        assertTrue(roles.contains("USER"));
        assertFalse(roles.contains("SUPER_ADMIN_FROM_IDP"));
        assertTrue(roles.contains("OPERATOR"));
    }

    /** 内存 StoreManager 替身：真实实现 mutateConfig 语义。 */
    private static final class InMemoryStoreManager implements StoreManager {
        private final Map<String, Map<String, Object>> data = new HashMap<>();

        @Override public void saveConfig(String key, Map<String, Object> config) { data.put(key, new LinkedHashMap<>(config)); }
        @Override public Map<String, Object> getConfig(String key) { return data.get(key); }
        @Override public void deleteConfig(String key) { data.remove(key); }
        @Override public Iterable<String> getAllKeys() { return new ArrayList<>(data.keySet()); }
        @Override public boolean exists(String key) { return data.containsKey(key); }
        @Override public void updateConfig(String key, Map<String, Object> config) { saveConfig(key, config); }
        @Override public Map<String, Object> getLatestConfig(String configKey) { return getConfig(configKey); }
        @Override public Map<String, Object> mutateConfig(String key, UnaryOperator<Map<String, Object>> mutator) {
            Map<String, Object> updated = mutator.apply(data.get(key));
            if (updated != null) data.put(key, updated);
            return updated;
        }
        // 兼容可能的接口默认方法签名差异
        @SuppressWarnings("unused")
        private LocalDateTime now() { return LocalDateTime.now(); }
    }
}
