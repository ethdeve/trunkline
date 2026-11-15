/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.enterprise.config;

import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.persistence.jpa.entity.TenantShared;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TenantIsolationStartupGuard#assess} 静态纯判定单测。 */
@DisplayName("租户隔离启动守卫判定")
class TenantIsolationStartupGuardTest {

    @Filter(name = TenantJpaFilterDefinition.NAME)
    private static class IsolatedEntity {
    }

    @TenantShared(reason = "测试用全局共享表")
    private static class SharedEntity {
    }

    private static class UndeclaredEntity {
    }

    @Test
    @DisplayName("三类实体被正确分类，且共享实体带出 reason")
    void classifyAllDeclaredEntities() {
        final TenantIsolationStartupGuard.Declaration declaration =
                TenantIsolationStartupGuard.assess(
                        List.of(IsolatedEntity.class, SharedEntity.class));

        assertEquals(List.of("IsolatedEntity"), declaration.isolated());
        assertEquals(1, declaration.shared().size());
        assertTrue(declaration.shared().get(0).contains("测试用全局共享表"));
        assertFalse(declaration.hasUndeclared());
    }

    @Test
    @DisplayName("未声明归属的实体被识别，fail-fast 消息含实体名与修复指引")
    void detectUndeclaredEntity() {
        final TenantIsolationStartupGuard.Declaration declaration =
                TenantIsolationStartupGuard.assess(List.of(UndeclaredEntity.class));

        assertTrue(declaration.hasUndeclared());
        assertEquals(List.of("UndeclaredEntity"), declaration.undeclared());
        final String message = TenantIsolationStartupGuard.describeUndeclared(declaration);
        assertTrue(message.contains("UndeclaredEntity"));
        assertTrue(message.contains("假隔离"));
        assertTrue(message.contains("@TenantShared"));
    }

    @Test
    @DisplayName("null 集合返回空结果，不抛异常")
    void nullInputIsSafe() {
        final TenantIsolationStartupGuard.Declaration declaration =
                TenantIsolationStartupGuard.assess(null);

        assertFalse(declaration.hasUndeclared());
        assertTrue(declaration.isolated().isEmpty());
        assertTrue(declaration.shared().isEmpty());
    }
}
