package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.ModerationProvider;

/**
 * 内容审核放行实现（社区版，S2 任务 33）。
 *
 * <p>不做任何审核，全部放行——社区版不部署审核链路，
 * 请求延迟不受本实现影响（无 IO、无拷贝）。
 */
public class NoopModerationProvider implements ModerationProvider {

    @Override
    public ModerationDecision check(final ModerationInput input) {
        return ModerationDecision.allow();
    }
}
