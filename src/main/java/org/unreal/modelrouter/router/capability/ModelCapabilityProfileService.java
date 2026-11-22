/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.capability;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 模型能力画像查询/筛选服务（v0.2.0）。
 *
 * <p>只做只读查询与内存筛选，不写任何库表——画像数据源由
 * {@link ModelCapabilityProfileProvider} 提供（内置 JSON 或企业 SPI 覆盖）。
 * 场景服务（下一批）通过本服务按 useCase / 能力门槛选模。</p>
 *
 * @author Trunkline Team
 */
@Service
public class ModelCapabilityProfileService {

    private static final int RANK_UNKNOWN = 0;
    private static final int RANK_LOW = 1;
    private static final int RANK_MID = 2;
    private static final int RANK_HIGH = 3;

    private final ModelCapabilityProfileProvider provider;

    public ModelCapabilityProfileService(final ModelCapabilityProfileProvider provider) {
        this.provider = provider;
    }

    /** 全部画像（按 canonicalId 排序，稳定输出）。 */
    public List<ModelCapabilityProfile> listAll() {
        List<ModelCapabilityProfile> result = new ArrayList<>(provider.load());
        result.sort(Comparator.comparing(
                p -> p.getCanonicalId() != null ? p.getCanonicalId() : "",
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * 按条件筛选画像。
     *
     * @param useCase         适用场景（精确匹配标签，null/空=不限）
     * @param serviceType     服务类型（7 类，null/空=不限）
     * @param vendor          厂商（大小写不敏感，null/空=不限）
     * @param preferStrength  偏好的强度维度（code/chinese/math/reasoning，null=不按强度排序）
     * @param minLatency      最低延迟要求（low/mid/high，null=不限；low 最严格）
     */
    public List<ModelCapabilityProfile> filter(final String useCase, final String serviceType,
                                               final String vendor, final String preferStrength,
                                               final String minLatency) {
        Stream<ModelCapabilityProfile> stream = provider.load().stream();
        if (hasText(useCase)) {
            final String uc = useCase.trim();
            stream = stream.filter(p -> p.getUseCases().stream().anyMatch(tag -> tag.equalsIgnoreCase(uc)));
        }
        if (hasText(serviceType)) {
            final String st = serviceType.trim().toLowerCase();
            stream = stream.filter(p -> st.equalsIgnoreCase(p.getServiceType()));
        }
        if (hasText(vendor)) {
            final String v = vendor.trim().toLowerCase();
            stream = stream.filter(p -> v.equalsIgnoreCase(p.getVendor()));
        }
        if (hasText(minLatency)) {
            final int floor = rankOf(minLatency);
            // 延迟档位语义反转：low（快）优先，过滤掉比要求更慢的（rank 更大视为更慢）
            stream = stream.filter(p -> {
                String l = p.getLatency();
                if (ModelCapabilityProfile.UNKNOWN.equals(l)) {
                    return true; // 未知不剔除，留给调用方判断
                }
                return rankOf(l) <= floor;
            });
        }
        List<ModelCapabilityProfile> result = stream.collect(Collectors.toList());
        if (hasText(preferStrength)) {
            final String dim = preferStrength.trim().toLowerCase();
            result.sort(Comparator.comparingInt((ModelCapabilityProfile p) -> -strengthRank(p, dim))
                    .thenComparing(p -> p.getCanonicalId() != null ? p.getCanonicalId() : ""));
        } else {
            result.sort(Comparator.comparing(
                    p -> p.getCanonicalId() != null ? p.getCanonicalId() : "",
                    String.CASE_INSENSITIVE_ORDER));
        }
        return result;
    }

    /**
     * 按模型名（modelId / alias / canonicalId，大小写不敏感）查找画像。
     * 多个画像命中时优先返回来源优先级更高的（persisted > probe > resolver > spi > builtin）。
     */
    public Optional<ModelCapabilityProfile> findByModelName(final String modelName) {
        if (!hasText(modelName)) {
            return Optional.empty();
        }
        return provider.load().stream()
                .filter(p -> p.matchesName(modelName))
                .max(Comparator.comparingInt(p -> sourcePriority(p.getSource())));
    }

    /** 全部厂商（去重、排序）。 */
    public List<String> vendors() {
        Set<String> set = new LinkedHashSet<>();
        for (ModelCapabilityProfile p : provider.load()) {
            if (hasText(p.getVendor())) {
                set.add(p.getVendor());
            }
        }
        List<String> result = new ArrayList<>(set);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /** 全部适用场景标签（去重、排序）。 */
    public List<String> useCases() {
        Set<String> set = new LinkedHashSet<>();
        for (ModelCapabilityProfile p : provider.load()) {
            set.addAll(p.getUseCases());
        }
        List<String> result = new ArrayList<>(set);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /** 画像来源名称（诊断用）。 */
    public String providerName() {
        return provider.name();
    }

    private static int strengthRank(final ModelCapabilityProfile p, final String dimension) {
        ModelCapabilityProfile.Strengths s = p.getStrengths();
        String value;
        switch (dimension) {
            case "code":
                value = s.getCode();
                break;
            case "chinese":
                value = s.getChinese();
                break;
            case "math":
                value = s.getMath();
                break;
            case "reasoning":
                value = s.getReasoning();
                break;
            default:
                value = ModelCapabilityProfile.UNKNOWN;
                break;
        }
        return rankOf(value);
    }

    private static int rankOf(final String level) {
        if (level == null) {
            return RANK_UNKNOWN;
        }
        switch (level.trim().toLowerCase()) {
            case ModelCapabilityProfile.HIGH:
                return RANK_HIGH;
            case ModelCapabilityProfile.MID:
                return RANK_MID;
            case ModelCapabilityProfile.LOW:
                return RANK_LOW;
            default:
                return RANK_UNKNOWN;
        }
    }

    private static int sourcePriority(final String source) {
        if (source == null) {
            return 0;
        }
        switch (source) {
            case ModelCapabilityProfile.SOURCE_PERSISTED:
                return 5;
            case ModelCapabilityProfile.SOURCE_PROBE:
                return 4;
            case ModelCapabilityProfile.SOURCE_RESOLVER:
                return 3;
            case ModelCapabilityProfile.SOURCE_SPI:
                return 2;
            case ModelCapabilityProfile.SOURCE_BUILTIN:
            default:
                return 1;
        }
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
