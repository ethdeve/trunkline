/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期安全前置处理的回归网：源码默认密钥必须被替换、显式注入不被覆盖、
 * 重启复用同一密钥（令牌不失效）、非 dev 的测试 API Key 必须阻断启动。
 */
@DisplayName("启动期安全前置处理")
class StartupSecurityEnvironmentPostProcessorTest {

    private static final String STRONG_SECRET =
            "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    @TempDir
    Path tempDir;

    private MockEnvironment environment(final String secret) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(StartupSecurityEnvironmentPostProcessor.SECRET_FILE_PROPERTY,
                tempDir.resolve("jwt-secret").toString());
        if (secret != null) {
            environment.setProperty(StartupSecurityEnvironmentPostProcessor.SECRET_PROPERTY, secret);
        }
        return environment;
    }

    private static void process(final MockEnvironment environment) {
        new StartupSecurityEnvironmentPostProcessor().postProcessEnvironment(environment, null);
    }

    @Test
    @DisplayName("空密钥被替换为强随机密钥并持久化")
    void blankSecretIsReplaced() throws Exception {
        MockEnvironment environment = environment("");
        process(environment);

        String generated = environment.getProperty(StartupSecurityEnvironmentPostProcessor.SECRET_PROPERTY);
        assertTrue(generated != null && generated.length() >= 32);
        assertEquals(generated, Files.readString(tempDir.resolve("jwt-secret")).trim());
    }

    @Test
    @DisplayName("出厂默认密钥同样被替换，源码公开的默认值永不生效")
    void knownDefaultSecretIsReplaced() {
        MockEnvironment environment = environment("ThisIsADefaultSecretKeyForDevOnly12345678");
        process(environment);

        assertNotEquals("ThisIsADefaultSecretKeyForDevOnly12345678",
                environment.getProperty(StartupSecurityEnvironmentPostProcessor.SECRET_PROPERTY));
    }

    @Test
    @DisplayName("显式注入的强密钥不被覆盖，也不生成额外文件")
    void explicitStrongSecretIsPreserved() {
        MockEnvironment environment = environment(STRONG_SECRET);
        process(environment);

        assertEquals(STRONG_SECRET,
                environment.getProperty(StartupSecurityEnvironmentPostProcessor.SECRET_PROPERTY));
        assertFalse(Files.exists(tempDir.resolve("jwt-secret")));
    }

    @Test
    @DisplayName("重启复用同一密钥，已签发令牌不会失效")
    void generatedSecretIsReusedAcrossRestarts() {
        MockEnvironment first = environment("");
        process(first);
        String firstSecret = first.getProperty(StartupSecurityEnvironmentPostProcessor.SECRET_PROPERTY);

        MockEnvironment second = environment("");
        process(second);
        assertEquals(firstSecret,
                second.getProperty(StartupSecurityEnvironmentPostProcessor.SECRET_PROPERTY));
    }

    @Test
    @DisplayName("非 dev 环境出现出厂测试 API Key 即拒绝启动")
    void nonDevRejectsTestApiKey() {
        MockEnvironment environment = environment(STRONG_SECRET);
        environment.setProperty("trunkline.security.api-key.keys[0].key-value", "sk-test-api-key-12345678");

        assertThrows(IllegalStateException.class, () -> process(environment));
    }

    @Test
    @DisplayName("dev 环境保留本地调试用测试 Key")
    void devAllowsTestApiKey() {
        MockEnvironment environment = environment(STRONG_SECRET);
        environment.setActiveProfiles("dev");
        environment.setProperty("trunkline.security.api-key.keys[0].key-value", "sk-test-api-key-12345678");

        process(environment);
        assertEquals(STRONG_SECRET,
                environment.getProperty(StartupSecurityEnvironmentPostProcessor.SECRET_PROPERTY));
    }
}
