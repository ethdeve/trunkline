/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单一 V1 基线守卫（2026-11-14 零存量决策）。
 *
 * <p>项目尚未发布、无生产环境，用户确认不存在任何已执行过旧 V1 的库，因此：</p>
 * <ul>
 *   <li>不保留 {@code V2__*} / {@code V3__*} 增量迁移：#653 的过滤索引与 O1/O2 的
 *       四个成交价快照列已直接写进 {@code V1__baseline.sql}；</li>
 *   <li>不保留 {@code db/stock-upgrade/} 存量升级包：B4 那套「旧 V1 + V4 拆表」脚本整体删除；</li>
 *   <li>默认扫描目录 {@code db/migration/{h2,postgres}} 各只剩一个 {@code V1__baseline.sql}，
 *       空库迁移历史只有一条 {@code 1|SQL|true}。</li>
 * </ul>
 *
 * <p>本用例替代 B4 的 {@code FlywayMigrationTest}（后者专门验存量库 V1→V4，前提已不存在）。
 * 基线脚本与实体模型的逐项一致性另由 {@code H2BaselineMatchesEntityModelTest} 与 PG 启动期
 * {@code ddl-auto: validate} 守护。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("Flyway 单一 V1 基线（零存量）")
class SingleBaselineMigrationTest {

    private static final String PARAMS = ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE";
    private static final Pattern VERSION_PATTERN = Pattern.compile("V(\\d+)__");

    @Test
    @DisplayName("默认扫描目录只有 V1，增量脚本与存量升级包均已移除")
    void onlySingleBaselineExists() throws IOException {
        for (String dialect : List.of("h2", "postgres")) {
            assertEquals(List.of(1), migrationVersions(dialect),
                    dialect + " 路径应只剩 V1__baseline.sql；未发版期间 schema 变更直接改 V1，"
                            + "不要新增增量脚本（若本地构建目录有残留旧脚本，请先清理 target/classes）");
            assertNull(getClass().getResource("/db/migration/" + dialect
                            + "/V4__split_model_price_to_quota.sql"),
                    "存量升级脚本不得进入默认扫描目录");
            assertNull(getClass().getResource("/db/stock-upgrade/" + dialect + "/V1__baseline.sql"),
                    "零存量决策后不应再有存量升级包");
            assertNull(getClass().getResource("/db/stock-upgrade/" + dialect
                            + "/V4__split_model_price_to_quota.sql"),
                    "零存量决策后不应再有存量升级包");
        }
    }

    @Test
    @DisplayName("空库只执行 V1：四快照列、过滤索引、model_quota 齐备，无 model_price")
    void freshInstallRunsOnlyV1() throws SQLException {
        final String url = "jdbc:h2:mem:single_baseline_" + System.nanoTime() + PARAMS;
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();

        assertEquals(List.of("1|SQL|true"), history(url),
                "零存量基线：Flyway 历史应只有一条成功执行的 V1");

        assertTrue(tableExists(url, "MODEL_QUOTA"), "开源额度表必须建在 V1 基线里");
        assertFalse(tableExists(url, "MODEL_PRICE"), "单价属商业能力，开源基线不得含 model_price");
        for (String column : List.of("PRICE_INPUT_SNAPSHOT", "PRICE_OUTPUT_SNAPSHOT",
                "PRICE_VERSION", "PRICE_MISSING")) {
            assertTrue(columnExists(url, "TOKEN_USAGE", column),
                    "成交价快照列 " + column + " 必须由 V1 直接建出（原 V3 已并入）");
        }
        assertTrue(indexExists(url, "IDX_CB_METRICS_ADJUSTMENT_APPLIED"),
                "#653 过滤索引必须由 V1 直接建出（原 V2 已并入）");
    }

    // ==================== helpers ====================

    private static List<Integer> migrationVersions(final String dialect) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/" + dialect + "/V*.sql");
        List<Integer> versions = new ArrayList<>();
        for (Resource resource : resources) {
            Matcher matcher = VERSION_PATTERN.matcher(String.valueOf(resource.getFilename()));
            if (matcher.find()) {
                versions.add(Integer.parseInt(matcher.group(1)));
            }
        }
        versions.sort(Integer::compareTo);
        return versions;
    }

    private static List<String> history(final String url) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT version || '|' || type || '|' "
                     + "|| lower(CAST(success AS VARCHAR)) FROM flyway_schema_history "
                     + "WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static long count(final String url, final String sql, final String... args)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             var prepared = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                prepared.setString(i + 1, args[i]);
            }
            try (ResultSet rs = prepared.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static boolean tableExists(final String url, final String table) throws SQLException {
        return count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC' AND UPPER(TABLE_NAME) = ?", table) > 0;
    }

    private static boolean columnExists(final String url, final String table,
                                        final String column) throws SQLException {
        return count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?", table, column) > 0;
    }

    private static boolean indexExists(final String url, final String index) throws SQLException {
        return count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                + "WHERE UPPER(INDEX_NAME) = ?", index) > 0;
    }
}
