/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

/**
 * 租户行级过滤器常量（S2 任务 36 预埋）。
 *
 * <p>单独抽出的原因：{@code @FilterDef} 注解只能出现在
 * {@code package-info.java} / 类型 / 字段级，实体包需要引用过滤器名
 * 而不依赖过滤器的实现细节。
 * @author Trunkline Team
 */
public final class TenantJpaFilterDefinition {

    /** Hibernate 过滤器名，与 persistence.jpa.entity 包级 @FilterDef 对应 */
    public static final String NAME = "tenantFilter";

    /** 过滤器参数名，即 @FilterDef 中声明的参数（集合：本租户 + 全局共享哨兵） */
    public static final String PARAM_TENANT_IDS = "tenantIds";

    /**
     * 过滤器条件：当前租户的数据 + 全局共享数据。
     *
     * <p>用 {@code in} 而非 {@code =}，让同一张 {@code config_data} 表既能承载
     * 按租户隔离的业务配置（池/规则），又能承载全局唯一的字典配置（模型分类/模板/审批）。
     * 详见 {@link #SHARED_TENANT_ID}。</p>
     */
    public static final String CONDITION = "tenant_id in (:tenantIds)";

    /** 行级过滤总开关配置键（默认 false，商业版多租户解锁后置 true） */
    public static final String ENABLED_PROPERTY = "trunkline.tenant.filter-enabled";

    /**
     * 全局共享配置哨兵租户（{@code __shared__}）。
     *
     * <p><b>为什么需要</b>：同一张 {@code config_data} 表里混放两类配置——
     * <ul>
     *   <li>按租户切分的业务配置（虚拟模型池 {@code pool_definitions}、路由规则
     *       {@code rule_definitions}）——必须隔离；</li>
     *   <li>全部署唯一的字典/模板配置（模型分类 {@code service_config}、提示词模板、
     *       Key 审批单、组-角色映射、适配器生命周期）——必须<b>全局共享</b>，
     *       否则每个租户都要重复维护一份同名分类，切租户就"看不见自己刚配的东西"。</li>
     * </ul>
     * 行级过滤器按实体生效，无法在实体粒度区分同一张表里的两类行，故引入哨兵值：
     * 过滤条件放宽为 {@code tenant_id = :tenantId OR tenant_id = '__shared__'}，
     * 全局配置恒写入哨兵租户，任何租户都能读到，而业务配置仍严格互不可见。</p>
     *
     * <p><b>为什么不用 {@code default}</b>：{@code default} 是社区版/单租户企业版的
     * 真实业务租户，占用它会让单租户形态下的"全局配置"与"本租户业务配置"混为一谈。</p>
     *
     * <p><b>命名兼容性</b>：下划线不符合业务租户 ID 正则 {@code ^[a-z0-9-]{1,63}$}，
     * 因此它不可能与真实租户撞名——真实租户永远建不出这个名字。</p>
     */
    public static final String SHARED_TENANT_ID = "__shared__";

    private TenantJpaFilterDefinition() {
    }
}
