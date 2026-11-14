package org.unreal.modelrouter.common.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群事件总线的 <b>Spring 装配</b>回归测试（B3 真 PG 冒烟发现）。
 *
 * <p>{@link ClusterEventBusTest} 全部用 {@code new RedisClusterEventBus(...)} 直接构造，
 * 因此「本类有两个构造器、都没有 {@code @Autowired}」的问题一直没被测出来：一旦部署方
 * 把 {@code trunkline.cluster.events.enabled=true} 打开（企业版改价跨副本失效正是依赖它），
 * Spring 抛 {@code BeanInstantiationException: No default constructor found}，
 * <b>整个应用启动失败</b>。这里走真实容器 refresh，把装配路径钉死。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("集群事件总线 Spring 装配")
class RedisClusterEventBusWiringTest {

    /** 只提供 ObjectMapper；总线本身必须由容器按构造器注入实例化，才能覆盖本次缺陷。 */
    @Configuration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    @DisplayName("开启广播时容器能装配总线；无 Redis 连接工厂时降级但不阻断启动")
    void redisBusIsInstantiableWhenClusterEventsEnabled() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("trunkline.cluster.events.enabled=true").applyTo(ctx);
            ctx.register(TestConfig.class, RedisClusterEventBus.class);
            ctx.refresh();

            final ClusterEventBus bus = ctx.getBean(ClusterEventBus.class);
            assertInstanceOf(RedisClusterEventBus.class, bus);
            assertFalse(bus.isClusterWide(), "无连接工厂时应自报降级");
            assertNotNull(bus.subscribe("price-changed"));
        }
    }

    @Test
    @DisplayName("未开启广播时不注册 Redis 总线（条件注解仍生效）")
    void redisBusIsAbsentWhenClusterEventsDisabled() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TestConfig.class, RedisClusterEventBus.class);
            ctx.refresh();

            assertTrue(ctx.getBeansOfType(ClusterEventBus.class).isEmpty());
        }
    }
}
