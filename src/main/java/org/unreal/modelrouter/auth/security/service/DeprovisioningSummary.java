package org.unreal.modelrouter.auth.security.service;

import java.util.List;

/**
 * A4 最小回收闭环（闸门② P0 红线）——对账/回收结果汇总。
 *
 * <p>用于「全量对账（reconcile）」与「单账号停用」的返回，便于脚本/端点消费与留痕：
 * 本次被停用的本地账号清单、受影响的 API Key 数量。</p>
 */
public class DeprovisioningSummary {

    /** 本次被停用的本地账号数。 */
    private final int disabledCount;

    /** 本次被停用的本地账号登录名清单。 */
    private final List<String> disabledUsernames;

    /** 本次被吊销的 API Key 数量（跨所有停用账号）。 */
    private final int revokedKeyCount;

    public DeprovisioningSummary(final int disabledCount,
                                 final List<String> disabledUsernames,
                                 final int revokedKeyCount) {
        this.disabledCount = disabledCount;
        this.disabledUsernames = disabledUsernames == null ? List.of() : List.copyOf(disabledUsernames);
        this.revokedKeyCount = revokedKeyCount;
    }

    public int getDisabledCount() {
        return disabledCount;
    }

    public List<String> getDisabledUsernames() {
        return disabledUsernames;
    }

    public int getRevokedKeyCount() {
        return revokedKeyCount;
    }
}
