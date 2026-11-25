/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.Set;

/**
 * 数据擦除扩展点（#10 被遗忘权精确擦除）。
 *
 * <p><b>契约定位</b>：开源主干只定义"擦除某个主体 + 返回报告"的抽象；
 * 具体擦哪些表、哪些字段物理删除、哪些假名化（账单/用量金额不可动）、
 * 企业 JDBC 自举表如何绕过 {@code @Filter} 显式带 tenant_id，全部是企业侧实现细节。</p>
 *
 * <p><b>安全语义</b>：
 * <ul>
 *   <li>{@code subjectType=TENANT} 时实现必须先解析出 userId 集合，禁止全表擦除；</li>
 *   <li>租户上下文缺失时<b>拒绝执行</b>（返回 success=false 的报告），不退化成全量擦除；</li>
 *   <li>财务字段（金额 / token / trace_id / 价格快照）只假名化身份列，金额分毫不动；</li>
 *   <li>{@code domains} 为空或 null 表示"全部域"。</li>
 * </ul>
 *
 * <p><b>兼容性</b>：本接口全部方法带 {@code default} 实现，属新增扩展点，不推
 * {@link EnterpriseSpiVersion#VERSION}；社区版无 Bean 时返回"未启用"报告（零副作用），
 * 不抛异常——被遗忘权是企业版能力，社区版端点直接告知不可用。</p>
 *
 * @author Trunkline Team
 */
public interface DataEraser {

    /**
     * 是否启用擦除能力。社区版默认 false；企业版按 {@code erasure} 功能位装配后恒 true。
     */
    default boolean enabled() {
        return false;
    }

    /**
     * 执行擦除。
     *
     * @param subjectType 主体类型（USER / TENANT）
     * @param subject     主体标识（userId 或 tenantId），一律由服务端认证上下文解析
     * @param domains     待擦域集合，空集 / null 表示全部域
     * @return 不可变擦除报告；社区版返回 success=false、reason="erasure not available"
     */
    default ErasureReport erase(final ErasureReport.SubjectType subjectType,
                                final String subject,
                                final Set<DataDomain> domains) {
        return ErasureReport.builder(subjectType, subject)
                .failure("erasure not available in community edition")
                .build();
    }
}
