package org.unreal.modelrouter.router.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.unreal.modelrouter.enterprise.config.TenantContextHolder;
import org.unreal.modelrouter.enterprise.spi.TenantContext;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;

/**
 * PoolSelector 租户隔离回归测试。
 *
 * <p><b>守住的缺陷</b>：虚拟模型（资源池）按租户隔离，但早期 {@code PoolSelector}
 * 只有一个全局 {@code List<PoolDefinition>}，后写入的租户覆盖前一个，导致：
 * <ul>
 *   <li>网关按 model 名查池时命中别家租户的池（跨租户串用）；</li>
 *   <li>切换租户后下拉框仍显示上一个租户的模型，选中即报
 *       {@code Model 'xxx' is not a model group}。</li>
 * </ul>
 * 本测试断言两个租户即使建了<b>同名</b>池也互不干扰，且各自只看到自己的池。</p>
 */
@DisplayName("PoolSelector 租户隔离")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PoolSelectorTenantIsolationTest {

    @Mock private ServiceStateManager serviceStateManager;
    @Mock private CircuitBreakerManager circuitBreakerManager;

    private PoolSelector selector;

    @BeforeEach
    void setUp() {
        selector = new PoolSelector(serviceStateManager, circuitBreakerManager);
        when(serviceStateManager.isInstanceHealthy(anyString(), any())).thenReturn(true);
        when(circuitBreakerManager.canExecute(anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.restoreDefault();
    }

    /** 把当前线程租户切到指定值（模拟请求进入某租户）。 */
    private void enterTenant(final String tenantId) {
        TenantContextHolder.setDelegate(new TenantContext() {
            @Override
            public String getTenantId() {
                return tenantId;
            }

            @Override
            public boolean isMultiTenant() {
                return true;
            }
        });
    }

    private PoolDefinition pool(final String name, final String chatModel) {
        final PoolDefinition pool = new PoolDefinition();
        pool.setPoolName(name);
        pool.setName(name);
        pool.setServiceType("chat");
        pool.setEnabled(true);
        pool.setStrategy("weighted-random");
        return pool;
    }

    @Test
    @DisplayName("两租户各建同名池：互不覆盖，各自只看到自己那份")
    void sameNamePoolDoesNotLeakAcrossTenants() {
        enterTenant("tenant-a");
        selector.reloadPools(List.of(pool("test", "a-model")));

        enterTenant("tenant-b");
        selector.reloadPools(List.of(pool("test", "b-model")));

        // 回到 tenant-a：必须还是它自己那份
        enterTenant("tenant-a");
        assertEquals(1, selector.getAllPools().size(), "tenant-a 只应有 1 个池");
        assertNotNull(selector.findPool(ModelServiceRegistry.ServiceType.chat, "test"),
                "tenant-a 必须能命中自己的 test 池");

        // tenant-b 同理
        enterTenant("tenant-b");
        assertEquals(1, selector.getAllPools().size(), "tenant-b 只应有 1 个池");
        assertNotNull(selector.findPool(ModelServiceRegistry.ServiceType.chat, "test"),
                "tenant-b 必须能命中自己的 test 池");
    }

    @Test
    @DisplayName("A 租户建的池对 B 租户不可见——B 不应命中（这是 is not a model group 的正解）")
    void poolInvisibleToOtherTenant() {
        enterTenant("tenant-a");
        selector.reloadPools(List.of(pool("only-in-a", null)));

        enterTenant("tenant-b");
        selector.reloadPools(List.of());
        assertNull(selector.findPool(ModelServiceRegistry.ServiceType.chat, "only-in-a"),
                "tenant-b 不得命中 tenant-a 的池，否则流量会进别家上游");
        assertEquals(0, selector.getAllPools().size(),
                "tenant-b 的池列表必须为空");
        assertEquals(false, selector.isPoolName("only-in-a"),
                "tenant-b 不能把别家的池名当自己的分组");
    }

    @Test
    @DisplayName("社区版/单租户：无租户上下文时恒落 default，行为与改造前一致")
    void defaultTenantBehaviorUnchanged() {
        TenantContextHolder.restoreDefault();
        selector.reloadPools(List.of(pool("legacy-pool", null)));

        assertNotNull(selector.findPool(ModelServiceRegistry.ServiceType.chat, "legacy-pool"),
                "单租户下池必须照常命中");
        assertEquals(1, selector.getAllPools().size());
    }
}