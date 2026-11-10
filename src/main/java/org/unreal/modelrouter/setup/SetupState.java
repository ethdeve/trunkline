/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

/**
 * 初始化向导持久化状态（不对外暴露密码）。
 *
 * @param mode          已选择的数据源模式
 * @param datasourceUrl 远程模式下的 JDBC URL，本地模式为 {@code null}
 * @param username      远程模式下的数据库用户名，本地模式为 {@code null}
 * @param password      远程模式下的数据库密码（仅服务端内存态），本地模式为 {@code null}
 * @param updatedAt     状态最近更新时间（ISO-8601）
 * @author Trunkline Team
 */
public record SetupState(SetupMode mode, String datasourceUrl, String username, String password, String updatedAt) {

    /** 本地模式状态 */
    public static SetupState local(final String updatedAt) {
        return new SetupState(SetupMode.LOCAL, null, null, null, updatedAt);
    }

    /** 远程模式状态 */
    public static SetupState remote(final String datasourceUrl, final String username,
                                    final String password, final String updatedAt) {
        return new SetupState(SetupMode.REMOTE, datasourceUrl, username, password, updatedAt);
    }

    /** 尚未选择过模式时按本地模式处理 */
    public static SetupState defaultLocal() {
        return local(null);
    }
}
