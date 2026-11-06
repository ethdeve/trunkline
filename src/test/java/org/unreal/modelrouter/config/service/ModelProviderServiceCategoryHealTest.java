package org.unreal.modelrouter.config.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.config.core.ServiceInstanceManager;
import org.unreal.modelrouter.config.dependency.DependencyCheckService;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.model.WebClientCacheManager;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接入厂商时「模型分类缺失自愈」测试。
 *
 * <p><b>背景：</b>实例的归属完全依赖 {@code service_config} 外键，而分类目录的权威来源是
 * {@code config_data} 里持久化的 {@code services} 映射。历史上初始化条件是「两者都为空」
 * 才灌数据，导致「分类目录有、{@code service_config} 表空」的库：接入厂商时按分类查服务配置
 * 必然落空，报「服务配置不存在: chat」。修复后接入流程自行补建缺失的分类行。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@DisplayName("接入厂商 · 模型分类缺失自愈测试")
class ModelProviderServiceCategoryHealTest {

    private ServiceConfigRepository serviceConfigRepository;
    private ModelProviderService service;

    private void setUp() {
        serviceConfigRepository = mock(ServiceConfigRepository.class);
        service = new ModelProviderService(
                mock(ModelProviderRepository.class),
                serviceConfigRepository,
                mock(ServiceInstanceRepository.class),
                mock(ServiceInstanceManager.class),
                mock(WebClientCacheManager.class),
                mock(DependencyCheckService.class));
    }

    /**
     * 反射调用私有的自愈方法（它是实现细节，但修复行为需要被锁住）。
     */
    private ServiceConfigEntity invokeEnsure(final String serviceType) throws Exception {
        final Method method = ModelProviderService.class
                .getDeclaredMethod("ensureServiceConfig", String.class);
        method.setAccessible(true);
        return (ServiceConfigEntity) method.invoke(service, serviceType);
    }

    @Test
    @DisplayName("分类不存在：自动补建 service_config 行（不再抛「服务配置不存在」）")
    void shouldCreateServiceConfigWhenMissing() throws Exception {
        setUp();
        when(serviceConfigRepository.findFirstByServiceTypeAndIsLatestTrue("chat"))
                .thenReturn(Optional.empty());
        when(serviceConfigRepository.save(any(ServiceConfigEntity.class)))
                .thenAnswer(invocation -> {
                    final ServiceConfigEntity entity = invocation.getArgument(0);
                    entity.setId(42L);
                    return entity;
                });

        final ServiceConfigEntity created = invokeEnsure("chat");

        assertNotNull(created);
        assertEquals("chat", created.getServiceType());
        assertEquals("trunkline-config", created.getConfigKey());
        assertEquals(Boolean.TRUE, created.getIsLatest());
        verify(serviceConfigRepository, times(1)).save(any(ServiceConfigEntity.class));
    }

    @Test
    @DisplayName("分类已存在：直接复用，不重复建行")
    void shouldReuseExistingServiceConfig() throws Exception {
        setUp();
        final ServiceConfigEntity existing = ServiceConfigEntity.builder()
                .id(7L)
                .configKey("trunkline-config")
                .serviceType("chat")
                .version(1)
                .isLatest(true)
                .build();
        when(serviceConfigRepository.findFirstByServiceTypeAndIsLatestTrue("chat"))
                .thenReturn(Optional.of(existing));

        final ServiceConfigEntity result = invokeEnsure("chat");

        assertEquals(7L, result.getId());
        verify(serviceConfigRepository, times(0)).save(any(ServiceConfigEntity.class));
    }

    @Test
    @DisplayName("分类为空：明确报错「请先选择模型分类」")
    void shouldRejectBlankServiceType() {
        setUp();
        final Exception error = assertThrows(Exception.class, () -> invokeEnsure("  "));
        // 反射调用时异常会被包成 InvocationTargetException，取其 cause 才是真实业务异常
        final Throwable cause = error.getCause() == null ? error : error.getCause();
        assertEquals("请先选择模型分类", cause.getMessage());
    }
}
