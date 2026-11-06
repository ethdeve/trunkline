package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.model.UsageStatistics;
import org.unreal.modelrouter.auth.security.util.ApiKeyHashUtil;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API Key 持久化服务
 * 负责 API Key 的存储、加载和版本管理
 *
 * @since v2.14.2
 */
@Slf4j
@Service
public class ApiKeyPersistenceService {

    private static final String API_KEYS_STORE_KEY = "security.api-keys";
    public static final String STORE_API_KEYS = "apiKeys";

    private final StoreManager storeManager;
    private final ObjectMapper objectMapper;

    // API Key 配置管理器（用于版本管理）
    @Autowired(required = false)
    private ApiKeyConfigManager apiKeyConfigManager;

    /** O(1) 查找索引（C6 整改）：在明文 keyValue 可用时补全确定性 lookupHash */
    @Autowired(required = false)
    private ApiKeyLookupIndex apiKeyLookupIndex;

    public ApiKeyPersistenceService(final StoreManager storeManager,
                                     final ObjectMapper objectMapper) {
        this.storeManager = storeManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存 API Key 数据到存储
     *
     * @param apiKeyCache API Key 缓存（keyHash -> ApiKey）
     */
    public void saveApiKeysToStore(final Map<String, ApiKey> apiKeyCache) {
        try {
            List<ApiKey> apiKeys = new ArrayList<>(apiKeyCache.values());
            Map<String, Object> config = new HashMap<>();
            config.put(STORE_API_KEYS, apiKeys);
            storeManager.saveConfig(API_KEYS_STORE_KEY, config);
            log.debug("保存了 {} 个API Key到存储", apiKeys.size());
        } catch (Exception e) {
            log.error("保存API Key数据失败", e);
        }
    }

    /**
     * 检查是否有持久化的 API Key 配置
     *
     * @return 是否存在持久化配置
     */
    public boolean hasPersistedAccountConfig() {
        try {
            List<Integer> versions = storeManager.getConfigVersions(API_KEYS_STORE_KEY);
            if (!versions.isEmpty()) {
                return true;
            }
            return storeManager.exists(API_KEYS_STORE_KEY);
        } catch (Exception e) {
            log.warn("检查持久化ApiKey存在性时发生错误: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 YAML 配置初始化 API Key
     *
     * @param apiKeyCache    API Key 缓存
     * @param keyIdIndex     ID 索引
     * @param yamlApiKeys    YAML 配置中的 API Keys
     */
    public void initializeApiKeyFromYaml(final Map<String, ApiKey> apiKeyCache,
                                          final Map<String, String> keyIdIndex,
                                          final List<ApiKey> yamlApiKeys) {
        log.info("首次启动，将YAML API Key配置保存为版本1");

        try {
            Map<String, Object> defaultConfig = new HashMap<>();
            defaultConfig.put(STORE_API_KEYS, yamlApiKeys);

            // 保存配置版本
            if (apiKeyConfigManager != null) {
                apiKeyConfigManager.saveNewVersion(defaultConfig);
            } else {
                // 兼容旧实现：直接使用 storeManager（E1: saveNewVersion 等价于
                // 追加快照 + 刷新 v0 当前行，这里手工对齐两步）
                List<Integer> versions = storeManager.getConfigVersions(API_KEYS_STORE_KEY);
                int version = versions.isEmpty() ? 1 : versions.stream().max(Integer::compareTo).orElse(0) + 1;
                storeManager.saveConfigVersion(API_KEYS_STORE_KEY, defaultConfig, version);
                storeManager.saveConfig(API_KEYS_STORE_KEY, defaultConfig);
            }

            // 迁移并加载到缓存
            yamlApiKeys.forEach(item -> {
                // 对旧的明文 keyValue 进行哈希迁移
                if (item.getKeyValue() != null && !ApiKeyHashUtil.isHashedFormat(item.getKeyValue())) {
                    String keyHash = ApiKeyHashUtil.hashApiKey(item.getKeyValue());
                    item.setKeyHash(keyHash);
                    if (apiKeyLookupIndex != null) {
                        apiKeyLookupIndex.attachLookupHash(item, item.getKeyValue());
                    }
                    item.setKeyValue(null);  // 清除明文
                    item.setKeyPrefix("sk-");
                    log.info("迁移API Key {} 从明文存储到哈希存储", item.getKeyId());
                }

                if (item.getKeyHash() != null && !apiKeyCache.containsKey(item.getKeyHash())) {
                    apiKeyCache.put(item.getKeyHash(), item);
                    keyIdIndex.put(item.getKeyId(), item.getKeyHash());
                }
            });

            // 保存迁移后的配置
            saveApiKeysToStore(apiKeyCache);
            log.info("YAML API Key配置已保存为版本1，并完成哈希迁移");
        } catch (Exception e) {
            log.error("从YAML配置初始化API Key配置失败", e);
            throw new RuntimeException("Failed to initialize API keys from YAML config", e);
        }
    }

    /**
     * 加载最新的 API Key 配置
     *
     * @param apiKeyCache    API Key 缓存
     * @param keyIdIndex     ID 索引
     * @param yamlApiKeys    YAML 配置中的 API Keys（用于回退）
     */
    public void loadLatestApiKeyConfig(final Map<String, ApiKey> apiKeyCache,
                                         final Map<String, String> keyIdIndex,
                                         final List<ApiKey> yamlApiKeys) {
        log.info("发现持久化API Key配置，加载最新版本");

        try {
            // E1: 当前值 = v0 当前行（运行期追加的密钥只在这里；快照行是只读历史）。
            // 旧路径「getConfigVersions(max) + getConfigByVersion(max)」会读到冻结快照，已废弃。
            Map<String, Object> versionConfig = storeManager.getConfig(API_KEYS_STORE_KEY);

            List<Map<String, Object>> keys = versionConfig != null
                    ? (List<Map<String, Object>>) versionConfig.get(STORE_API_KEYS)
                    : null;
            log.debug("从当前配置加载了 {} 个API Key配置", keys != null ? keys.size() : 0);

            // 如果持久化数据为空或无效，从 YAML 重新初始化
            if (keys == null || keys.isEmpty()) {
                log.warn("持久化API Key配置为空，从YAML重新初始化");
                initializeApiKeyFromYaml(apiKeyCache, keyIdIndex, yamlApiKeys);
                return;
            }

            keys.stream()
                    .map(item -> objectMapper.convertValue(item, ApiKey.class))
                    .forEach(item -> {
                        initializeApiKeyFields(item);

                        // 对旧的明文 keyValue 进行哈希迁移
                        if (item.getKeyValue() != null && !ApiKeyHashUtil.isHashedFormat(item.getKeyValue())) {
                            String keyHash = ApiKeyHashUtil.hashApiKey(item.getKeyValue());
                            item.setKeyHash(keyHash);
                            if (apiKeyLookupIndex != null) {
                                apiKeyLookupIndex.attachLookupHash(item, item.getKeyValue());
                            }
                            item.setKeyValue(null);
                            item.setKeyPrefix("sk-");
                            log.info("迁移API Key {} 从明文存储到哈希存储", item.getKeyId());
                        }

                        if (item.getKeyHash() != null && !apiKeyCache.containsKey(item.getKeyHash())) {
                            apiKeyCache.put(item.getKeyHash(), item);
                            keyIdIndex.put(item.getKeyId(), item.getKeyHash());
                        } else if (item.getKeyHash() == null && item.getKeyValue() == null) {
                            log.warn("API Key {} 没有有效的keyHash或keyValue，跳过", item.getKeyId());
                        }
                    });

            // 如果缓存仍然为空，说明数据无效，从YAML重新初始化
            if (apiKeyCache.isEmpty()) {
                log.warn("加载后的API Key缓存为空，从YAML重新初始化");
                initializeApiKeyFromYaml(apiKeyCache, keyIdIndex, yamlApiKeys);
                return;
            }

            // 保存迁移后的配置
            saveApiKeysToStore(apiKeyCache);
            log.info("已加载API Key当前配置（v0 行），共 {} 个密钥", apiKeyCache.size());
        } catch (Exception e) {
            log.error("加载持久化API Key配置失败", e);
            throw new RuntimeException("Failed to load persisted API key config", e);
        }
    }
    
    /**
     * 加载持久化配置并与 YAML 配置合并（YAML 优先）
     * v2.x 修复：解决 YAML 中定义的 API Key 无法生效的问题
     *
     * @param apiKeyCache    API Key 缓存
     * @param keyIdIndex     ID 索引
     * @param yamlApiKeys    YAML 配置中的 API Keys
     */
    public void loadAndMergeWithYaml(final Map<String, ApiKey> apiKeyCache,
                                      final Map<String, String> keyIdIndex,
                                      final List<ApiKey> yamlApiKeys) {
        log.info("合并持久化API Key配置与YAML配置（YAML优先）");
        
        try {
            // 1. 先加载持久化配置（E1: 当前值 = v0 当前行）
            Map<String, Object> versionConfig = storeManager.getConfig(API_KEYS_STORE_KEY);

            List<Map<String, Object>> persistedKeys = versionConfig != null
                    ? (List<Map<String, Object>>) versionConfig.get(STORE_API_KEYS)
                    : null;
            
            // 2. 加载持久化的 API Key 到缓存
            if (persistedKeys != null && !persistedKeys.isEmpty()) {
                persistedKeys.stream()
                        .map(item -> objectMapper.convertValue(item, ApiKey.class))
                        .forEach(item -> {
                            initializeApiKeyFields(item);

                            // 对旧的明文 keyValue 进行哈希迁移
                            if (item.getKeyValue() != null && !ApiKeyHashUtil.isHashedFormat(item.getKeyValue())) {
                                String keyHash = ApiKeyHashUtil.hashApiKey(item.getKeyValue());
                                item.setKeyHash(keyHash);
                                if (apiKeyLookupIndex != null) {
                                    apiKeyLookupIndex.attachLookupHash(item, item.getKeyValue());
                                }
                                item.setKeyValue(null);
                                item.setKeyPrefix("sk-");
                                log.info("迁移API Key {} 从明文存储到哈希存储", item.getKeyId());
                            }

                            if (item.getKeyHash() != null && !apiKeyCache.containsKey(item.getKeyHash())) {
                                apiKeyCache.put(item.getKeyHash(), item);
                                keyIdIndex.put(item.getKeyId(), item.getKeyHash());
                            }
                        });
                log.debug("从持久化加载了 {} 个API Key", apiKeyCache.size());
            }
            
            // 3. 合并 YAML 配置（YAML 优先，覆盖同名 keyId）
            int mergedCount = 0;
            for (ApiKey yamlKey : yamlApiKeys) {
                String keyId = yamlKey.getKeyId();
                
                // 处理明文 keyValue
                if (yamlKey.getKeyValue() != null && !ApiKeyHashUtil.isHashedFormat(yamlKey.getKeyValue())) {
                    String keyHash = ApiKeyHashUtil.hashApiKey(yamlKey.getKeyValue());
                    yamlKey.setKeyHash(keyHash);
                    yamlKey.setKeyValue(null);
                    yamlKey.setKeyPrefix("sk-");
                }
                
                // 如果已存在同名 keyId，先移除旧的
                String existingKeyHash = keyIdIndex.get(keyId);
                if (existingKeyHash != null) {
                    apiKeyCache.remove(existingKeyHash);
                    log.debug("移除旧的API Key: {} (keyId={})", existingKeyHash, keyId);
                }
                
                // 添加 YAML 中的新配置
                if (yamlKey.getKeyHash() != null) {
                    apiKeyCache.put(yamlKey.getKeyHash(), yamlKey);
                    keyIdIndex.put(keyId, yamlKey.getKeyHash());
                    mergedCount++;
                    log.info("合并YAML API Key: {} (keyId={})", yamlKey.getKeyHash().substring(0, 16) + "...", keyId);
                }
            }
            
            log.info("合并完成：持久化 {} 个，YAML 合并 {} 个，总计 {} 个API Key", 
                    persistedKeys != null ? persistedKeys.size() : 0, mergedCount, apiKeyCache.size());
            
            // 4. 保存合并后的配置
            if (mergedCount > 0) {
                saveApiKeysToStore(apiKeyCache);
                log.info("已保存合并后的API Key配置");
            }
        } catch (Exception e) {
            log.error("合并API Key配置失败，回退到YAML初始化: {}", e.getMessage());
            // 失败时回退到 YAML 初始化
            initializeApiKeyFromYaml(apiKeyCache, keyIdIndex, yamlApiKeys);
        }
    }

    /**
     * 获取下一个版本号
     *
     * @return 下一个版本号
     */
    public int getNextAccountVersion() {
        List<Integer> versions = storeManager.getConfigVersions(API_KEYS_STORE_KEY);
        return versions.isEmpty() ? 1 : versions.stream().max(Integer::compareTo).orElse(0) + 1;
    }

    /**
     * 获取当前版本号
     *
     * @return 当前版本号
     */
    public int getCurrentVersion() {
        return storeManager.getConfigVersions(API_KEYS_STORE_KEY).stream()
                .max(Integer::compareTo)
                .orElse(0);
    }

    /**
     * 获取所有版本列表
     *
     * @return 版本号列表
     */
    public List<Integer> getAllVersions() {
        return storeManager.getConfigVersions(API_KEYS_STORE_KEY);
    }

    /**
     * 加载指定版本的配置
     *
     * <p>E1: version=0 统一语义为「当前生效配置」（走 v0 当前行）；
     * version>=1 读不可变快照行。</p>
     *
     * @param version 版本号
     * @return 配置数据
     */
    public Map<String, Object> getVersionConfig(final int version) {
        if (version == 0) {
            return storeManager.getConfig(API_KEYS_STORE_KEY);
        }
        if (apiKeyConfigManager != null) {
            return apiKeyConfigManager.getVersionConfig(version);
        }
        return storeManager.getConfigByVersion(API_KEYS_STORE_KEY, version);
    }

    /**
     * 初始化使用统计
     *
     * <p>P0-A：除日桶外，一并初始化小时 / 月窗口桶（多窗口配额判定口径）。
     * 存量密钥从存储反序列化时这四个桶为 {@code null}，若不补空 Map，
     * 首次写入用量会走懒初始化分支——但判定侧读的是 {@code getOrDefault}，
     * 空 Map 与 null 行为一致；此处显式补空是为了让「已初始化」的语义一致，
     * 避免后续新增字段时再出现半初始化状态。</p>
     *
     * @param apiKey ApiKey 实体
     */
    public void initializeUsageStatistics(final ApiKey apiKey) {
        if (apiKey.getUsage() == null) {
            apiKey.setUsage(UsageStatistics.builder()
                    .dailyUsage(new HashMap<>())
                    .dailyTokenUsage(new HashMap<>())
                    .hourlyUsage(new HashMap<>())
                    .hourlyTokenUsage(new HashMap<>())
                    .monthlyUsage(new HashMap<>())
                    .monthlyTokenUsage(new HashMap<>())
                    .failedRequests(0L)
                    .lastUsedAt(null)
                    .successfulRequests(0L)
                    .totalRequests(0L)
                    .build());
        } else {
            if (apiKey.getUsage().getDailyUsage() == null) {
                apiKey.getUsage().setDailyUsage(new HashMap<>());
            }
            if (apiKey.getUsage().getDailyTokenUsage() == null) {
                apiKey.getUsage().setDailyTokenUsage(new HashMap<>());
            }
            if (apiKey.getUsage().getHourlyUsage() == null) {
                apiKey.getUsage().setHourlyUsage(new HashMap<>());
            }
            if (apiKey.getUsage().getHourlyTokenUsage() == null) {
                apiKey.getUsage().setHourlyTokenUsage(new HashMap<>());
            }
            if (apiKey.getUsage().getMonthlyUsage() == null) {
                apiKey.getUsage().setMonthlyUsage(new HashMap<>());
            }
            if (apiKey.getUsage().getMonthlyTokenUsage() == null) {
                apiKey.getUsage().setMonthlyTokenUsage(new HashMap<>());
            }
        }
    }

    /**
     * 从共享存储刷新 API Key 缓存（跨副本最终一致，#162）。
     *
     * <p>与 {@link #loadLatestApiKeyConfig} 的关键差异：本方法按存储的最新内容<b>替换</b>缓存——
     * 既补入其它副本新建的 Key，也移除其它副本已删除的 Key；且<b>不回写</b>存储，避免用本副本
     * 的旧视图覆盖兄弟副本的变更（{@code loadLatestApiKeyConfig} 只增不减且末尾会全量回写，
     * 不能用作刷新）。</p>
     *
     * <p>为规避「清空再填充」造成的空窗——校验路径在空窗内会把有效 Key 判为不存在——实现为
     * 「先 putAll 新内容、再移除多余项」，任一时刻缓存都是旧值 ∪ 新值的超集。</p>
     *
     * <p>存储读不到配置、内容为空、或条目全部缺少 {@code keyHash} 时<b>不修改缓存</b>并返回
     * {@code -1}：宁可让已删除的 Key 多存活一轮，也不要把全部有效 Key 剔除导致服务不可用。</p>
     *
     * @param apiKeyCache 待刷新的缓存（keyHash → ApiKey）
     * @param keyIdIndex  待刷新的 ID 索引（keyId → keyHash）
     * @return 刷新后的条目数；未发生刷新时返回 {@code -1}
     */
    /**
     * 刷新 API Key 缓存（跨副本最终一致，#162）。
     *
     * <p>E1: 当前值 = v0 当前行（运行期变更只落当前行，快照行是只读历史）。</p>
     *
     * @param apiKeyCache 待刷新的缓存（keyHash → ApiKey）
     * @param keyIdIndex  待刷新的 ID 索引（keyId → keyHash）
     * @return 刷新后的条目数；未发生刷新时返回 {@code -1}
     */
    public int refreshApiKeyCache(final Map<String, ApiKey> apiKeyCache,
                                  final Map<String, String> keyIdIndex) {
        try {
            Map<String, Object> versionConfig = storeManager.getConfig(API_KEYS_STORE_KEY);
            if (versionConfig == null) {
                log.debug("刷新 API Key 缓存：存储中暂无当前配置，保留现有缓存");
                return -1;
            }

            Object raw = versionConfig.get(STORE_API_KEYS);
            if (!(raw instanceof List<?> items) || items.isEmpty()) {
                log.debug("刷新 API Key 缓存：当前配置无 API Key 条目，保留现有缓存");
                return -1;
            }

            Map<String, ApiKey> fresh = new HashMap<>();
            Map<String, String> freshIndex = new HashMap<>();
            for (Object item : items) {
                ApiKey apiKey = objectMapper.convertValue(item, ApiKey.class);
                initializeApiKeyFields(apiKey);
                if (apiKey.getKeyHash() == null || apiKey.getKeyId() == null) {
                    continue;
                }
                fresh.put(apiKey.getKeyHash(), apiKey);
                freshIndex.put(apiKey.getKeyId(), apiKey.getKeyHash());
            }
            if (fresh.isEmpty()) {
                log.warn("刷新 API Key 缓存：当前配置的条目均缺少 keyHash，保留现有缓存");
                return -1;
            }

            apiKeyCache.putAll(fresh);
            apiKeyCache.keySet().retainAll(fresh.keySet());
            keyIdIndex.putAll(freshIndex);
            keyIdIndex.keySet().retainAll(freshIndex.keySet());

            log.debug("API Key 缓存已按当前配置刷新，共 {} 个密钥", fresh.size());
            return fresh.size();
        } catch (Exception e) {
            log.warn("刷新 API Key 缓存失败，保留现有缓存: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 初始化 ApiKey 基本字段
     *
     * @param apiKey ApiKey 实体
     */
    public void initializeApiKeyFields(final ApiKey apiKey) {
        if (apiKey.getCreatedAt() == null) {
            apiKey.setCreatedAt(java.time.LocalDateTime.now());
        }
        if (apiKey.getUsage() == null) {
            initializeUsageStatistics(apiKey);
        }
        if (apiKey.getKeyPrefix() == null) {
            apiKey.setKeyPrefix("sk-");
        }
        if (apiKey.getDailyRequestLimit() <= 0L) {
            apiKey.setDailyRequestLimit(0L);
        }
        // 持有人兜底：存量密钥没有 holder 字段，回落为创建者，
        // 保证「全部密钥」列表的「持有人」列口径统一（无需等用户编辑一次才生效）
        if ((apiKey.getHolder() == null || apiKey.getHolder().isBlank())
                && apiKey.getCreatedBy() != null && !apiKey.getCreatedBy().isBlank()) {
            apiKey.setHolder(apiKey.getCreatedBy());
        }
    }
}