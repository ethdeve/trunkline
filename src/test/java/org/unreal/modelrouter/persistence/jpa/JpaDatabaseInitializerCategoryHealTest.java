/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.config.core.helper.ConfigConverterHelper;
import org.unreal.modelrouter.persistence.jpa.entity.ConfigEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动自愈：{@code service_config} 空表时按已有分类目录补建外键行。
 *
 * <p><b>背景：</b>实例归属完全依赖 {@code service_config} 外键，分类目录的权威来源是
 * {@code config_data} 里持久化的 {@code services} 映射。历史上初始化条件是
 * 「{@code config_data} 与 {@code service_config} 都为空」才从 YAML 灌数据，
 * 于是「分类目录有、外键表空」的库接入厂商时必然报「服务配置不存在: chat」。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("启动自愈 · 模型分类外键补建测试")
class JpaDatabaseInitializerCategoryHealTest {

    private static final String CONFIG_JSON = """
            {
              "services": {
                "chat": {"adapter": "openai-compat", "loadBalance": {"type": "round-robin"}},
                "embedding": {"adapter": "openai-compat"},
                "imgGen": {}
              }
            }
            """;

    private ConfigRepository configRepository;
    private ServiceConfigRepository serviceConfigRepository;
    private JpaDatabaseInitializer initializer;

    private void setUp(final long configCount, final long serviceConfigCount) {
        configRepository = mock(ConfigRepository.class);
        serviceConfigRepository = mock(ServiceConfigRepository.class);
        when(configRepository.count()).thenReturn(configCount);
        when(serviceConfigRepository.count()).thenReturn(serviceConfigCount);
        when(serviceConfigRepository.findFirstByServiceTypeAndIsLatestTrue(anyString()))
                .thenReturn(Optional.empty());
        when(serviceConfigRepository.save(any(ServiceConfigEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        final ModelRouterProperties properties = mock(ModelRouterProperties.class);
        when(properties.getAdapter()).thenReturn("default-adapter");
        final ModelRouterProperties.LoadBalanceConfig loadBalance =
                mock(ModelRouterProperties.LoadBalanceConfig.class);
        when(loadBalance.getType()).thenReturn("round-robin");
        when(properties.getLoadBalance()).thenReturn(loadBalance);

        initializer = new JpaDatabaseInitializer(
                configRepository,
                serviceConfigRepository,
                mock(ServiceInstanceRepository.class),
                mock(JwtAccountRepository.class),
                properties,
                mock(ConfigConverterHelper.class),
                mock(ApiKeyService.class),
                mock(SecurityProperties.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                new ObjectMapper());
    }

    /** 反射触发启动初始化（@PostConstruct 私有链路）。 */
    private void invokeInitialize() throws Exception {
        final var method = JpaDatabaseInitializer.class
                .getDeclaredMethod("initializeModelConfig");
        method.setAccessible(true);
        method.invoke(initializer);
    }

    @Test
    @DisplayName("service_config 为空：按持久化分类目录补建（含各分类自己的 adapter）")
    void shouldCreateRowsFromPersistedCatalog() throws Exception {
        setUp(13L, 0L);
        when(configRepository.findFirstByConfigKeyAndIsLatestTrue("trunkline-config"))
                .thenReturn(Optional.of(ConfigEntity.builder()
                        .configKey("trunkline-config")
                        .configValue(CONFIG_JSON)
                        .version(0)
                        .isLatest(true)
                        .build()));

        invokeInitialize();

        final ArgumentCaptor<ServiceConfigEntity> captor =
                ArgumentCaptor.forClass(ServiceConfigEntity.class);
        verify(serviceConfigRepository, times(3)).save(captor.capture());
        final List<ServiceConfigEntity> saved = captor.getAllValues();
        assertEquals(3, saved.size());
        assertTrue(saved.stream().allMatch(e -> "trunkline-config".equals(e.getConfigKey())));
        assertTrue(saved.stream().allMatch(e -> Boolean.TRUE.equals(e.getIsLatest())));
        assertTrue(saved.stream().allMatch(e -> Integer.valueOf(1).equals(e.getVersion())));

        final ServiceConfigEntity chat = saved.stream()
                .filter(e -> "chat".equals(e.getServiceType())).findFirst().orElseThrow();
        assertEquals("openai-compat", chat.getAdapter());
        assertEquals("round-robin", chat.getLoadBalanceType());

        final ServiceConfigEntity imgGen = saved.stream()
                .filter(e -> "imgGen".equals(e.getServiceType())).findFirst().orElseThrow();
        assertEquals("default-adapter", imgGen.getAdapter(), "分类未配 adapter 时回退全局默认");
    }

    @Test
    @DisplayName("service_config 已有数据：不补建（不重复建行）")
    void shouldNotCreateWhenServiceConfigNotEmpty() throws Exception {
        setUp(13L, 7L);

        invokeInitialize();

        verify(serviceConfigRepository, times(0)).save(any(ServiceConfigEntity.class));
    }
}
