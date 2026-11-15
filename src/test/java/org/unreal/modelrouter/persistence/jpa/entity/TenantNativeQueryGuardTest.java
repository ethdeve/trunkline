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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 租户隔离实体原生 SQL 守卫（体检报告 P0 第 3 点回归守卫 / 闸门⑦）。
 *
 * <p>背景：Hibernate {@code @Filter} <b>不作用于</b> {@code @Query(nativeQuery = true)} /
 * JDBC / {@code EntityManager.createNativeQuery}。一旦某个租户隔离实体（{@code @Filter} 标注）
 * 上出现裸 native 查询，该查询会绕过行级隔离，导致跨租户数据串扰。</p>
 *
 * <p>本测试在 CI 侧分两层静态拦截：</p>
 * <ol>
 *   <li><b>注解层</b>：扫描 {@code persistence.jpa.repository} 下全部 Spring Data 仓储，
 *       若某仓储的领域实体是租户隔离实体（{@code @Filter}）却挂了 {@code nativeQuery = true}
 *       的方法，立即失败；任何 native SQL 文本直接点名五张租户表也判违规。</li>
 *   <li><b>源码层（闸门⑦ 新增）</b>：扫描<b>全部主代码</b>的字符串字面量（含 service 里的
 *       {@code JdbcTemplate} 裸 SQL、字符串拼接片段、text block），任何以 SQL 动词开头且点名
 *       五张租户表的字面量一律失败。注解层只看得到 Repository 上的 {@code @Query}，
 *       看不到散落在服务类的手写 SQL，这一层补的正是那个盲区。</li>
 * </ol>
 *
 * <p>当前实测：开源主代码对五张租户表的查询<b>全部为 JPQL</b>（经 Hibernate，受 {@code @Filter}
 * 约束），{@code nativeQuery = true} 命中数为 0；五个表名在主代码中<b>仅出现于 {@code @Table}
 * 注解</b>，SQL 字面量零命中。故本测试当前为绿，作用是在将来有人误加 native 查询时提前熔断。
 * 配套的可运行实证见 {@code NativeSqlTenantIsolationProofTest}（真实 H2 上证明 @Filter 拦不住
 * 裸 JDBC），企业侧 JdbcTemplate 的同类守卫见企业仓 {@code TenantAwareJdbcSqlPolicyTest}。</p>
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
    @DisplayName("租户隔离实体上不得出现绕过 @Filter 的裸 native 查询（注解层）")
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
                            final String sql = q.value().toLowerCase(Locale.ROOT);
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

    @Test
    @DisplayName("主代码任何 SQL 字面量都不得点名五张租户表（源码层，含 service 裸 JDBC）")
    void noSqlLiteralInMainSourcesNamesTenantTables() {
        final Path root = resolveMainSourceRoot();
        final Set<String> violations = new LinkedHashSet<>();
        final Set<String> annotationTableHits = new LinkedHashSet<>();
        final int[] scannedFiles = {0};

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        scannedFiles[0]++;
                        final String source = stripComments(read(p));
                        for (final String literal : extractStringLiterals(source)) {
                            final String normalized = normalize(literal);
                            if (normalized.isEmpty()) {
                                continue;
                            }
                            if (isSqlFragment(normalized)) {
                                for (final String table : TENANT_TABLES) {
                                    if (wordMatch(normalized, table)) {
                                        violations.add(p.getFileName() + " → " + table + " → "
                                                + normalized.substring(0, Math.min(normalized.length(), 120)));
                                    }
                                }
                            } else {
                                // 非 SQL 字面量里出现表名是允许的（@Table(name=...) / 索引名等）
                                for (final String table : TENANT_TABLES) {
                                    if (wordMatch(normalized, table)) {
                                        annotationTableHits.add(table);
                                    }
                                }
                            }
                        }
                    });
        } catch (final IOException e) {
            fail("无法扫描主代码源码目录 " + root + "：" + e.getMessage());
        }

        // 守卫自检：源码根没找对 / 提取器失效时不能静默变绿
        assertTrue(scannedFiles[0] >= 100,
                "扫描到的主代码 .java 文件仅 " + scannedFiles[0] + " 个，疑似源码根解析错误，守卫不得静默通过");
        assertTrue(annotationTableHits.containsAll(TENANT_TABLES),
                "五张租户表的 @Table 注解未全部被提取到（实际 " + annotationTableHits
                        + "），表名可能已漂移，请同步 TENANT_TABLES 常量");
        assertTrue(violations.isEmpty(),
                "主代码出现直接点名租户表的 SQL 字面量（@Filter 不生效于 native/JDBC）：\n"
                        + String.join("\n", violations)
                        + "\n若确需裸 SQL，必须显式带 tenant_id 谓词并改用 JPQL 或接入租户过滤。");
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

    /** Maven surefire 从仓根启动；同时兼容 IDE 与 CI 传 basedir 的情况。 */
    private static Path resolveMainSourceRoot() {
        final List<Path> candidates = new ArrayList<>();
        final String basedir = System.getProperty("basedir");
        if (basedir != null && !basedir.isBlank()) {
            candidates.add(Paths.get(basedir, "src", "main", "java"));
        }
        candidates.add(Paths.get("src", "main", "java"));
        Path cursor = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            candidates.add(cursor.resolve(Paths.get("src", "main", "java")));
            cursor = cursor.getParent();
        }
        for (final Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return fail("找不到 src/main/java 源码根（候选：" + candidates + "）。守卫必须扫描真实源码，不能跳过。");
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            return fail("读取源码失败 " + path + "：" + e.getMessage());
        }
    }

    /** 去掉 // 与 /&#42; ... &#42;/ 注释，但完整保留字符串字面量（含 text block）。 */
    private static String stripComments(final String src) {
        final StringBuilder out = new StringBuilder(src.length());
        final int n = src.length();
        int i = 0;
        while (i < n) {
            final char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                final int end = skipLiteral(src, i, c);
                out.append(src, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i < n) {
                    if (i + 1 < n && src.charAt(i) == '*' && src.charAt(i + 1) == '/') {
                        out.append("  ");
                        i += 2;
                        break;
                    }
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 提取所有 Java 字符串字面量的内容（普通字符串 + text block），跳过 char 字面量。 */
    private static List<String> extractStringLiterals(final String src) {
        final List<String> literals = new ArrayList<>();
        final int n = src.length();
        int i = 0;
        while (i < n) {
            final char c = src.charAt(i);
            if (c == '\'') {
                i = skipLiteral(src, i, '\'');
                continue;
            }
            if (c == '"') {
                if (i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                    final int end = src.indexOf("\"\"\"", i + 3);
                    final int close = end < 0 ? n : end;
                    literals.add(src.substring(i + 3, close));
                    i = end < 0 ? n : end + 3;
                } else {
                    final StringBuilder value = new StringBuilder();
                    int j = i + 1;
                    while (j < n) {
                        final char d = src.charAt(j);
                        if (d == '\\' && j + 1 < n) {
                            value.append(src.charAt(j + 1));
                            j += 2;
                            continue;
                        }
                        if (d == '"') {
                            j++;
                            break;
                        }
                        value.append(d);
                        j++;
                    }
                    literals.add(value.toString());
                    i = j;
                }
                continue;
            }
            i++;
        }
        return literals;
    }

    /** 返回字面量结束后的下标（含闭合引号）；支持转义与 text block。 */
    private static int skipLiteral(final String src, final int start, final char quote) {
        final int n = src.length();
        if (quote == '"' && start + 2 < n
                && src.charAt(start + 1) == '"' && src.charAt(start + 2) == '"') {
            final int end = src.indexOf("\"\"\"", start + 3);
            return end < 0 ? n : end + 3;
        }
        int j = start + 1;
        while (j < n) {
            final char d = src.charAt(j);
            if (d == '\\' && j + 1 < n) {
                j += 2;
                continue;
            }
            if (d == quote) {
                return j + 1;
            }
            j++;
        }
        return n;
    }

    private static boolean isSqlFragment(final String normalized) {
        return normalized.startsWith("select ") || normalized.startsWith("insert ")
                || normalized.startsWith("update ") || normalized.startsWith("delete ")
                || normalized.startsWith("create ") || normalized.startsWith("alter ")
                || normalized.startsWith("drop ") || normalized.startsWith("merge ")
                || normalized.startsWith("from ") || normalized.startsWith("where ")
                || normalized.startsWith("and ") || normalized.startsWith("order by ");
    }

    /** 词边界匹配，避免把 idx_service_config_tenant 之类的索引名误判为表名 service_config。 */
    private static boolean wordMatch(final String normalized, final String table) {
        return Pattern.compile("(?<!\\w)" + Pattern.quote(table) + "(?!\\w)")
                .matcher(normalized).find();
    }

    private static String normalize(final String raw) {
        return raw.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
