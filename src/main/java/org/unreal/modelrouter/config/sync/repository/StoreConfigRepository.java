package org.unreal.modelrouter.config.sync.repository;

import org.unreal.modelrouter.config.core.ConfigMergeService;
import org.unreal.modelrouter.persistence.store.ConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.config.core.dto.ServiceConfiguration;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 服务配置仓库（基于 StoreManager）
 *
 * 负责服务配置的数据访问，封装 StoreManager 的底层操作。
 * 提供强类型的 ServiceConfiguration 访问接口。
 *
 * <p>读取链与分类目录（{@code ConfigMergeService#getPersistedConfig}）同源：
 * 当前配置行（v0）读不到时回退版本快照，再回退 YAML 默认——否则
 * 「目录能列出分类、单查却报不存在」的读分裂会把编辑页打成 500
 * （2026-11-21 模型分类适配器列全空 + 编辑报服务内部错误的根因）。</p>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Component("storeConfigRepository")
public class StoreConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(StoreConfigRepository.class);
    private static final String CONFIG_KEY = ConfigKeys.CURRENT_CONFIG_KEY;

    private final StoreManager storeManager;
    private final ConfigMergeService configMergeService;

    public StoreConfigRepository(final StoreManager storeManager,
                                 final ConfigMergeService configMergeService) {
        this.storeManager = storeManager;
        this.configMergeService = configMergeService;
    }

    /**
     * 读取合并后的主配置：当前配置行优先，读不到时走 ConfigMergeService 的
     * 版本快照 / YAML 默认回退链，保证与 getAvailableServiceTypes 目录同源。
     */
    private Map<String, Object> loadMergedConfig() {
        Map<String, Object> config = storeManager.getConfig(CONFIG_KEY);
        if (config == null) {
            logger.warn("当前持久化配置不可读（v0 行缺失），回退版本快照 / YAML 默认：key={}", CONFIG_KEY);
            config = configMergeService.getPersistedConfig();
        }
        return config;
    }

    /**
     * 获取所有服务配置
     *
     * @return 服务配置 Map，key 为 serviceType
     */
    public Map<String, ServiceConfiguration> findAll() {
        try {
            Map<String, Object> config = loadMergedConfig();
            if (config == null || !config.containsKey("services")) {
                return Map.of();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> services = (Map<String, Object>) config.get("services");
            return ServiceConfiguration.fromServicesMap(services);

        } catch (Exception e) {
            logger.error("获取所有服务配置失败：error={}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 获取指定服务配置
     *
     * @param serviceType 服务类型
     * @return 服务配置，不存在返回 Optional.empty()
     */
    public Optional<ServiceConfiguration> findById(final String serviceType) {
        try {
            Map<String, Object> config = loadMergedConfig();
            if (config == null || !config.containsKey("services")) {
                return Optional.empty();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> services = (Map<String, Object>) config.get("services");
            @SuppressWarnings("unchecked")
            Map<String, Object> serviceMap = (Map<String, Object>) services.get(serviceType);

            return Optional.ofNullable(serviceMap != null ? ServiceConfiguration.fromMap(serviceMap) : null);

        } catch (Exception e) {
            logger.error("获取服务配置失败：serviceType={}, error={}", serviceType, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 保存服务配置
     *
     * @param serviceType 服务类型
     * @param config 服务配置
     */
    public void save(final String serviceType, final ServiceConfiguration config) {
        logger.info("保存服务配置：serviceType={}", serviceType);

        try {
            Map<String, Object> currentConfig = getCurrentConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> services = (Map<String, Object>) currentConfig.get("services");

            // 保存配置
            services.put(serviceType, config.toMap());

            // 添加版本元数据
            addMetadata(currentConfig, "save", "保存服务配置：" + serviceType, serviceType);

            storeManager.saveConfig(CONFIG_KEY, currentConfig);
            logger.info("服务配置保存成功：serviceType={}", serviceType);

        } catch (Exception e) {
            logger.error("保存服务配置失败：serviceType={}, error={}", serviceType, e.getMessage(), e);
            throw new RuntimeException("保存服务配置失败：" + e.getMessage(), e);
        }
    }

    /**
     * 删除服务配置
     *
     * @param serviceType 服务类型
     */
    public void delete(final String serviceType) {
        logger.info("删除服务配置：serviceType={}", serviceType);

        try {
            Map<String, Object> currentConfig = getCurrentConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> services = (Map<String, Object>) currentConfig.get("services");

            if (!services.containsKey(serviceType)) {
                logger.warn("删除不存在的服配置：serviceType={}", serviceType);
                return;
            }

            services.remove(serviceType);

            // 添加版本元数据
            addMetadata(currentConfig, "delete", "删除服务配置：" + serviceType, serviceType);

            storeManager.saveConfig(CONFIG_KEY, currentConfig);
            logger.info("服务配置删除成功：serviceType={}", serviceType);

        } catch (Exception e) {
            logger.error("删除服务配置失败：serviceType={}, error={}", serviceType, e.getMessage(), e);
            throw new RuntimeException("删除服务配置失败：" + e.getMessage(), e);
        }
    }

    /**
     * 检查服务是否存在
     *
     * @param serviceType 服务类型
     * @return 是否存在
     */
    public boolean exists(final String serviceType) {
        return findById(serviceType).isPresent();
    }

    /**
     * 获取配置并转换为 Map
     *
     * @return 配置 Map
     */
    public Map<String, Object> getConfigAsMap() {
        return storeManager.getConfig(CONFIG_KEY);
    }

    /**
     * 保存配置 Map
     *
     * @param config 配置 Map
     */
    public void saveConfigMap(final Map<String, Object> config) {
        storeManager.saveConfig(CONFIG_KEY, config);
    }

    /**
     * v2.8.8: 获取指定服务的限流配置原始 Map(canonical 格式,不经 DTO 转换)
     *
     * @param serviceType 服务类型
     * @return 限流配置 Map,不存在返回 Optional.empty()
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> findRateLimitRaw(final String serviceType) {
        try {
            Map<String, Object> config = loadMergedConfig();
            if (config == null || !(config.get("services") instanceof Map)) {
                return Optional.empty();
            }
            Map<String, Object> services = (Map<String, Object>) config.get("services");
            Object svc = services.get(serviceType);
            if (!(svc instanceof Map)) {
                return Optional.empty();
            }
            Object rateLimit = ((Map<String, Object>) svc).get("rateLimit");
            return rateLimit instanceof Map
                    ? Optional.of((Map<String, Object>) rateLimit)
                    : Optional.empty();
        } catch (Exception e) {
            logger.error("获取服务限流配置失败：serviceType={}, error={}", serviceType, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * v2.8.8: 保存指定服务的限流配置原始 Map(canonical 格式,不经 DTO 转换;服务不存在则创建)
     *
     * @param serviceType 服务类型
     * @param rateLimitMap 限流配置 Map
     */
    @SuppressWarnings("unchecked")
    public void saveRateLimitRaw(final String serviceType, final Map<String, Object> rateLimitMap) {
        logger.info("保存服务限流配置：serviceType={}", serviceType);
        try {
            Map<String, Object> currentConfig = getCurrentConfig();
            Map<String, Object> services = (Map<String, Object>) currentConfig.get("services");
            Map<String, Object> svc = (Map<String, Object>) services.computeIfAbsent(
                    serviceType, k -> new HashMap<String, Object>());
            svc.put("rateLimit", rateLimitMap);

            addMetadata(currentConfig, "save", "保存服务限流配置：" + serviceType, serviceType);

            storeManager.saveConfig(CONFIG_KEY, currentConfig);
            logger.info("服务限流配置保存成功：serviceType={}", serviceType);
        } catch (Exception e) {
            logger.error("保存服务限流配置失败：serviceType={}, error={}", serviceType, e.getMessage(), e);
            throw new RuntimeException("保存服务限流配置失败：" + e.getMessage(), e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取当前配置
     */
    private Map<String, Object> getCurrentConfig() {
        Map<String, Object> config = loadMergedConfig();
        // 防御性拷贝：合并配置（版本快照 / YAML 兜底）可能不可变或被多个调用方共享，
        // 保存路径要在独立可变副本上改写，不能就地污染源 Map。
        config = config == null ? new HashMap<>() : new HashMap<>(config);
        if (!(config.get("services") instanceof Map)) {
            config.put("services", new HashMap<String, Object>());
        } else {
            @SuppressWarnings("unchecked")
            final Map<String, Object> services = (Map<String, Object>) config.get("services");
            config.put("services", new HashMap<>(services));
        }
        return config;
    }

    /**
     * 添加版本元数据
     */
    private void addMetadata(final Map<String, Object> config, final String operation,
                             final String operationDetail, final String serviceType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("operation", operation);
        metadata.put("operationDetail", operationDetail);
        metadata.put("serviceType", serviceType);
        metadata.put("timestamp", System.currentTimeMillis());
        config.put("_metadata", metadata);
    }
}
