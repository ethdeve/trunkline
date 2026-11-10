/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 启动期远程数据源注入测试。
 *
 * <p>关键约束：显式部署配置（DATABASE_URL / spring.datasource.url 系统属性 / 命令行参数）
 * 优先于向导保存的连接信息，避免覆盖运维已给定的数据源。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("SetupEnvironmentPostProcessor 远程数据源注入")
class SetupEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    private Path stateFile;
    private String previousStateFileProperty;
    private String previousDsUrlProperty;

    @BeforeEach
    void setUp() {
        stateFile = tempDir.resolve("setup.properties");
        previousStateFileProperty = System.getProperty(SetupStateStore.STATE_FILE_PROPERTY);
        previousDsUrlProperty = System.getProperty("spring.datasource.url");
        System.setProperty(SetupStateStore.STATE_FILE_PROPERTY, stateFile.toString());
        // 隔离外部可能注入的 spring.datasource.url 系统属性，避免误判为「显式覆盖」
        System.clearProperty("spring.datasource.url");
    }

    @AfterEach
    void tearDown() {
        restore(SetupStateStore.STATE_FILE_PROPERTY, previousStateFileProperty);
        restore("spring.datasource.url", previousDsUrlProperty);
    }

    private static void restore(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void writeState(final String content) {
        try {
            Files.writeString(stateFile, content);
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("无状态文件 → 不注入任何数据源属性")
    void noStateFile_noInjection() {
        MockEnvironment env = new MockEnvironment();
        new SetupEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertNull(env.getProperty("spring.datasource.url"));
    }

    @Test
    @DisplayName("本地模式状态 → 不注入")
    void localMode_noInjection() {
        writeState("mode=LOCAL\nupdatedAt=2026-01-01T00:00:00Z\n");
        MockEnvironment env = new MockEnvironment();
        new SetupEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertNull(env.getProperty("spring.datasource.url"));
    }

    @Test
    @DisplayName("远程模式 → 注入 url / username / password")
    void remoteMode_injectsDatasource() {
        writeState("mode=REMOTE\n"
                + "datasource.url=jdbc:postgresql://db:5432/trunkline\n"
                + "datasource.username=pguser\n"
                + "datasource.password=pgpass\n"
                + "updatedAt=2026-01-01T00:00:00Z\n");
        MockEnvironment env = new MockEnvironment();
        new SetupEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertEquals("jdbc:postgresql://db:5432/trunkline", env.getProperty("spring.datasource.url"));
        assertEquals("pguser", env.getProperty("spring.datasource.username"));
        assertEquals("pgpass", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("显式 DATABASE_URL 优先，向导配置不覆盖")
    void explicitDatabaseUrl_wins() {
        writeState("mode=REMOTE\n"
                + "datasource.url=jdbc:postgresql://wizard:5432/trunkline\n"
                + "datasource.username=pguser\n"
                + "datasource.password=pgpass\n");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "jdbc:postgresql://explicit:5432/trunkline");
        new SetupEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertEquals("jdbc:postgresql://explicit:5432/trunkline", env.getProperty("DATABASE_URL"));
        assertNull(env.getProperty("spring.datasource.url"), "显式配置存在时不应注入向导 URL");
    }

    @Test
    @DisplayName("maskUrl 不泄露用户名 / 密码 / 库名")
    void maskUrl_hidesSecrets() {
        String masked = SetupEnvironmentPostProcessor.maskUrl(
                "jdbc:postgresql://dbhost:5432/trunkline?user=admin&password=secret");
        assertEquals("jdbc:postgresql://dbhost:5432", masked);
        assertFalse(masked.contains("trunkline"));
        assertFalse(masked.contains("secret"));
    }
}
