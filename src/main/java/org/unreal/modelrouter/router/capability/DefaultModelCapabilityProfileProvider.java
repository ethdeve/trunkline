/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置画像默认数据源：解析 classpath 下的 model-capability-profiles.json。
 *
 * <p>本类本身不带 {@code @Component}：其 Bean 由
 * {@code TrunklineEnterpriseAutoConfiguration.modelCapabilityProfileProvider()} 以
 * {@code @ConditionalOnMissingBean(ModelCapabilityProfileProvider.class)} 注册，企业版
 * 先注册真实实现时本实现自动退位——与 {@code CommunityPriceResolver} 完全同构。</p>
 *
 * <p><strong>fail-soft</strong>：JSON 缺失、损坏或字段非法时只告警并返回空画像列表，
 * 绝不阻断应用启动——画像是增强能力，不是路由/计费的必需依赖。</p>
 *
 * @author Trunkline Team
 */
public class DefaultModelCapabilityProfileProvider implements ModelCapabilityProfileProvider {

    private static final Logger logger = LoggerFactory.getLogger(DefaultModelCapabilityProfileProvider.class);
    private static final String RESOURCE = "data/model-capability-profiles.json";

    private final ObjectMapper objectMapper;
    private final List<ModelCapabilityProfile> cache;

    public DefaultModelCapabilityProfileProvider(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.cache = loadInternal();
    }

    @Override
    public List<ModelCapabilityProfile> load() {
        return cache;
    }

    @Override
    public String name() {
        return "builtin-json";
    }

    private List<ModelCapabilityProfile> loadInternal() {
        final ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            logger.warn("内置画像文件缺失({})，画像能力返回空列表（不影响路由/计费）", RESOURCE);
            return Collections.emptyList();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode profiles = root.get("profiles");
            if (profiles == null || !profiles.isArray()) {
                logger.warn("内置画像文件缺少 profiles 数组，返回空列表");
                return Collections.emptyList();
            }
            List<ModelCapabilityProfile> result = new ArrayList<>();
            for (JsonNode node : profiles) {
                ModelCapabilityProfile profile = parseOne(node);
                if (profile != null && profile.getCanonicalId() != null) {
                    result.add(profile);
                }
            }
            logger.info("内置画像已加载：{} 条（来源 {}）", result.size(), RESOURCE);
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            logger.warn("内置画像解析失败，画像能力降级为空列表：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private ModelCapabilityProfile parseOne(final JsonNode node) {
        try {
            return objectMapper.treeToValue(node, ModelCapabilityProfile.class);
        } catch (Exception e) {
            logger.warn("跳过非法画像条目：{}", node.path("canonicalId").asText("?"));
            return null;
        }
    }
}
