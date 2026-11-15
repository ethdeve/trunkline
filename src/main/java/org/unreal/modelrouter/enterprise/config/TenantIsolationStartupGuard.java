/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.enterprise.config;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.hibernate.annotations.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.persistence.jpa.entity.TenantShared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 多租户数据层「完整性」启动守卫（体检报告 P0：杜绝假隔离）。
 *
 * <p><b>要解决的问题</b>：{@link TenantJpaFilter} 只保证「已挂 {@code @Filter} 的实体」被过滤，
 * 对「完全没挂过滤器的实体」是静默放行。于是打开 {@code filter-enabled=true} 后会出现
 * 一部分表隔离、一部分表不隔离的「假隔离」——比完全不隔离更危险，因为管理员会误以为已生效。</p>
 *
 * <p><b>本守卫的约束</b>：仅在多租户开关开启时装配，从 JPA Metamodel 取<b>全部</b>实体
 * （不局限于某个包，避免新增包漏检），每个 {@code @Entity} 必须显式声明归属之一：
 * <ul>
 *   <li>挂 {@code @Filter(name = "tenantFilter")} → 行级隔离；</li>
 *   <li>标 {@link TenantShared} 并写明 reason → 有意全局共享，不做行级隔离。</li>
 * </ul>
 * 未声明任何一类的实体 → <b>fail-fast 拒绝启动</b>；已声明为共享的实体以 WARN 清单输出，
 * 让部署方在日志里看到「哪些表有意不隔离」，而不是静默。</p>
 *
 * <p>社区版（{@code filter-enabled} 默认 false）本组件不装配，行为零变化。
 * 核心判定做成静态纯函数 {@link #assess(Collection)}，便于单元测试，与
 * {@code MultiReplicaSharedStateGate} / {@code StartupSecurityGate} 同一范式。</p>
 *
 * @author Trunkline Team
 */
@ConditionalOnProperty(name = TenantJpaFilterDefinition.ENABLED_PROPERTY, havingValue = "true")
@Component
public class TenantIsolationStartupGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TenantIsolationStartupGuard.class);

    private final EntityManagerFactory entityManagerFactory;

    public TenantIsolationStartupGuard(final EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        final List<Class<?>> entityTypes = new ArrayList<>();
        for (final EntityType<?> type : entityManagerFactory.getMetamodel().getEntities()) {
            if (type.getJavaType() != null) {
                entityTypes.add(type.getJavaType());
            }
        }
        final Declaration declaration = assess(entityTypes);
        if (declaration.hasUndeclared()) {
            throw new IllegalStateException(describeUndeclared(declaration));
        }
        if (!declaration.shared().isEmpty()) {
            log.warn("多租户已开启：{} 个实体走行级隔离；以下 {} 个实体为「有意全局共享」，"
                            + "不做行级隔离，请确认部署形态可接受（私有化单客户部署下成立）：{}{}",
                    declaration.isolated().size(), declaration.shared().size(),
                    System.lineSeparator(), String.join(System.lineSeparator(), declaration.shared()));
        } else {
            log.info("多租户已开启：{} 个实体全部走行级隔离", declaration.isolated().size());
        }
    }

    /**
     * 实体归属声明清单。
     *
     * @param isolated   挂了 tenantFilter 的实体简名
     * @param shared     标了 @TenantShared 的实体（含 reason）
     * @param undeclared 未声明归属的实体简名
     */
    public record Declaration(List<String> isolated, List<String> shared, List<String> undeclared) {

        /** 是否存在漏声明实体（必须 fail-fast）。 */
        public boolean hasUndeclared() {
            return !undeclared.isEmpty();
        }
    }

    /**
     * 静态纯判定：把实体类分成「已隔离 / 有意共享 / 未声明」三类。
     *
     * @param entityTypes 待检查的实体类集合，允许为 null
     * @return 分类结果，绝不返回 null
     */
    public static Declaration assess(final Collection<Class<?>> entityTypes) {
        final List<String> isolated = new ArrayList<>();
        final List<String> shared = new ArrayList<>();
        final List<String> undeclared = new ArrayList<>();
        if (entityTypes == null) {
            return new Declaration(List.of(), List.of(), List.of());
        }
        for (final Class<?> type : entityTypes) {
            if (type == null) {
                continue;
            }
            final Filter filter = type.getAnnotation(Filter.class);
            if (filter != null && TenantJpaFilterDefinition.NAME.equals(filter.name())) {
                isolated.add(type.getSimpleName());
                continue;
            }
            final TenantShared sharedAnnotation = type.getAnnotation(TenantShared.class);
            if (sharedAnnotation != null) {
                shared.add("  - " + type.getSimpleName() + " → " + sharedAnnotation.reason());
                continue;
            }
            undeclared.add(type.getSimpleName());
        }
        return new Declaration(List.copyOf(isolated), List.copyOf(shared), List.copyOf(undeclared));
    }

    /** 生成 fail-fast 的可读消息，含修复指引。 */
    public static String describeUndeclared(final Declaration declaration) {
        return "多租户开关已开启，但以下 JPA 实体未声明数据层归属：" + declaration.undeclared()
                + "。请为每个实体挂 @Filter(name = \"" + TenantJpaFilterDefinition.NAME
                + "\") 走行级隔离，或标 @TenantShared(reason = \"...\") 显式声明为全局共享；"
                + "静默漏挂会导致「假隔离」（部分表不隔离），因此拒绝启动。";
    }
}
