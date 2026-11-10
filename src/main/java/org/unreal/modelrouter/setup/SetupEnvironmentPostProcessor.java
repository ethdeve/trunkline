/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 把初始化向导保存的远程 PG 连接信息注入到 Spring 环境。
 *
 * <p>必须在 {@link org.unreal.modelrouter.persistence.migration.FlywayDialectEnvironmentPostProcessor}
 * 之前运行，让方言处理器看到的是向导选择的 URL 而不是默认 H2 URL。</p>
 *
 * <p>显式部署配置优先：若存在 {@code DATABASE_URL} 环境变量、{@code spring.datasource.url}
 * 系统属性或命令行参数，则不覆盖运维已经给定的数据源。</p>
 *
 * @author Trunkline Team
 */
public class SetupEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 注入的数据源属性来源名 */
    static final String SOURCE_NAME = "trunklineSetupDatasource";

    private static final Logger log = LoggerFactory.getLogger(SetupEnvironmentPostProcessor.class);

    /** 晚于常规配置导入、早于 Flyway 方言处理器 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
                                       final SpringApplication application) {
        final Path stateFile = SetupStateStore.resolveStateFile(environment);
        if (!Files.exists(stateFile)) {
            return;
        }

        final Properties props = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("初始化状态文件不可读，跳过数据源注入: {} -> {}", stateFile, e.getMessage());
            return;
        }

        if (!"REMOTE".equalsIgnoreCase(props.getProperty("mode"))) {
            return;
        }
        final String url = trimToNull(props.getProperty("datasource.url"));
        if (url == null) {
            return;
        }
        if (hasExplicitDatasourceOverride(environment)) {
            log.info("检测到显式数据源配置，初始化向导保存的连接信息不覆盖: {}", stateFile);
            return;
        }

        final Map<String, Object> datasource = new LinkedHashMap<>();
        datasource.put("spring.datasource.url", url);
        datasource.put("spring.datasource.username", nullToEmpty(props.getProperty("datasource.username")));
        datasource.put("spring.datasource.password", nullToEmpty(props.getProperty("datasource.password")));
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, datasource));
        log.info("已应用初始化向导保存的远程数据源: {}", maskUrl(url));
    }

    private static boolean hasExplicitDatasourceOverride(final ConfigurableEnvironment environment) {
        if (hasText(environment.getProperty("DATABASE_URL"))
                || hasText(System.getProperty("spring.datasource.url"))) {
            return true;
        }
        final PropertySource<?> commandLine = environment.getPropertySources().get("commandLineArgs");
        return commandLine != null && commandLine.containsProperty("spring.datasource.url");
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static String trimToNull(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    /** 日志脱敏：只保留协议与主机，不显示用户名/密码/库名参数。 */
    static String maskUrl(final String url) {
        if (url == null || url.isBlank()) {
            return "jdbc:<masked>";
        }
        try {
            // java.net.URI 会把完整的 "jdbc:postgresql://host" 当作 opaque URI（scheme 只有 jdbc），
            // getHost() 恒为 null，日志会退化成 jdbc://null。必须先剥掉 jdbc: 前缀再解析。
            final String normalized = url.regionMatches(true, 0, "jdbc:", 0, 5)
                    ? url.substring(5)
                    : url;
            final java.net.URI uri = java.net.URI.create(normalized);
            final String scheme = uri.getScheme();
            final String host = uri.getHost();
            if (scheme == null || host == null) {
                // 非层次 URL（如 H2 的 jdbc:h2:file:./data/...）：只回显协议名，路径与参数一律不回显。
                return "jdbc:" + (scheme == null ? "<masked>" : scheme) + "://<masked>";
            }
            final int port = uri.getPort();
            return "jdbc:" + scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (RuntimeException e) {
            return "jdbc:<masked>";
        }
    }
}
