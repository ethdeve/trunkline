/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ModelProviderService「模型列表路径推导」单测（v2.11.0）。
 *
 * <p>纯 JUnit + 反射：不拉起 Spring 上下文、不依赖 Mockito（本机 GraalVM JDK 21 下
 * inline MockMaker 初始化失败，Mockito 用例不可用，属既有环境限制）。
 * resolveModelsPath 不触碰任何 repository，传 null 依赖安全。
 * @author Trunkline Team
 */
@DisplayName("ModelProviderService 模型列表路径推导")
class ModelProviderServiceModelsPathTest {

    private String resolve(final ModelProviderEntity entity) throws Exception {
        final ModelProviderService service = new ModelProviderService(null, null, null, null, null, null);
        final Method method = ModelProviderService.class
                .getDeclaredMethod("resolveModelsPath", ModelProviderEntity.class);
        method.setAccessible(true);
        return (String) method.invoke(service, entity);
    }

    @Test
    @DisplayName("显式 modelsPath 优先")
    void explicitModelsPathWins() throws Exception {
        final ModelProviderEntity entity = ModelProviderEntity.builder()
                .path("/v1/chat/completions")
                .modelsPath("/v3/models")
                .build();
        assertEquals("/v3/models", resolve(entity));
    }

    @Test
    @DisplayName("按 path 版本前缀推导：/v1、/v4 → 对应 /models")
    void deriveFromVersionPrefix() throws Exception {
        assertEquals("/v1/models", resolve(ModelProviderEntity.builder()
                .path("/v1/chat/completions").build()));
        assertEquals("/v4/models", resolve(ModelProviderEntity.builder()
                .path("/v4/chat/completions").build()));
    }

    @Test
    @DisplayName("无版本前缀或空 path → /models")
    void fallbackToRootModels() throws Exception {
        assertEquals("/models", resolve(ModelProviderEntity.builder()
                .path("/chat/completions").build()));
        assertEquals("/models", resolve(ModelProviderEntity.builder()
                .path(null).build()));
        assertEquals("/models", resolve(ModelProviderEntity.builder()
                .path("").build()));
    }
}
