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
import org.springframework.core.env.Profiles;
import org.unreal.modelrouter.auth.security.config.StartupSecurityGate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 启动期安全前置处理，覆盖两件必须在任何 Bean 创建之前完成的事：
 *
 * <p>1. <b>JWT 密钥不再有可预测的出厂默认值</b>。配置文件只声明占位符，不内置任何可用密钥。
 * 若最终生效的 {@code trunkline.security.jwt.secret} 为空或仍是已知默认值，本处理器生成
 * 48 字节随机值并持久化到 {@code data/jwt-secret}，再以最高优先级注入环境。持久化保证重启后
 * 已签发令牌仍然有效；显式设置 {@code JWT_SECRET} / {@code DEV_JWT_SECRET} 时完全不干预，
 * 生产环境的 {@link StartupSecurityGate} 门闩行为保持不变。</p>
 *
 * <p>2. <b>非 dev 环境拒绝出厂测试 API Key</b>。内置 {@code keys} 已置空，若外部挂载仍带来
 * {@code sk-test-} 前缀的 Key，则启动即失败，避免把测试凭据带进准生产/生产。</p>
 *
 * <p>顺序上晚于配置数据加载（否则读不到已解析的占位符），早于数据源向导与 Flyway 方言处理器，
 * 二者都不依赖本处理器注入的键。</p>
 *
 * @author Trunkline Team
 */
public class StartupSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 注入属性来源名 */
    static final String SOURCE_NAME = "trunklineStartupSecurity";

    static final String SECRET_PROPERTY = "trunkline.security.jwt.secret";
    static final String SECRET_FILE_PROPERTY = "trunkline.security.jwt.secret-file";
    static final String DEFAULT_SECRET_FILE = "./data/jwt-secret";

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityEnvironmentPostProcessor.class);

    /** 晚于配置数据加载、早于 SetupEnvironmentPostProcessor(-10) */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 12;
    }

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
                                       final SpringApplication application) {
        ensureTestApiKeysAbsent(environment);
        ensureJwtSecret(environment);
    }

    /**
     * 已显式注入且强度合规的密钥一律不动；否则生成并持久化一个进程内稳定的随机密钥。
     */
    private void ensureJwtSecret(final ConfigurableEnvironment environment) {
        final String configured = environment.getProperty(SECRET_PROPERTY);
        if (isAcceptableSecret(configured)) {
            return;
        }

        final String generated = loadOrCreateSecret(environment);
        final Map<String, Object> props = new LinkedHashMap<>();
        props.put(SECRET_PROPERTY, generated);
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));

        log.warn("未显式配置可用的 JWT 密钥，已自动生成并持久化随机密钥；"
                + "生产部署请显式设置 JWT_SECRET 环境变量（密钥内容不会写入日志）。");
    }

    /**
     * 非 dev 环境出现出厂测试 Key 即 fail-fast。dev 保留本地调试能力。
     */
    private void ensureTestApiKeysAbsent(final ConfigurableEnvironment environment) {
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            return;
        }
        for (int i = 0; ; i++) {
            final String value = environment.getProperty(
                    "trunkline.security.api-key.keys[" + i + "].key-value");
            if (value == null) {
                return;
            }
            if (value.trim().startsWith("sk-test-")) {
                throw new IllegalStateException(
                        "Refusing to start: built-in test API Key detected in a non-dev environment. "
                                + "Remove sk-test- keys from the mounted api-key.yml "
                                + "or create keys through the API management console.");
            }
        }
    }

    private static boolean isAcceptableSecret(final String secret) {
        return secret != null
                && secret.trim().length() >= 32
                && !StartupSecurityGate.isDefaultJwtSecret(secret);
    }

    /**
     * 复用已有密钥文件；不存在时用 CREATE_NEW 原子创建，避免多进程同时启动时互相覆盖。
     * 文件不可写时退化为进程级随机密钥（重启令牌失效，但绝不回退到可预测默认值）。
     */
    private String loadOrCreateSecret(final ConfigurableEnvironment environment) {
        final Path path = Path.of(environment.getProperty(SECRET_FILE_PROPERTY, DEFAULT_SECRET_FILE));
        try {
            if (Files.isRegularFile(path)) {
                final String stored = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (isAcceptableSecret(stored)) {
                    return stored;
                }
                log.warn("JWT 密钥文件内容不可用，将重新生成: {}", path);
            }
            final String generated = randomSecret();
            final Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                Files.writeString(path, generated, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException race) {
                final String stored = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (isAcceptableSecret(stored)) {
                    return stored;
                }
                Files.writeString(path, generated, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
            restrictToOwner(path);
            return generated;
        } catch (IOException | RuntimeException e) {
            log.warn("无法读写 JWT 密钥文件 {}，本次改用进程级随机密钥（重启后已签发令牌将失效）: {}",
                    path, e.toString());
            return randomSecret();
        }
    }

    private static String randomSecret() {
        final byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** POSIX 主机上收紧为仅属主可读写；Windows 不支持该属性，忽略即可。 */
    private static void restrictToOwner(final Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / 不支持 POSIX 权限的文件系统：文件仍位于受控的数据目录内
        }
    }
}
