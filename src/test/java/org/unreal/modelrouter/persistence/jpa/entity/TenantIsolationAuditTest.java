/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa.entity;

import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.unreal.modelrouter.enterprise.config.TenantJpaFilterDefinition;

import jakarta.persistence.Entity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O9 租户隔离兜底审计：反射扫描 {@code persistence.jpa.entity} 包，
 * 任何含 {@code tenantId} 字段的实体必须满足其一——
 * 类上挂 {@code @Filter(name = "tenantFilter")}，或标 {@link TenantShared}（有意共享）。
 *
 * <p>防止后续新增实体静默漏挂行级隔离（QuotaLedgerEntity 曾漏挂，O9 修复）。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("O9 租户隔离反射审计")
class TenantIsolationAuditTest {

    @Test
    @DisplayName("含 tenantId 字段的实体必须挂 @Filter 或标 @TenantShared")
    void everyTenantScopedEntityIsFilteredOrShared() throws Exception {
        final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final Resource[] resources = resolver.getResources(
                "classpath*:org/unreal/modelrouter/persistence/jpa/entity/*.class");

        final List<String> violations = new ArrayList<>();
        int audited = 0;

        for (final Resource resource : resources) {
            final String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".class")
                    || filename.contains("$") || filename.startsWith("package-info")) {
                continue;
            }
            final String className = "org.unreal.modelrouter.persistence.jpa.entity."
                    + filename.substring(0, filename.length() - ".class".length());
            // 实体均在同一 jar，父加载器可见；不触发静态初始化（initialize=false）
            final Class<?> clazz = Class.forName(className, false, TenantShared.class.getClassLoader());
            if (!clazz.isAnnotationPresent(Entity.class)) {
                continue;
            }
            if (!hasTenantIdField(clazz)) {
                continue;
            }
            audited++;

            final Filter filter = clazz.getAnnotation(Filter.class);
            final boolean hasTenantFilter =
                    filter != null && TenantJpaFilterDefinition.NAME.equals(filter.name());
            if (hasTenantFilter || clazz.isAnnotationPresent(TenantShared.class)) {
                continue;
            }
            violations.add(clazz.getSimpleName());
        }

        // 与 O9 事实基线对齐：含 tenantId 的实体已知 8 个（7 个挂 @Filter + QuotaLedger 补挂）
        assertTrue(audited >= 8,
                "反射审计覆盖不足：期望至少 8 个含租户字段的实体，实际 " + audited
                        + "（包扫描路径或实体位置变更时请同步本阈值）");
        assertTrue(violations.isEmpty(),
                "含 tenantId 字段但未挂 @Filter 也未标 @TenantShared 的实体（O9 违规）: " + violations);
    }

    /** 沿父类链查找 tenantId 字段（实体可能继承基类） */
    private static boolean hasTenantIdField(final Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (final java.lang.reflect.Field field : c.getDeclaredFields()) {
                if ("tenantId".equals(field.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
