package org.unreal.modelrouter.auth.security.config;

import java.util.Locale;

/**
 * 生产环境启动安全门闩（R4-P1）。
 *
 * <p>密钥/密码检查失败时在生产必须 fail-fast，禁止只打日志后带着默认密钥上线。
 * 逃生舱：{@code TRUNKLINE_SKIP_AUTH_WARNING=true}（需显式设置）。</p>
 */
public final class StartupSecurityGate {

    /** 出厂默认管理员密码（与 jwt.yml / SecretKeyValidator 一致） */
    public static final String DEFAULT_ADMIN_PASSWORD = "ChangeMeOnFirstStartup123456";

    /** 出厂默认 JWT 密钥（与 config/auth/jwt.yml 一致） */
    public static final String DEFAULT_JWT_SECRET = "ThisIsADefaultSecretKeyForDevOnly12345678";

    /** 显式跳过生产安全检查的环境变量 */
    public static final String SKIP_ENV = "TRUNKLINE_SKIP_AUTH_WARNING";

    private StartupSecurityGate() {
    }

    /**
     * 是否应在生产 fail-fast。
     */
    public static boolean shouldFailFast(final boolean production,
                                         final boolean checksPassed,
                                         final String skipAuthWarning) {
        return production && !checksPassed && !"true".equalsIgnoreCase(skipAuthWarning);
    }

    /**
     * 执行门闩：该失败时抛 {@link IllegalStateException}。
     */
    public static void enforce(final boolean production,
                               final boolean checksPassed,
                               final String skipAuthWarning) {
        if (shouldFailFast(production, checksPassed, skipAuthWarning)) {
            throw new IllegalStateException(
                    "Production security startup checks failed. "
                            + "Set JWT_SECRET (or PROD_JWT_SECRET / DEV_JWT_SECRET) to a strong value, "
                            + "complete /init to create the admin password, "
                            + "or set " + SKIP_ENV + "=true only for trusted isolated networks.");
        }
    }

    /**
     * 管理员密码是否可接受。
     */
    public static boolean adminPasswordOk(final boolean production, final String password) {
        if (password == null || password.isBlank()) {
            // 生产必须配置；开发仅告警
            return !production;
        }
        if (isDefaultAdminPassword(password)) {
            return !production;
        }
        return true;
    }

    /**
     * JWT 密钥是否可接受（空值/已知默认值在生产失败）。
     */
    public static boolean jwtSecretOk(final boolean production, final String secret) {
        if (secret == null || secret.isBlank()) {
            return !production;
        }
        // 与 isDefaultJwtSecret 共用同一张表：出厂常量、devonly、文档占位符一视同仁。
        if (isDefaultJwtSecret(secret)) {
            return !production;
        }
        return true;
    }

    /**
     * 是否为出厂默认管理员密码。
     */
    public static boolean isDefaultAdminPassword(final String password) {
        return password != null && DEFAULT_ADMIN_PASSWORD.equals(password.trim());
    }

    /**
     * 是否为出厂默认 / 文档示例 JWT 密钥。
     *
     * <p>除两个历史常量与 devonly 外，还拒绝 .env.example、README、config-external
     * 文档里「看起来像密钥、其实可以照抄」的占位值（issue #664 残余）。占位符判定只认
     * 明确的前缀/整词，不用 contains("example") 这类宽松匹配，以免误伤真实强密钥。
     * 本方法同时被 StartupSecretKeyChecker（生产门闩）与
     * StartupSecurityEnvironmentPostProcessor（是否生成随机密钥）使用。</p>
     */
    public static boolean isDefaultJwtSecret(final String secret) {
        if (secret == null) {
            return false;
        }
        String value = secret.trim();
        if (DEFAULT_JWT_SECRET.equals(value)
                || "dev-secret-key-for-testing-only-32-characters-minimum".equals(value)
                || value.toLowerCase(Locale.ROOT).contains("devonly")) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("your-") || lower.startsWith("your_")
                || lower.startsWith("change-") || lower.startsWith("change_")
                || lower.startsWith("changeme")
                || lower.startsWith("replace-") || lower.startsWith("replace_")
                || lower.startsWith("replace-with")
                || lower.startsWith("placeholder")
                || lower.equals("example")
                || lower.startsWith("example-") || lower.startsWith("example_");
    }
}
