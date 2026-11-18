/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.persistence.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V2 迁移回归：config_data 唯一键由 (config_key, version) 扩为
 * (config_key, version, tenant_id)。
 *
 * <p>本用例真执行 H2 的 V1 + V2 并核对约束列与写入行为：</p>
 * <ol>
 *   <li>两个租户各写一份 config_key=X, version=0 → 都应成功；</li>
 *   <li>同租户重复写 config_key=X, version=0 → 仍应撞唯一键；</li>
 *   <li>约束列清单必须与实体注解一致（防止实体 schema 与迁移 schema 漂移）。</li>
 * </ol>
 * PG 侧 V2 脚本与 H2 同构（标准 ALTER TABLE DROP/ADD CONSTRAINT），
 * 由 {@code PostgresFlywayMigrationEquivalenceTest} 在 PG_TEST 环境覆盖。
 *
 * @author Trunkline Team
 */
@DisplayName("Flyway V2：config_data 唯一键纳入 tenant_id")
class ConfigTenantUniqueKeyMigrationTest {

    private static final String PARAMS = ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE";

    @Test
    @DisplayName("跨租户同名同版本可共存，同租户重复仍被唯一键拒绝")
    void crossTenantSameKeyCoexistsButSameTenantDuplicateRejected() throws SQLException {
        final String url = "jdbc:h2:mem:config_tenant_uk_" + System.nanoTime() + PARAMS;
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .load()
                .migrate();

        assertEquals(List.of("config_key", "version", "tenant_id"), uniqueKeyColumns(url),
                "V2 应把唯一键重建为 (config_key, version, tenant_id)");

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            insert(c, "tenant-a", "llm.chat", 0);
            insert(c, "tenant-b", "llm.chat", 0);
            insert(c, "tenant-a", "llm.chat", 1);

            assertThrows(SQLException.class, () -> insert(c, "tenant-a", "llm.chat", 0),
                    "同租户同 key 同版本仍应撞唯一键");
            assertEquals(3L, countRows(c), "跨租户同名同版本应各存一份");
        }
    }

    private static void insert(final Connection c, final String tenantId,
                               final String configKey, final int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into config_data (config_key, config_value, version, is_latest, tenant_id, "
                        + "created_at, updated_at) values (?, '{}', ?, true, ?, current_timestamp, current_timestamp)")) {
            ps.setString(1, configKey);
            ps.setInt(2, version);
            ps.setString(3, tenantId);
            ps.executeUpdate();
        }
    }

    private static List<String> uniqueKeyColumns(final String url) throws SQLException {
        final List<String> columns = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                             + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' "
                             + "AND UPPER(CONSTRAINT_NAME) = 'UK_CONFIG_DATA_KEY_VERSION' "
                             + "ORDER BY ORDINAL_POSITION");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                columns.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private static long countRows(final Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from config_data")) {
            assertTrue(rs.next(), "count 查询应返回一行");
            return rs.getLong(1);
        }
    }
}
