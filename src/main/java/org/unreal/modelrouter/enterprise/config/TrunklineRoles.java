/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

/**
 * Trunkline 角色常量（S2 任务 39 预埋）。
 *
 * <p>预留两个企业角色名，供商业版多租户权限体系使用；
 * 社区版登录/授权流<b>不会</b>签发这两个角色，行为零变化：
 * <ul>
 *   <li>{@code PLATFORM_ADMIN}：平台超管，商业版管理所有租户与平台级上游</li>
 *   <li>{@code TENANT_ADMIN}：租户管理员，商业版管理本租户成员与配额</li>
 * </ul>
 *
 * <p>社区版 {@code ADMIN} 角色语义 = 平台唯一管理员；商业版启用后
 * 由 enterprise jar 在登录流中给 admin 账号补签这两个角色
 * （对应迭代计划任务 39 「社区版 admin 同时挂两个角色」）。
 * @author Trunkline Team
 */
public final class TrunklineRoles {

    /** 平台超管（预留） */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** 租户管理员（预留） */
    public static final String TENANT_ADMIN = "TENANT_ADMIN";

    private TrunklineRoles() {
    }
}
