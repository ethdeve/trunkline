package org.unreal.modelrouter.persistence.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H2 基线脚本与实体模型一致（issue #192）。
 *
 * <p><b>为什么需要这条测试</b>：PostgreSQL 路径靠 {@code ddl-auto: validate} 兜底 —— 脚本与实体不一致
 * 时启动直接失败。H2 路径**没有这层保险**（validate 在 {@code DATABASE_TO_UPPER=FALSE} 下查不到对象，
 * 见 {@code FlywayDialectEnvironmentPostProcessor} 类注释），保留的是 {@code update}，而 Hibernate 的
 * {@code update} 不修改已存在表 —— 也就是说，若 {@code h2/V1__baseline.sql} 漏了实体上的某列，
 * 空库启动不会报错，要到真正写那一列时才炸。本测试就是这层保险的替代品。</p>
 *
 * <p><b>比较方式</b>：同一个内存库上先让 Hibernate 从**实体元数据**建表（{@code ddl-auto: create}）
 * 快照一次，再 {@code DROP ALL OBJECTS} 后用 Flyway 执行 {@code classpath:db/migration/h2} 快照一次，
 * 两次快照必须完全一致。两侧的 DDL 都出自同一版 Hibernate，因此名字（除库内对象 ID）与类型都应逐字相同。</p>
 *
 * <p><b>URL 参数与生产默认值一致</b>（{@code MODE=MySQL;DATABASE_TO_UPPER=FALSE}）—— 大小写与模式正是
 * 类型/命名最容易出分歧的地方，不能用测试环境的 {@code MODE=PostgreSQL} 内存库来比。</p>
 *
 * <p>测试环境（{@code application-test.yml}）已显式关闭 Flyway，因此本类的 Spring 上下文由 Hibernate 建表；
 * Flyway 只在测试方法里手工跑一次。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("H2 基线脚本与实体模型一致（issue #192）")
class H2BaselineMatchesEntityModelTest {

    /** 与 core.yml 的默认 H2 URL 同参数，只把库换成内存库 */
    private static final String URL =
            "jdbc:h2:mem:h2_baseline_vs_entity;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE";

    /** Flyway 自己的历史表不属于被测 schema，两侧快照都要排除 */
    private static final String HISTORY_TABLE = "flyway_schema_history";

    @DynamicPropertySource
    static void datasourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        // 实体模型 → DDL 的执行者换成 Hibernate 自己（默认的 update 不会建全量快照语义）
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Hibernate 由实体建出的 schema 与 h2/V1 脚本建出的 schema 逐项一致")
    void baselineMatchesHibernateGeneratedSchema() throws SQLException {
        Map<String, List<String>> fromEntityModel = snapshot();

        jdbcTemplate.execute("DROP ALL OBJECTS");
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("classpath:db/migration/h2")
                .load()
                .migrate();
        Map<String, List<String>> fromBaseline = snapshot();

        for (String section : fromEntityModel.keySet()) {
            assertEquals(fromEntityModel.get(section), fromBaseline.get(section),
                    section + " 不一致：Hibernate 由实体建出的 schema 与 h2/V1__baseline.sql 有差异。"
                            + "若基线已发布请补增量脚本；未发版则直接修正 V1（两侧 db/migration 需同步）。");
        }
    }

    // ==================== 结构快照 ====================

    private Map<String, List<String>> snapshot() throws SQLException {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("tables", query(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' "
                        + "AND TABLE_NAME <> '" + HISTORY_TABLE + "' ORDER BY TABLE_NAME"));
        sections.put("columns", query(
                "SELECT TABLE_NAME || '.' || COLUMN_NAME || ' ' || DATA_TYPE"
                        + " || ' null=' || IS_NULLABLE || ' len=' || COALESCE(CHARACTER_MAXIMUM_LENGTH, -1)"
                        + " || ' prec=' || COALESCE(NUMERIC_PRECISION, -1)"
                        + " || ' identity=' || IS_IDENTITY "
                        + "FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME <> '" + HISTORY_TABLE + "' "
                        + "ORDER BY TABLE_NAME, COLUMN_NAME"));
        sections.put("constraints", normalizeGeneratedNames(query(
                "SELECT TABLE_NAME || '.' || CONSTRAINT_NAME || ' ' || CONSTRAINT_TYPE "
                        + "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME <> '" + HISTORY_TABLE + "' "
                        + "ORDER BY TABLE_NAME, CONSTRAINT_NAME")));
        sections.put("indexes", normalizeGeneratedNames(query(
                "SELECT INDEX_NAME || ' on ' || TABLE_NAME "
                        + "FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME <> '" + HISTORY_TABLE + "' "
                        + "ORDER BY TABLE_NAME, INDEX_NAME")));
        return sections;
    }

    /**
     * H2 给自动生成的索引/约束编的名字里带**库内部对象 ID**（{@code PRIMARY_KEY_B}、
     * {@code CONSTRAINT_INDEX_7}），同一个库两次建表也不同 —— 直接比名字会假红。归一化尾部的 ID 段。
     *
     * <p>X5（2026-11-04，固定权限矩阵改造）：纯自动命名的约束 {@code CONSTRAINT_<hex>}
     * （如 {@code CONSTRAINT_E}、{@code CONSTRAINT_E9D}）同样随库内对象 ID 漂移——
     * 实体集合变化（如删除 role_permissions 实体）会让同一张表的自动约束名整体移位。
     * 这类名字没有任何语义（显式命名均带 uk_/fk_ 前缀），一并归一化为 {@code CONSTRAINT_<id>}，
     * 归一化后重排序以消除排序键漂移。</p>
     */
    private static List<String> normalizeGeneratedNames(final List<String> rows) {
        List<String> normalized = new ArrayList<>(rows.size());
        for (String row : rows) {
            normalized.add(row
                    .replaceAll("(PRIMARY_KEY|CONSTRAINT_INDEX)_[0-9A-F]+", "$1_<id>")
                    .replaceAll("(_INDEX_)[0-9A-F]+\\b", "$1<id>")
                    // X5：纯自动命名约束（大写 hex 后缀，非显式 uk_/fk_ 名）
                    .replaceAll("(?i)(?<![A-Z_])CONSTRAINT_[0-9A-F]+(?=\\s)", "CONSTRAINT_<id>")
                    // X4（2026-10-28）：自动命名唯一约束的背书索引，H2 对「列级 unique」与
                    // 「表级 unique 约束」取的名字前缀不同（CONSTRAINT_INDEX_x vs
                    // CONSTRAINT_<id>_INDEX_x）。约束本身已在 constraints 节比较过，
                    // 这里把背书索引名归一化，避免内部 ID 噪音假红。
                    .replaceAll("(?i)CONSTRAINT\\S*_INDEX_<id>", "CONSTRAINT_BACKED_INDEX"));
        }
        // 归一化改变名字 → 排序键随之变化，重排序保证两侧可比
        Collections.sort(normalized);
        return normalized;
    }

    private static List<String> query(final String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
