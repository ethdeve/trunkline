package org.unreal.modelrouter.auth.security.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【issue #674】启动密钥门闩必须读「绑定后属性 + 三别名显式注入」，而不是只认两个 env。
 *
 * <p>反证重点：生产即使被启动期处理器塞了随机密钥，只要没有显式注入，仍必须 fail-fast；
 * 显式注入示例占位符也不得靠随机覆盖蒙混过关。</p>
 */
@DisplayName("#674 StartupSecretKeyChecker 绑定值与密钥别名")
class StartupSecretKeyCheckerTest {

    private static final String STRONG = "Pr0d!Only#Random$Secret-Key-2026-abcdefghijklmnopqrst";
    private static final String GENERATED = "G3n3rated!Startup#Random$Key-2026-zyxwvutsrqponmlk";
    private static final String PLACEHOLDER =
            "your-very-secure-jwt-secret-key-at-least-32-characters-long";

    private static StartupSecretKeyChecker checker(final String profile,
                                                   final String boundSecret,
                                                   final String... envPairs) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        for (int i = 0; i < envPairs.length; i += 2) {
            env.setProperty(envPairs[i], envPairs[i + 1]);
        }
        SecurityProperties props = new SecurityProperties();
        props.getJwt().setEnabled(true);
        props.getJwt().setSecret(boundSecret);

        StartupSecretKeyChecker checker = new StartupSecretKeyChecker();
        ReflectionTestUtils.setField(checker, "environment", env);
        ReflectionTestUtils.setField(checker, "securityProperties", props);
        return checker;
    }

    @Test
    @DisplayName("只设 PROD_JWT_SECRET 的强密钥（prod）→ 通过（旧实现会误判 fail-fast）")
    void onlyProdAliasStrongSecret_passes() {
        assertTrue(checker("prod", STRONG, "PROD_JWT_SECRET", STRONG).checkJwtSecrets());
    }

    @Test
    @DisplayName("反证：三别名全空 + prod，即使绑定值是启动期随机密钥也必须失败")
    void noExplicitAlias_prod_failsEvenWithGeneratedSecret() {
        assertFalse(checker("prod", GENERATED).checkJwtSecrets(),
                "生产必须显式注入；自动生成的随机密钥不能被当作已配置");
    }

    @Test
    @DisplayName("三别名全空 + dev + 随机密钥 → 允许启动")
    void noExplicitAlias_dev_generatedSecret_passes() {
        assertTrue(checker("dev", GENERATED).checkJwtSecrets());
    }

    @Test
    @DisplayName("反证：默认出厂密钥经 PROD_JWT_SECRET 注入（prod）→ 拒绝")
    void defaultSecretViaProdAlias_prod_fails() {
        assertFalse(checker("prod", StartupSecurityGate.DEFAULT_JWT_SECRET,
                "PROD_JWT_SECRET", StartupSecurityGate.DEFAULT_JWT_SECRET).checkJwtSecrets());
    }

    @Test
    @DisplayName("反证：.env.example 占位符经 JWT_SECRET 注入（prod）→ 拒绝，随机覆盖不得绕过")
    void placeholderViaJwtAlias_prod_fails() {
        assertFalse(checker("prod", GENERATED, "JWT_SECRET", PLACEHOLDER).checkJwtSecrets(),
                "注入原值不合规时，即使绑定值已被随机密钥替换也不能放行");
    }

    @Test
    @DisplayName("DEV + PROD 并存时校验绑定后的 PROD 值（与占位符优先级一致）")
    void devAndProdAliases_boundProdValueWins() {
        assertTrue(checker("prod", STRONG,
                "DEV_JWT_SECRET", "short", "PROD_JWT_SECRET", STRONG).checkJwtSecrets());
    }

    @Test
    @DisplayName("别名顺序与四处 yml 占位符一致：JWT > PROD > DEV")
    void aliasOrderMatchesPlaceholders() {
        assertArrayEquals(new String[]{"JWT_SECRET", "PROD_JWT_SECRET", "DEV_JWT_SECRET"},
                StartupSecretKeyChecker.JWT_SECRET_ALIASES);
    }

    @Test
    @DisplayName("run() 端到端：prod 缺显式密钥 fail-fast，dev 不抛")
    void runEnforcesFailFast() {
        Assumptions.assumeTrue(System.getenv(StartupSecurityGate.SKIP_ENV) == null,
                "本机设置了跳过开关时该断言不适用");
        assertThrows(IllegalStateException.class,
                () -> checker("prod", GENERATED).run());
        assertDoesNotThrow(() -> checker("dev", GENERATED).run());
    }
}
