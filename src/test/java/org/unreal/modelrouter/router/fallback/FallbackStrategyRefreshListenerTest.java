package org.unreal.modelrouter.router.fallback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.config.event.ConfigSyncEvent;
import org.unreal.modelrouter.router.factory.ComponentFactory;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #873：降级策略缓存热更新监听器测试。
 *
 * <p>修复前 {@code FallbackManager.fallbackStrategies} 永久缓存——
 * {@code clearAllFallbackStrategies} 全库无调用点、{@code ConfigSyncEvent}
 * 从未被降级链路消费，服务级降级配置热更新后旧策略对象一直生效。</p>
 *
 * <p>修复后 {@link FallbackStrategyRefreshListener} 同步消费 REFRESH /
 * ROLLBACK / INSTANCE_UPDATE：广播事件清全部、定向事件只清 targetService。</p>
 */
@DisplayName("issue #873: 降级策略缓存刷新监听器")
@ExtendWith(MockitoExtension.class)
class FallbackStrategyRefreshListenerTest {

    @Mock
    private ComponentFactory componentFactory;

    private FallbackManager fallbackManager;

    private FallbackStrategyRefreshListener listener;

    @BeforeEach
    void setUp() {
        fallbackManager = new FallbackManager(componentFactory);
        listener = new FallbackStrategyRefreshListener(fallbackManager);
    }

    private FallbackStrategy<ResponseEntity<?>> stubStrategy() {
        // 仅作为缓存条目对象参与断言（身份/数量），fallback 行为不在本测试范围，
        // 不 stub——strict-stubs 下未触发的 stub 会报 UnnecessaryStubbing
        @SuppressWarnings("unchecked")
        FallbackStrategy<ResponseEntity<?>> strategy = mock(FallbackStrategy.class);
        return strategy;
    }

    private ModelRouterProperties.ServiceConfig serviceConfig() {
        return new ModelRouterProperties.ServiceConfig();
    }

    @Test
    @DisplayName("广播 REFRESH 事件 → 全部策略缓存清空，下次按新配置重建（反证修复前恒 1 且对象身份不变）")
    void broadcastRefreshClearsAllStrategies() {
        FallbackStrategy<ResponseEntity<?>> first = stubStrategy();
        when(componentFactory.createFallbackStrategy(any(), eq("chat")))
                .thenReturn((FallbackStrategy) first);

        fallbackManager.getFallbackStrategy("chat", serviceConfig());
        assertEquals(1, fallbackManager.getFallbackStrategyCount(), "前置：缓存已建立");

        listener.onConfigSync(new ConfigSyncEvent("REFRESH", Map.of(), null, Instant.now()));

        assertEquals(0, fallbackManager.getFallbackStrategyCount(),
                "修复前 clearAllFallbackStrategies 无调用点，缓存永不失效");

        // 下次 get 按新配置重建（构造新对象）
        FallbackStrategy<ResponseEntity<?>> second = stubStrategy();
        when(componentFactory.createFallbackStrategy(any(), eq("chat")))
                .thenReturn((FallbackStrategy) second);
        FallbackStrategy<ResponseEntity<?>> rebuilt =
                fallbackManager.getFallbackStrategy("chat", serviceConfig());
        assertNotSame(first, rebuilt, "重建后必须基于新配置生成新策略对象");
    }

    @Test
    @DisplayName("定向 INSTANCE_UPDATE 事件 → 只清 targetService，其余服务缓存保留")
    void targetedInstanceUpdateClearsOnlyTargetService() {
        FallbackStrategy<ResponseEntity<?>> chatStrategy = stubStrategy();
        FallbackStrategy<ResponseEntity<?>> embeddingStrategy = stubStrategy();
        when(componentFactory.createFallbackStrategy(any(), eq("chat")))
                .thenReturn((FallbackStrategy) chatStrategy);
        when(componentFactory.createFallbackStrategy(any(), eq("embedding")))
                .thenReturn((FallbackStrategy) embeddingStrategy);

        fallbackManager.getFallbackStrategy("chat", serviceConfig());
        fallbackManager.getFallbackStrategy("embedding", serviceConfig());
        assertEquals(2, fallbackManager.getFallbackStrategyCount());

        listener.onConfigSync(new ConfigSyncEvent("INSTANCE_UPDATE", Map.of(), "chat", Instant.now()));

        assertEquals(1, fallbackManager.getFallbackStrategyCount(), "只清目标服务");
        // embedding 的缓存对象身份不变（未被清除，命中缓存不再调 factory）
        FallbackStrategy<ResponseEntity<?>> embeddingAgain =
                fallbackManager.getFallbackStrategy("embedding", serviceConfig());
        assertSame(embeddingStrategy, embeddingAgain, "未被定向清除的服务缓存对象身份不变");
        verify(componentFactory, times(1)).createFallbackStrategy(any(), eq("embedding"));
    }

    @Test
    @DisplayName("定向 ROLLBACK 事件同样清除目标服务缓存")
    void rollbackClearsTargetService() {
        FallbackStrategy<ResponseEntity<?>> chatStrategy = stubStrategy();
        when(componentFactory.createFallbackStrategy(any(), eq("chat")))
                .thenReturn((FallbackStrategy) chatStrategy);
        fallbackManager.getFallbackStrategy("chat", serviceConfig());
        assertEquals(1, fallbackManager.getFallbackStrategyCount());

        listener.onConfigSync(new ConfigSyncEvent("ROLLBACK", Map.of(), "chat", Instant.now()));

        assertEquals(0, fallbackManager.getFallbackStrategyCount());
    }

    @Test
    @DisplayName("MIGRATE 等无关事件不清缓存")
    void unrelatedEventDoesNotClear() {
        FallbackStrategy<ResponseEntity<?>> chatStrategy = stubStrategy();
        when(componentFactory.createFallbackStrategy(any(), eq("chat")))
                .thenReturn((FallbackStrategy) chatStrategy);
        fallbackManager.getFallbackStrategy("chat", serviceConfig());

        listener.onConfigSync(new ConfigSyncEvent("MIGRATE", Map.of(), null, Instant.now()));

        assertEquals(1, fallbackManager.getFallbackStrategyCount(), "无关事件不得清空缓存");
    }

    @Test
    @DisplayName("targetService 空白视为广播（清全部）")
    void blankTargetServiceTreatedAsBroadcast() {
        FallbackStrategy<ResponseEntity<?>> chatStrategy = stubStrategy();
        when(componentFactory.createFallbackStrategy(any(), eq("chat")))
                .thenReturn((FallbackStrategy) chatStrategy);
        fallbackManager.getFallbackStrategy("chat", serviceConfig());

        listener.onConfigSync(new ConfigSyncEvent("REFRESH", Map.of(), "  ", Instant.now()));

        assertEquals(0, fallbackManager.getFallbackStrategyCount());
    }
}
