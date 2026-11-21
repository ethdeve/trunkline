/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.List;

/**
 * 部门/组织候选项扩展点（v3 兼容扩展，不推版本）。
 *
 * <p>账号编辑页的「部门」字段候选来自 {@code GET /api/security/jwt/accounts/departments}，
 * 社区版仅返回账号表里出现过的历史部门名（手动填写）。企业版若已同步组织机构
 * （SCIM Group / 稳定部门登记表），可通过本 SPI 把组织名并入候选，使账号编辑时能从
 * 已同步组织中下拉选择；不新增/改写用户实体字段，仍复用 {@code JwtAccountEntity.department}。</p>
 *
 * <p><b>社区版零行为影响</b>：无 Bean 时主干 {@code JwtAccountService.getDepartments()} 仅保留
 * 历史部门（等同现状的手动填写）。本接口全 default，属
 * {@link EnterpriseSpiVersion} v3 契约允许的「仅新增带默认实现方法」，不 bump 版本。</p>
 *
 * @author Trunkline Team
 */
public interface DepartmentOptionsProvider {

    /**
     * 额外的部门/组织候选名（企业版返回已同步组织名）。
     *
     * <p>主干会对返回值做 trim / 去空 / 去重 / 排序，并与账号历史部门合并；
     * 实现抛异常时主干 fail-soft 忽略，绝不影响账号列表与部门端点可用性。</p>
     *
     * @return 组织名列表；默认空列表（社区版行为）
     */
    default List<String> extraDepartments() {
        return List.of();
    }
}
