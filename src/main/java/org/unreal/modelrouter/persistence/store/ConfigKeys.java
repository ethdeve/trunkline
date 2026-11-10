/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.store;

/**
 * 持久化配置键常量。
 *
 * <p>Trunkline 已从旧版本中移除主配置 key 兼容回落；所有服务统一使用
 * {@link #CURRENT_CONFIG_KEY} 读写同一份主配置。</p>
 *
 * @author Trunkline Team
 */
public final class ConfigKeys {

    /** 主配置存储 key */
    public static final String CURRENT_CONFIG_KEY = "trunkline-config";

    private ConfigKeys() {
    }
}
