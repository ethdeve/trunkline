package org.unreal.modelrouter.persistence.store;

import org.unreal.modelrouter.persistence.store.ConfigKeyCompatStoreManager;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 主配置 key 兼容装饰器（品牌清理：{@code model-router-config} → {@code trunkline-config}）。
 *
 * <p><b>背景</b>：JAiRouter 3.x 把主配置存储在 key {@code model-router-config} 下（含版本历史）；
 * 品牌清理后新 key 为 {@code trunkline-config}。为让存量部署无缝升级，本装饰器实现
 * <b>读回落</b>兼容：</p>
 * <ul>
 *   <li>读新 key（{@code getConfig} / {@code exists} / {@code getConfigVersions} /
 *       {@code getConfigByVersion}）时，若新 key 不存在且旧 key 存在，则回落读旧 key；</li>
 *   <li>写操作一律落新 key（{@code saveConfig} / {@code updateConfig}），自然完成向新 key 的迁移；</li>
 *   <li>删除新 key（{@code deleteConfig}）时同时删除旧 key，避免回落读取把已删除的配置"复活"。</li>
 * </ul>
 *
 * <p>装配点：{@link StoreManagerConfiguration#storeManager}（唯一的 {@code @Primary} 出口），
 * 对所有注入 {@link StoreManager} 的服务透明生效。</p>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team 品牌清理新增：主配置 key 旧→新读回落兼容
 * @since 3.3.1
 */
@Slf4j
public class ConfigKeyCompatStoreManager implements StoreManager {

    /** 主配置新 key */
    public static final String CURRENT_CONFIG_KEY = "trunkline-config";

    /** 主配置旧 key（JAiRouter 3.x） */
    public static final String LEGACY_CONFIG_KEY = "model-router-config";

    private final StoreManager delegate;

    /**
     * 构造兼容装饰器。
     *
     * @param delegate 实际存储后端，不能为 {@code null}
     */
    public ConfigKeyCompatStoreManager(final StoreManager delegate) {
        this.delegate = delegate;
        log.info("主配置 key 兼容装饰器已启用: {} → {}（旧 key 读回落）",
            LEGACY_CONFIG_KEY, CURRENT_CONFIG_KEY);
    }

    /**
     * 主配置当前 key（服务层统一引用，避免散落字面量）。
     *
     * @return 新 key
     */
    public static String currentConfigKey() {
        return CURRENT_CONFIG_KEY;
    }

    @Override
    public void saveConfig(final String key, final Map<String, Object> config) {
        delegate.saveConfig(key, config);
    }

    @Override
    public Map<String, Object> getConfig(final String key) {
        if (isCurrent(key) && !delegate.exists(CURRENT_CONFIG_KEY)
            && delegate.exists(LEGACY_CONFIG_KEY)) {
            log.debug("主配置新 key 不存在，回落读取旧 key: {}", LEGACY_CONFIG_KEY);
            return delegate.getConfig(LEGACY_CONFIG_KEY);
        }
        return delegate.getConfig(key);
    }

    @Override
    public void deleteConfig(final String key) {
        delegate.deleteConfig(key);
        if (isCurrent(key) && delegate.exists(LEGACY_CONFIG_KEY)) {
            // 防止回落读取把已删除的旧配置"复活"
            delegate.deleteConfig(LEGACY_CONFIG_KEY);
        }
    }

    @Override
    public Iterable<String> getAllKeys() {
        return delegate.getAllKeys();
    }

    @Override
    public boolean exists(final String key) {
        if (isCurrent(key)) {
            return delegate.exists(CURRENT_CONFIG_KEY) || delegate.exists(LEGACY_CONFIG_KEY);
        }
        return delegate.exists(key);
    }

    @Override
    public void updateConfig(final String key, final Map<String, Object> config) {
        if (isCurrent(key) && !delegate.exists(CURRENT_CONFIG_KEY)
            && delegate.exists(LEGACY_CONFIG_KEY)) {
            // 旧 key 存量：写入新 key，自然迁移（旧 key 保留至下次 deleteConfig 清理）
            delegate.saveConfig(CURRENT_CONFIG_KEY, config);
            return;
        }
        delegate.updateConfig(key, config);
    }

    @Override
    public void saveConfigVersion(final String key, final Map<String, Object> config, final int version) {
        delegate.saveConfigVersion(key, config, version);
    }

    @Override
    public List<Integer> getConfigVersions(final String key) {
        final List<Integer> versions = delegate.getConfigVersions(key);
        if ((versions == null || versions.isEmpty()) && isCurrent(key)) {
            final List<Integer> legacyVersions = delegate.getConfigVersions(LEGACY_CONFIG_KEY);
            if (legacyVersions != null && !legacyVersions.isEmpty()) {
                return legacyVersions;
            }
        }
        return versions;
    }

    @Override
    public Map<String, Object> getConfigByVersion(final String key, final int version) {
        final Map<String, Object> config = delegate.getConfigByVersion(key, version);
        if (config == null && isCurrent(key)) {
            return delegate.getConfigByVersion(LEGACY_CONFIG_KEY, version);
        }
        return config;
    }

    @Override
    public void deleteConfigVersion(final String key, final int version) {
        delegate.deleteConfigVersion(key, version);
    }

    @Override
    public boolean versionExists(final String key, final int version) {
        return delegate.versionExists(key, version);
    }

    @Override
    public String getVersionFilePath(final String key, final int version) {
        return delegate.getVersionFilePath(key, version);
    }

    @Override
    public java.time.LocalDateTime getVersionCreatedTime(final String key, final int version) {
        return delegate.getVersionCreatedTime(key, version);
    }

    @Override
    public Map<String, Object> getLatestConfig(final String configKey) {
        if (isCurrent(configKey)) {
            final Map<String, Object> config = delegate.getLatestConfig(configKey);
            if (config == null) {
                final Map<String, Object> legacy = delegate.getLatestConfig(LEGACY_CONFIG_KEY);
                if (legacy != null) {
                    log.debug("主配置新 key 无最新版本，回落读取旧 key: {}", LEGACY_CONFIG_KEY);
                    return legacy;
                }
            }
            return config;
        }
        return delegate.getLatestConfig(configKey);
    }

    private static boolean isCurrent(final String key) {
        return CURRENT_CONFIG_KEY.equals(key);
    }
}
