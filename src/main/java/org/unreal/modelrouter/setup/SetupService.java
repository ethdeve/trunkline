/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.util.SecretKeyValidator;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 首次部署初始化服务。
 *
 * <p>「是否已初始化」以业务库里是否存在 JWT 账户为准；默认管理员不再由 YAML 静态账户提供，
 * 必须由本服务在向导第 3 步显式创建。远程 PG 模式先把连接信息落盘并要求重启，
 * 重启后 JPA / Flyway 才会真正连到 PG，再创建管理员，避免在 H2 里建完又丢失。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@Service
public class SetupService {

    /** 管理员登录名：允许字母数字、点、下划线、连字符，3~64 位 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{3,64}$");

    /** 远程模式只接受 PostgreSQL JDBC URL */
    private static final String POSTGRES_PREFIX = "jdbc:postgresql:";

    private final SetupStateStore stateStore;
    private final JwtAccountRepository jwtAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @Value("${spring.datasource.url:}")
    private String currentDatasourceUrl;

    public SetupService(final SetupStateStore stateStore,
                        final JwtAccountRepository jwtAccountRepository,
                        final PasswordEncoder passwordEncoder,
                        final ObjectMapper objectMapper) {
        this.stateStore = stateStore;
        this.jwtAccountRepository = jwtAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    /** 当前初始化状态（不回传任何凭据）。 */
    public synchronized SetupStatus status() {
        final SetupState state = stateStore.read();
        final boolean initialized = isInitialized();
        final boolean restartRequired = !initialized && isRestartRequired(state);
        final String message;
        if (initialized) {
            message = "系统已完成初始化";
        } else if (restartRequired) {
            message = "数据源配置已保存，请重启服务后继续";
        } else {
            message = "系统尚未初始化，请完成初始化向导";
        }
        return new SetupStatus(initialized, state.mode().name(), stateStore.exists(),
                datasourceType(currentDatasourceUrl), restartRequired, message);
    }

    /** 步骤 1/2：选择数据源模式。远程模式会先验证连接再落盘。 */
    public synchronized DatasourceResult configureDatasource(final SetupMode mode,
                                                             final String datasourceUrl,
                                                             final String username,
                                                             final String password) {
        ensureNotInitialized();
        if (mode == null) {
            throw SetupException.invalid("请选择数据源模式");
        }

        if (mode == SetupMode.LOCAL) {
            stateStore.saveLocal();
            log.info("初始化向导选择本地服务模式（H2）");
            return new DatasourceResult(SetupMode.LOCAL.name(), isRestartRequired(stateStore.read()),
                    "本地服务模式已选择，可直接设置管理员密码");
        }

        final String url = requireText(datasourceUrl, "PostgreSQL JDBC URL");
        if (!url.toLowerCase().startsWith(POSTGRES_PREFIX)) {
            throw SetupException.invalid("远程数据源仅支持 PostgreSQL JDBC URL（jdbc:postgresql://...）");
        }
        final String user = requireText(username, "数据库用户名");
        final String pwd = requireText(password, "数据库密码");
        testPostgresConnection(url, user, pwd);

        stateStore.saveRemote(url, user, pwd);
        final boolean restartRequired = isRestartRequired(stateStore.read());
        log.info("初始化向导保存远程数据源: {}（重启后生效）", SetupEnvironmentPostProcessor.maskUrl(url));
        return new DatasourceResult(SetupMode.REMOTE.name(), restartRequired,
                restartRequired
                        ? "PostgreSQL 连接信息已保存，请重启服务后继续初始化管理员"
                        : "PostgreSQL 连接信息已生效，可继续初始化管理员");
    }

    /** 步骤 3：创建首个管理员账户（仅一次）。 */
    public synchronized AdminResult initializeAdmin(final String username, final String password) {
        ensureNotInitialized();
        if (isRestartRequired(stateStore.read())) {
            throw SetupException.restartRequired();
        }

        final String adminUsername = requireText(username, "管理员用户名").trim();
        if (!USERNAME_PATTERN.matcher(adminUsername).matches()) {
            throw SetupException.invalid("管理员用户名需为 3~64 位字母、数字、点、下划线或连字符");
        }
        if (jwtAccountRepository.existsByUsername(adminUsername)) {
            throw SetupException.invalid("管理员用户名已存在，请更换");
        }

        // 长度与强度分别判定，给出与事实相符的提示。
        // 旧实现把所有失败一律套上"强度不足（至少 12 位）"，导致 14 位但含弱模式的密码
        // 被错误提示为"不足 12 位"，自相矛盾。
        if (password == null || password.isEmpty()) {
            throw SetupException.invalid("管理员密码不能为空");
        }
        final int minLength = SecretKeyValidator.getMinPasswordLength();
        if (password.length() < minLength) {
            throw SetupException.invalid(String.format("管理员密码至少需要 %d 位（当前 %d 位）",
                    minLength, password.length()));
        }
        final SecretKeyValidator.ValidationResult strength = SecretKeyValidator.validatePassword(password);
        if (!strength.isPassed()) {
            // 直接透传强度校验的具体原因（如命中的弱片段），不再叠加误导性的长度话术
            throw SetupException.invalid(strength.getMessage());
        }

        final String rolesJson;
        try {
            rolesJson = objectMapper.writeValueAsString(List.of("ADMIN", "USER"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化管理员角色失败", e);
        }

        jwtAccountRepository.save(JwtAccountEntity.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(password))
                .roles(rolesJson)
                .enabled(true)
                .name("系统管理员")
                .build());
        log.info("初始化向导已创建首个管理员账户: {}", adminUsername);
        return new AdminResult(adminUsername, "管理员账户已创建，请使用新密码登录");
    }

    private boolean isInitialized() {
        return jwtAccountRepository.count() > 0;
    }

    /** 已选模式与当前实际数据源不一致时必须重启，否则管理员会建在错误的库里。 */
    private boolean isRestartRequired(final SetupState state) {
        final String current = currentDatasourceUrl == null ? "" : currentDatasourceUrl.trim();
        if (state.mode() == SetupMode.REMOTE) {
            return state.datasourceUrl() == null
                    || !state.datasourceUrl().trim().equalsIgnoreCase(current);
        }
        return !current.toLowerCase().startsWith("jdbc:h2:");
    }

    private void ensureNotInitialized() {
        if (isInitialized()) {
            throw SetupException.alreadyInitialized();
        }
    }

    private static String requireText(final String value, final String label) {
        if (value == null || value.isBlank()) {
            throw SetupException.invalid(label + "不能为空");
        }
        return value;
    }

    private static void testPostgresConnection(final String url, final String username, final String password) {
        try {
            DriverManager.setLoginTimeout(5);
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                if (!connection.isValid(5)) {
                    throw SetupException.invalid("PostgreSQL 连接测试失败：连接无效");
                }
            }
        } catch (SQLException e) {
            log.warn("初始化向导 PostgreSQL 连接测试失败: {}", e.getMessage());
            throw SetupException.invalid("PostgreSQL 连接测试失败：" + e.getMessage());
        }
    }

    private static String datasourceType(final String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        final String normalized = url.toLowerCase();
        if (normalized.startsWith("jdbc:h2:")) {
            return "H2";
        }
        if (normalized.startsWith(POSTGRES_PREFIX)) {
            return "PostgreSQL";
        }
        return "unknown";
    }

    /** 初始化状态响应体。 */
    public record SetupStatus(boolean initialized, String mode, boolean modeSelected, String datasource,
                              boolean restartRequired, String message) {
    }

    /** 数据源配置响应体。 */
    public record DatasourceResult(String mode, boolean restartRequired, String message) {
    }

    /** 管理员初始化响应体。 */
    public record AdminResult(String username, String message) {
    }
}
