package org.unreal.modelrouter.auth.security.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.util.SecretKeyValidator;
import org.unreal.modelrouter.auth.security.util.SecretKeyValidator.ValidationResult;

import java.util.Arrays;

/**
 * 启动时密钥安全检查器
 * 在应用启动时验证 JWT 密钥和密码的强度
 *
 * 启用条件：
 * - trunkline.security.startup-check.enabled=true（默认启用）
 * - 生产环境自动启用
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "trunkline.security.startup-check.enabled", havingValue = "true", matchIfMissing = true)
public class StartupSecretKeyChecker implements CommandLineRunner {

    /**
     * 【issue #674】与 config/auth/jwt.yml、application-{dev,prod}.yml 的占位符顺序完全一致：
     * {@code ${JWT_SECRET:${PROD_JWT_SECRET:${DEV_JWT_SECRET:}}}}。
     * 门闩必须认得全部三别名，否则「只设 PROD_JWT_SECRET」会出现绑定侧有密钥、
     * 门闩读 null → 生产 fail-fast 的假故障。
     */
    static final String[] JWT_SECRET_ALIASES = {"JWT_SECRET", "PROD_JWT_SECRET", "DEV_JWT_SECRET"};

    private static final String ASCII_ART_BORDER = "\n"
            + "╔══════════════════════════════════════════════════════════════════════════════╗\n"
            + "║                                                                              ║\n"
            + "║                            Trunkline 安全密钥检查                             ║\n"
            + "║                                                                              ║\n"
            + "╚══════════════════════════════════════════════════════════════════════════════╝\n";

    @Autowired
    private Environment environment;

    @Autowired
    private SecurityProperties securityProperties;

    @Override
    public void run(final String... args) throws Exception {
        log.info(ASCII_ART_BORDER);

        boolean allPassed = true;
        boolean isProduction = isProductionEnvironment();

        // 生产环境检查 JWT 是否启用
        allPassed &= checkJwtEnabledForProduction(isProduction);

        // 检查 JWT 密钥
        allPassed &= checkJwtSecrets();

        // 【2026-11-10】管理员密码改由 /init 向导在创建账户时校验，
        // 不再要求启动前设置 INITIAL_ADMIN_PASSWORD，否则「未初始化先启动进向导」会自相矛盾。

        if (!allPassed) {
            log.error("\n"
                + "╔══════════════════════════════════════════════════════════════════════════════╗\n"
                + "║  ⚠️  安全警告：检测到弱密钥或弱密码                                          ║\n"
                + "║                                                                              ║\n"
                + "║  在生产环境使用之前，请务必：                                                ║\n"
                + "║  1. 设置强密钥：export JWT_SECRET=\"<随机生成的 32+ 字节密钥>\"                    ║\n"
                + "║     （也支持 PROD_JWT_SECRET / DEV_JWT_SECRET，优先级依次降低）                 ║\n"
                + "║  2. 首次部署访问 /init 完成初始化，在向导中设置强管理员密码                  ║\n"
                + "║  3. 启用 JWT 认证：trunkline.security.jwt.enabled=true                       ║\n"
                + "║  4. 使用密钥生成工具：java -jar trunkline.jar --generate-key                 ║\n"
                + "║                                                                              ║\n"
                + "║  密钥生成工具使用说明：                                                      ║\n"
                + "║  --generate-key              生成 Base64 编码的 JWT 密钥                          ║\n"
                + "║  --generate-api-token        生成 API Token                                  ║\n"
                + "║  --generate-password         生成随机密码                                    ║\n"
                + "╚══════════════════════════════════════════════════════════════════════════════╝\n");
        } else {
            log.info("\n"
                + "╔══════════════════════════════════════════════════════════════════════════════╗\n"
                + "║  ✓ 所有密钥和密码检查通过                                                    ║\n"
                + "╚══════════════════════════════════════════════════════════════════════════════╝\n");
        }

        // R4-P1：生产 fail-fast — 检查失败不得带着默认密钥/弱配置继续启动
        StartupSecurityGate.enforce(isProduction, allPassed,
                System.getenv(StartupSecurityGate.SKIP_ENV));
    }

    /**
     * 检查是否为生产环境
     */
    private boolean isProductionEnvironment() {
        String[] activeProfiles = environment.getActiveProfiles();
        return Arrays.stream(activeProfiles)
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile)
                        || "production".equalsIgnoreCase(profile));
    }

    /**
     * 生产环境检查 JWT 是否启用
     */
    private boolean checkJwtEnabledForProduction(final boolean isProduction) {
        if (!isProduction) {
            log.info("当前环境：开发/测试环境，跳过 JWT 启用检查");
            return true;
        }

        log.info("当前环境：生产环境，检查 JWT 认证配置...");

        boolean jwtEnabled = securityProperties.getJwt() != null
                && securityProperties.getJwt().isEnabled();

        if (!jwtEnabled) {
            log.error("\n"
                + "╔══════════════════════════════════════════════════════════════════════════════╗\n"
                + "║  🚨 严重安全警告：生产环境 JWT 认证未启用！                                   ║\n"
                + "║                                                                              ║\n"
                + "║  这意味着所有 API 端点都将没有认证保护，存在极高的安全风险！                  ║\n"
                + "║                                                                              ║\n"
                + "║  请立即采取以下措施之一：                                                    ║\n"
                + "║  1. 启用 JWT 认证：                                                          ║\n"
                + "║     export JWT_SECRET=\"<your-secret-key>\"                                   ║\n"
                + "║     在 application-prod.yml 中设置：                                         ║\n"
                + "║     trunkline.security.jwt.enabled: true                                     ║\n"
                + "║                                                                              ║\n"
                + "║  2. 或配置其他认证方式（如 API Key、OAuth2）                                 ║\n"
                + "║                                                                              ║\n"
                + "║  如果您确定要在无认证情况下运行（如内网环境），请设置：                       ║\n"
                + "║     export " + StartupSecurityGate.SKIP_ENV + "=true"
                + " ".repeat(Math.max(0, 61 - StartupSecurityGate.SKIP_ENV.length())) + "║\n"
                + "╚══════════════════════════════════════════════════════════════════════════════╝\n");

            // 检查是否设置了跳过警告的环境变量。
            // 引用同一个常量（issue #201）：此前这里另写了一个拼写 JAROUTER_SKIP_AUTH_WARNING，
            // 而失败路径读的是 StartupSecurityGate.SKIP_ENV，导致"按提示设置却不生效"。
            String skipWarning = System.getenv(StartupSecurityGate.SKIP_ENV);
            if ("true".equalsIgnoreCase(skipWarning)) {
                log.warn("已设置 {}=true，跳过认证警告", StartupSecurityGate.SKIP_ENV);
                return true;
            }
            return false;
        }

        log.info("✓ 生产环境 JWT 认证已启用");
        return true;
    }

    /**
     * 检查 JWT 密钥配置（包可见，便于同包单测直接反证）。
     *
     * <p>【issue #674】此前只读 {@code System.getenv("DEV_JWT_SECRET")} / {@code JWT_SECRET}，
     * 既不认 PROD_JWT_SECRET，也 DEV 优先于绑定侧（绑定侧是 JWT_SECRET 优先）。现在：
     * 「是否已显式注入」看三个别名，「密钥是否可用」看绑定后的真实值 + 注入原值，
     * 两者缺一不可。</p>
     */
    boolean checkJwtSecrets() {
        log.info("正在检查 JWT 密钥配置...");

        final boolean production = isProductionEnvironment();
        // 显式注入的原始值：用于判定「生产必须显式配置」，以及拦截示例占位符。
        // 用 Environment 而非 System.getenv，既能读环境变量也能读 -D 系统属性，且可注入测试替身。
        final String injected = firstExplicitSecret();
        // 绑定后真正用于签名的值：可能已被外部挂载覆盖，或被启动期随机密钥替换。
        final String bound = securityProperties.getJwt() == null
                ? null : securityProperties.getJwt().getSecret();
        final String effective = (bound == null || bound.isBlank()) ? injected : bound;

        if (injected == null || injected.isBlank()) {
            log.warn("⚠️  未显式注入 JWT 密钥（JWT_SECRET / PROD_JWT_SECRET / DEV_JWT_SECRET 均为空）");
            if (production) {
                log.error("❌ 生产环境必须显式注入强密钥；启动期自动生成的随机密钥不适用于生产");
                // 关键：StartupSecurityEnvironmentPostProcessor 在 prod 同样会生成随机密钥，
                // 因此这里必须显式用 null 触发 fail-fast，不能把绑定后的随机值当成「已配置」。
                return StartupSecurityGate.jwtSecretOk(true, null);
            }
            log.warn("   非生产环境沿用 StartupSecurityEnvironmentPostProcessor 生成并持久化的随机密钥");
        } else if (StartupSecurityGate.isDefaultJwtSecret(injected)) {
            if (production) {
                log.error("❌ 显式注入的 JWT 密钥仍是出厂/文档示例值，生产环境禁止使用");
                return false;
            }
            log.warn("⚠️  注入的是示例/默认值，非生产环境将被启动期随机密钥替换");
        }

        if (effective == null || effective.isBlank()) {
            return StartupSecurityGate.jwtSecretOk(production, null);
        }
        // 绑定侧也可能被外部挂载改成默认值，同样按生产 fail-closed 处理。
        if (StartupSecurityGate.isDefaultJwtSecret(effective)) {
            if (production) {
                log.error("❌ 最终生效的 JWT 密钥仍为出厂/文档默认值，生产环境禁止使用");
                return false;
            }
            log.warn("⚠️  最终生效的密钥为示例/默认值，非生产环境仅告警");
        }

        ValidationResult result = SecretKeyValidator.validateJwtSecret(effective);

        switch (result.getStrengthLevel()) {
            case VERY_WEAK:
            case WEAK:
                log.error("❌ JWT 密钥强度：{} - {}", result.getStrengthLevel().getDisplayName(), result.getMessage());
                return false;
            case MEDIUM:
                log.warn("⚠️  JWT 密钥强度：{} - {}", result.getStrengthLevel().getDisplayName(), result.getMessage());
                log.warn("   建议用于生产环境前更换为更强的密钥");
                return true;  // 允许启动，但发出警告
            case STRONG:
            case VERY_STRONG:
                log.info("✓ JWT 密钥强度：{} - 通过检查", result.getStrengthLevel().getDisplayName());
                return true;
            default:
                return true;
        }
    }

    /**
     * 按配置占位符的优先级返回第一个非空显式注入值。
     * 走 {@link Environment} 而非直接 {@code System.getenv}，兼容 -D 系统属性并便于测试注入。
     */
    private String firstExplicitSecret() {
        if (environment == null) {
            return null;
        }
        for (String alias : JWT_SECRET_ALIASES) {
            String value = environment.getProperty(alias);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

}