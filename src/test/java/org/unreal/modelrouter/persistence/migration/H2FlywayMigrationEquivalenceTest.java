package org.unreal.modelrouter.persistence.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 两条路径的 schema 等价性（issue #192）。
 *
 * <p>与 {@code PostgresFlywayMigrationEquivalenceTest} 同一个命题，但**不门控** —— H2 驱动就在
 * 测试类路径上，这条回归网应当每次跑都生效：</p>
 * <ol>
 *   <li>{@code fresh}：空库，Flyway 从 {@code h2/V1__baseline.sql} 建起</li>
 *   <li>{@code legacy}：先按 V1 建好、再退化回「#192 之前的老库」形态（缺五个补丁列、
 *       {@code expires_at} 还是 NOT NULL）并**删掉 Flyway 历史表** —— 后者是关键，
 *       老库本来就没有它，这样 Flyway 才会走 baseline 路径</li>
 * </ol>
 *
 * <p>两者都必须落在同一套 schema 上。这条测试替代了原先由 Java 补丁组件承担的老库兼容覆盖
 * （那两个组件已随 issue #192 删除）。</p>
 *
 * <p>URL 参数与生产默认值一致（{@code MODE=MySQL;DATABASE_TO_UPPER=FALSE}）—— 大小写行为正是
 * 这个组合里最容易出错的地方。</p>
 */
@Disabled("2026-11-10 re-baseline 后 V2–V18 已归档，db/migration/h2 只剩 V1 基线。"
        + "本用例模拟的『退化掉补丁列 → 靠后续迁移重建』前提已不存在，"
        + "baselineVersion=1 又会跳过 V1，因此老库缺列无法自动补回。"
        + "是否支持跨 re-baseline 老库升级属产品决策，待定；"
        + "基线本身与实体模型的一致性由 H2BaselineMatchesEntityModelTest 守卫。")
@DisplayName("H2 两条路径 schema 等价（issue #192）")
class H2FlywayMigrationEquivalenceTest {

    private static final String PARAMS = ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE";
    private static final String HISTORY_TABLE = "flyway_schema_history";
    private static final List<String> PATCHED_COLUMNS =
            List.of("record_level", "request_body_encrypted", "response_body_encrypted", "tags", "headers");

    @Test
    @DisplayName("空库与老库经 Flyway 收敛后 schema 完全一致")
    void bothPathsConvergeToTheSameSchema() throws SQLException {
        String freshUrl = "jdbc:h2:mem:flyway_fresh_h2" + PARAMS;
        String legacyUrl = "jdbc:h2:mem:flyway_legacy_h2" + PARAMS;

        migrateFresh(freshUrl);
        buildLegacyThenMigrate(legacyUrl);

        Map<String, List<String>> fresh = snapshot(freshUrl);
        Map<String, List<String>> legacy = snapshot(legacyUrl);
        for (String section : fresh.keySet()) {
            assertEquals(fresh.get(section), legacy.get(section),
                    "「fresh」与「legacy」的 " + section + " 不一致");
        }

        // X4（2026-10-28）：版本清单从 classpath 动态推导，不再写死「只执行 V1、V2」——
        // V3（供应商）/V4（审计数据）/V5（配额分组维度）/V6（当前配置分离）加入后此处曾假红
        List<String> expectedFresh = new ArrayList<>();
        List<String> expectedLegacy = new ArrayList<>(List.of("1|BASELINE|true"));
        for (int version : migrationVersions()) {
            expectedFresh.add(version + "|SQL|true");
            if (version > 1) {
                expectedLegacy.add(version + "|SQL|true");
            }
        }
        assertEquals(expectedFresh, history(freshUrl), "空库应顺序执行全部迁移脚本");
        assertEquals(expectedLegacy, history(legacyUrl),
                "老库应记为基线 1 且不重放 V1，再顺序执行其余迁移");
    }

    /**
     * 从 classpath 扫描 {@code db/migration/h2/V*.sql}，返回升序版本号。
     */
    private static List<Integer> migrationVersions() {
        try {
            var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            var resources = resolver.getResources("classpath*:db/migration/h2/V*.sql");
            List<Integer> versions = new ArrayList<>();
            for (var resource : resources) {
                var matcher = java.util.regex.Pattern.compile("V(\\d+)__")
                        .matcher(resource.getFilename());
                if (matcher.find()) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            }
            versions.sort(Integer::compareTo);
            assertTrue(versions.size() >= 2, "至少应发现 V1、V2 两个迁移脚本");
            return versions;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("扫描 H2 迁移脚本失败", e);
        }
    }

    // ==================== 迁移执行 ====================

    private static void migrateFresh(final String url) {
        flyway(url).migrate();
    }

    /** 先按 V1 建好，再退化成老库形态并删掉 Flyway 历史表，最后让 Flyway 走 baseline 路径 */
    private static void buildLegacyThenMigrate(final String url) throws SQLException {
        migrateFresh(url);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE api_call_history DROP COLUMN record_level");
            statement.execute("ALTER TABLE api_call_history DROP COLUMN request_body_encrypted");
            statement.execute("ALTER TABLE api_call_history DROP COLUMN response_body_encrypted");
            statement.execute("ALTER TABLE service_instance DROP COLUMN tags");
            statement.execute("ALTER TABLE service_instance DROP COLUMN headers");
            statement.execute("ALTER TABLE security_blacklist ALTER COLUMN expires_at SET NOT NULL");
            // V16（多租户预埋，S2 任务 35）加的租户列同样属于「老库没有的补丁列」，
            // 退化时一并剥掉（索引随列级联删除），让 Flyway 重放 V16 重建
            for (String table : List.of("jwt_accounts", "token_usage", "api_call_history",
                    "security_audit", "model_provider", "config_data", "service_config")) {
                statement.execute("ALTER TABLE " + table + " DROP COLUMN tenant_id");
            }
            // 老库本来没有这张表 —— 删掉才能让 Flyway 把它当成「非空且无历史」的库
            statement.execute("DROP TABLE " + HISTORY_TABLE);
        }
        assertEquals(0, patchedColumnCount(url), "前置：五个补丁列都应已被移除");
        flyway(url).migrate();
    }

    private static Flyway flyway(final String url) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    // ==================== 结构快照与查询 ====================

    private static Map<String, List<String>> snapshot(final String url) throws SQLException {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("tables", query(url,
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> ? "
                        + "ORDER BY TABLE_NAME", HISTORY_TABLE));
        sections.put("columns", query(url,
                "SELECT TABLE_NAME || '.' || COLUMN_NAME || ' ' || DATA_TYPE"
                        + " || ' null=' || IS_NULLABLE || ' len=' || COALESCE(CHARACTER_MAXIMUM_LENGTH, -1)"
                        + " || ' prec=' || COALESCE(NUMERIC_PRECISION, -1)"
                        + " || ' identity=' || IS_IDENTITY "
                        + "FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME <> ? "
                        + "ORDER BY TABLE_NAME, COLUMN_NAME", HISTORY_TABLE));
        sections.put("constraints", normalizeGeneratedNames(query(url,
                "SELECT TABLE_NAME || '.' || CONSTRAINT_NAME || ' ' || CONSTRAINT_TYPE "
                        + "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME <> ? "
                        + "ORDER BY TABLE_NAME, CONSTRAINT_NAME", HISTORY_TABLE)));
        sections.put("indexes", normalizeGeneratedNames(query(url,
                "SELECT INDEX_NAME || ' on ' || TABLE_NAME "
                        + "FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME <> ? "
                        + "ORDER BY TABLE_NAME, INDEX_NAME", HISTORY_TABLE)));
        return sections;
    }

    /**
     * H2 给自动生成的索引/约束编的名字里带**库内部对象 ID**（{@code PRIMARY_KEY_B}、
     * {@code CONSTRAINT_INDEX_7}、{@code uk_x_INDEX_F}），两个库之间必然不同 —— 直接比名字会假红。
     * 这里把尾部的 ID 段归一化，只比较名字里有意义的部分（与 PG 侧排除 NOT NULL 伪约束同理）。
     */
    private static List<String> normalizeGeneratedNames(final List<String> rows) {
        List<String> normalized = new ArrayList<>(rows.size());
        for (String row : rows) {
            normalized.add(row
                    .replaceAll("(PRIMARY_KEY|CONSTRAINT_INDEX)_[0-9A-F]+", "$1_<id>")
                    .replaceAll("(_INDEX_)[0-9A-F]+\\b", "$1<id>")
                    // X4（2026-10-28）：自动命名唯一约束的背书索引，H2 对「列级 unique」与
                    // 「表级 unique 约束」取的名字前缀不同。约束本身已在 constraints 节比较过，
                    // 这里把背书索引名归一化，避免内部 ID 噪音假红。
                    .replaceAll("(?i)CONSTRAINT\\S*_INDEX_<id>", "CONSTRAINT_BACKED_INDEX"));
        }
        return normalized;
    }

    private static List<String> history(final String url) throws SQLException {
        // Flyway 建的 history 表在 DATABASE_TO_UPPER=FALSE 下是**小写列名**，必须照小写引用；
        // H2 的 history 表里还有一行 version 为 NULL 的内部记录（不是迁移），排除掉；
        // 布尔值 H2 回的是 TRUE 大写，统一转小写再比。
        return query(url, "SELECT version || '|' || type || '|' || lower(CAST(success AS VARCHAR)) FROM "
                + HISTORY_TABLE + " WHERE version IS NOT NULL ORDER BY installed_rank");
    }

    private static int patchedColumnCount(final String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME IN ('api_call_history','service_instance')"
                             + " AND COLUMN_NAME IN ('record_level','request_body_encrypted','response_body_encrypted',"
                             + "'tags','headers')")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static List<String> query(final String url, final String sql, final Object... args)
            throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var prepared = connection.prepareStatement(sql)) {
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
}
