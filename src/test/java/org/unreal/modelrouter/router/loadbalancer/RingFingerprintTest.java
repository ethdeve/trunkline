/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.loadbalancer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * #687/#691: 哈希环指纹的顺序无关性、字段敏感性与防碰撞编码。
 */
@DisplayName("RingFingerprint 单元测试")
class RingFingerprintTest {

    @Test
    @DisplayName("实例顺序变化不改变指纹")
    void orderIndependent() {
        List<ModelRouterProperties.ModelInstance> list = instances(3);
        List<ModelRouterProperties.ModelInstance> reversed = new ArrayList<>(list);
        Collections.reverse(reversed);

        assertEquals(RingFingerprint.compute(list), RingFingerprint.compute(reversed));
    }

    @Test
    @DisplayName("权重/地址/路径/名称/id 任一变化都重建指纹")
    void anyRingInputChangeAltersFingerprint() {
        List<ModelRouterProperties.ModelInstance> base = instances(2);
        String original = RingFingerprint.compute(base, "murmur3", "150");

        assertNotEquals(original, withMutation(base, 0, "weight", "7"));
        assertNotEquals(original, withMutation(base, 0, "baseUrl", "http://changed:8080"));
        assertNotEquals(original, withMutation(base, 0, "path", "/v2/chat"));
        assertNotEquals(original, withMutation(base, 0, "name", "renamed"));
        assertNotEquals(original, withMutation(base, 0, "instanceId", "explicit-id"));
    }

    @Test
    @DisplayName("附加维度（算法/虚拟节点数）参与指纹")
    void extrasParticipate() {
        List<ModelRouterProperties.ModelInstance> list = instances(2);
        assertNotEquals(RingFingerprint.compute(list, "md5", "100"),
                RingFingerprint.compute(list, "sha256", "100"));
        assertNotEquals(RingFingerprint.compute(list, "md5", "100"),
                RingFingerprint.compute(list, "md5", "150"));
    }

    @Test
    @DisplayName("长度前缀避免分隔符拼接碰撞")
    void lengthPrefixPreventsCollisions() {
        ModelRouterProperties.ModelInstance a = instance("a", "http://x|y", "/z", 1);
        ModelRouterProperties.ModelInstance b = instance("b", "http://x", "y|z", 1);
        // 朴素拼接会得到同样的 "http://x|y|z" 片段；长度前缀编码必须区分
        assertNotEquals(RingFingerprint.compute(Arrays.asList(a)),
                RingFingerprint.compute(Arrays.asList(b)));
    }

    @Test
    @DisplayName("null 列表与 null 元素不抛异常且指纹稳定")
    void nullSafe() {
        assertNotNull(RingFingerprint.compute(null));
        assertDoesNotThrow(() -> RingFingerprint.compute(Collections.singletonList(null)));
        assertEquals(RingFingerprint.compute(null), RingFingerprint.compute(null));
    }

    private String withMutation(final List<ModelRouterProperties.ModelInstance> source,
                                 final int index, final String field, final String value) {
        List<ModelRouterProperties.ModelInstance> copy = instances(source.size());
        ModelRouterProperties.ModelInstance target = copy.get(index);
        switch (field) {
            case "weight" -> target.setWeight(Integer.parseInt(value));
            case "baseUrl" -> target.setBaseUrl(value);
            case "path" -> target.setPath(value);
            case "name" -> target.setName(value);
            case "instanceId" -> target.setInstanceId(value);
            default -> throw new IllegalArgumentException(field);
        }
        return RingFingerprint.compute(copy, "murmur3", "150");
    }

    private List<ModelRouterProperties.ModelInstance> instances(final int count) {
        List<ModelRouterProperties.ModelInstance> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(instance("instance-" + i, "http://host" + i + ":8080", "/v1/chat", 1));
        }
        return list;
    }

    private ModelRouterProperties.ModelInstance instance(final String name, final String baseUrl,
                                                          final String path, final int weight) {
        ModelRouterProperties.ModelInstance item = new ModelRouterProperties.ModelInstance();
        item.setName(name);
        item.setBaseUrl(baseUrl);
        item.setPath(path);
        item.setWeight(weight);
        item.setHealthy(true);
        return item;
    }
}
