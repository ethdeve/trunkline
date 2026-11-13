/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.loadbalancer;

import org.unreal.modelrouter.router.model.InstanceKeys;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一致性哈希环指纹（#687/#691/#692）。
 *
 * <p>环是否需要重建，取决于所有会影响虚拟节点位置的输入：实例身份、权重、
 * 地址、路径、名称，以及调用方给出的算法/虚拟节点数等附加维度。指纹对实例
 * 描述符排序，因此上游过滤后列表顺序抖动不会触发无谓重建；每个字段用
 * {@code 长度:值|} 编码，避免 {@code a|b} 与 {@code a}、{@code b} 之类的拼接碰撞。</p>
 *
 * <p>健康位不纳入指纹：健康判定在选择时按当前状态实时读取，不需要重建环。</p>
 *
 * @author Trunkline Team
 */
public final class RingFingerprint {

    private static final String NULL_MARK = "~";

    private RingFingerprint() {
    }

    /**
     * 计算实例列表指纹。
     *
     * @param instances 当前参与选择的实例列表（允许 null，由调用方决定语义）
     * @param extras 附加维度，例如哈希算法名、虚拟节点数
     * @return 稳定且顺序无关的指纹
     */
    public static String compute(final List<ModelRouterProperties.ModelInstance> instances,
                                 final String... extras) {
        StringBuilder sb = new StringBuilder();
        if (extras != null) {
            for (String extra : extras) {
                append(sb, extra);
            }
        }
        if (instances == null) {
            append(sb, "null-list");
            return sb.toString();
        }
        List<String> descriptors = new ArrayList<>(instances.size());
        for (ModelRouterProperties.ModelInstance instance : instances) {
            descriptors.add(describe(instance));
        }
        Collections.sort(descriptors);
        append(sb, String.valueOf(descriptors.size()));
        for (String descriptor : descriptors) {
            append(sb, descriptor);
        }
        return sb.toString();
    }

    private static String describe(final ModelRouterProperties.ModelInstance instance) {
        if (instance == null) {
            return "null-instance";
        }
        StringBuilder sb = new StringBuilder();
        append(sb, InstanceKeys.resolve(instance));
        append(sb, instance.getInstanceId());
        append(sb, instance.getBaseUrl());
        append(sb, instance.getPath());
        append(sb, instance.getName());
        append(sb, String.valueOf(instance.getWeight()));
        return sb.toString();
    }

    private static void append(final StringBuilder sb, final String value) {
        String safe = value == null ? NULL_MARK : value;
        sb.append(safe.length()).append(':').append(safe).append('|');
    }
}
