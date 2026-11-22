/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.router.capability.ModelCapabilityProfile;
import org.unreal.modelrouter.router.capability.ModelCapabilityProfileService;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.pool.PoolConfigService;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.rule.RuleConfigService;
import org.unreal.modelrouter.router.rule.model.RuleDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一键虚拟模型：模板加载、选模、池/规则生成与部分失败的回归测试。
 *
 * @author Trunkline Team
 */
@DisplayName("一键虚拟模型场景服务")
class ScenarioTemplateServiceTest {

    private final ModelCapabilityProfileService profileService = mock(ModelCapabilityProfileService.class);
    private final ModelServiceRegistry registry = mock(ModelServiceRegistry.class);
    private final PoolConfigService poolService = mock(PoolConfigService.class);
    private final RuleConfigService ruleService = mock(RuleConfigService.class);
    private final ScenarioTemplateService service = new ScenarioTemplateService(
            new ObjectMapper(), profileService, registry, poolService, ruleService);

    @Test
    @DisplayName("内置 4 个场景模板加载成功并可按 id 查询")
    void loadsBuiltinTemplates() {
        assertEquals(4, service.list().size());
        assertTrue(service.findById("customer-support").isPresent());
        assertTrue(service.findById("doc-retrieval").isPresent());
        assertTrue(service.findById("code-assistant").isPresent());
        assertTrue(service.findById("cost-guardrail").isPresent());
        assertEquals(Optional.empty(), service.findById(""));
    }

    @Test
    @DisplayName("预览只生成草稿，不调用池/规则创建服务")
    void previewDoesNotPersist() {
        when(registry.getAllInstances()).thenReturn(Map.of(
                ModelServiceRegistry.ServiceType.chat, List.of(instance("gpt-4o", "inst-chat"))));

        ScenarioApplyResult result = service.preview("customer-support");

        assertFalse(result.isApplied());
        assertEquals("customer-support", result.getScenarioId());
        assertEquals(1, result.getPools().size());
        assertEquals("scenario-customer-support-chat", result.getPools().get(0).getPoolName());
        assertEquals(1, result.getRules().size());
        verify(poolService, never()).createValidated(any(PoolDefinition.class));
        verify(ruleService, never()).createValidated(any(RuleDefinition.class));
    }

    @Test
    @DisplayName("画像命中时摘要标注 canonicalId，未命中时标注并降级到第一个实例")
    void profileHitAndFallback() {
        when(registry.getAllInstances()).thenReturn(Map.of(
                ModelServiceRegistry.ServiceType.chat,
                List.of(instance("gpt-4o", "inst-chat"), instance("other-model", "inst-other"))));
        ModelCapabilityProfile profile = new ModelCapabilityProfile();
        profile.setCanonicalId("gpt-4o");
        profile.setModelId("gpt-4o");
        when(profileService.filter(eq("客服问答"), eq("chat"), nullable(String.class),
                eq("chinese"), eq("mid"))).thenReturn(List.of(profile));

        ScenarioApplyResult hit = service.preview("customer-support");
        assertTrue(hit.getPools().get(0).isProfileMatched());
        assertEquals("gpt-4o", hit.getPools().get(0).getCanonicalId());
        assertEquals("gpt-4o", hit.getPools().get(0).getInstanceName());

        when(profileService.filter(anyString(), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class))).thenReturn(List.of());
        ScenarioApplyResult fallback = service.preview("customer-support");
        assertFalse(fallback.getPools().get(0).isProfileMatched());
        assertTrue(fallback.getWarnings().stream().anyMatch(w -> w.contains("未命中画像")));
    }

    @Test
    @DisplayName("一键应用多池场景：三个池 + 三条规则，动作值是注入的真实池名")
    void applyMultiPoolScenario() {
        when(registry.getAllInstances()).thenReturn(Map.of(
                ModelServiceRegistry.ServiceType.chat, List.of(instance("chat-model", "id-chat")),
                ModelServiceRegistry.ServiceType.embedding, List.of(instance("embed-model", "id-embed")),
                ModelServiceRegistry.ServiceType.rerank, List.of(instance("rerank-model", "id-rerank"))));
        when(profileService.filter(anyString(), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class))).thenReturn(List.of());

        ScenarioApplyResult result = service.apply("doc-retrieval");

        assertTrue(result.isApplied());
        assertEquals(3, result.getPools().size());
        assertEquals(3, result.getRules().size());
        verify(poolService, times(3)).createValidated(any(PoolDefinition.class));
        ArgumentCaptor<RuleDefinition> captor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleService, times(3)).createValidated(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(rule ->
                "scenario-doc-retrieval-embedding".equals(rule.getAction().getModelName())));
        assertTrue(result.getRules().stream().allMatch(ScenarioApplyResult.RuleSummary::isCreated));
    }

    @Test
    @DisplayName("成本护栏场景不建池只建规则")
    void costGuardrailCreatesRulesOnly() {
        ScenarioApplyResult result = service.apply("cost-guardrail");

        assertEquals(0, result.getPools().size());
        assertEquals(1, result.getRules().size());
        verify(poolService, never()).createValidated(any(PoolDefinition.class));
        verify(ruleService).createValidated(any(RuleDefinition.class));
    }

    @Test
    @DisplayName("单个池重名冲突时记为 warning 并继续创建规则，不抛 5xx")
    void partialFailureContinues() {
        when(registry.getAllInstances()).thenReturn(Map.of(
                ModelServiceRegistry.ServiceType.chat, List.of(instance("gpt-4o", "inst-chat"))));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Pool already exists"))
                .when(poolService).createValidated(any(PoolDefinition.class));

        ScenarioApplyResult result = service.apply("customer-support");

        assertFalse(result.getPools().get(0).isCreated());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("创建池") && w.contains("失败")));
        verify(ruleService).createValidated(any(RuleDefinition.class));
        assertTrue(result.getRules().get(0).isCreated());
    }

    @Test
    @DisplayName("未知场景 id 返回 404")
    void unknownScenarioReturns404() {
        assertThrows(ResponseStatusException.class, () -> service.apply("not-exist"));
    }

    @Test
    @DisplayName("没有已接入实例时不建池，并给出明确跳过说明")
    void noInstancesSkipsPool() {
        when(registry.getAllInstances()).thenReturn(Map.of());

        ScenarioApplyResult result = service.preview("customer-support");

        assertEquals(0, result.getPools().size());
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("暂无已接入实例")));
    }

    private static ModelRouterProperties.ModelInstance instance(String name, String instanceId) {
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName(name);
        instance.setInstanceId(instanceId);
        return instance;
    }
}
