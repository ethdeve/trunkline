package org.unreal.modelrouter.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产 fail-closed 安全默认值绑定守护（issue #118 / 生产配置必做）。
 *
 * <p>M-14/M-16 修复把“黑名单存储读失败静默放行”升级为“ERROR 日志 + 按
 * {@code blacklistFailClosedWhenUnavailable} 开关决策”，但 Java 基础默认仍是 false
 * （fail-open，可用性优先）。生产必须显式置 true 才拿到完整安全态。</p>
 *
 * <p>本测试用 Spring 自己的 {@link YamlPropertySourceLoader} + {@link Binder} 真实绑定
 * 仓库里的 yml（不是文本断言），确保：
 * <ul>
 *   <li>{@code application-prod.yml} 绑定后该开关确为 true（写了却不生效会红）；</li>
 *   <li>基础 {@code config/auth/jwt.yml} 仍为 false——证明 prod=true 来自 profile 覆盖，
 *       而非有人误改了 Java 默认（那会连 dev / 测试环境一起收紧）；</li>
 *   <li>prod 的 jwt 段其它键绑定不被 secret 占位符阻断。</li>
 * </ul>
 */
@DisplayName("生产黑名单 fail-closed 默认值绑定守护")
class ProdBlacklistFailClosedBindingTest {

    private static final String PROD_YML = "application-prod.yml";
    private static final String BASE_JWT_YML = "config/auth/jwt.yml";

    /** 读仓库里真实的 yml（不是测试专用夹具），绑定 trunkline.security。 */
    private SecurityProperties bindSecurity(final String location) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load(location, new ClassPathResource(location))
                .forEach(ps -> environment.getPropertySources().addLast(ps));
        return Binder.get(environment)
                .bind("trunkline.security", Bindable.of(SecurityProperties.class))
                .orElseGet(SecurityProperties::new);
    }

    @Test
    @DisplayName("prod profile 真实绑定为 fail-closed=true")
    void prodDefaultsToFailClosed() throws Exception {
        assertTrue(bindSecurity(PROD_YML).getJwt().isBlacklistFailClosedWhenUnavailable(),
                "application-prod.yml 必须把 blacklist-fail-closed-when-unavailable 绑成 true");
    }

    @Test
    @DisplayName("基础 jwt.yml 仍为 false——证明 prod=true 来自 profile 覆盖而非 Java 默认")
    void baseStaysFailOpen() throws Exception {
        assertFalse(bindSecurity(BASE_JWT_YML).getJwt().isBlacklistFailClosedWhenUnavailable(),
                "基础模块应保持 fail-open（可用性优先）；若此处为 true，说明 Java 默认被误改");
    }

    @Test
    @DisplayName("prod 的 jwt 段其它键绑定不被 secret 占位符阻断")
    void prodJwtOtherKeysStillBind() throws Exception {
        SecurityProperties prod = bindSecurity(PROD_YML);
        assertTrue(prod.getJwt().isEnabled(), "prod 下 jwt.enabled 应绑定为 true");
        assertEquals("trunkline-prod", prod.getJwt().getIssuer(), "prod issuer 应绑定为 trunkline-prod");
    }
}
