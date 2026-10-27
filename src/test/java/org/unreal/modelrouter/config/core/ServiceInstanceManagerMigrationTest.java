package org.unreal.modelrouter.config.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.config.dto.CreateServiceInstanceRequest;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 2026-10-22：实例跨模型分类（serviceType）迁移。
 *
 * <p>实例表没有 service_type 列，服务类型由 service_config_id 决定，因此「换分类」是改外键。
 * 但这个外键是路由的一级维度（请求分桶、健康位图 key、限流桶 key、池成员匹配都带 serviceType），
 * 所以迁移必须带前置校验与级联清理，否则会出现路由断裂与孤儿状态。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("实例跨模型分类迁移")
class ServiceInstanceManagerMigrationTest {

    private static final Long CHAT_CONFIG_ID = 1L;
    private static final Long EMBED_CONFIG_ID = 2L;
    private static final String RUNTIME_ID = "uuid-runtime-001";

    @Mock
    private ServiceInstanceRepository serviceInstanceRepository;
    @Mock
    private org.unreal.modelrouter.persistence.jpa.repository.InstanceRateLimitRepository rateLimitRepository;
    @Mock
    private org.unreal.modelrouter.persistence.jpa.repository.InstanceCircuitBreakerRepository circuitBreakerRepository;
    @Mock
    private org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository serviceConfigRepository;
    @Mock
    private ConfigurationService configurationService;
    @Mock
    private org.unreal.modelrouter.config.core.manager.ConfigVersionManager configVersionManager;
    @Mock
    private org.unreal.modelrouter.router.model.ModelServiceRegistry modelServiceRegistry;
    @Mock
    private org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager circuitBreakerManager;
    @Mock
    private org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository modelProviderRepository;
    @Mock
    private org.unreal.modelrouter.router.checker.ServiceStateManager serviceStateManager;
    @Mock
    private org.unreal.modelrouter.router.ratelimit.RateLimitManager rateLimitManager;
    @Mock
    private org.unreal.modelrouter.router.pool.PoolPersistenceService poolPersistenceService;
    @Mock
    private org.unreal.modelrouter.config.dependency.DependencyCheckService dependencyCheckService;

    private ServiceInstanceManager manager;

    @BeforeEach
    void setUp() {
        manager = new ServiceInstanceManager(
                serviceInstanceRepository, rateLimitRepository, circuitBreakerRepository,
                serviceConfigRepository, configurationService, configVersionManager,
                modelServiceRegistry, circuitBreakerManager, modelProviderRepository,
                serviceStateManager, rateLimitManager, poolPersistenceService, dependencyCheckService);

        when(serviceInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 默认：chat 配置 + embedding 配置都存在；实例属 chat；无池引用；无同名冲突
        when(serviceConfigRepository.findFirstByServiceTypeAndIsLatestTrue("embedding"))
                .thenReturn(Optional.of(config(EMBED_CONFIG_ID, "embedding")));
        when(serviceConfigRepository.findFirstByServiceTypeAndIsLatestTrue("chat"))
                .thenReturn(Optional.of(config(CHAT_CONFIG_ID, "chat")));
        when(serviceConfigRepository.findById(CHAT_CONFIG_ID)).thenReturn(Optional.of(config(CHAT_CONFIG_ID, "chat")));
        when(serviceConfigRepository.findById(EMBED_CONFIG_ID)).thenReturn(Optional.of(config(EMBED_CONFIG_ID, "embedding")));
        when(serviceInstanceRepository.findAllByInstanceName(anyString())).thenReturn(List.of());
        when(poolPersistenceService.loadAll()).thenReturn(List.of());
    }

    private ServiceConfigEntity config(final Long id, final String serviceType) {
        return ServiceConfigEntity.builder().id(id).serviceType(serviceType).configKey(serviceType).build();
    }

    private ServiceInstanceEntity chatInstance() {
        return ServiceInstanceEntity.builder()
                .id(100L)
                .serviceConfigId(CHAT_CONFIG_ID)
                .instanceName("glm-5.1")
                .instanceId(RUNTIME_ID)
                .baseUrl("https://yq.sh-cloudwalk.com")
                .path("/v1/chat/completions")
                .weight(1)
                .status("ACTIVE")
                .build();
    }

    private CreateServiceInstanceRequest migrateTo(final String serviceType) {
        final CreateServiceInstanceRequest request = new CreateServiceInstanceRequest();
        request.setServiceType(serviceType);
        return request;
    }

    @Test
    @DisplayName("目标分类不存在 → ApiException(SERVICE_CONFIG_NOT_FOUND)，不落库")
    void migrate_targetConfigMissing_throws() {
        final ServiceInstanceEntity entity = chatInstance();
        when(serviceInstanceRepository.findById(100L)).thenReturn(Optional.of(entity));
        when(serviceConfigRepository.findFirstByServiceTypeAndIsLatestTrue("rerank"))
                .thenReturn(Optional.empty());

        final ApiException ex = assertThrows(ApiException.class,
                () -> manager.updateInstance(100L, migrateTo("rerank")));
        assertEquals("SERVICE_CONFIG_NOT_FOUND", ex.getErrorCode());
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals(CHAT_CONFIG_ID, entity.getServiceConfigId(), "失败时不得改归属");
    }

    @Test
    @DisplayName("目标分类下已有同名实例 → ApiException(CONFLICT_INSTANCE_NAME) = 409")
    void migrate_nameConflict_throws() {
        final ServiceInstanceEntity entity = chatInstance();
        when(serviceInstanceRepository.findById(100L)).thenReturn(Optional.of(entity));

        final ServiceInstanceEntity other = ServiceInstanceEntity.builder()
                .id(200L).serviceConfigId(EMBED_CONFIG_ID).instanceName("glm-5.1").build();
        when(serviceInstanceRepository.findAllByInstanceName("glm-5.1")).thenReturn(List.of(other));

        final ApiException ex = assertThrows(ApiException.class,
                () -> manager.updateInstance(100L, migrateTo("embedding")));
        assertEquals("CONFLICT_INSTANCE_NAME", ex.getErrorCode());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(CHAT_CONFIG_ID, entity.getServiceConfigId(), "冲突时不得改归属");
    }

    @Test
    @DisplayName("实例被模型分组引用 → ApiException(CONFLICT_INSTANCE_IN_POOL) = 409")
    void migrate_referencedByPool_throws() {
        final ServiceInstanceEntity entity = chatInstance();
        when(serviceInstanceRepository.findById(100L)).thenReturn(Optional.of(entity));

        final PoolMember member = new PoolMember();
        member.setInstanceId(RUNTIME_ID);
        member.setWeight(1);
        final PoolDefinition pool = new PoolDefinition();
        pool.setPoolName("my-pool");
        pool.setServiceType("chat");
        pool.setMembers(List.of(member));
        when(poolPersistenceService.loadAll()).thenReturn(List.of(pool));

        final ApiException ex = assertThrows(ApiException.class,
                () -> manager.updateInstance(100L, migrateTo("embedding")));
        assertEquals("CONFLICT_INSTANCE_IN_POOL", ex.getErrorCode());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("my-pool"), "错误信息应指出具体分组名");
        assertEquals(CHAT_CONFIG_ID, entity.getServiceConfigId(), "被池引用时不得改归属");
    }

    @Test
    @DisplayName("迁移成功 → 改指目标 service_config，并清理旧分类的健康状态与限流桶")
    void migrate_success_changesOwnerAndCleansDerivedState() {
        final ServiceInstanceEntity entity = chatInstance();
        when(serviceInstanceRepository.findById(100L)).thenReturn(Optional.of(entity));

        final ServiceInstanceDTO dto = manager.updateInstance(100L, migrateTo("embedding"));

        assertEquals(EMBED_CONFIG_ID, entity.getServiceConfigId(), "归属应改指目标 service_config");
        assertEquals("embedding", dto.getServiceType(), "DTO 应带出后端权威的模型分类");
        // 旧分类下的派生状态必须清理（键含 serviceType 前缀）
        org.mockito.Mockito.verify(serviceStateManager)
                .removeInstanceHealthStatus("chat", RUNTIME_ID);
        org.mockito.Mockito.verify(rateLimitManager)
                .removeInstanceRateLimiterById(
                        org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType.chat, RUNTIME_ID);
    }

    @Test
    @DisplayName("目标分类与当前相同 → 不做任何变更（幂等）")
    void migrate_sameCategory_isNoop() {
        final ServiceInstanceEntity entity = chatInstance();
        when(serviceInstanceRepository.findById(100L)).thenReturn(Optional.of(entity));

        final ServiceInstanceDTO dto = manager.updateInstance(100L, migrateTo("chat"));

        assertEquals(CHAT_CONFIG_ID, entity.getServiceConfigId());
        assertEquals("chat", dto.getServiceType());
        // 断言收窄为「不触发迁移专属的级联清理」：
        // 2026-10-27 起，实例更新会统一重置该实例的健康判定（配置变更后需重新探活），
        // 这与分类迁移无关，不能算作迁移副作用。
        org.mockito.Mockito.verify(serviceStateManager, org.mockito.Mockito.never())
                .removeInstanceHealthStatus(anyString(), anyString());
        org.mockito.Mockito.verify(rateLimitManager, org.mockito.Mockito.never())
                .removeInstanceRateLimiterById(any(), anyString());
    }

    @Test
    @DisplayName("请求未带 serviceType → 不触发迁移（原语义不变）")
    void migrate_absentField_isNoop() {
        final ServiceInstanceEntity entity = chatInstance();
        when(serviceInstanceRepository.findById(100L)).thenReturn(Optional.of(entity));

        final CreateServiceInstanceRequest request = new CreateServiceInstanceRequest();
        request.setName("glm-5.1");

        final ServiceInstanceDTO dto = manager.updateInstance(100L, request);

        assertEquals(CHAT_CONFIG_ID, entity.getServiceConfigId());
        assertEquals("chat", dto.getServiceType());
        // 同上：不触发迁移专属的级联清理（健康判定重置与迁移无关）
        org.mockito.Mockito.verify(serviceStateManager, org.mockito.Mockito.never())
                .removeInstanceHealthStatus(anyString(), anyString());
        org.mockito.Mockito.verify(rateLimitManager, org.mockito.Mockito.never())
                .removeInstanceRateLimiterById(any(), anyString());
    }

    @Test
    @DisplayName("DTO 的 serviceType 由 service_config 反查得出（后端权威值）")
    void dto_serviceType_isResolvedFromServiceConfig() {
        final ServiceInstanceEntity entity = chatInstance();
        when(serviceInstanceRepository.findById(100L)).thenReturn(Optional.of(entity));

        final ServiceInstanceDTO dto = manager.updateInstance(100L, new CreateServiceInstanceRequest());

        assertNotNull(dto.getServiceType());
        assertEquals("chat", dto.getServiceType());
        // atLeastOnce：2026-10-27 起实例更新会额外反查一次 service_config 拿到分类名，
        // 用于重置该实例的健康判定（与 DTO 反查是两处独立调用）。
        org.mockito.Mockito.verify(serviceConfigRepository, org.mockito.Mockito.atLeastOnce())
                .findById(anyLong());
    }

    @Test
    @DisplayName("服务类型枚举常量为全小写且大小写混排（imgGen），解析必须大小写无关")
    void resolveServiceTypeEnum_isCaseInsensitive() throws Exception {
        final java.lang.reflect.Method method = ServiceInstanceManager.class
                .getDeclaredMethod("resolveServiceTypeEnum", String.class);
        method.setAccessible(true);

        final Object chat = method.invoke(manager, "chat");
        final Object chatUpper = method.invoke(manager, "CHAT");
        final Object imgGen = method.invoke(manager, "imgGen");
        final Object imgGenUpper = method.invoke(manager, "IMGGEN");
        final Object unknown = method.invoke(manager, "not-a-type");

        assertEquals(org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType.chat, chat);
        assertEquals(chat, chatUpper);
        assertEquals(org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType.imgGen, imgGen);
        assertEquals(imgGen, imgGenUpper, "IMGGEN 必须能解析——用 valueOf(toUpperCase()) 会永远失败");
        assertNull(unknown, "无法识别的类型名应返回 null（跳过清理）");
    }
}
