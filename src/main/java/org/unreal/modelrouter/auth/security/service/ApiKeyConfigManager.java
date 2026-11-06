package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API Key 配置持久化管理器
 * 负责 API Key 配置的加载、保存和版本管理
 */
@Slf4j
@Component
public class ApiKeyConfigManager {

    private final StoreManager storeManager;
    private final String apiKeysStoreKey = "security.api-keys";
    private final String storeApiKeys = "apiKeys";

    public ApiKeyConfigManager(final StoreManager storeManager) {
        this.storeManager = storeManager;
    }

    /**
     * 保存 API Key 数据到存储
     */
    public void saveApiKeysToStore(final List<ApiKey> apiKeys) {
        try {
            Map<String, Object> config = new HashMap<>();
            config.put(storeApiKeys, apiKeys);
            storeManager.saveConfig(apiKeysStoreKey, config);
            log.debug("保存了 {} 个 API Key 到存储", apiKeys.size());
        } catch (Exception e) {
            log.error("保存 API Key 数据失败", e);
        }
    }

    /**
     * 检查是否存在持久化的账户配置
     */
    public boolean hasPersistedAccountConfig() {
        try {
            List<Integer> versions = storeManager.getConfigVersions(apiKeysStoreKey);
            if (!versions.isEmpty()) {
                return true;
            }
            return storeManager.exists(apiKeysStoreKey);
        } catch (Exception e) {
            log.warn("检查持久化 ApiKey 存在性时发生错误：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 YAML 配置初始化 API Key
     */
    public Map<String, Object> loadFromYaml(final List<ApiKey> yamlKeys) {
        Map<String, Object> config = new HashMap<>();
        config.put(storeApiKeys, yamlKeys);
        return config;
    }

    /**
     * 获取版本配置
     *
     * <p>E1: version=0 统一语义为「当前生效配置」（走 v0 当前行）；
     * version>=1 读不可变快照行。</p>
     */
    public Map<String, Object> getVersionConfig(final int version) {
        if (version == 0) {
            Map<String, Object> config = storeManager.getConfig(apiKeysStoreKey);
            if (config != null) {
                return config;
            }
            Map<String, Object> empty = new HashMap<>();
            empty.put(storeApiKeys, new java.util.ArrayList<>());
            return empty;
        }
        return storeManager.getConfigByVersion(apiKeysStoreKey, version);
    }

    /**
     * 保存新版本配置
     *
     * <p>E1: 版本快照只追加（isLatest=false），当前值由 v0 当前行承载。</p>
     */
    public int saveNewVersion(final Map<String, Object> config) {
        int version = getCurrentVersion() + 1;
        storeManager.saveConfigVersion(apiKeysStoreKey, config, version);
        // E1: 同步刷新当前行（saveConfig 只 upsert v0，不触碰快照）
        storeManager.saveConfig(apiKeysStoreKey, config);
        log.info("已保存 API Key 配置为新版本：{}", version);
        return version;
    }

    /**
     * 获取所有版本
     */
    public List<Integer> getAllVersions() {
        return storeManager.getConfigVersions(apiKeysStoreKey);
    }

    /**
     * 获取当前版本
     */
    public int getCurrentVersion() {
        List<Integer> versions = getAllVersions();
        return versions.isEmpty() ? 0 : versions.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * 加载当前生效的 API Key 配置
     *
     * <p>E1: 当前值 = v0 当前行（storeManager.getConfig 已按此语义实现，
     * 兼容迁移前无 v0 行的存量数据回退 isLatest 行）。调用方不要再走
     * 「getConfigVersions(max) + getConfigByVersion」拼接——那只代表快照，
     * 运行期追加的密钥只存在于 v0 当前行。</p>
     */
    public Map<String, Object> loadCurrentConfig() {
        return storeManager.getConfig(apiKeysStoreKey);
    }

    /**
     * 从版本配置中加载 API Keys
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadApiKeysFromVersionConfig(final Map<String, Object> versionConfig) {
        List<Map<String, Object>> keys = (List<Map<String, Object>>) versionConfig.get(storeApiKeys);
        return keys != null ? keys : new java.util.ArrayList<>();
    }
}
