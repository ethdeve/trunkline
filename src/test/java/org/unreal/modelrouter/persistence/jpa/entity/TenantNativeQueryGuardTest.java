/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Entity;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户隔离实体原生 SQL 守卫（体检报告 P0 第 3 点回归守卫）。
 *
 * <p>背景：Hibernate {@code @Filter} <b>不作用于</b> {@code @Query(nativeQuery = true)} /
 * JDBC / {@code EntityManager.createNativeQuery}。一旦某个租户隔离实体（{@code @Filter} 标注）
 * 上出现裸 native 查询，该查询会绕过行级隔离，导致跨租户数据串扰。</p>
 *
 * <p>本测试在 CI 静态拦截：扫描 {@code persistence.jpa.repository} 下全部 Spring Data 仓储，
 * 若发现某仓储的领域实体是租户隔离实体（{@code @Filter}），但其上挂了 {@code nativeQuery = true}
 * 的方法，立即失败。同时做一道兜底：任何 native SQL 文本若直接点名五张租户表
 * （{@code config_data / service_config / quota_ledger / token_usage / api_call_history}），
 * 也判违规。</p>
 *
 * <p>当前实测：开源主代码对五张租户表的查询<b>全部为 JPQL</b>（经 Hibernate，受 {@code @Filter} 约束），
 * {@code nativeQuery = true} 命中数为 0，故本测试当前为绿。其作用是在将来有人误加 native 查询时
 * 提前熔断，而非在私有化客户现场开启多租户后才暴露。私有化单租户（{@code tenant_id} 恒为
 * {@code default}）下即便漏过也无害，但本守卫保证「真正启用多租户前」零盲区。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("租户隔离实体原生 SQL 守卫")
class TenantNativeQueryGuardTest {

    private static final String REPO_BASE_PACKAGE = "org.unreal.modelrouter.persistence.jpa.repository";

    /** 五张租户业务表（带 tenant_id + @Filter） */
    private static final Set<String> TENANT_TABLES = Set.of(
            "config_data", "service_config", "quota_ledger", "token_usage", "api_call_history");

    @Test
    @DisplayName("租户隔离实体上不得出现绕过 @Filter 的裸 native 查询")
    void noTenantEntityHasBypassingNativeQuery() {
        final ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(JpaRepository.class));

        final Set<String> violations = new LinkedHashSet<>();
        scanner.findCandidateComponents(REPO_BASE_PACKAGE)
                .forEach(def -> {
                    try {
                        final Class<?> repo = Class.forName(def.getBeanClassName());
                        final Class<?> entity = resolveEntityType(repo);
                        if (entity == null || entity.getAnnotation(Entity.class) == null) {
                            return;
                        }
                        final boolean tenantIsolated = entity.isAnnotationPresent(Filter.class);
                        for (final Method m : repo.getMethods()) {
                            final Query q = m.getAnnotation(Query.class);
                            if (q == null || !q.nativeQuery()) {
                                continue;
                            }
                            if (tenantIsolated) {
                                violations.add(repo.getSimpleName() + "#" + m.getName()
                                        + "：租户隔离实体上的 native 查询会绕过 @Filter");
                            }
                            final String sql = q.value().toLowerCase();
                            if (TENANT_TABLES.stream().anyMatch(sql::contains)) {
                                violations.add(repo.getSimpleName() + "#" + m.getName()
                                        + "：native SQL 直接点名租户表 " + sql.replaceAll("\\s+", " ").substring(0,
                                        Math.min(sql.length(), 80)));
                            }
                        }
                    } catch (final ClassNotFoundException e) {
                        throw new IllegalStateException("无法加载仓储类：" + def.getBeanClassName(), e);
                    }
                });

        assertTrue(violations.isEmpty(),
                "发现可能绕过租户隔离的裸 native 查询（@Filter 不作用于 native SQL）：\n"
                        + String.join("\n", violations));
    }

    /** 从 Spring Data 仓储的泛型父接口中解析领域实体类型。 */
    private static Class<?> resolveEntityType(final Class<?> repo) {
        for (final Type type : repo.getGenericInterfaces()) {
            if (type instanceof ParameterizedType pt) {
                final Type raw = pt.getRawType();
                if (raw == JpaRepository.class || raw == CrudRepository.class) {
                    final Type entityType = pt.getActualTypeArguments()[0];
                    if (entityType instanceof Class) {
                        return (Class<?>) entityType;
                    }
                }
            }
        }
        for (final Class<?> superInterface : repo.getInterfaces()) {
            final Class<?> resolved = resolveEntityType(superInterface);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }
}
