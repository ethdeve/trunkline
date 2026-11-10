/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAccountRoleMigration 单元测试（P0-2：存量账户的废弃角色 VIEWER → USER 迁移）
 *
 * <p>覆盖：持有 VIEWER 的账户被迁移为 USER；不含 VIEWER 的账户不被写库（幂等）；
 * 多角色账户保留其它角色；脏 JSON 跳过不抛错。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("JwtAccountRoleMigration 测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAccountRoleMigrationTest {

    @Mock
    private JwtAccountRepository jwtAccountRepository;

    private JwtAccountRoleMigration migration;

    @BeforeEach
    void setUp() {
        migration = new JwtAccountRoleMigration(jwtAccountRepository, new ObjectMapper());
    }

    private static JwtAccountEntity account(final String username, final String rolesJson) {
        return JwtAccountEntity.builder()
                .id(1L)
                .username(username)
                .password("encoded")
                .roles(rolesJson)
                .enabled(true)
                .build();
    }

    @Nested
    @DisplayName("废弃角色迁移")
    class MigrateTests {

        @Test
        @DisplayName("持有 VIEWER 的账户被迁移为 USER")
        void viewerAccountMigratedToUser() {
            final JwtAccountEntity account = account("viewer-user", "[\"VIEWER\"]");
            when(jwtAccountRepository.findAll()).thenReturn(List.of(account));

            assertEquals(1, migration.migrate());

            assertEquals("[\"USER\"]", account.getRoles());
            verify(jwtAccountRepository).save(account);
        }

        @Test
        @DisplayName("VIEWER 与其它角色共存时，VIEWER 被替换为 USER，其它角色保留")
        void viewerReplacedWhileOtherRolesKept() {
            final JwtAccountEntity account = account("mixed", "[\"ADMIN\",\"VIEWER\"]");
            when(jwtAccountRepository.findAll()).thenReturn(List.of(account));

            assertEquals(1, migration.migrate());

            assertEquals("[\"ADMIN\",\"USER\"]", account.getRoles());
        }

        @Test
        @DisplayName("已同时持有 USER 与 VIEWER 时去重为单个 USER")
        void viewerAndUserDeduplicated() {
            final JwtAccountEntity account = account("both", "[\"USER\",\"VIEWER\"]");
            when(jwtAccountRepository.findAll()).thenReturn(List.of(account));

            assertEquals(1, migration.migrate());

            assertEquals("[\"USER\"]", account.getRoles());
        }

        @Test
        @DisplayName("不含 VIEWER 的账户不被写库（幂等）")
        void accountWithoutViewerNotWritten() {
            final JwtAccountEntity account = account("normal", "[\"ADMIN\",\"USER\"]");
            when(jwtAccountRepository.findAll()).thenReturn(List.of(account));

            assertEquals(0, migration.migrate());

            assertEquals("[\"ADMIN\",\"USER\"]", account.getRoles());
            verify(jwtAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("角色为 null 或空串的账户被跳过")
        void nullRolesSkipped() {
            final JwtAccountEntity account = account("no-roles", null);
            when(jwtAccountRepository.findAll()).thenReturn(List.of(account));

            assertEquals(0, migration.migrate());

            verify(jwtAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("角色 JSON 非法时跳过该账户，不影响其它账户迁移")
        void malformedJsonSkippedWithoutFailure() {
            final JwtAccountEntity broken = account("broken", "not-json");
            final JwtAccountEntity normal = account("viewer-user", "[\"VIEWER\"]");
            when(jwtAccountRepository.findAll()).thenReturn(List.of(broken, normal));

            assertEquals(1, migration.migrate());

            assertEquals("[\"USER\"]", normal.getRoles());
            verify(jwtAccountRepository, never()).save(broken);
            verify(jwtAccountRepository).save(normal);
        }

        @Test
        @DisplayName("小写 viewer 同样被识别并迁移")
        void lowercaseViewerAlsoMigrated() {
            final JwtAccountEntity account = account("lower", "[\"viewer\"]");
            when(jwtAccountRepository.findAll()).thenReturn(List.of(account));

            assertEquals(1, migration.migrate());

            assertEquals("[\"USER\"]", account.getRoles());
        }
    }

    @Nested
    @DisplayName("DeprecatedRoles 归一化")
    class DeprecatedRolesTests {

        @Test
        @DisplayName("VIEWER 归一化为 USER，其余角色转大写")
        void normalizeRoleMapsViewerToUser() {
            assertEquals("USER", DeprecatedRoles.normalizeRole("VIEWER"));
            assertEquals("USER", DeprecatedRoles.normalizeRole("viewer"));
            assertEquals("ADMIN", DeprecatedRoles.normalizeRole("admin"));
            assertEquals("", DeprecatedRoles.normalizeRole(null));
            assertEquals("", DeprecatedRoles.normalizeRole("  "));
        }

        @Test
        @DisplayName("migrateRoles 替换废弃角色并按大小写不敏感去重")
        void migrateRolesReplacesAndDeduplicates() {
            assertEquals(List.of("USER"), DeprecatedRoles.migrateRoles(List.of("VIEWER")));
            assertEquals(List.of("USER"), DeprecatedRoles.migrateRoles(List.of("USER", "viewer")));
            assertEquals(List.of("ADMIN", "USER"),
                    DeprecatedRoles.migrateRoles(List.of("ADMIN", "VIEWER")));
            assertEquals(List.of(), DeprecatedRoles.migrateRoles(null));
            assertTrue(DeprecatedRoles.isDeprecated("Viewer"));
        }
    }
}
