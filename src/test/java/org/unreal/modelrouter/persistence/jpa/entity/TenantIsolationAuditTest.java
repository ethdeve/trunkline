/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.unreal.modelrouter.enterprise.config.TenantIsolationStartupGuard;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全实体租户归属覆盖审计（体检报告 P0 回归守卫）。
 *
 * <p>与 {@link TenantIsolationStartupGuard} 共用同一套判定：反射扫描所有含 {@code @Entity} 的包，
 * 任何一个实体若既未挂 {@code @Filter(name = "tenantFilter")} 也未标 {@link TenantShared}，
 * 测试失败。这样「新实体静默漏挂 / 漏声明」在 CI 就会被拦住，而不是等到生产开启多租户后
 * 才发现「假隔离」。</p>
 *
 * <p>历史：O9 只审计「已含 tenantId 字段」的实体，对完全没有租户字段的实体不检查，
 * 因此 12 张表长期处于未覆盖状态。现改为全实体审计。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("全实体租户归属覆盖审计")
class TenantIsolationAuditTest {

    /** 实体类所在包（新增实体包时必须同步登记，否则本测试覆盖不到） */
    private static final List<String> ENTITY_PACKAGES = List.of(
            "org.unreal.modelrouter.persistence.jpa.entity",
            "org.unreal.modelrouter.auth.audit");

    @Test
    @DisplayName("所有 @Entity 都必须声明：@Filter 行级隔离 或 @TenantShared 全局共享")
    void everyEntityDeclaresTenancy() throws Exception {
        final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final List<Class<?>> entities = new ArrayList<>();
        for (final String pkg : ENTITY_PACKAGES) {
            final Resource[] resources = resolver.getResources(
                    "classpath*:" + pkg.replace('.', '/') + "/*.class");
            for (final Resource resource : resources) {
                final String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".class")
                        || filename.contains("$") || filename.startsWith("package-info")) {
                    continue;
                }
                final String className = pkg + "."
                        + filename.substring(0, filename.length() - ".class".length());
                // 不触发静态初始化，避免扫描时拉起无关依赖
                final Class<?> clazz = Class.forName(className, false, TenantShared.class.getClassLoader());
                if (clazz.isAnnotationPresent(Entity.class)) {
                    entities.add(clazz);
                }
            }
        }

        final TenantIsolationStartupGuard.Declaration declaration =
                TenantIsolationStartupGuard.assess(entities);

        // 审计覆盖下限：当前基线 21 个实体，包路径或实体迁移时须同步本阈值
        assertTrue(entities.size() >= 21,
                "反射审计覆盖不足：期望至少 21 个 @Entity，实际 " + entities.size()
                        + "（新增实体包时请同步 ENTITY_PACKAGES 与本阈值）");
        assertEquals(List.of(), declaration.undeclared(),
                "以下实体未声明数据层归属（会构成假隔离）：" + declaration.undeclared()
                        + "。请挂 @Filter 或标 @TenantShared(reason=...)");
        assertTrue(declaration.isolated().size() >= 4,
                "行级隔离实体数量下降：当前仅 4 个（ApiCallHistory / Config / QuotaLedger / TokenUsage），"
                        + "其余均按「上游基础设施 / 身份 / 运维审计 / 全局字典」平面全局共享，实际 "
                        + declaration.isolated().size()
                        + "。注意 ServiceConfig 已改为全局共享（模型分类是协议事实），不再计入隔离集");
    }
}
