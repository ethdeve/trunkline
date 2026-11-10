/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #636 健康键解析测试：instance-id → baseUrl → name → unknown。
 * 不用 Mockito，直接构造真实 ModelInstance。
 * @author Trunkline Team
 */
@DisplayName("InstanceKeys 健康键解析测试")
class InstanceKeysTest {

    private ModelRouterProperties.ModelInstance instance(final String id, final String baseUrl, final String name) {
        final ModelRouterProperties.ModelInstance inst = new ModelRouterProperties.ModelInstance();
        inst.setInstanceId(id);
        inst.setBaseUrl(baseUrl);
        inst.setName(name);
        return inst;
    }

    @Test
    @DisplayName("KEY-001: 有 instance-id 时优先使用")
    void resolvesInstanceId() {
        assertEquals("uuid-1", InstanceKeys.resolve(instance("uuid-1", "http://a", "n1")));
        assertEquals("chat:uuid-1", InstanceKeys.healthKey("chat", instance("uuid-1", "http://a", "n1")));
    }

    @Test
    @DisplayName("KEY-002: instance-id 为 null 时回退 baseUrl（不再塌缩为 :null）")
    void fallsBackToBaseUrl() {
        assertEquals("http://a:8080", InstanceKeys.resolve(instance(null, "http://a:8080", "n1")));
        assertEquals("chat:http://a:8080", InstanceKeys.healthKey("chat", instance(null, "http://a:8080", "n1")));
    }

    @Test
    @DisplayName("KEY-003: baseUrl 也为空时回退 name")
    void fallsBackToName() {
        assertEquals("inst-name", InstanceKeys.resolve(instance(null, null, "inst-name")));
    }

    @Test
    @DisplayName("KEY-004: 全空回退 unknown，且两个实例不再共享 :null 键")
    void fallsBackToUnknown() {
        assertEquals("unknown", InstanceKeys.resolve(instance(null, null, null)));
        assertEquals("unknown", InstanceKeys.resolve(instance("  ", "", null)));
    }

    @Test
    @DisplayName("KEY-005: 空白字符串视同缺失")
    void blankTreatedAsMissing() {
        assertEquals("http://b", InstanceKeys.resolve(instance("  ", "http://b", "n")));
    }

    @Test
    @DisplayName("KEY-006: null 实例安全")
    void nullInstanceSafe() {
        assertEquals("unknown", InstanceKeys.resolve(null));
        assertEquals("chat:unknown", InstanceKeys.healthKey("chat", null));
    }
}
