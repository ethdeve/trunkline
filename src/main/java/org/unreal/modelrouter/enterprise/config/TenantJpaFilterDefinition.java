package org.unreal.modelrouter.enterprise.config;

/**
 * 租户行级过滤器常量（S2 任务 36 预埋）。
 *
 * <p>单独抽出的原因：{@code @FilterDef} 注解只能出现在
 * {@code package-info.java} / 类型 / 字段级，实体包需要引用过滤器名
 * 而不依赖过滤器的实现细节。
 */
public final class TenantJpaFilterDefinition {

    /** Hibernate 过滤器名，与 persistence.jpa.entity 包级 @FilterDef 对应 */
    public static final String NAME = "tenantFilter";

    /** 过滤器参数名，即 @FilterDef 中声明的参数 */
    public static final String PARAM_TENANT_ID = "tenantId";

    /** 行级过滤总开关配置键（默认 false，商业版多租户解锁后置 true） */
    public static final String ENABLED_PROPERTY = "trunkline.tenant.filter-enabled";

    private TenantJpaFilterDefinition() {
    }
}
