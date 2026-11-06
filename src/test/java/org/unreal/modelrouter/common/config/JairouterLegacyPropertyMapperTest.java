package org.unreal.modelrouter.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link JairouterLegacyPropertyMapper} 单元测试。
 *
 * <p>验证三件事：旧前缀点分键映射、旧前缀环境变量映射、新前缀显式配置优先。
 *
 * @author Trunkline Team
 * @since 0.1.0
 */
class JairouterLegacyPropertyMapperTest {

    private StandardEnvironment environment;
    private JairouterLegacyPropertyMapper mapper;

    @BeforeEach
    void setUp() {
        environment = new StandardEnvironment();
        mapper = new JairouterLegacyPropertyMapper();
        JairouterLegacyPropertyMapper.DETECTED_LEGACY_KEYS.clear();
    }

    @AfterEach
    void tearDown() {
        JairouterLegacyPropertyMapper.DETECTED_LEGACY_KEYS.clear();
    }

    @Test
    @DisplayName("旧前缀点分键映射为新前缀键")
    void mapsDottedLegacyKeys() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("jairouter.security.enabled", "true");
        legacy.put("jairouter.quota.limit", "100");
        environment.getPropertySources().addFirst(new MapPropertySource("legacy-yml", legacy));

        mapper.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("true", environment.getProperty("trunkline.security.enabled"));
        assertEquals("100", environment.getProperty("trunkline.quota.limit"));
    }

    @Test
    @DisplayName("旧前缀环境变量映射为新前缀（宽松解析可点分取值）")
    void mapsLegacyEnvironmentVariables() {
        Map<String, Object> envVars = new HashMap<>();
        envVars.put("JAIROUTER_SECURITY_TOKEN_TTL", "3600");
        environment.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("systemEnvironment", envVars));

        mapper.postProcessEnvironment(environment, new SpringApplication());

        // 直接新环境变量名可取
        assertEquals("3600", environment.getProperty("TRUNKLINE_SECURITY_TOKEN_TTL"));
        // 点分形式经 SystemEnvironmentPropertySource 宽松解析同样可取
        assertEquals("3600", environment.getProperty("trunkline.security.token.ttl"));
    }

    @Test
    @DisplayName("新前缀显式配置优先于旧前缀映射")
    void explicitNewPrefixWins() {
        Map<String, Object> explicit = new HashMap<>();
        explicit.put("trunkline.quota.limit", "200");
        // 最高优先级来源：用户已显式写新前缀
        environment.getPropertySources().addFirst(new MapPropertySource("explicit-new", explicit));
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("jairouter.quota.limit", "100");
        environment.getPropertySources().addLast(new MapPropertySource("legacy-yml", legacy));

        mapper.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("200", environment.getProperty("trunkline.quota.limit"));
    }

    @Test
    @DisplayName("环境变量中新前缀显式存在时不做旧前缀映射")
    void explicitNewEnvVarWins() {
        Map<String, Object> envVars = new HashMap<>();
        envVars.put("JAIROUTER_SECURITY_TOKEN_TTL", "3600");
        envVars.put("TRUNKLINE_SECURITY_TOKEN_TTL", "9999");
        environment.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("systemEnvironment", envVars));

        mapper.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("9999", environment.getProperty("TRUNKLINE_SECURITY_TOKEN_TTL"));
        assertEquals("9999", environment.getProperty("trunkline.security.token.ttl"));
    }

    @Test
    @DisplayName("没有旧前缀键时不注入映射属性源")
    void noLegacyKeysNoInjection() {
        Map<String, Object> modern = new HashMap<>();
        modern.put("trunkline.security.enabled", "true");
        environment.getPropertySources().addFirst(new MapPropertySource("modern", modern));

        mapper.postProcessEnvironment(environment, new SpringApplication());

        assertNull(environment.getPropertySources().get(JairouterLegacyPropertyMapper.SOURCE_NAME));
    }
}
