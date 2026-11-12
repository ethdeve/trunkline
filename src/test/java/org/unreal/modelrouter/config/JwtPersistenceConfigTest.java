package org.unreal.modelrouter.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【issue #676】JWT 持久化子树「唯一真源」与真实 ConfigData 绑定基线。
 *
 * <p>用 Spring 自己的 ConfigData 加载链（含 spring.config.import 顺序与 profile 覆盖）
 * 断言生效值，而不是逐个 yml 文本比对——文本相同不代表运行时相同。</p>
 */
@DisplayName("#676 JWT 持久化配置真源与绑定基线")
class JwtPersistenceConfigTest {

    private static final String P_ENABLED = "trunkline.security.jwt.persistence.enabled";
    private static final String P_STORAGE = "trunkline.security.jwt.persistence.primary-storage";
    private static final String P_COMPOSITE = "trunkline.security.jwt.persistence.composite.enabled";
    private static final String P_SYNC = "trunkline.security.jwt.persistence.sync.enabled";
    private static final String P_CLEANUP = "trunkline.security.jwt.persistence.cleanup.enabled";
    private static final String P_BATCH = "trunkline.security.jwt.persistence.cleanup.batch-size";
    private static final String P_MEMORY = "trunkline.security.jwt.persistence.memory.max-tokens";
    private static final String B_ENABLED = "trunkline.security.jwt.blacklist.persistence.enabled";
    private static final String B_STORAGE = "trunkline.security.jwt.blacklist.persistence.primary-storage";
    private static final String B_COMPOSITE = "trunkline.security.jwt.blacklist.composite.enabled";
    private static final String FAIL_CLOSED =
            "trunkline.security.jwt.blacklist-fail-closed-when-unavailable";
    private static final String SECRET = "trunkline.security.jwt.secret";

    private static ApplicationContextRunner runner(final String profile, final String... props) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile)
                .withPropertyValues(props);
    }

    @Test
    @DisplayName("整改不改变任何装配开关：dev/prod 生效值与整改前一致")
    void baselineUnchanged() {
        for (String profile : new String[]{"dev", "prod"}) {
            runner(profile).run(ctx -> {
                assertEquals("false", ctx.getEnvironment().getProperty(P_ENABLED), profile);
                assertEquals("false", ctx.getEnvironment().getProperty(B_ENABLED), profile);
                assertEquals("file", ctx.getEnvironment().getProperty(P_STORAGE), profile);
                assertEquals("file", ctx.getEnvironment().getProperty(B_STORAGE), profile);
                assertEquals("true", ctx.getEnvironment().getProperty(P_COMPOSITE), profile);
                assertEquals("true", ctx.getEnvironment().getProperty(B_COMPOSITE), profile);
                assertEquals("true", ctx.getEnvironment().getProperty(P_SYNC), profile);
                assertEquals("true", ctx.getEnvironment().getProperty(P_CLEANUP), profile);
                assertEquals("1000", ctx.getEnvironment().getProperty(P_BATCH), profile);
                assertEquals("500", ctx.getEnvironment().getProperty(P_MEMORY), profile);
            });
        }
    }

    @Test
    @DisplayName("prod fail-closed 仍为 true，dev 未被误抬")
    void failClosedProfileDifference() {
        runner("prod").run(ctx ->
                assertEquals("true", ctx.getEnvironment().getProperty(FAIL_CLOSED)));
        runner("dev").run(ctx ->
                assertNull(ctx.getEnvironment().getProperty(FAIL_CLOSED)));
    }

    @Test
    @DisplayName("真源唯一：jwt.yml 与外部示例不再重复定义 persistence/blacklist 子树")
    void noDuplicateSubtrees() throws Exception {
        String internal;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("config/auth/jwt.yml")) {
            internal = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String external = Files.readString(Path.of("config-external/auth/jwt.yml"));
        for (String yaml : new String[]{internal, external}) {
            assertFalse(yaml.lines().anyMatch(l -> l.matches("^\\s{4,}persistence:\\s*$")),
                    "不应再出现 persistence: 配置键（只允许出现在注释里）");
            assertFalse(yaml.lines().anyMatch(l -> l.matches("^\\s{4,}blacklist:\\s*$")),
                    "不应再出现 blacklist: 配置键（只允许出现在注释里）");
        }
        String source;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("config/security/persistence-base.yml")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(source.contains("${JWT_PERSISTENCE_ENABLED:false}"));
        assertTrue(source.contains("${JWT_BLACKLIST_PERSISTENCE_ENABLED:false}"));
    }

    @Test
    @DisplayName("环境变量可显式打开两个开关（能力不因去重而丢失）")
    void explicitSwitchesStillWork() {
        runner("dev", "JWT_PERSISTENCE_ENABLED=true",
                "JWT_BLACKLIST_PERSISTENCE_ENABLED=true").run(ctx -> {
            assertEquals("true", ctx.getEnvironment().getProperty(P_ENABLED));
            assertEquals("true", ctx.getEnvironment().getProperty(B_ENABLED));
        });
    }

    @Test
    @DisplayName("#674 三个密钥别名都能绑定到 jwt.secret，优先级 JWT > PROD > DEV")
    void secretAliasesBind() {
        runner("prod", "DEV_JWT_SECRET=dev-alias-value").run(ctx ->
                assertEquals("dev-alias-value", ctx.getEnvironment().getProperty(SECRET)));
        runner("prod", "DEV_JWT_SECRET=dev-alias-value",
                "PROD_JWT_SECRET=prod-alias-value").run(ctx ->
                assertEquals("prod-alias-value", ctx.getEnvironment().getProperty(SECRET)));
        runner("prod", "DEV_JWT_SECRET=dev-alias-value",
                "PROD_JWT_SECRET=prod-alias-value",
                "JWT_SECRET=primary-alias-value").run(ctx ->
                assertEquals("primary-alias-value", ctx.getEnvironment().getProperty(SECRET)));
    }
}
