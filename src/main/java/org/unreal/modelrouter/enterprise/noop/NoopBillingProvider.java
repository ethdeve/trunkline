/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.BillingProvider;

/**
 * 计费放行实现（社区版，S2 任务 33）。
 *
 * <p>社区版计费口径 = token_usage 账本 + quota 配额（既有体系），
 * 本实现不做外部扣费，直接回执 accepted。
 * @author Trunkline Team
 */
public class NoopBillingProvider implements BillingProvider {

    @Override
    public BillingAck charge(final BillingEntry entry) {
        return BillingAck.ok();
    }
}
