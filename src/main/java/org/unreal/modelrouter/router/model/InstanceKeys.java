/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.model;

/**
 * 实例键统一解析（对齐上游 3.5.3 #636）。
 *
 * <p>历史实现的健康键是 {@code serviceType + ":" + instanceId}，未配置
 * {@code instance-id} 的实例全部塌缩成 {@code serviceType:null} 共享同一个键，
 * 一个实例探活失败会拖垮同类所有实例的健康判定。</p>
 *
 * <p>解析顺序：{@code instance-id} → {@code baseUrl} → {@code name} → {@code unknown}。
 * 读写两侧（探活写入、路由查询、SSE 展示）必须共用本类，避免键口径不一致。</p>
 * @author Trunkline Team
 */
public final class InstanceKeys {

    private static final String UNKNOWN = "unknown";

    private InstanceKeys() {
    }

    /**
     * 解析实例的唯一键片段。
     */
    public static String resolve(final ModelRouterProperties.ModelInstance instance) {
        if (instance == null) {
            return UNKNOWN;
        }
        return resolveParts(instance.getInstanceId(), instance.getBaseUrl(), instance.getName());
    }

    /**
     * 按解析顺序从三个候选值取第一个非空值。
     */
    public static String resolveParts(final String instanceId, final String baseUrl, final String name) {
        final String id = normalize(instanceId);
        if (id != null) {
            return id;
        }
        final String url = normalize(baseUrl);
        if (url != null) {
            return url;
        }
        final String n = normalize(name);
        return n != null ? n : UNKNOWN;
    }

    /**
     * 健康状态键：{@code serviceType:<resolved>}。
     */
    public static String healthKey(final String serviceType, final ModelRouterProperties.ModelInstance instance) {
        return serviceType + ":" + resolve(instance);
    }

    private static String normalize(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
