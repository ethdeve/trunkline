/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitSet;
import org.unreal.modelrouter.common.dto.ModelPriceDTO;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.config.dto.ModelPriceCatalogItemDTO;
import org.unreal.modelrouter.config.dto.ModelPriceUpsertRequest;
import org.unreal.modelrouter.persistence.jpa.entity.ModelPriceEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.InstanceCircuitBreakerRepository;
import org.unreal.modelrouter.persistence.jpa.repository.InstanceRateLimitRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelPriceRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型价格与额度服务（v3.5 Trunkline UI 改造，接入管理「价格与额度」tab）。
 *
 * <p><b>一张表，两个维度合一</b>：需求「价格与额度」与「额度与限速」复用一张表，
 * 且额度与限速是<b>针对厂商模型</b>的配置（虚拟模型/模型分组的额度限速落在 pool 定义上）。
 * 故本服务的行粒度统一为<b>厂商模型</b>（{@code model_name} =
 * {@code service_instance.instance_name}），每行既有价格、也有额度与限速。</p>
 *
 * <p><b>计价口径</b>：账单按<b>厂商模型名</b>计价——员工请求的虚拟模型最终落到某个厂商模型实例，
 * 用量落在 {@code token_usage.instance_name}，按该名查本表单价。
 * 单价默认 {@code 0}：未配置价格的模型按 ¥0 计入，前端账单页给空态提示。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelPriceService {

    private final ModelPriceRepository modelPriceRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final ServiceConfigRepository serviceConfigRepository;
    private final InstanceCircuitBreakerRepository circuitBreakerRepository;
    private final InstanceRateLimitRepository rateLimitRepository;

    /** 厂商模型额度限额缓存（请求主链路逐请求读取，避免每请求一次 DB 往返） */
    private volatile Map<String, QuotaLimitSet> quotaLimitCache = Map.of();

    /** 限额缓存是否已载入（避免「表为空 ⇒ 每次都重查」的抖动） */
    private volatile boolean quotaLimitCacheLoaded;

    /** 限额缓存的写锁（载入与就地更新互斥） */
    private final Object quotaLimitCacheLock = new Object();

    /**
     * 全部价格与额度行（含停用）。
     */
    @Transactional(readOnly = true)
    public List<ModelPriceDTO> listAll() {
        return modelPriceRepository.findAll().stream().map(this::toDTO).toList();
    }

    /**
     * 按厂商模型名 upsert 价格与额度（存在更新，不存在新建）。
     *
     * @param modelName 厂商模型名
     * @param request   价格与额度内容（null 字段 = 不修改）
     * @return 保存后的行
     */
    @Transactional
    public ModelPriceDTO upsert(final String modelName, final ModelPriceUpsertRequest request) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new ApiException("MODEL_PRICE_NAME_REQUIRED", "模型名不能为空");
        }
        if (request == null) {
            throw new ApiException("INVALID_REQUEST", "请求体不能为空");
        }
        final String name = modelName.trim();
        final ModelPriceEntity entity = modelPriceRepository.findByModelName(name)
                .orElseGet(() -> ModelPriceEntity.builder()
                        .modelName(name)
                        .priceInput(BigDecimal.ZERO)
                        .priceOutput(BigDecimal.ZERO)
                        .priceCache(BigDecimal.ZERO)
                        .currency("CNY")
                        .enabled(Boolean.TRUE)
                        .build());

        if (request.getServiceType() != null && !request.getServiceType().trim().isEmpty()) {
            entity.setServiceType(request.getServiceType().trim());
        }
        if (request.getPriceInput() != null) {
            entity.setPriceInput(nonNegative(request.getPriceInput()));
        }
        if (request.getPriceOutput() != null) {
            entity.setPriceOutput(nonNegative(request.getPriceOutput()));
        }
        if (request.getPriceCache() != null) {
            entity.setPriceCache(nonNegative(request.getPriceCache()));
        }
        if (request.getCurrency() != null && !request.getCurrency().trim().isEmpty()) {
            entity.setCurrency(request.getCurrency().trim().toUpperCase());
        }
        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
        }
        if (request.getDescription() != null) {
            final String desc = request.getDescription().trim();
            entity.setDescription(desc.isEmpty() ? null : desc);
        }

        // 额度与限速（厂商模型维度）：null = 不修改，0 = 不限制
        if (request.getRateLimitPerMinute() != null) {
            entity.setRateLimitPerMinute(Math.max(0, request.getRateLimitPerMinute()));
        }
        if (request.getHourlyRequestLimit() != null) {
            entity.setHourlyRequestLimit(Math.max(0L, request.getHourlyRequestLimit()));
        }
        if (request.getHourlyTokenLimit() != null) {
            entity.setHourlyTokenLimit(Math.max(0L, request.getHourlyTokenLimit()));
        }
        if (request.getDailyRequestLimit() != null) {
            entity.setDailyRequestLimit(Math.max(0L, request.getDailyRequestLimit()));
        }
        if (request.getDailyTokenLimit() != null) {
            entity.setDailyTokenLimit(Math.max(0L, request.getDailyTokenLimit()));
        }
        if (request.getMonthlyRequestLimit() != null) {
            entity.setMonthlyRequestLimit(Math.max(0L, request.getMonthlyRequestLimit()));
        }
        if (request.getMonthlyTokenLimit() != null) {
            entity.setMonthlyTokenLimit(Math.max(0L, request.getMonthlyTokenLimit()));
        }
        if (request.getQuotaAlertThreshold() != null) {
            final double threshold = request.getQuotaAlertThreshold();
            entity.setQuotaAlertThreshold(Math.min(1.0d, Math.max(0.0d, threshold)));
        }

        final ModelPriceEntity saved = modelPriceRepository.save(entity);
        // 限额缓存就地更新：改额度后无需等缓存失效即可生效
        cacheQuotaLimit(saved.getModelName(), saved.toQuotaLimitSet());
        log.info("Upserted model price/quota: {} (in={}, out={}, cache={}, rateLimitPerMinute={})",
                saved.getModelName(), saved.getPriceInput(), saved.getPriceOutput(),
                saved.getPriceCache(), saved.getRateLimitPerMinute());
        return toDTO(saved);
    }

    /**
     * 删除价格与额度行（该模型回到 ¥0 计、无限额）。
     */
    @Transactional
    public void delete(final String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new ApiException("MODEL_PRICE_NAME_REQUIRED", "模型名不能为空");
        }
        final String name = modelName.trim();
        modelPriceRepository.findByModelName(name).ifPresent(entity -> {
            modelPriceRepository.delete(entity);
            evictQuotaLimit(name);
            log.info("Deleted model price/quota: {}", name);
        });
    }

    /**
     * 价格与额度目录：<b>一行 = 一个厂商模型</b>。
     *
     * <p>条目来源为已接入的实例（{@code service_instance.instance_name}），与「接入厂商」
     * tab 的模型清单同源——保证「接进来��个模型」与「能配价格/额度的模型」是同一集合，
     * 不会出现表格里配了却查无此模型的空行。</p>
     *
     * <p><b>同名实例合并为一行</b>：{@code service_instance.instance_name} 没有唯一约束，
     * 不同厂商可能接入同名模型（如两家都提供 {@code gpt-4o}）。但价格与额度以
     * {@code model_name} 为键（一行 = 一个厂商模型），因此同名实例<b>共用一行</b>——
     * 目录按模型名去重，供应商名列合并展示，避免表格里出现两行同名模型
     * 却只对应一条价格记录。</p>
     */
    @Transactional(readOnly = true)
    public List<ModelPriceCatalogItemDTO> catalog() {
        // 价格索引：厂商模型名 → 价格与额度行
        final Map<String, ModelPriceEntity> priceIndex = new HashMap<>();
        for (final ModelPriceEntity price : modelPriceRepository.findAll()) {
            if (price.getModelName() != null) {
                priceIndex.put(price.getModelName(), price);
            }
        }

        // 厂商已接入的模型（行粒度 = 厂商模型名；同名实例合并）
        final Map<Long, String> providerNames = new LinkedHashMap<>();
        modelProviderRepository.findAll().forEach(p -> providerNames.put(p.getId(), p.getName()));
        final Map<Long, String> serviceTypeByConfigId = new HashMap<>();
        for (final ServiceConfigEntity cfg : serviceConfigRepository.findAll()) {
            serviceTypeByConfigId.put(cfg.getId(), cfg.getServiceType());
        }
        final Map<String, List<String>> providersByModel = new LinkedHashMap<>();
        final Map<String, String> serviceTypeByModel = new LinkedHashMap<>();
        // 每个厂商模型名对应的代表实例主键（熔断器按实例粒度配置，用于前端跳转实例级熔断配置）。
        // 同名实例合并时取首个实例主键作为代表（常见 1:1；罕见 1:N 时熔断配置作用于该代表实例）。
        final Map<String, Long> instanceIdByModel = new LinkedHashMap<>();
        for (final ServiceInstanceEntity instance : serviceInstanceRepository.findAll()) {
            final String instanceName = instance.getInstanceName();
            if (instanceName == null || instanceName.isBlank()) {
                continue;
            }
            instanceIdByModel.putIfAbsent(instanceName, instance.getId());
            final String providerName = instance.getProviderId() == null
                    ? null : providerNames.get(instance.getProviderId());
            // 无供应商归属的实例也要成行（供应商列留空），否则该模型会从目录里消失
            final List<String> names = providersByModel.computeIfAbsent(instanceName, k -> new ArrayList<>());
            if (providerName != null && !providerName.isBlank() && !names.contains(providerName)) {
                names.add(providerName);
            }
            final String serviceType = serviceTypeByConfigId.get(instance.getServiceConfigId());
            if (serviceType != null && !serviceType.isBlank()) {
                serviceTypeByModel.putIfAbsent(instanceName, serviceType);
            }
        }

        final List<ModelPriceCatalogItemDTO> items = new ArrayList<>(providersByModel.size());
        for (final Map.Entry<String, List<String>> entry : providersByModel.entrySet()) {
            final String modelName = entry.getKey();
            final Long repInstanceId = instanceIdByModel.get(modelName);
            // 代表实例的熔断/限流开关状态（来自 instance_circuit_breaker / instance_rate_limit），
            // 供「价格与额度」表格直接渲染两列开关；无配置默认 false。
            final Boolean cbEnabled = repInstanceId != null
                    ? circuitBreakerRepository.findByInstanceId(repInstanceId)
                        .map(e -> Boolean.TRUE.equals(e.getEnabled())).orElse(false)
                    : false;
            final Boolean rlEnabled = repInstanceId != null
                    ? rateLimitRepository.findByInstanceId(repInstanceId)
                        .map(e -> Boolean.TRUE.equals(e.getEnabled())).orElse(false)
                    : false;
            items.add(buildItem(modelName,
                    String.join("、", entry.getValue()),
                    serviceTypeByModel.get(modelName),
                    repInstanceId,
                    cbEnabled,
                    rlEnabled,
                    priceIndex.get(modelName)));
        }

        return items;
    }

    /**
     * 解析某厂商模型的限额集合（供配额执行层的「模型维度」判定）。
     *
     * <p>未配置该模型的额度时返回 {@link QuotaLimitSet#UNLIMITED}（不限额）。</p>
     *
     * <p><b>走内存缓存</b>：本方法在请求主链路上被逐请求调用（选实例后判定厂商模型额度），
     * 直接查库会给每个请求加一次 DB 往返。限额配置变更极少，故全量载入内存并缓存，
     * 由 {@link #upsert} / {@link #delete} 就地更新缓存条目。</p>
     *
     * @param modelName 厂商模型名
     * @return 限额集合；未配置时为不限额
     */
    public QuotaLimitSet resolveQuotaLimit(final String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return QuotaLimitSet.UNLIMITED;
        }
        return quotaLimitCache().getOrDefault(modelName, QuotaLimitSet.UNLIMITED);
    }

    /**
     * 取限额缓存（首次访问时全量载入）。
     *
     * @return 厂商模型名 → 限额集合
     */
    private Map<String, QuotaLimitSet> quotaLimitCache() {
        Map<String, QuotaLimitSet> cache = quotaLimitCache;
        if (quotaLimitCacheLoaded) {
            return cache;
        }
        synchronized (quotaLimitCacheLock) {
            if (!quotaLimitCacheLoaded) {
                final Map<String, QuotaLimitSet> loaded = new HashMap<>();
                for (final ModelPriceEntity price : modelPriceRepository.findAll()) {
                    if (price.getModelName() != null) {
                        loaded.put(price.getModelName(), price.toQuotaLimitSet());
                    }
                }
                quotaLimitCache = Map.copyOf(loaded);
                quotaLimitCacheLoaded = true;
            }
            return quotaLimitCache;
        }
    }

    /**
     * 就地更新限额缓存条目（upsert 后调用，避免限额变更要等缓存失效才生效）。
     *
     * @param modelName 厂商模型名
     * @param limits    该模型最新的限额集合
     */
    private void cacheQuotaLimit(final String modelName, final QuotaLimitSet limits) {
        if (modelName == null) {
            return;
        }
        synchronized (quotaLimitCacheLock) {
            final Map<String, QuotaLimitSet> next = new HashMap<>(quotaLimitCache);
            next.put(modelName, limits);
            quotaLimitCache = Map.copyOf(next);
            quotaLimitCacheLoaded = true;
        }
    }

    /**
     * 从限额缓存中移除条目（delete 后调用）。
     *
     * @param modelName 厂商模型名
     */
    private void evictQuotaLimit(final String modelName) {
        if (modelName == null) {
            return;
        }
        synchronized (quotaLimitCacheLock) {
            if (!quotaLimitCache.containsKey(modelName)) {
                return;
            }
            final Map<String, QuotaLimitSet> next = new HashMap<>(quotaLimitCache);
            next.remove(modelName);
            quotaLimitCache = Map.copyOf(next);
            quotaLimitCacheLoaded = true;
        }
    }

    private ModelPriceCatalogItemDTO buildItem(final String modelName,
                                               final String providerName,
                                               final String serviceType,
                                               final Long instanceId,
                                               final Boolean circuitBreakerEnabled,
                                               final Boolean rateLimitEnabled,
                                               final ModelPriceEntity price) {
        return ModelPriceCatalogItemDTO.builder()
                .modelName(modelName)
                .kind(ModelPriceCatalogItemDTO.KIND_PROVIDER_MODEL)
                .providerName(providerName)
                .serviceType(serviceType)
                .instanceId(instanceId)
                .circuitBreakerEnabled(Boolean.TRUE.equals(circuitBreakerEnabled))
                .rateLimitEnabled(Boolean.TRUE.equals(rateLimitEnabled))
                .hasPrice(price != null)
                .priceInput(price != null && price.getPriceInput() != null
                        ? price.getPriceInput() : BigDecimal.ZERO)
                .priceOutput(price != null && price.getPriceOutput() != null
                        ? price.getPriceOutput() : BigDecimal.ZERO)
                .priceCache(price != null && price.getPriceCache() != null
                        ? price.getPriceCache() : BigDecimal.ZERO)
                .currency(price != null && price.getCurrency() != null ? price.getCurrency() : "CNY")
                .enabled(price != null && price.getEnabled() != null ? price.getEnabled() : Boolean.TRUE)
                .description(price != null ? price.getDescription() : null)
                .rateLimitPerMinute(price != null && price.getRateLimitPerMinute() != null
                        ? price.getRateLimitPerMinute() : 0)
                .hourlyRequestLimit(price != null && price.getHourlyRequestLimit() != null
                        ? price.getHourlyRequestLimit() : 0L)
                .hourlyTokenLimit(price != null && price.getHourlyTokenLimit() != null
                        ? price.getHourlyTokenLimit() : 0L)
                .dailyRequestLimit(price != null && price.getDailyRequestLimit() != null
                        ? price.getDailyRequestLimit() : 0L)
                .dailyTokenLimit(price != null && price.getDailyTokenLimit() != null
                        ? price.getDailyTokenLimit() : 0L)
                .monthlyRequestLimit(price != null && price.getMonthlyRequestLimit() != null
                        ? price.getMonthlyRequestLimit() : 0L)
                .monthlyTokenLimit(price != null && price.getMonthlyTokenLimit() != null
                        ? price.getMonthlyTokenLimit() : 0L)
                .quotaAlertThreshold(price != null && price.getQuotaAlertThreshold() != null
                        ? price.getQuotaAlertThreshold() : 0.8d)
                .build();
    }

    private static BigDecimal nonNegative(final BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private ModelPriceDTO toDTO(final ModelPriceEntity entity) {
        return ModelPriceDTO.builder()
                .id(entity.getId())
                .modelName(entity.getModelName())
                .serviceType(entity.getServiceType())
                .priceInput(entity.getPriceInput())
                .priceOutput(entity.getPriceOutput())
                .priceCache(entity.getPriceCache())
                .currency(entity.getCurrency())
                .enabled(entity.getEnabled())
                .description(entity.getDescription())
                .rateLimitPerMinute(entity.getRateLimitPerMinute())
                .hourlyRequestLimit(entity.getHourlyRequestLimit())
                .hourlyTokenLimit(entity.getHourlyTokenLimit())
                .dailyRequestLimit(entity.getDailyRequestLimit())
                .dailyTokenLimit(entity.getDailyTokenLimit())
                .monthlyRequestLimit(entity.getMonthlyRequestLimit())
                .monthlyTokenLimit(entity.getMonthlyTokenLimit())
                .quotaAlertThreshold(entity.getQuotaAlertThreshold())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
