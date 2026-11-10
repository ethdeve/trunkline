/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型供应商模板持久化服务
 * 使用 StoreManager 持久化用户自定义的供应商模板（与 adapter_definitions 同机制，无需 SQL 迁移）；
 * 内置 13 个模板由 AdapterTemplateService 硬编码，不落存储，但「用户隐藏的内置模板」在此持久化。
 * @author Trunkline Team
 */
@Service
public class ProviderTemplatePersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(ProviderTemplatePersistenceService.class);
    private static final String STORE_KEY = "provider_templates";
    private static final String STORE_KEY_HIDDEN = "provider_templates_hidden";

    private final StoreManager storeManager;
    private final ObjectMapper objectMapper;

    public ProviderTemplatePersistenceService(final StoreManager storeManager,
                                              final ObjectMapper objectMapper) {
        this.storeManager = storeManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存全部自定义模板
     */
    public void saveAll(final Map<String, AdapterTemplate> templates) {
        try {
            storeManager.saveConfig(STORE_KEY, new ConcurrentHashMap<>(templates));
        } catch (Exception e) {
            logger.warn("保存供应商模板失败，降级为仅内存模式: {}", e.getMessage());
        }
    }

    /**
     * 加载全部自定义模板
     */
    @SuppressWarnings("unchecked")
    public Map<String, AdapterTemplate> loadAll() {
        Map<String, AdapterTemplate> result = new ConcurrentHashMap<>();
        try {
            Map<String, Object> configMap = storeManager.getConfig(STORE_KEY);
            if (configMap == null || configMap.isEmpty()) {
                return result;
            }
            for (Map.Entry<String, Object> entry : configMap.entrySet()) {
                try {
                    Object value = entry.getValue();
                    AdapterTemplate template;
                    if (value instanceof AdapterTemplate t) {
                        template = t;
                    } else {
                        String json = objectMapper.writeValueAsString(value);
                        template = objectMapper.readValue(json, AdapterTemplate.class);
                    }
                    template.setSource("custom");
                    result.put(entry.getKey(), template);
                } catch (Exception e) {
                    logger.warn("反序列化供应商模板失败: {}, 跳过", entry.getKey());
                }
            }
        } catch (Exception e) {
            logger.warn("加载供应商模板失败，返回空集合: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 保存被隐藏的内置模板 ID 集合（用户从默认模板中移除的内置项，可逆）
     */
    public void saveHiddenIds(final Set<String> ids) {
        try {
            Map<String, Object> payload = new ConcurrentHashMap<>();
            payload.put("ids", new ArrayList<>(ids));
            storeManager.saveConfig(STORE_KEY_HIDDEN, payload);
        } catch (Exception e) {
            logger.warn("保存隐藏模板失败，降级为仅内存模式: {}", e.getMessage());
        }
    }

    /**
     * 加载被隐藏的内置模板 ID 集合
     */
    public Set<String> loadHiddenIds() {
        Set<String> result = new LinkedHashSet<>();
        try {
            Map<String, Object> configMap = storeManager.getConfig(STORE_KEY_HIDDEN);
            if (configMap == null || configMap.isEmpty()) {
                return result;
            }
            Object ids = configMap.get("ids");
            if (ids instanceof Collection<?> collection) {
                for (Object o : collection) {
                    if (o != null) {
                        result.add(String.valueOf(o));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("加载隐藏模板失败，返回空集合: {}", e.getMessage());
        }
        return result;
    }
}
