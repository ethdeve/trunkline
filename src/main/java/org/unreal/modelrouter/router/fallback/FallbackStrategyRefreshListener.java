package org.unreal.modelrouter.router.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.config.event.ConfigSyncEvent;

/**
 * 降级策略缓存刷新监听器（issue #873）
 *
 * <p>修复前 {@link FallbackManager#fallbackStrategies} 是永久缓存：
 * {@code getFallbackStrategy} 仅 miss 才重建，{@code clearFallbackStrategy}/
 * {@code clearAllFallbackStrategies} 全库无调用点，配置事件也从未被消费——
 * 服务级降级配置热更新后旧策略对象一直生效，重启才失效。</p>
 *
 * <p>本监听器消费 {@link ConfigSyncEvent}：REFRESH / ROLLBACK /
 * INSTANCE_UPDATE 三类事件触发缓存清除，下一次 {@code getFallbackStrategy}
 * 按新配置重建。定向语义：事件带 {@code targetService} 时只清该服务，
 * 广播事件（null）清全部。</p>
 *
 * <p><b>刻意不加 {@code @Async}</b>：保证「保存配置 → 返回 → 后续请求」
 * 时序上缓存已被清除（同步生效），避免异步延迟窗口内请求拿到旧策略。</p>
 *
 * <p>已知边界（方案风险条目，定案接受）：clear 后并发请求可能以旧
 * {@code globalFallbackConfig} 立刻重建旧口径策略——该配置来自启动绑定的
 * {@code ModelRouterProperties}，本事件链路不更新它，行为与修复前一致。</p>
 *
 * @author Trunkline Team
 */
@Component
public class FallbackStrategyRefreshListener {

    private static final Logger logger = LoggerFactory.getLogger(FallbackStrategyRefreshListener.class);

    private final FallbackManager fallbackManager;

    public FallbackStrategyRefreshListener(final FallbackManager fallbackManager) {
        this.fallbackManager = fallbackManager;
    }

    /**
     * 配置同步事件触发降级策略缓存清除.
     *
     * @param event 配置同步事件
     */
    @EventListener(ConfigSyncEvent.class)
    public void onConfigSync(final ConfigSyncEvent event) {
        // MIGRATE 等其他类型不影响降级策略，跳过
        if (!event.isRefresh() && !event.isRollback()
                && !"INSTANCE_UPDATE".equals(event.syncType())) {
            return;
        }

        if (event.targetService() == null || event.targetService().isBlank()) {
            fallbackManager.clearAllFallbackStrategies();
            logger.info("配置事件 [{}] 广播：已清除全部降级策略缓存，下次请求按新配置重建",
                    event.syncType());
        } else {
            fallbackManager.clearFallbackStrategy(event.targetService());
            logger.info("配置事件 [{}] 定向 service={}: 已清除该服务降级策略缓存",
                    event.syncType(), event.targetService());
        }
    }
}
