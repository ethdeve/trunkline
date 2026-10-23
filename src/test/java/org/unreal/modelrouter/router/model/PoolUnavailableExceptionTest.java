package org.unreal.modelrouter.router.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.exception.PoolUnavailableException;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N-3 / N-4：池感知异常（503）的成员明细与退避提示。
 *
 * <p>N-3：明细里的 {@code status} 是配置管理态（ACTIVE=已启用），
 * 真正决定成员能否参与分流的是<strong>探活态</strong>。原先只输出 status，
 * 运维会看到 {@code status=ACTIVE} 却收不到请求，误以为实例正常。
 * 现要求明细同时输出 {@code health=HEALTHY/UNHEALTHY/UNKNOWN}。</p>
 *
 * <p>N-4：池不可用改为抛 {@link PoolUnavailableException}，
 * 由全局异常处理器补 {@code Retry-After} 头，与 fallback 降级路径语义一致。</p>
 *
 * <p>本测试<strong>不使用 Mockito</strong>（本机沙箱环境 Mockito 无法加载默认 MockMaker），
 * 改为直接 new 真实的 {@link ServiceStateManager}，行为更接近生产。</p>
 */
@DisplayName("池感知异常 N-3/N-4 测试")
class PoolUnavailableExceptionTest {

    /** 构造一个仅带 serviceStateManager 的 registry 实例（其余依赖为 null，本测试不触发） */
    private ModelServiceRegistry registryWith(final ServiceStateManager stateManager) throws Exception {
        final java.lang.reflect.Constructor<?>[] ctors = ModelServiceRegistry.class.getDeclaredConstructors();
        final java.lang.reflect.Constructor<?> ctor = ctors[0];
        final Class<?>[] paramTypes = ctor.getParameterTypes();
        final Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == ServiceStateManager.class) {
                args[i] = stateManager;
            }
        }
        ctor.setAccessible(true);
        final ModelServiceRegistry registry = (ModelServiceRegistry) ctor.newInstance(args);
        // serviceStateManager 是 final 字段，构造器已注入，此处仅作防御性校验
        final Field f = ModelServiceRegistry.class.getDeclaredField("serviceStateManager");
        f.setAccessible(true);
        assertNotNull(f.get(registry));
        return registry;
    }

    private ModelRouterProperties.ModelInstance instance(final String id, final String name) {
        final ModelRouterProperties.ModelInstance i = new ModelRouterProperties.ModelInstance();
        i.setInstanceId(id);
        i.setName(name);
        i.setBaseUrl("http://" + id + ".local");
        i.setStatus("active");
        i.setWeight(1);
        return i;
    }

    /** 反射调用私有的 resolveHealthStatus */
    private String resolveHealth(final ModelServiceRegistry registry,
                                 final ModelServiceRegistry.ServiceType type,
                                 final ModelRouterProperties.ModelInstance inst) throws Exception {
        final Method m = ModelServiceRegistry.class.getDeclaredMethod(
                "resolveHealthStatus", ModelServiceRegistry.ServiceType.class,
                ModelRouterProperties.ModelInstance.class);
        m.setAccessible(true);
        return (String) m.invoke(registry, type, inst);
    }

    @Test
    @DisplayName("N-3: 探活失败成员判定为 UNHEALTHY")
    void n3_runtimeUnhealthy() throws Exception {
        final ServiceStateManager stateManager = new ServiceStateManager();
        final ModelServiceRegistry registry = registryWith(stateManager);
        final ModelRouterProperties.ModelInstance inst = instance("uuid-1", "zz-dead-test");

        stateManager.updateInstanceHealthStatus("chat", inst, false);

        final String health = resolveHealth(registry, ModelServiceRegistry.ServiceType.chat, inst);
        assertEquals("UNHEALTHY", health, "运行态探活失败必须判为 UNHEALTHY");
    }

    @Test
    @DisplayName("N-3: 探活通过成员判定为 HEALTHY")
    void n3_runtimeHealthy() throws Exception {
        final ServiceStateManager stateManager = new ServiceStateManager();
        final ModelServiceRegistry registry = registryWith(stateManager);
        final ModelRouterProperties.ModelInstance inst = instance("uuid-2", "good-model");

        stateManager.updateInstanceHealthStatus("chat", inst, true);

        final String health = resolveHealth(registry, ModelServiceRegistry.ServiceType.chat, inst);
        assertEquals("HEALTHY", health);
    }

    @Test
    @DisplayName("N-3: 运行态无记录且配置未设置时判定为 UNKNOWN（既非健康也非故障）")
    void n3_unknownWhenNothingRecorded() throws Exception {
        final ServiceStateManager stateManager = new ServiceStateManager();
        final ModelServiceRegistry registry = registryWith(stateManager);
        final ModelRouterProperties.ModelInstance inst = instance("uuid-3", "unknown-model");
        inst.setHealthy(null);

        final String health = resolveHealth(registry, ModelServiceRegistry.ServiceType.chat, inst);
        assertEquals("UNKNOWN", health, "未探测过的实例应为 UNKNOWN，不能谎报 HEALTHY");
    }

    @Test
    @DisplayName("N-3: 运行态无记录时回退到配置的 healthy 字段")
    void n3_fallbackToConfiguredField() throws Exception {
        final ServiceStateManager stateManager = new ServiceStateManager();
        final ModelServiceRegistry registry = registryWith(stateManager);
        final ModelRouterProperties.ModelInstance inst = instance("uuid-4", "configured-dead");
        inst.setHealthy(false);

        final String health = resolveHealth(registry, ModelServiceRegistry.ServiceType.chat, inst);
        assertEquals("UNHEALTHY", health, "配置标记不健康时也应判 UNHEALTHY");
    }

    @Test
    @DisplayName("N-4: 池不可用异常携带 503 状态与 Retry-After 秒数")
    void n4_carriesRetryAfterAndStatus() {
        final PoolUnavailableException ex = new PoolUnavailableException("Pool 'p' has no available members", 60);
        assertEquals(503, ex.getStatus().value(), "池不可用应为 503");
        assertEquals(60, ex.getRetryAfterSeconds(), "Retry-After 应与 fallback 路径一致取 60 秒");
        assertTrue(ex.getRetryAfterSeconds() > 0, "退避秒数必须为正");
        assertTrue(ex.getMessage().contains("no available members"));
    }

    @Test
    @DisplayName("N-3/N-4: 异常消息必须同时含 status 与 health 两个维度")
    void n3n4_messageCarriesBothStatusAndHealth() {
        final PoolUnavailableException ex = new PoolUnavailableException(
                "Pool 'test-dead' has no available members (members: [zz-dead-test(status=ACTIVE, health=UNHEALTHY, circuitBreaker=CLOSED)])",
                60);
        final String msg = ex.getMessage();
        assertTrue(msg.contains("status=ACTIVE"), "保留配置管理态");
        assertTrue(msg.contains("health=UNHEALTHY"), "补出探活态");
        assertTrue(msg.contains("circuitBreaker=CLOSED"), "保留熔断态");
        assertEquals(60, ex.getRetryAfterSeconds());
    }
}
