/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.util.ApiKeyHashUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key O(1) 查找索引（C6 整改）。
 *
 * <p>维护 {@code lookupHash -> ApiKey} 的派生索引，避免校验路径对全部密钥做 O(n) 线性扫描。
 * 查找流程：</p>
 * <ol>
 *   <li>据客户端提交的 {@code keyValue} 计算确定性 {@code lookupHash = HMAC-SHA256(pepper, keyValue)}；</li>
 *   <li>索引命中则 O(1) 返回候选；</li>
 *   <li>未命中则回落到 O(n) 全表扫描（覆盖无明文、未建索引的存量密钥），并在命中后<b>惰性补全</b>
 *       该密钥的 lookupHash（灰度迁移），后续请求即走 O(1)。</li>
 * </ol>
 *
 * <p>本组件为无状态叶子组件，被 {@link ApiKeyService}、{@link ApiKeyValidator}、
 * {@link ApiKeyPersistenceService} 共同依赖，自身不反向依赖它们，避免循环依赖。</p>
 * @author Trunkline Team
 */
@Slf4j
@Component
public class ApiKeyLookupIndex {

    private final Map<String, ApiKey> lookupIndex = new ConcurrentHashMap<>();

    @Autowired
    private ApiKeyPepperService pepperService;

    /**
     * 计算 keyValue 的确定性查找哈希（pepper 不可用时返回 null，调用方退化为 O(n)）。
     */
    public String computeLookupHash(final String keyValue) {
        if (pepperService == null || keyValue == null) {
            return null;
        }
        return ApiKeyHashUtil.computeLookupHash(pepperService.getPepper(), keyValue);
    }

    /**
     * 在明文 keyValue 可用时，为实体补全 lookupHash（幂等）。
     */
    public void attachLookupHash(final ApiKey apiKey, final String plaintextKeyValue) {
        if (apiKey == null || plaintextKeyValue == null) {
            return;
        }
        String lh = computeLookupHash(plaintextKeyValue);
        if (lh != null) {
            apiKey.setLookupHash(lh);
        }
    }

    /**
     * 将单个实体登记进索引（仅当其已有 lookupHash）。
     */
    public void index(final ApiKey apiKey) {
        if (apiKey != null && apiKey.getLookupHash() != null) {
            lookupIndex.put(apiKey.getLookupHash(), apiKey);
        }
    }

    /**
     * 从完整缓存重建索引（O(n)，仅在加载 / 刷新 / 导入等低频路径调用）。
     */
    public void rebuild(final Map<String, ApiKey> apiKeyCache) {
        lookupIndex.clear();
        if (apiKeyCache == null) {
            return;
        }
        for (ApiKey apiKey : apiKeyCache.values()) {
            index(apiKey);
        }
    }

    /**
     * 按实体当前 lookupHash 从索引移除（轮换旧值时先调用，避免遗留脏条目）。
     */
    public void remove(final ApiKey apiKey) {
        if (apiKey != null && apiKey.getLookupHash() != null) {
            lookupIndex.remove(apiKey.getLookupHash());
        }
    }

    /**
     * O(1) 优先的密钥查找；未命中索引时落入 O(n) 并惰性迁移。
     *
     * @param keyValue    客户端提交的原始 keyValue
     * @param apiKeyCache 主键缓存（keyHash -> ApiKey），用于 O(n) 兜底扫描
     * @return 匹配的 ApiKey，未找到返回 null
     */
    public ApiKey find(final String keyValue, final Map<String, ApiKey> apiKeyCache) {
        String lh = computeLookupHash(keyValue);
        if (lh != null) {
            ApiKey hit = lookupIndex.get(lh);
            if (hit != null) {
                return hit;
            }
        }
        // 索引未命中：回落 O(n)（覆盖存量无 lookupHash 的密钥）
        if (apiKeyCache == null) {
            return null;
        }
        for (ApiKey apiKey : apiKeyCache.values()) {
            if (apiKey.verifyKey(keyValue)) {
                // 惰性迁移：首次成功使用即补建索引，后续走 O(1)
                if (apiKey.getLookupHash() == null) {
                    attachLookupHash(apiKey, keyValue);
                    index(apiKey);
                    log.debug("惰性迁移 API Key {} 的 lookupHash 索引", apiKey.getKeyId());
                }
                return apiKey;
            }
        }
        return null;
    }
}
