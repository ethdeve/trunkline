package org.unreal.modelrouter.router.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.config.core.helper.ServiceTypeResolver;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.pool.PoolConfigService;
import org.unreal.modelrouter.router.pool.PoolDefinitionProperties;
import org.unreal.modelrouter.router.pool.PoolPersistenceService;
import org.unreal.modelrouter.router.pool.PoolSelector;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PoolConfigController 资源池 API 测试
 * CRUD、校验、重名 409、未知 404、YAML 合并语义
 */
@DisplayName("PoolConfigController 资源池 API 测试")
class PoolConfigControllerTest {

    private PoolDefinitionProperties poolProperties;
    private InMemoryPoolPersistence persistenceService;
    private PoolSelector poolSelector;
    private PoolConfigController controller;

    /** 内存版持久化,避免依赖 StoreManager；支持按 currentTenant 模拟 @Filter(tenantFilter) 行级隔离 */
    private static class InMemoryPoolPersistence extends PoolPersistenceService {
        private final java.util.Map<String, List<PoolDefinition>> storeByTenant = new java.util.LinkedHashMap<>();
        private String currentTenant = "default";

        InMemoryPoolPersistence() {
            super(null, null);
        }

        void useTenant(final String tenant) {
            this.currentTenant = tenant;
        }

        private List<PoolDefinition> bucket() {
            return storeByTenant.computeIfAbsent(currentTenant, k -> new ArrayList<>());
        }

        @Override
        public void saveAll(final List<PoolDefinition> pools) {
            List<PoolDefinition> bucket = bucket();
            bucket.clear();
            bucket.addAll(pools);
        }

        @Override
        public List<PoolDefinition> loadAll() {
            return new ArrayList<>(bucket());
        }

        @Override
        public void save(final PoolDefinition pool) {
            List<PoolDefinition> bucket = bucket();
            bucket.removeIf(p -> p.getPoolName() != null && p.getPoolName().equals(pool.getPoolName()));
            bucket.add(pool);
        }

        @Override
        public void remove(final String poolName) {
            bucket().removeIf(p -> p.getPoolName() != null && p.getPoolName().equals(poolName));
        }
    }

    @BeforeEach
    void setUp() {
        poolProperties = new PoolDefinitionProperties();
        persistenceService = new InMemoryPoolPersistence();
        poolSelector = new PoolSelector(mock(ServiceStateManager.class), mock(CircuitBreakerManager.class));
        controller = new PoolConfigController(
                poolProperties, persistenceService, poolSelector, new ServiceTypeResolver(), mockRegistry(),
                mock(org.unreal.modelrouter.config.dependency.DependencyCheckService.class),
                newPoolConfigService());
    }

    /** 用与 Controller 相同的依赖构造下沉服务（场景 apply 共用的那一个）。 */
    private PoolConfigService newPoolConfigService() {
        return new PoolConfigService(poolProperties, persistenceService, poolSelector,
                new ServiceTypeResolver(), mockRegistry());
    }

    /**
     * mock 运行时注册表:chat 服务类型下含 instanceId=uuid-1 / name=inst-gpt 的实例,
     * 供成员解析校验(P2-6)使用。
     */
    private ModelServiceRegistry mockRegistry() {
        ModelRouterProperties.ModelInstance inst = new ModelRouterProperties.ModelInstance();
        inst.setName("inst-gpt");
        inst.setInstanceId("uuid-1");
        inst.setStatus("active");
        ModelServiceRegistry registry = mock(ModelServiceRegistry.class);
        when(registry.getAllInstances())
                .thenReturn(Map.of(ModelServiceRegistry.ServiceType.chat, List.of(inst)));
        return registry;
    }

    private PoolDefinition validPool(final String poolName) {
        PoolDefinition p = new PoolDefinition();
        p.setPoolName(poolName);
        p.setName(poolName);
        p.setServiceType("chat");
        p.setStrategy("weighted-random");
        PoolMember m = new PoolMember();
        m.setInstanceId("inst-gpt");
        m.setWeight(9);
        p.setMembers(List.of(m));
        return p;
    }

    @Nested
    @DisplayName("创建资源池测试")
    class CreateTests {

        @Test
        @DisplayName("POOL-API-001: 创建资源池成功")
        void testCreate_success() {
            ResponseEntity<RouterResponse<PoolDefinition>> response =
                    controller.createPool(validPool("auto-model"));

            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertEquals("auto-model", response.getBody().getData().getPoolName());
        }

        @Test
        @DisplayName("POOL-API-002: 池名为空返回 400")
        void testCreate_blankName_400() {
            PoolDefinition p = validPool("auto-model");
            p.setPoolName("  ");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.createPool(p));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("POOL-API-003: 非法 serviceType 返回 400")
        void testCreate_invalidServiceType_400() {
            PoolDefinition p = validPool("auto-model");
            p.setServiceType("bogus");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.createPool(p));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("POOL-API-004: 不支持的策略返回 400")
        void testCreate_unsupportedStrategy_400() {
            PoolDefinition p = validPool("auto-model");
            p.setStrategy("magic");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.createPool(p));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("POOL-API-005: 重名池返回 409")
        void testCreate_duplicate_409() {
            controller.createPool(validPool("auto-model"));
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.createPool(validPool("auto-model")));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("查询与更新测试")
    class QueryUpdateTests {

        @Test
        @DisplayName("POOL-API-006: 创建后列表包含该池")
        void testList_containsCreated() {
            controller.createPool(validPool("auto-model"));

            List<PoolDefinition> pools = controller.getAllPools().getBody().getData();

            assertEquals(1, pools.size());
            assertEquals("auto-model", pools.get(0).getPoolName());
        }

        @Test
        @DisplayName("POOL-API-007: 更新池生效")
        void testUpdate_updatesPool() {
            PoolDefinition created = controller.createPool(validPool("auto-model")).getBody().getData();
            PoolDefinition updated = validPool("auto-model");
            updated.setStrategy("least-connections");

            controller.updatePool(created.getPoolName(), updated);

            assertEquals("least-connections", controller.getPool("auto-model").getBody().getData().getStrategy());
        }

        @Test
        @DisplayName("POOL-API-008: 更新不存在的池返回 404")
        void testUpdate_missing_404() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.updatePool("nonexistent", validPool("auto-model")));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }

        @Test
        @DisplayName("POOL-API-009: 获取不存在的池返回 404")
        void testGet_missing_404() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.getPool("nonexistent"));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("删除与 YAML 合并测试")
    class DeleteMergeTests {

        @Test
        @DisplayName("POOL-API-010: 删除池生效")
        void testDelete_removesPool() {
            controller.createPool(validPool("auto-model"));

            controller.deletePool("auto-model");

            assertTrue(controller.getAllPools().getBody().getData().isEmpty());
        }

        @Test
        @DisplayName("POOL-API-011: 删除不存在的池返回 404")
        void testDelete_missing_404() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.deletePool("nonexistent"));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }

        @Test
        @DisplayName("POOL-API-012: YAML 默认池 + 持久化合并,持久化同池名覆盖")
        void testYamlMerge_persistedOverridesYaml() {
            PoolDefinition yamlPool = validPool("auto-model");
            yamlPool.setStrategy("round-robin");
            poolProperties.setPools(List.of(yamlPool));

            // 控制器构造时已加载 YAML;新建同名持久化池覆盖
            controller.createPool(validPool("auto-model"));

            List<PoolDefinition> pools = controller.getAllPools().getBody().getData();
            assertEquals(1, pools.size());
            assertEquals("weighted-random", pools.get(0).getStrategy(),
                    "持久化池应覆盖 YAML 默认池");
        }

        @Test
        @DisplayName("POOL-API-013: YAML 池与持久化池并存")
        void testYamlMerge_distinctNames() {
            poolProperties.setPools(List.of(validPool("yaml-pool")));
            controller = new PoolConfigController(
                    poolProperties, persistenceService, poolSelector, new ServiceTypeResolver(), mockRegistry(),
                mock(org.unreal.modelrouter.config.dependency.DependencyCheckService.class),
                newPoolConfigService());
            controller.createPool(validPool("auto-model"));

            List<PoolDefinition> pools = controller.getAllPools().getBody().getData();
            assertEquals(2, pools.size());
        }
    }

    // ==================== 租户隔离：列表按当前租户实时读取（切租户/新建即时可见） ====================

    @Nested
    @DisplayName("租户隔离测试（资源池 = 租户业务数据，列表按当前租户实时读取）")
    class TenantIsolationTests {

        @Test
        @DisplayName("POOL-TENANT-001: 切租户后列表只反映当前租户的池，不返回旧租户快照")
        void listReflectsCurrentTenant() {
            // 租户 A 建池
            persistenceService.useTenant("tenant-a");
            controller.createPool(validPool("pool-a"));
            assertEquals(1, controller.getAllPools().getBody().getData().size());

            // 切到租户 B：仍调用同一个 controller 单例的 getAllPools，应为空（不得命中 A 的缓存）
            persistenceService.useTenant("tenant-b");
            assertTrue(controller.getAllPools().getBody().getData().isEmpty(),
                    "切租户后不得看到其它租户的池");

            // 切回租户 A：A 的池仍在
            persistenceService.useTenant("tenant-a");
            assertEquals(1, controller.getAllPools().getBody().getData().size());
        }

        @Test
        @DisplayName("POOL-TENANT-002: 新建池后在当前租户列表即时可见，且不泄漏到其它租户")
        void createdPoolVisibleImmediately() {
            persistenceService.useTenant("tenant-a");
            controller.createPool(validPool("pool-a"));

            // 新建后立刻查列表（走实时读取，不再依赖构造期快照）
            List<PoolDefinition> pools = controller.getAllPools().getBody().getData();
            assertEquals(1, pools.size());
            assertEquals("pool-a", pools.get(0).getPoolName());

            // B 租户看不到 A 刚建的池
            persistenceService.useTenant("tenant-b");
            assertTrue(controller.getAllPools().getBody().getData().isEmpty());

            // B 用同名池不与 A 冲突
            ResponseEntity<RouterResponse<PoolDefinition>> resp = controller.createPool(validPool("pool-a"));
            assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                    "不同租户下同名池不应冲突（唯一键含 tenant_id）");
            assertEquals(1, controller.getAllPools().getBody().getData().size());
        }

        @Test
        @DisplayName("POOL-TENANT-003: 单条查询与删除均按当前租户视角")
        void getAndDeleteScopedToCurrentTenant() {
            persistenceService.useTenant("tenant-a");
            controller.createPool(validPool("shared-name"));

            persistenceService.useTenant("tenant-b");
            // B 租户下按名字查 A 的池 → 404
            assertThrows(ResponseStatusException.class, () -> controller.getPool("shared-name"));
            // B 租户下删 A 的池 → 404，不得误删
            assertThrows(ResponseStatusException.class, () -> controller.deletePool("shared-name"));

            persistenceService.useTenant("tenant-a");
            assertEquals(200, controller.getPool("shared-name").getStatusCode().value(),
                    "A 租户的池不得被 B 租户误删");
        }
    }

    @Nested
    @DisplayName("成员解析校验测试(P2-6)")
    class MemberValidationTests {

        @Test
        @DisplayName("POOL-API-014: 成员为实例名时创建成功")
        void testCreate_memberByName_ok() {
            ResponseEntity<RouterResponse<PoolDefinition>> response =
                    controller.createPool(validPool("auto-model"));
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
        }

        @Test
        @DisplayName("POOL-API-015: 成员为运行时 instanceId(UUID)时创建成功")
        void testCreate_memberByUuid_ok() {
            PoolDefinition p = validPool("auto-model");
            p.getMembers().get(0).setInstanceId("uuid-1");

            ResponseEntity<RouterResponse<PoolDefinition>> response =
                    controller.createPool(p);
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
        }

        @Test
        @DisplayName("POOL-API-016: 成员为数据库数字 id 时返回 400 并列出坏成员")
        void testCreate_numericIdMember_400() {
            PoolDefinition p = validPool("auto-model");
            p.getMembers().get(0).setInstanceId("19");

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.createPool(p));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertTrue(ex.getReason().contains("19"),
                    "400 原因应包含坏成员: " + ex.getReason());
        }

        @Test
        @DisplayName("POOL-API-017: 多个坏成员全部列出")
        void testCreate_multipleBadMembers_allListed() {
            PoolDefinition p = validPool("auto-model");
            PoolMember bad2 = new PoolMember();
            bad2.setInstanceId("ghost-instance");
            bad2.setWeight(1);
            p.setMembers(List.of(p.getMembers().get(0), bad2));
            p.getMembers().get(0).setInstanceId("19");

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.createPool(p));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertTrue(ex.getReason().contains("19") && ex.getReason().contains("ghost-instance"),
                    "400 原因应列出全部坏成员: " + ex.getReason());
        }
    }

    // ==================== YAML 管理池冲突（issue #708） ====================

    @Nested
    @DisplayName("YAML 管理池冲突测试（#708）")
    class YamlPoolConflictTests {

        /** 构建「YAML 含一个池、持久化为空」的控制器（构造时 reload 合并）。 */
        private PoolConfigController controllerWithYamlPool(final String poolName) {
            poolProperties.setPools(new ArrayList<>(List.of(validPool(poolName))));
            return new PoolConfigController(
                    poolProperties, persistenceService, poolSelector, new ServiceTypeResolver(), mockRegistry(),
                    mock(org.unreal.modelrouter.config.dependency.DependencyCheckService.class),
                    newPoolConfigService());
        }

        @Test
        @DisplayName("POOL-701: 删除 YAML 池返回 409 + YAML_POOL_ACTIVE，池仍生效")
        void yamlPoolDeleteReturns409() {
            PoolConfigController yamlController = controllerWithYamlPool("yaml-pool");

            ResponseEntity<RouterResponse<Void>> response = yamlController.deletePool("yaml-pool");

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("YAML_POOL_ACTIVE", response.getBody().getErrorCode());
            assertTrue(persistenceService.loadAll().stream()
                            .noneMatch(p -> "yaml-pool".equals(p.getPoolName())),
                    "持久化集合不得出现 YAML 池");
            assertEquals(200, yamlController.getPool("yaml-pool").getStatusCode().value(),
                    "YAML 池 reload 后仍应生效（反证旧 200 删除谎报）");
        }

        @Test
        @DisplayName("POOL-702: 更新 YAML 池返回 409 且不落库")
        void yamlPoolUpdateReturns409() {
            PoolConfigController yamlController = controllerWithYamlPool("yaml-pool");

            ResponseEntity<RouterResponse<PoolDefinition>> response =
                    yamlController.updatePool("yaml-pool", validPool("yaml-pool"));

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertEquals("YAML_POOL_ACTIVE", response.getBody().getErrorCode());
            assertTrue(persistenceService.loadAll().isEmpty(), "更新被拒绝时不得落库");
        }

        @Test
        @DisplayName("POOL-703: 持久化池删除仍返回 200（不受新规则影响）")
        void persistedPoolDeleteStill200() {
            controller.createPool(validPool("persisted-pool"));

            ResponseEntity<RouterResponse<Void>> response = controller.deletePool("persisted-pool");

            assertEquals(HttpStatus.OK, response.getStatusCode(), "持久化池删除不回归");
        }
    }
}
