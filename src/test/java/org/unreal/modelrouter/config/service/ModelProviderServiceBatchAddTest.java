package org.unreal.modelrouter.config.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.config.dto.BatchAddModelsResult;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelProviderService「批量添加模型跳过明细」单测。
 *
 * <p>纯 JUnit + 反射 + {@link Proxy} 仓库替身：不拉起 Spring 上下文、不依赖 Mockito
 * （本机 GraalVM JDK 21 下 Mockito inline MockMaker 初始化失败，属既有环境限制）。
 * {@code buildSkippedItem} 只读 {@code providerRepository}，其余依赖传 null 安全。
 */
@DisplayName("ModelProviderService 批量添加模型跳过明细")
class ModelProviderServiceBatchAddTest {

    /** 构造带指定供应商查询结果的 service（Proxy 替身 ModelProviderRepository） */
    private ModelProviderService serviceWith(final Optional<ModelProviderEntity> findByIdResult) {
        final ModelProviderRepository repo = (ModelProviderRepository) Proxy.newProxyInstance(
                ModelProviderRepository.class.getClassLoader(),
                new Class<?>[]{ModelProviderRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return findByIdResult;
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("toString")) {
                        return "ModelProviderRepositoryStub";
                    }
                    return defaultValue(method.getReturnType());
                });
        return new ModelProviderService(repo, null, null, null, null, null);
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    private String invokeReason(final ModelProviderService service,
                                final ServiceInstanceDTO existing,
                                final Long currentProviderId,
                                final String serviceType) throws Exception {
        final Method method = ModelProviderService.class.getDeclaredMethod(
                "buildSkippedItem", String.class, ServiceInstanceDTO.class, Long.class, String.class);
        method.setAccessible(true);
        final BatchAddModelsResult.SkippedModel skipped =
                (BatchAddModelsResult.SkippedModel) method.invoke(service, "gpt-4o", existing, currentProviderId, serviceType);
        assertTrue(skipped.getModelId().equals("gpt-4o"));
        return skipped.getReason();
    }

    @Test
    @DisplayName("同名实例属当前供应商：原因含「当前供应商」与分类名")
    void skippedByCurrentProvider() throws Exception {
        final ModelProviderService service = serviceWith(Optional.empty());
        final ServiceInstanceDTO existing = ServiceInstanceDTO.builder().providerId(1L).name("gpt-4o").build();
        final String reason = invokeReason(service, existing, 1L, "chat");
        assertTrue(reason.contains("当前供应商"), reason);
        assertTrue(reason.contains("chat"), reason);
    }

    @Test
    @DisplayName("同名实例属其他供应商：原因含该供应商名称")
    void skippedByOtherProvider() throws Exception {
        final ModelProviderEntity owner = ModelProviderEntity.builder().name("上游A").build();
        final ModelProviderService service = serviceWith(Optional.of(owner));
        final ServiceInstanceDTO existing = ServiceInstanceDTO.builder().providerId(2L).name("gpt-4o").build();
        final String reason = invokeReason(service, existing, 1L, "chat");
        assertTrue(reason.contains("上游A"), reason);
        assertTrue(reason.contains("chat"), reason);
    }

    @Test
    @DisplayName("归属供应商已删除（查不到）：原因回退显示 ID")
    void skippedOwnerMissing() throws Exception {
        final ModelProviderService service = serviceWith(Optional.empty());
        final ServiceInstanceDTO existing = ServiceInstanceDTO.builder().providerId(9L).name("gpt-4o").build();
        final String reason = invokeReason(service, existing, 1L, "chat");
        assertTrue(reason.contains("ID=9"), reason);
    }

    @Test
    @DisplayName("无供应商归属的历史实例：原因只说明分类内已存在")
    void skippedNoOwner() throws Exception {
        final ModelProviderService service = serviceWith(Optional.empty());
        final ServiceInstanceDTO existing = ServiceInstanceDTO.builder().providerId(null).name("gpt-4o").build();
        final String reason = invokeReason(service, existing, 1L, "chat");
        assertTrue(reason.contains("已存在同名实例"), reason);
    }

    @Test
    @DisplayName("本批次重复项（existing=null）：原因仍可读")
    void skippedDuplicateInBatch() throws Exception {
        final ModelProviderService service = serviceWith(Optional.empty());
        final String reason = invokeReason(service, null, 1L, "chat");
        assertTrue(reason.contains("已存在同名实例"), reason);
    }
}
