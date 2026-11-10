/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Properties;

/**
 * 初始化向导状态存储。
 *
 * <p>状态落在工作目录的 {@code data/setup.properties}（Docker 部署即 {@code /app/data/setup.properties}），
 * 与业务数据库解耦：远程 PG 模式需要在「服务重启切换数据源」前后保持向导进度，
 * 因此不能把模式信息只放在即将被替换的 H2 库里。</p>
 *
 * <p>数据库密码会以明文写入该文件，权限跟随工作目录；部署时应确保 {@code data/} 仅对服务账号可读。
 * 状态接口绝不回传密码。</p>
 *
 * @author Trunkline Team
 */
@Component
public class SetupStateStore {

    /** 默认状态文件（相对工作目录） */
    public static final String DEFAULT_STATE_FILE = "./data/setup.properties";

    /** 可覆盖状态文件路径的系统属性 / 环境变量名 */
    public static final String STATE_FILE_PROPERTY = "trunkline.setup.state-file";

    private static final Logger log = LoggerFactory.getLogger(SetupStateStore.class);

    private final Path stateFile;

    /** Spring 装配构造：读取默认或显式配置的状态文件路径。 */
    public SetupStateStore() {
        this(Path.of(System.getProperty(STATE_FILE_PROPERTY, DEFAULT_STATE_FILE)));
    }

    /** 测试用构造：注入临时状态文件。 */
    public SetupStateStore(final Path stateFile) {
        this.stateFile = stateFile;
    }

    /**
     * 解析状态文件路径（供 EnvironmentPostProcessor 在 Bean 容器之外复用同一口径）。
     */
    public static Path resolveStateFile(final Environment environment) {
        String configured = environment == null ? null : environment.getProperty(STATE_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty(STATE_FILE_PROPERTY, DEFAULT_STATE_FILE);
        }
        return Path.of(configured);
    }

    /** 是否已经保存过模式选择（用于区分「首次进入」与「中断后续做」）。 */
    public synchronized boolean exists() {
        return Files.exists(stateFile);
    }

    /** 读取当前状态；文件不存在或解析失败时按「本地模式、未选择过」处理。 */
    public synchronized SetupState read() {
        if (!Files.exists(stateFile)) {
            return SetupState.defaultLocal();
        }
        final Properties props = new Properties();
        try (InputStream in = Files.newInputStream(stateFile, StandardOpenOption.READ)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("读取初始化状态文件失败，按本地模式处理: {} -> {}", stateFile, e.getMessage());
            return SetupState.defaultLocal();
        }

        final String mode = props.getProperty("mode");
        if (!"REMOTE".equalsIgnoreCase(mode)) {
            return SetupState.local(props.getProperty("updatedAt"));
        }
        return SetupState.remote(
                props.getProperty("datasource.url"),
                props.getProperty("datasource.username"),
                props.getProperty("datasource.password"),
                props.getProperty("updatedAt"));
    }

    /** 写入本地模式状态（同时清除历史远程连接信息，避免残留密码）。 */
    public synchronized void saveLocal() {
        final Properties props = new Properties();
        props.setProperty("mode", "LOCAL");
        props.setProperty("updatedAt", Instant.now().toString());
        write(props);
    }

    /** 写入远程模式状态。 */
    public synchronized void saveRemote(final String datasourceUrl, final String username, final String password) {
        final Properties props = new Properties();
        props.setProperty("mode", "REMOTE");
        props.setProperty("datasource.url", datasourceUrl);
        props.setProperty("datasource.username", username);
        props.setProperty("datasource.password", password);
        props.setProperty("updatedAt", Instant.now().toString());
        write(props);
    }

    private void write(final Properties props) {
        try {
            Files.createDirectories(stateFile.getParent());
            try (OutputStream out = Files.newOutputStream(stateFile, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                props.store(out, "Trunkline setup wizard state - keep this file private");
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存初始化状态失败: " + e.getMessage(), e);
        }
    }
}
