package org.unreal.modelrouter.persistence.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 单一基线下两条路径的 schema 等价性（issue #192）。
 *
 * <p>项目尚未发布、无生产环境（2026-11-14 用户确认零存量），因此不开增量迁移：
 * {@code db/migration/{h2,postgres}} 各只剩单一 {@code V1__baseline.sql}。#653 的
 * {@code adjustment_applied} 过滤索引（原 V2）与四个成交价快照列（原 V3）已并入 V1，
 * 存量升级包 {@code db/stock-upgrade/} 也已整体删除。本测试守两件事：</p>
 * <ol>
 *   <li>同一份 V1 基线经两条路径建立后 schema 完全一致 —— {@code fresh} 由 Flyway 从头执行 V1
 *       （历史记 {@code 1|SQL}），{@code legacy_v1} 先手工按 V1 建库再让 baseline-on-migrate 记为
 *       基线 1（历史记 {@code 1|BASELINE}，V1 不重放）；</li>
 *   <li>两条路径都有 {@code model_quota} 表（开源基础能力：厂商模型额度与限速），
 *       守住额度配置确实进了 V1 基线，且单价（商业能力）已从开源版移除。</li>
 * </ol>
 *
 * <p>与 {@code H2FlywayMigrationEquivalenceTest} 的区别：后者模拟「老库缺补丁列→靠后续迁移补回」，
 * re-baseline 后该前提已不存在，故保持 {@code @Disabled}；本测试验证的是「单一基线、无增量」场景的
 * 两路径一致性，前提仍成立，故继续保留。跨 re-baseline 老库升级仍属产品决策，另行评估。</p>
 *
 * <p>基线脚本与实体模型的一致性由 PG 启动期 {@code ddl-auto: validate} 兜底（见
 * {@code H2BaselineMatchesEntityModelTest} 类注释），本测试不重复该职责。</p>
 *
 * <p>门控与仓库既有的 {@code PostgresCompatibilityIntegrationTest} 一致（{@code PG_TEST=true}），
 * CI 里由 {@code java-tests.yml} 的「校验门控测试未被跳过」一并看住。</p>
 */
@EnabledIfEnvironmentVariable(named = "PG_TEST", matches = "true")
@DisplayName("Flyway 单一 V1 基线两路径 schema 一致（PG_TEST=true 时执行，issue #192）")
class PostgresFlywayMigrationEquivalenceTest {

    private static final String URL =
            env("PG_TEST_URL", "jdbc:postgresql://127.0.0.1:15432/postgres");
    private static final String USERNAME = env("PG_TEST_USERNAME", "postgres");
    private static final String PASSWORD = env("PG_TEST_PASSWORD", "postgres");

    private static final String FRESH = "flyway_probe_fresh";
    private static final String LEGACY_V1 = "flyway_probe_legacy_v1";
    private static final String HISTORY_TABLE = "flyway_schema_history";

    @Test
    @DisplayName("空库 Flyway 建库 与 手工按 V1 建库后 baseline，schema 完全一致")
    void bothPathsConvergeToTheSameSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD)) {
            dropSchema(connection, FRESH);
            dropSchema(connection, LEGACY_V1);

            migrateEmptySchema(connection, FRESH);
            migrateLegacySchema(connection, LEGACY_V1);

            Map<String, List<String>> fresh = snapshot(connection, FRESH);
            Map<String, List<String>> legacy = snapshot(connection, LEGACY_V1);
            for (String section : fresh.keySet()) {
                assertEquals(fresh.get(section), legacy.get(section),
                        "「" + FRESH + "」与「" + LEGACY_V1 + "」的 " + section + " 不一致");
            }
            // 反向也断言一次，避免只有一边为空时的假等价
            assertEquals(fresh.keySet(), legacy.keySet(), "快照分节不同");

            // 两条路径都必须建出 model_quota 表（开源基础能力：厂商模型额度与限速）
            assertTrue(fresh.get("columns").stream()
                            .anyMatch(c -> c.startsWith("model_quota.model_name ")),
                    "空库执行 V1 后应含 model_quota.model_name（开源额度表已并入基线）");
            assertTrue(legacy.get("columns").stream()
                            .anyMatch(c -> c.startsWith("model_quota.model_name ")),
                    "手工按 V1 建库后应含 model_quota.model_name（开源额度表已并入基线）");

            // [BREAKING-SCHEMA] P0-2：单价已整体移出开源版，两条路径都不得再出现 model_price 表
            assertFalse(fresh.get("columns").stream()
                            .anyMatch(c -> c.startsWith("model_price.")),
                    "空库执行 V1 后不应存在 model_price（单价属商业能力，已拆表）");
            assertFalse(legacy.get("columns").stream()
                            .anyMatch(c -> c.startsWith("model_price.")),
                    "手工按 V1 建库后不应存在 model_price（单价属商业能力，已拆表）");

            // 原 V3 的成交价快照列、原 V2 的过滤索引已并入 V1，两条路径都必须直接建出
            for (Map<String, List<String>> schema : List.of(fresh, legacy)) {
                for (String column : List.of("price_input_snapshot", "price_output_snapshot",
                        "price_version", "price_missing")) {
                    assertTrue(schema.get("columns").stream()
                                    .anyMatch(c -> c.startsWith("token_usage." + column + " ")),
                            column + " 必须由 V1 基线直接建出，不得依赖已删除的增量脚本");
                }
                assertTrue(schema.get("indexes").stream()
                                .anyMatch(i -> i.contains("idx_cb_metrics_adjustment_applied")),
                        "#653 过滤索引必须由 V1 基线直接建出");
            }

            // 迁移历史：单一基线 —— 空库记 1|SQL；老库记基线 1（V1 不重放），无后续增量
            assertEquals(List.of("1|SQL|true"), history(connection, FRESH),
                    "空库应只执行 V1（单一基线）");
            assertEquals(List.of("1|BASELINE|true"), history(connection, LEGACY_V1),
                    "老库应记为基线 1 且不重放 V1（单一基线，无增量可执行）");
        }
    }

    /**
     * 退出时必须清掉探针 schema，这不只是整洁问题：它们各自包含一套与 {@code public}
     * 同名的表，留下影子表会让同一库上运行的其它门控测试（如 {@code PostgresCompatibilityIntegrationTest}）
     * 读到不该读的对象而失败 —— 这是 issue #216 当时暴露出来的教训，清理仍是必须的。
     */
    @AfterAll
    static void dropProbeSchemas() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD)) {
            for (String schema : List.of(FRESH, LEGACY_V1)) {
                dropSchema(connection, schema);
            }
        }
    }

    // ==================== 迁移执行 ====================

    /** 空 schema：Flyway 从 V1 建起（对应全新数据库，历史记 1|SQL） */
    private static void migrateEmptySchema(final Connection connection, final String schema)
            throws SQLException {
        createSchema(connection, schema);
        flyway(schema).migrate();
    }

    /** 老库：先手工按 V1 建好（无历史表），再让 Flyway 走 baseline-on-migrate 记为基线 1 */
    private static void migrateLegacySchema(final Connection connection, final String schema)
            throws SQLException {
        createSchema(connection, schema);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute(baselineScript());
        } finally {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO public");
            }
        }
        flyway(schema).migrate();
    }

    private static Flyway flyway(final String schema) {
        return Flyway.configure()
                .dataSource(URL, USERNAME, PASSWORD)
                .schemas(schema)
                .defaultSchema(schema)
                // 让脚本里的非限定表名落到被测 schema，而不是连接的默认 schema
                .initSql("SET search_path TO " + schema)
                .locations("classpath:db/migration/postgres")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    private static String baselineScript() {
        try (InputStream in = PostgresFlywayMigrationEquivalenceTest.class
                .getResourceAsStream("/db/migration/postgres/V1__baseline.sql")) {
            assertTrue(in != null, "classpath 上找不到 V1__baseline.sql");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取 V1 基线脚本失败", e);
        }
    }

    // ==================== 结构快照 ====================

    private static Map<String, List<String>> snapshot(final Connection connection, final String schema)
            throws SQLException {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("tables", query(connection,
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_type = 'BASE TABLE' AND table_name <> ? "
                        + "ORDER BY table_name", schema, HISTORY_TABLE));
        sections.put("columns", query(connection,
                "SELECT table_name || '.' || column_name || ' ' || data_type || '/' || udt_name"
                        + " || ' null=' || is_nullable || ' len=' || coalesce(character_maximum_length, -1)"
                        + " || ' prec=' || coalesce(numeric_precision, -1)"
                        + " || ' scale=' || coalesce(numeric_scale, -1)"
                        + " || ' dtprec=' || coalesce(datetime_precision, -1)"
                        + " || ' identity=' || is_identity "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name <> ? "
                        + "ORDER BY table_name, column_name", schema, HISTORY_TABLE));
        sections.put("indexes", query(connection,
                "SELECT indexname || ' :: ' || replace(replace(indexdef, '\"" + schema + "\".', ''), '"
                        + schema + ".', '') "
                        + "FROM pg_indexes WHERE schemaname = ? AND tablename <> ? "
                        + "ORDER BY indexname", schema, HISTORY_TABLE));
        sections.put("checkConstraints", query(connection,
                "SELECT tc.table_name || '.' || cc.constraint_name || ' ' || cc.check_clause "
                        + "FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.check_constraints cc "
                        + "  ON cc.constraint_name = tc.constraint_name "
                        + " AND cc.constraint_schema = tc.constraint_schema "
                        + "WHERE tc.table_schema = ? AND tc.constraint_type = 'CHECK' AND tc.table_name <> ? "
                        // PostgreSQL 把 NOT NULL 也暴露成 check 约束，其自动名里带表 OID
                        // （形如 17077_17088_11_not_null），跨 schema 不可比 —— 而可空性已由
                        // columns 分节的 is_nullable 覆盖，这里只比有稳定名字的枚举 CHECK。
                        + "  AND cc.check_clause NOT LIKE '% IS NOT NULL' "
                        + "ORDER BY 1", schema, HISTORY_TABLE));
        return sections;
    }

    private static List<String> history(final Connection connection, final String schema)
            throws SQLException {
        return query(connection,
                "SELECT version || '|' || type || '|' || success FROM " + schema + "." + HISTORY_TABLE
                        + " ORDER BY installed_rank");
    }

    private static List<String> query(final Connection connection, final String sql, final Object... args)
            throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var prepared = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                prepared.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = prepared.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
        }
        return rows;
    }

    // ==================== 辅助 ====================

    private static void createSchema(final Connection connection, final String schema)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private static void dropSchema(final Connection connection, final String schema)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static String env(final String name, final String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
