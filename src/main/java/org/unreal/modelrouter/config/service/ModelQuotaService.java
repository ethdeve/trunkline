/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitSet;
import org.unreal.modelrouter.common.dto.ModelQuotaDTO;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.config.dto.ModelQuotaCatalogItemDTO;
import org.unreal.modelrouter.config.dto.ModelQuotaUpsertRequest;
import org.unreal.modelrouter.persistence.jpa.entity.ModelQuotaEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.InstanceCircuitBreakerRepository;
import org.unreal.modelrouter.persistence.jpa.repository.InstanceRateLimitRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelQuotaRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商模型额度与限速服务（开源基础能力，v3.5 Trunkline UI 改造接入管理「额度与限速」tab）。
 *
 * <p><b>一行 = 一个厂商模型</b>（{@code model_name} = {@code service_instance.instance_name}），
 * 每行承载额度与限速配置。单价属商业能力，移至企业版。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelQuotaService {

    private final ModelQuotaRepository modelQuotaRepository;
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
     * 全部额度与限速行（含停用）。
     */
    @Transactional(readOnly = true)
    public List<ModelQuotaDTO> listAll() {
        return modelQuotaRepository.findAll().stream().map(this::toDTO).toList();
    }

    /**
     * 按厂商模型名 upsert 额度与限速（存在更新，不存在新建）。
     *
     * @param modelName 厂商模型名
     * @param request   额度与限速内容（null 字段 = 不修改）
     * @return 保存后的行
     */
    @Transactional
    public ModelQuotaDTO upsert(final String modelName, final ModelQuotaUpsertRequest request) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new ApiException("MODEL_QUOTA_NAME_REQUIRED", "模型名不能为空");
        }
        if (request == null) {
            throw new ApiException("INVALID_REQUEST", "请求体不能为空");
        }
        final String name = modelName.trim();
        final ModelQuotaEntity entity = modelQuotaRepository.findByModelName(name)
                .orElseGet(() -> ModelQuotaEntity.builder()
                        .modelName(name)
                        .enabled(Boolean.TRUE)
                        .build());

        if (request.getServiceType() != null && !request.getServiceType().trim().isEmpty()) {
            entity.setServiceType(request.getServiceType().trim());
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

        final ModelQuotaEntity saved = modelQuotaRepository.save(entity);
        // 限额缓存就地更新：改额度后无需等缓存失效即可生效
        cacheQuotaLimit(saved.getModelName(), saved.toQuotaLimitSet());
        log.info("Upserted model quota: {} (rateLimitPerMinute={})", saved.getModelName(),
                saved.getRateLimitPerMinute());
        return toDTO(saved);
    }

    /**
     * 删除额度与限速行（该模型回到不限额）。
     */
    @Transactional
    public void delete(final String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new ApiException("MODEL_QUOTA_NAME_REQUIRED", "模型名不能为空");
        }
        final String name = modelName.trim();
        modelQuotaRepository.findByModelName(name).ifPresent(entity -> {
            modelQuotaRepository.delete(entity);
            evictQuotaLimit(name);
            log.info("Deleted model quota: {}", name);
        });
    }

    /**
     * 额度与限速目录：<b>一行 = 一个厂商模型</b>。
     */
    @Transactional(readOnly = true)
    public List<ModelQuotaCatalogItemDTO> catalog() {
        final Map<String, ModelQuotaEntity> quotaIndex = new HashMap<>();
        for (final ModelQuotaEntity quota : modelQuotaRepository.findAll()) {
            if (quota.getModelName() != null) {
                quotaIndex.put(quota.getModelName(), quota);
            }
        }

        final Map<Long, String> providerNames = new LinkedHashMap<>();
        modelProviderRepository.findAll().forEach(p -> providerNames.put(p.getId(), p.getName()));
        final Map<Long, String> serviceTypeByConfigId = new HashMap<>();
        for (final ServiceConfigEntity cfg : serviceConfigRepository.findAll()) {
            serviceTypeByConfigId.put(cfg.getId(), cfg.getServiceType());
        }
        final Map<String, List<String>> providersByModel = new LinkedHashMap<>();
        final Map<String, String> serviceTypeByModel = new LinkedHashMap<>();
        final Map<String, Long> instanceIdByModel = new LinkedHashMap<>();
        for (final ServiceInstanceEntity instance : serviceInstanceRepository.findAll()) {
            final String instanceName = instance.getInstanceName();
            if (instanceName == null || instanceName.isBlank()) {
                continue;
            }
            instanceIdByModel.putIfAbsent(instanceName, instance.getId());
            final String providerName = instance.getProviderId() == null
                    ? null : providerNames.get(instance.getProviderId());
            final List<String> names = providersByModel.computeIfAbsent(instanceName, k -> new ArrayList<>());
            if (providerName != null && !providerName.isBlank() && !names.contains(providerName)) {
                names.add(providerName);
            }
            final String serviceType = serviceTypeByConfigId.get(instance.getServiceConfigId());
            if (serviceType != null && !serviceType.isBlank()) {
                serviceTypeByModel.putIfAbsent(instanceName, serviceType);
            }
        }

        final List<ModelQuotaCatalogItemDTO> items = new ArrayList<>(providersByModel.size());
        for (final Map.Entry<String, List<String>> entry : providersByModel.entrySet()) {
            final String modelName = entry.getKey();
            final Long repInstanceId = instanceIdByModel.get(modelName);
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
                    quotaIndex.get(modelName)));
        }

        return items;
    }

    /**
     * 解析某厂商模型的限额集合（供配额执行层的「模型维度」判定）。
     *
     * <p>未配置该模型的额度时返回 {@link QuotaLimitSet#UNLIMITED}（不限额）。</p>
     */
    public QuotaLimitSet resolveQuotaLimit(final String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return QuotaLimitSet.UNLIMITED;
        }
        return quotaLimitCache().getOrDefault(modelName, QuotaLimitSet.UNLIMITED);
    }

    private Map<String, QuotaLimitSet> quotaLimitCache() {
        Map<String, QuotaLimitSet> cache = quotaLimitCache;
        if (quotaLimitCacheLoaded) {
            return cache;
        }
        synchronized (quotaLimitCacheLock) {
            if (!quotaLimitCacheLoaded) {
                final Map<String, QuotaLimitSet> loaded = new HashMap<>();
                for (final ModelQuotaEntity quota : modelQuotaRepository.findAll()) {
                    if (quota.getModelName() != null) {
                        loaded.put(quota.getModelName(), quota.toQuotaLimitSet());
                    }
                }
                quotaLimitCache = Map.copyOf(loaded);
                quotaLimitCacheLoaded = true;
            }
            return quotaLimitCache;
        }
    }

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

    private ModelQuotaCatalogItemDTO buildItem(final String modelName,
                                               final String providerName,
                                               final String serviceType,
                                               final Long instanceId,
                                               final Boolean circuitBreakerEnabled,
                                               final Boolean rateLimitEnabled,
                                               final ModelQuotaEntity quota) {
        return ModelQuotaCatalogItemDTO.builder()
                .modelName(modelName)
                .kind(ModelQuotaCatalogItemDTO.KIND_PROVIDER_MODEL)
                .providerName(providerName)
                .serviceType(serviceType)
                .instanceId(instanceId)
                .circuitBreakerEnabled(Boolean.TRUE.equals(circuitBreakerEnabled))
                .rateLimitEnabled(Boolean.TRUE.equals(rateLimitEnabled))
                .enabled(quota != null && quota.getEnabled() != null ? quota.getEnabled() : Boolean.TRUE)
                .description(quota != null ? quota.getDescription() : null)
                .rateLimitPerMinute(quota != null && quota.getRateLimitPerMinute() != null
                        ? quota.getRateLimitPerMinute() : 0)
                .hourlyRequestLimit(quota != null && quota.getHourlyRequestLimit() != null
                        ? quota.getHourlyRequestLimit() : 0L)
                .hourlyTokenLimit(quota != null && quota.getHourlyTokenLimit() != null
                        ? quota.getHourlyTokenLimit() : 0L)
                .dailyRequestLimit(quota != null && quota.getDailyRequestLimit() != null
                        ? quota.getDailyRequestLimit() : 0L)
                .dailyTokenLimit(quota != null && quota.getDailyTokenLimit() != null
                        ? quota.getDailyTokenLimit() : 0L)
                .monthlyRequestLimit(quota != null && quota.getMonthlyRequestLimit() != null
                        ? quota.getMonthlyRequestLimit() : 0L)
                .monthlyTokenLimit(quota != null && quota.getMonthlyTokenLimit() != null
                        ? quota.getMonthlyTokenLimit() : 0L)
                .quotaAlertThreshold(quota != null && quota.getQuotaAlertThreshold() != null
                        ? quota.getQuotaAlertThreshold() : 0.8d)
                .build();
    }

    private ModelQuotaDTO toDTO(final ModelQuotaEntity entity) {
        return ModelQuotaDTO.builder()
                .id(entity.getId())
                .modelName(entity.getModelName())
                .serviceType(entity.getServiceType())
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
