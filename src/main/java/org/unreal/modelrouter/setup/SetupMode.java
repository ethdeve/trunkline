/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

/**
 * 初始化向导的数据源模式。
 *
 * @author Trunkline Team
 */
public enum SetupMode {
    /** 本地服务模式：使用内置 H2 数据库 */
    LOCAL,
    /** 远程服务模式：使用外部 PostgreSQL 数据库 */
    REMOTE
}
