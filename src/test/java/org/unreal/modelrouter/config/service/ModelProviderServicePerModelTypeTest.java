package org.unreal.modelrouter.config.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.config.core.ServiceInstanceManager;
import org.unreal.modelrouter.config.dto.BatchAddModelsResult;
import org.unreal.modelrouter.config.dto.CreateModelProviderRequest;
import org.unreal.modelrouter.config.dto.CreateServiceInstanceRequest;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「批量接入模型 · 逐模型分类」测试（v3.5）。
 *
 * <p><b>为什么要有这个能力：</b>同一个厂商常同时提供聊天 / 向量 / 图片模型，分类一刀切到
 * 供应商级会把图片模型接进聊天分类。而上游请求路径取自<b>实例自身的 path 字段</b>
 * （{@code ModelServiceRegistry#getModelPath}），不随分类实时换算——分类不落到模型级、
 * 路径不跟着分类改，图片模型就会打到 /chat/completions。</p>
 *
 * <p>本测试用 {@link Proxy} 仓库替身 + 真实对象（继承 {@link ServiceInstanceManager} 记录调用），
 * 不依赖 Mockito（本机 GraalVM JDK 21 下 Mockito inline MockMaker 初始化失败）。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@DisplayName("批量接入模型 · 逐模型分类")
class ModelProviderServicePerModelTypeTest {

    /** service_config 表替身：按分类查 / 自愈补建 */
    private static final class ConfigStub {
        private final Map<String, ServiceConfigEntity> configs = new HashMap<>();
        private final AtomicLong idSeq = new AtomicLong(100);

        void seed(final String serviceType, final Long id) {
            configs.put(serviceType, ServiceConfigEntity.builder()
                    .id(id)
                    .configKey("trunkline-config")
                    .serviceType(serviceType)
                    .version(1)
                    .isLatest(true)
                    .build());
        }
    }

    /** 实例管理器替身：记录创建了什么实例、落到哪个服务配置、路径是什么 */
    private static final class RecordingInstanceManager extends ServiceInstanceManager {
        private final Map<Long, List<ServiceInstanceDTO>> existing = new HashMap<>();
        private final List<Long> configIds = new ArrayList<>();
        private final List<CreateServiceInstanceRequest> requests = new ArrayList<>();

        RecordingInstanceManager() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public List<ServiceInstanceDTO> getInstancesByServiceConfigId(final Long serviceConfigId) {
            return existing.getOrDefault(serviceConfigId, List.of());
        }

        @Override
        public ServiceInstanceDTO createInstance(final Long serviceConfigId,
                                                 final CreateServiceInstanceRequest request) {
            configIds.add(serviceConfigId);
            requests.add(request);
            return ServiceInstanceDTO.builder().name(request.getName()).build();
        }
    }

    // ==================== 仓库替身 ====================

    private ServiceConfigRepository configRepository(final ConfigStub stub) {
        return (ServiceConfigRepository) Proxy.newProxyInstance(
                ServiceConfigRepository.class.getClassLoader(),
                new Class<?>[]{ServiceConfigRepository.class},
                (proxy, method, args) -> {
                    final String name = method.getName();
                    if ("findFirstByServiceTypeAndIsLatestTrue".equals(name)) {
                        return Optional.ofNullable(stub.configs.get((String) args[0]));
                    }
                    if ("save".equals(name)) {
                        final ServiceConfigEntity entity = (ServiceConfigEntity) args[0];
                        if (entity.getId() == null) {
                            entity.setId(stub.idSeq.incrementAndGet());
                            stub.configs.put(entity.getServiceType(), entity);
                        }
                        return entity;
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(name)) {
                        return "ServiceConfigRepositoryStub";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private ModelProviderRepository providerRepository(final ModelProviderEntity provider) {
        return (ModelProviderRepository) Proxy.newProxyInstance(
                ModelProviderRepository.class.getClassLoader(),
                new Class<?>[]{ModelProviderRepository.class},
                (proxy, method, args) -> {
                    final String name = method.getName();
                    if ("findById".equals(name)) {
                        return Optional.of(provider);
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(name)) {
                        return "ModelProviderRepositoryStub";
                    }
                    return defaultValue(method.getReturnType());
                });
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

    // ==================== 夹具 ====================

    private ModelProviderEntity provider(final String path) {
        return ModelProviderEntity.builder()
                .id(1L)
                .name("上游A")
                .serviceType("chat")
                .baseUrl("https://api.example.com")
                .path(path)
                .adapter("normal")
                .build();
    }

    private ModelProviderService service(final ModelProviderEntity provider,
                                         final RecordingInstanceManager manager,
                                         final ConfigStub stub) {
        return new ModelProviderService(
                providerRepository(provider),
                configRepository(stub),
                null,
                manager,
                null,
                null);
    }

    private static CreateModelProviderRequest.BatchModelItem item(
            final String modelId, final String serviceType) {
        final CreateModelProviderRequest.BatchModelItem item =
                new CreateModelProviderRequest.BatchModelItem();
        item.setModelId(modelId);
        item.setServiceType(serviceType);
        return item;
    }

    private static CreateModelProviderRequest.BatchAddModelsRequest batch(
            final String batchType,
            final CreateModelProviderRequest.BatchModelItem... items) {
        final CreateModelProviderRequest.BatchAddModelsRequest request =
                new CreateModelProviderRequest.BatchAddModelsRequest();
        request.setServiceType(batchType);
        request.setItems(List.of(items));
        return request;
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("同一批模型按各自分类落到不同的服务配置，路径随分类换算")
    void shouldSplitByPerModelServiceType() {
        final RecordingInstanceManager manager = new RecordingInstanceManager();
        final ModelProviderService service = service(provider("/v1/chat/completions"), manager, new ConfigStub());

        final BatchAddModelsResult result = service.batchAddModels(1L, batch("chat",
                item("gpt-4o", "chat"),
                item("dall-e-3", "imgGen")));

        assertEquals(2, result.getCreated());
        assertEquals(2, manager.requests.size());
        // 不同分类 → 不同服务配置（实例归属靠 service_config 外键）
        assertNotEquals(manager.configIds.get(0), manager.configIds.get(1));
        // 路径随分类换算：聊天保持原样，图片生成换成图片端点
        assertEquals("/v1/chat/completions", manager.requests.get(0).getPath());
        assertEquals("/v1/images/generations", manager.requests.get(1).getPath());
    }

    @Test
    @DisplayName("baseUrl 已含版本段（path=/chat/completions）：图片端点用短路径")
    void shouldUseShortPathWhenVersionInBaseUrl() {
        final RecordingInstanceManager manager = new RecordingInstanceManager();
        final ModelProviderService service = service(provider("/chat/completions"), manager, new ConfigStub());

        service.batchAddModels(1L, batch("chat", item("dall-e-3", "imgGen")));

        assertEquals("/images/generations", manager.requests.get(0).getPath());
    }

    @Test
    @DisplayName("多级版本前缀（/api/paas/v4/chat/completions）：保留整段前缀")
    void shouldKeepMultiSegmentVersionPrefix() {
        final RecordingInstanceManager manager = new RecordingInstanceManager();
        final ModelProviderService service = service(
                provider("/api/paas/v4/chat/completions"), manager, new ConfigStub());

        service.batchAddModels(1L, batch("chat", item("glm-4v", "imgGen")));

        assertEquals("/api/paas/v4/images/generations", manager.requests.get(0).getPath());
    }

    @Test
    @DisplayName("分类大小写变体归一化（IMGGEN → imgGen），不重复建分类行")
    void shouldNormalizeServiceTypeCase() {
        final ConfigStub stub = new ConfigStub();
        final RecordingInstanceManager manager = new RecordingInstanceManager();
        final ModelProviderService service = service(provider("/v1/chat/completions"), manager, stub);

        service.batchAddModels(1L, batch("chat", item("dall-e-3", "IMGGEN")));

        assertTrue(stub.configs.containsKey("imgGen"), stub.configs.keySet().toString());
        assertEquals("/v1/images/generations", manager.requests.get(0).getPath());
    }

    @Test
    @DisplayName("模型未指定分类时沿用批次级分类")
    void shouldFallbackToBatchServiceType() {
        final RecordingInstanceManager manager = new RecordingInstanceManager();
        final ModelProviderService service = service(provider("/v1/chat/completions"), manager, new ConfigStub());

        service.batchAddModels(1L, batch("embedding", item("text-embedding-3", null)));

        assertEquals("/v1/embeddings", manager.requests.get(0).getPath());
    }

    @Test
    @DisplayName("同名去重按分类隔离：聊天下已存在则跳过，图片分类下同名仍可创建")
    void shouldDeduplicatePerServiceType() {
        final ConfigStub stub = new ConfigStub();
        stub.seed("chat", 7L);
        final RecordingInstanceManager manager = new RecordingInstanceManager();
        manager.existing.put(7L, List.of(
                ServiceInstanceDTO.builder().name("gpt-4o").providerId(1L).build()));
        final ModelProviderService service = service(provider("/v1/chat/completions"), manager, stub);

        final BatchAddModelsResult result = service.batchAddModels(1L, batch("chat",
                item("gpt-4o", "chat"),
                item("gpt-4o", "imgGen")));

        assertEquals(1, result.getCreated());
        assertEquals(1, result.getSkipped().size());
        assertEquals("gpt-4o", result.getSkipped().get(0).getModelId());
        assertTrue(result.getSkipped().get(0).getReason().contains("chat"),
                result.getSkipped().get(0).getReason());
        assertEquals("chat", result.getSkipped().get(0).getServiceType());
    }
}
