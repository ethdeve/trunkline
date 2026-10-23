package org.unreal.modelrouter.config.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.config.core.manager.ConfigVersionManager;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.common.dto.InstanceRateLimitDTO;
import org.unreal.modelrouter.common.dto.InstanceCircuitBreakerDTO;
import org.unreal.modelrouter.config.dto.CreateServiceInstanceRequest;
import org.unreal.modelrouter.persistence.jpa.entity.InstanceCircuitBreakerEntity;
import org.unreal.modelrouter.persistence.jpa.entity.InstanceRateLimitEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;
import org.unreal.modelrouter.persistence.jpa.repository.InstanceCircuitBreakerRepository;
import org.unreal.modelrouter.persistence.jpa.repository.InstanceRateLimitRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.common.util.SecurityUtils;
import org.unreal.modelrouter.common.exception.ApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 服务实例管理器
 * v1.5.2: 使用 JPA 实现服务实例管理，使用 DTO 替代 Map
 * v1.5.3: 添加版本管理支持，同步更新配置表
 * v1.7.1: 添加运行时配置刷新，确保实例变更后立即生效
 */
@Slf4j
@Service
public class ServiceInstanceManager {

    private final ServiceInstanceRepository serviceInstanceRepository;
    private final InstanceRateLimitRepository rateLimitRepository;
    private final InstanceCircuitBreakerRepository circuitBreakerRepository;
    private final ServiceConfigRepository serviceConfigRepository;
    private final ConfigurationService configurationService;
    private final ConfigVersionManager configVersionManager;  // 新增
    private final ModelServiceRegistry modelServiceRegistry;
    private final CircuitBreakerManager circuitBreakerManager;
    /** v2.11.0: 供应商层 —— 实例带 providerId 时从供应商带出地址/适配器/Key */
    private final ModelProviderRepository modelProviderRepository;
    /** 2026-10-22: 跨模型分类迁移时清理旧分类下的健康状态记录（键含 serviceType 前缀） */
    private final org.unreal.modelrouter.router.checker.ServiceStateManager serviceStateManager;
    /** 2026-10-22: 跨模型分类迁移时清理旧分类下的实例限流桶（键含 serviceType 前缀） */
    private final org.unreal.modelrouter.router.ratelimit.RateLimitManager rateLimitManager;
    /** 2026-10-22: 迁移前检查实例是否被模型分组（池）引用 */
    private final org.unreal.modelrouter.router.pool.PoolPersistenceService poolPersistenceService;

    public ServiceInstanceManager(
            final ServiceInstanceRepository serviceInstanceRepository,
            final InstanceRateLimitRepository rateLimitRepository,
            final InstanceCircuitBreakerRepository circuitBreakerRepository,
            final ServiceConfigRepository serviceConfigRepository,
            @Lazy final ConfigurationService configurationService,
            final ConfigVersionManager configVersionManager,
            final ModelServiceRegistry modelServiceRegistry,
            final CircuitBreakerManager circuitBreakerManager,
            final ModelProviderRepository modelProviderRepository,
            @Lazy final org.unreal.modelrouter.router.checker.ServiceStateManager serviceStateManager,
            @Lazy final org.unreal.modelrouter.router.ratelimit.RateLimitManager rateLimitManager,
            @Lazy final org.unreal.modelrouter.router.pool.PoolPersistenceService poolPersistenceService) {
        this.serviceInstanceRepository = serviceInstanceRepository;
        this.rateLimitRepository = rateLimitRepository;
        this.circuitBreakerRepository = circuitBreakerRepository;
        this.serviceConfigRepository = serviceConfigRepository;
        this.configurationService = configurationService;
        this.configVersionManager = configVersionManager;
        this.modelServiceRegistry = modelServiceRegistry;
        this.circuitBreakerManager = circuitBreakerManager;
        this.modelProviderRepository = modelProviderRepository;
        this.serviceStateManager = serviceStateManager;
        this.rateLimitManager = rateLimitManager;
        this.poolPersistenceService = poolPersistenceService;
    }

    /**
     * 获取服务下的所有实例
     */
    public List<ServiceInstanceDTO> getInstancesByServiceConfigId(final Long serviceConfigId) {
        return serviceInstanceRepository.findByServiceConfigId(serviceConfigId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有实例
     */
    public List<ServiceInstanceDTO> getAllInstances() {
        return serviceInstanceRepository.findAll()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 获取单个实例
     */
    public Optional<ServiceInstanceDTO> getInstance(final Long id) {
        return serviceInstanceRepository.findById(id)
                .map(this::convertToDTO);
    }

    /**
     * 创建实例
     * v2.11.0: 请求带 providerId 时，从供应商带出 baseUrl / path / adapter / Authorization 头
     * （请求中的显式值优先），实现「同一供应商只填一次 Key」。
     */
    @Transactional
    public ServiceInstanceDTO createInstance(final Long serviceConfigId, final CreateServiceInstanceRequest request) {
        // v2.11.0: 供应商继承（在 SSRF 校验之前，供应商地址同样过 SsrfGuard）
        final Long providerId = normalizeProviderId(request.getProviderId());
        if (providerId != null) {
            final ModelProviderEntity provider = modelProviderRepository.findById(providerId)
                    .orElseThrow(() -> new RuntimeException("Model provider not found: " + providerId));
            applyProviderDefaults(request, provider);
            // 创建态：请求头未显式带 Authorization 时，从供应商 Key 生成
            request.setHeaders(mergeProviderAuthorization(request.getHeaders(), provider));
        }
        // R3-P0 SSRF：落库前校验出站 baseUrl
        final String safeBaseUrl = org.unreal.modelrouter.config.core.helper.SsrfGuard
                .validateOutboundBaseUrl(request.getBaseUrl());
        // v2.x: 生成 UUID 作为实例唯一标识符，用于熔断器等组件的 key
        String instanceUuid = java.util.UUID.randomUUID().toString();

        ServiceInstanceEntity entity = ServiceInstanceEntity.builder()
                .serviceConfigId(serviceConfigId)
                .providerId(providerId)
                .instanceName(request.getName())
                .instanceId(instanceUuid)
                .baseUrl(safeBaseUrl)
                .path(request.getPath())
                .weight(request.getWeight() != null ? request.getWeight() : 1)
                .status(request.getStatus() != null ? request.getStatus().toUpperCase() : "ACTIVE")
                .healthStatus("UNKNOWN")
                .adapter(request.getAdapter())
                .headers(request.getHeaders())
                .tags(request.getTags())
                .build();

        ServiceInstanceEntity saved = serviceInstanceRepository.save(entity);
        log.info("Created service instance: {} for config: {}", saved.getId(), serviceConfigId);
        
        // 保存版本
        saveVersion("添加服务实例: " + request.getName());
        
        return convertToDTO(saved);
    }

    /**
     * 更新实例
     * v2.x: 更新实例时重置熔断器状态（使用正确的 instanceId）
     * 2026-10-22: 支持跨服务类型（模型分类）迁移，见 {@link #migrateServiceType}
     */
    @Transactional
    public ServiceInstanceDTO updateInstance(final Long id, final CreateServiceInstanceRequest request) {
        ServiceInstanceEntity entity = serviceInstanceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Instance not found: " + id));

        String instanceName = entity.getInstanceName();

        // 2026-10-22: 跨分类迁移（先做，因为迁移可能改变后续字段的归属上下文）
        migrateServiceType(entity, request.getServiceType());

        // v2.11.0: 供应商关联更新。providerId 语义：null=不变；<=0=解除关联；>0=关联并从供应商补全空白字段
        if (request.getProviderId() != null) {
            final Long providerId = normalizeProviderId(request.getProviderId());
            entity.setProviderId(providerId);
            if (providerId != null) {
                final ModelProviderEntity provider = modelProviderRepository.findById(providerId)
                        .orElseThrow(() -> new RuntimeException("Model provider not found: " + providerId));
                applyProviderDefaults(request, provider);
                // 请求头显式下发时才合并供应商 Key（null 表示不动实例已有请求头）
                if (request.getHeaders() != null) {
                    request.setHeaders(mergeProviderAuthorization(request.getHeaders(), provider));
                }
            }
        }

        if (request.getName() != null) {
            entity.setInstanceName(request.getName());
        }
        if (request.getBaseUrl() != null) {
            // R3-P0 SSRF
            entity.setBaseUrl(org.unreal.modelrouter.config.core.helper.SsrfGuard
                    .validateOutboundBaseUrl(request.getBaseUrl()));
        }
        if (request.getPath() != null) {
            entity.setPath(request.getPath());
        }
        if (request.getWeight() != null) {
            entity.setWeight(request.getWeight());
        }
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus().toUpperCase());
        }
        if (request.getAdapter() != null) {
            entity.setAdapter(request.getAdapter());
        }
        if (request.getHeaders() != null) {
            entity.setHeaders(request.getHeaders());
        }
        if (request.getTags() != null) {
            entity.setTags(request.getTags());
        }

        ServiceInstanceEntity saved = serviceInstanceRepository.save(entity);
        log.info("Updated service instance: {}", id);

        // 重置该实例的熔断器状态
        // 使用 entity.getInstanceId()（UUID）而不是数据库 ID
        String circuitBreakerKey = saved.getInstanceId() != null ? saved.getInstanceId() : String.valueOf(id);
        String instanceUrl = saved.getBaseUrl() + saved.getPath();
        circuitBreakerManager.resetCircuitBreaker(circuitBreakerKey, instanceUrl);
        log.info("Reset circuit breaker for updated instance: dbId={}, instanceId={}, url={}", 
                id, circuitBreakerKey, instanceUrl);

        // 保存版本
        saveVersion("更新服务实例: " + instanceName);

        return convertToDTO(saved);
    }

    /**
     * 跨服务类型（模型分类）迁移。
     * <p>实例表没有 service_type 列——服务类型由 {@code service_config_id} 决定，
     * 所以「换分类」实质是改指向 service_config 的外键。但这个外键是<b>路由的一级维度</b>：
     * 请求按 URL 端点分桶、健康位图键为 {@code serviceType:instanceId}、
     * 实例限流桶键同样带 serviceType 前缀、池成员引用按池的 serviceType 匹配。
     * 因此迁移必须连带处理这些派生状态，否则会出现「路由断裂」与「同实例两套健康态」。</p>
     *
     * <p>迁移前置校验（任一不满足则抛 {@link ApiException}，前端会收到明确的 4xx 文案）：</p>
     * <ol>
     *   <li>目标分类的 service_config 必须存在；</li>
     *   <li>目标分类下不能有同名实例（模型名是路由匹配键，重名会导致路由歧义）；</li>
     *   <li>实例若被任何池引用则<b>拒绝</b>迁移——池的 serviceType 是单值的，
     *       跨分类后原池的成员引用会悬空（{@code PoolSelector} 会静默跳过不存在的成员），
     *       导致该池路由 503。需要先在池里移除该成员。</li>
     * </ol>
     *
     * @param entity            实例实体
     * @param targetServiceType 目标服务类型；为空或与当前一致则不做任何事
     * @since 2026-10-22
     */
    private void migrateServiceType(final ServiceInstanceEntity entity, final String targetServiceType) {
        if (targetServiceType == null || targetServiceType.isBlank()) {
            return;
        }
        final String normalizedTarget = targetServiceType.trim();
        final Long currentConfigId = entity.getServiceConfigId();

        // 目标 service_config 必须存在
        final ServiceConfigEntity targetConfig = serviceConfigRepository
                .findFirstByServiceTypeAndIsLatestTrue(normalizedTarget)
                .orElseThrow(() -> new ApiException("SERVICE_CONFIG_NOT_FOUND",
                        "目标模型分类不存在: " + normalizedTarget));

        // 已是该分类 → 无需迁移
        if (targetConfig.getId().equals(currentConfigId)) {
            return;
        }

        // 当前分类（用于清理旧前缀下的派生状态）
        final String currentServiceType = serviceConfigRepository.findById(currentConfigId)
                .map(ServiceConfigEntity::getServiceType)
                .orElse(null);

        // 目标分类下不能有同名实例（模型名是路由匹配键，重名会导致路由歧义）
        final boolean nameConflict = serviceInstanceRepository.findAllByInstanceName(entity.getInstanceName())
                .stream()
                .anyMatch(other -> !other.getId().equals(entity.getId())
                        && targetConfig.getId().equals(other.getServiceConfigId()));
        if (nameConflict) {
            throw new ApiException("CONFLICT_INSTANCE_NAME",
                    "目标模型分类「" + normalizedTarget + "」下已存在同名模型「"
                            + entity.getInstanceName() + "」，请先改名或删除冲突项");
        }

        // 实例若被池引用 → 拒绝迁移（跨分类的池成员引用无法迁移，会造成池路由 503）
        final List<String> referencedPools = findPoolsReferencingInstance(entity);
        if (!referencedPools.isEmpty()) {
            throw new ApiException("CONFLICT_INSTANCE_IN_POOL",
                    "该模型已被模型分组「" + String.join("、", referencedPools)
                            + "」引用，分组不支持跨模型分类的成员。请先从这些分组中移除它，再修改分类");
        }

        // 执行迁移：改指向目标 service_config
        entity.setServiceConfigId(targetConfig.getId());
        log.info("实例 {} 跨模型分类迁移: {} -> {}", entity.getId(), currentServiceType, normalizedTarget);

        // 级联清理旧分类下的派生状态（健康位图 / 实例限流桶，均以 serviceType 为键前缀）
        final String runtimeInstanceId = entity.getInstanceId();
        if (runtimeInstanceId != null && !runtimeInstanceId.isBlank() && currentServiceType != null) {
            serviceStateManager.removeInstanceHealthStatus(currentServiceType, runtimeInstanceId);
            final ModelServiceRegistry.ServiceType oldType = resolveServiceTypeEnum(currentServiceType);
            if (oldType != null) {
                rateLimitManager.removeInstanceRateLimiterById(oldType, runtimeInstanceId);
            } else {
                // 未知的服务类型名（历史脏数据）→ 无对应限流桶可清理，跳过
                log.debug("跳过清理限流桶：无法识别的服务类型 {}", currentServiceType);
            }
        }
    }

    /**
     * 把服务类型名解析成 {@link ModelServiceRegistry.ServiceType} 枚举。
     * <p>枚举常量是<b>全小写</b>的（{@code chat / embedding / rerank / tts / stt / imgGen / imgEdit}），
     * 且大小写混排（{@code imgGen}）——因此不能用 {@code valueOf(name.toUpperCase())}，
     * 那会永远抛 {@link IllegalArgumentException}。这里改为大小写无关地逐个比对。</p>
     *
     * @param serviceType 服务类型名（可空）
     * @return 对应的枚举；无法识别时返回 {@code null}
     */
    private ModelServiceRegistry.ServiceType resolveServiceTypeEnum(final String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return null;
        }
        for (final ModelServiceRegistry.ServiceType type : ModelServiceRegistry.ServiceType.values()) {
            if (type.name().equalsIgnoreCase(serviceType.trim())) {
                return type;
            }
        }
        return null;
    }

    /**
     * 查出引用了指定实例的模型分组（池）名称列表。
     * <p>池成员以 {@code instanceId}（运行时 UUID）引用实例；
     * 老数据可能只匹配实例名，因此两个口径都查。</p>
     */
    private List<String> findPoolsReferencingInstance(final ServiceInstanceEntity entity) {
        if (poolPersistenceService == null) {
            return List.of();
        }
        final List<String> poolNames = new ArrayList<>();
        final String runtimeInstanceId = entity.getInstanceId();
        final String instanceName = entity.getInstanceName();
        try {
            for (final org.unreal.modelrouter.router.pool.model.PoolDefinition pool
                    : poolPersistenceService.loadAll()) {
                if (pool == null || pool.getMembers() == null) {
                    continue;
                }
                final boolean referenced = pool.getMembers().stream().anyMatch(member -> {
                    if (member == null) {
                        return false;
                    }
                    final String memberId = member.getInstanceId();
                    if (runtimeInstanceId != null && runtimeInstanceId.equals(memberId)) {
                        return true;
                    }
                    // 老数据口径：池成员按实例名匹配
                    return instanceName != null && instanceName.equals(memberId);
                });
                if (referenced) {
                    poolNames.add(pool.getPoolName());
                }
            }
        } catch (Exception e) {
            // 池定义读取异常不能阻断迁移（属于辅助校验），记录告警后继续
            log.warn("检查实例所属模型分组失败，跳过该校验: {}", e.getMessage());
        }
        return poolNames;
    }

    /**
     * 删除实例
     */
    @Transactional
    public void deleteInstance(final Long id) {
        // 获取实例名称用于版本描述
        ServiceInstanceEntity entity = serviceInstanceRepository.findById(id)
                .orElse(null);
        String instanceName = entity != null ? entity.getInstanceName() : String.valueOf(id);
        
        // 同时删除关联的限流器和熔断器配置
        rateLimitRepository.deleteByInstanceId(id);
        circuitBreakerRepository.deleteByInstanceId(id);
        serviceInstanceRepository.deleteById(id);
        log.info("Deleted service instance: {}", id);
        
        // 保存版本
        saveVersion("删除服务实例: " + instanceName);
    }

    // ==================== v2.11.0 供应商级联操作 ====================

    /**
     * 供应商更换 Key 后级联刷新其下所有实例的 Authorization 头。
     * 实例已显式覆盖过 Authorization（与供应商 Key 不同）时保持原值不动。
     */
    @Transactional
    public int cascadeProviderKeyChange(final Long providerId, final String apiKey) {
        final List<ServiceInstanceEntity> instances = serviceInstanceRepository.findByProviderId(providerId);
        int updated = 0;
        for (ServiceInstanceEntity entity : instances) {
            final Map<String, String> headers = new HashMap<>();
            if (entity.getHeaders() != null) {
                headers.putAll(entity.getHeaders());
            }
            // 大小写不敏感地移除旧 Authorization，再按新 Key 重写
            headers.keySet().removeIf(k -> k != null && k.equalsIgnoreCase("Authorization"));
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                headers.put("Authorization", "Bearer " + apiKey.trim());
            }
            entity.setHeaders(headers);
            serviceInstanceRepository.save(entity);
            updated++;
        }
        if (updated > 0) {
            log.info("Cascaded provider key change to {} instances of provider {}", updated, providerId);
            saveVersion("供应商更换Key级联更新 " + updated + " 个实例");
        }
        return updated;
    }

    /**
     * 供应商启停级联：停用供应商时其下实例全部停用；启用时恢复。
     *
     * <p>实例 status 与供应商一致存大写（ACTIVE / INACTIVE），API 输出层转小写。
     * 已单独停用的实例在供应商重新启用时一并恢复（简化语义：实例级状态以供应商为准）。
     *
     * @param providerId 供应商主键
     * @param active     true=启用（ACTIVE） / false=停用（INACTIVE）
     * @return 受影响实例数
     */
    public int cascadeProviderStatus(final Long providerId, final boolean active) {
        final List<ServiceInstanceEntity> instances = serviceInstanceRepository.findByProviderId(providerId);
        final String target = active ? "ACTIVE" : "INACTIVE";
        int updated = 0;
        for (ServiceInstanceEntity entity : instances) {
            if (!target.equals(entity.getStatus())) {
                entity.setStatus(target);
                serviceInstanceRepository.save(entity);
                updated++;
            }
        }
        if (updated > 0) {
            log.info("Cascaded provider status -> {} to {} instances of provider {}", target, updated, providerId);
            saveVersion("供应商" + (active ? "启用" : "停用") + "级联更新 " + updated + " 个实例");
        }
        return updated;
    }

    /**
     * 删除供应商时解绑其下实例（provider_id 置空）。
     * 实例本身保留（地址与 Authorization 已复制到实例上，行为不变），只是不再关联供应商。
     */
    @Transactional
    public int unlinkProvider(final Long providerId) {
        final List<ServiceInstanceEntity> instances = serviceInstanceRepository.findByProviderId(providerId);
        for (ServiceInstanceEntity entity : instances) {
            entity.setProviderId(null);
            serviceInstanceRepository.save(entity);
        }
        if (!instances.isEmpty()) {
            log.info("Unlinked {} instances from deleted provider {}", instances.size(), providerId);
            saveVersion("删除供应商，解绑 " + instances.size() + " 个实例");
        }
        return instances.size();
    }

    /**
     * 更新健康状态
     */
    @Transactional
    public void updateHealthStatus(final Long id, final String healthStatus, final String errorMessage) {
        serviceInstanceRepository.findById(id).ifPresent(entity -> {
            entity.setHealthStatus(healthStatus);
            entity.setErrorMessage(errorMessage);
            serviceInstanceRepository.save(entity);
        });
    }

    // ==================== 限流器配置管理 ====================

    /**
     * 获取实例的限流器配置
     */
    public Optional<InstanceRateLimitDTO> getRateLimitConfig(final Long instanceId) {
        return rateLimitRepository.findByInstanceId(instanceId)
                .map(this::convertRateLimitToDTO);
    }

    /**
     * 保存或更新限流器配置
     */
    @Transactional
    public InstanceRateLimitDTO saveRateLimitConfig(final Long instanceId, final InstanceRateLimitDTO dto) {
        // 确保实例存在
        if (!serviceInstanceRepository.existsById(instanceId)) {
            throw new RuntimeException("Instance not found: " + instanceId);
        }

        InstanceRateLimitEntity entity = rateLimitRepository
                .findByInstanceId(instanceId)
                .orElse(InstanceRateLimitEntity.builder()
                        .instanceId(instanceId)
                        .enabled(false)
                        .algorithm("token-bucket")
                        .capacity(100)
                        .rate(10)
                        .scope("instance")
                        .clientIpEnable(false)
                        .build());

        // 更新字段
        if (dto.getEnabled() != null) {
            entity.setEnabled(dto.getEnabled());
        }
        if (dto.getAlgorithm() != null) {
            entity.setAlgorithm(dto.getAlgorithm());
        }
        if (dto.getCapacity() != null) {
            entity.setCapacity(dto.getCapacity());
        }
        if (dto.getRate() != null) {
            entity.setRate(dto.getRate());
        }
        if (dto.getScope() != null) {
            entity.setScope(dto.getScope());
        }
        if (dto.getKey() != null) {
            entity.setRateLimitKey(dto.getKey());
        }
        if (dto.getClientIpEnable() != null) {
            entity.setClientIpEnable(dto.getClientIpEnable());
        }

        InstanceRateLimitEntity saved = rateLimitRepository.save(entity);
        log.info("Saved rate limit config for instance: {}", instanceId);
        return convertRateLimitToDTO(saved);
    }

    /**
     * 删除限流器配置
     */
    @Transactional
    public void deleteRateLimitConfig(final Long instanceId) {
        rateLimitRepository.deleteByInstanceId(instanceId);
        log.info("Deleted rate limit config for instance: {}", instanceId);
    }

    // ==================== 熔断器配置管理 ====================

    /**
     * 获取实例的熔断器配置
     */
    public Optional<InstanceCircuitBreakerDTO> getCircuitBreakerConfig(final Long instanceId) {
        return circuitBreakerRepository.findByInstanceId(instanceId)
                .map(this::convertCircuitBreakerToDTO);
    }

    /**
     * 保存或更新熔断器配置
     * v2.x: 更新熔断器配置时重置内存中的熔断器状态（使用正确的 instanceId）
     */
    @Transactional
    public InstanceCircuitBreakerDTO saveCircuitBreakerConfig(
            final Long instanceId, final InstanceCircuitBreakerDTO dto) {
        // 确保实例存在
        if (!serviceInstanceRepository.existsById(instanceId)) {
            throw new RuntimeException("Instance not found: " + instanceId);
        }

        InstanceCircuitBreakerEntity entity = circuitBreakerRepository
                .findByInstanceId(instanceId)
                .orElse(InstanceCircuitBreakerEntity.builder()
                        .instanceId(instanceId)
                        .enabled(false)
                        .failureThreshold(5)
                        .timeoutMs(60000)
                        .successThreshold(2)
                        .build());

        // 更新字段
        if (dto.getEnabled() != null) {
            entity.setEnabled(dto.getEnabled());
        }
        if (dto.getFailureThreshold() != null) {
            entity.setFailureThreshold(dto.getFailureThreshold());
        }
        if (dto.getTimeout() != null) {
            entity.setTimeoutMs(dto.getTimeout());
        }
        if (dto.getSuccessThreshold() != null) {
            entity.setSuccessThreshold(dto.getSuccessThreshold());
        }

        InstanceCircuitBreakerEntity saved = circuitBreakerRepository.save(entity);
        log.info("Saved circuit breaker config for instance: {}", instanceId);

        // 重置内存中的熔断器状态
        serviceInstanceRepository.findById(instanceId).ifPresent(inst -> {
            // 使用 entity.getInstanceId()（UUID）而不是数据库 ID
            String circuitBreakerKey = inst.getInstanceId() != null ? inst.getInstanceId() : String.valueOf(instanceId);
            String instUrl = inst.getBaseUrl() + inst.getPath();
            circuitBreakerManager.resetCircuitBreaker(circuitBreakerKey, instUrl);
            log.info("Reset circuit breaker state after config update: dbId={}, instanceId={}, url={}", 
                    instanceId, circuitBreakerKey, instUrl);
        });

        return convertCircuitBreakerToDTO(saved);
    }

    /**
     * 删除熔断器配置
     */
    @Transactional
    public void deleteCircuitBreakerConfig(final Long instanceId) {
        circuitBreakerRepository.deleteByInstanceId(instanceId);
        log.info("Deleted circuit breaker config for instance: {}", instanceId);
    }

    // ==================== v2.11.0 供应商继承辅助 ====================

    /**
     * 归一化 providerId：null 或 <=0 均视为「无供应商」（前端用 0/负数 表示解除关联）。
     */
    private Long normalizeProviderId(final Long providerId) {
        if (providerId == null || providerId <= 0L) {
            return null;
        }
        return providerId;
    }

    /**
     * 用供应商值补全请求中的空白字段（显式值优先）：baseUrl / path / adapter。
     * 请求头（Authorization）不在此处合并 —— 由调用点显式处理，避免把「请求头未下发」
     * （更新语义为不动）误判为「用空头覆盖」。
     */
    private void applyProviderDefaults(final CreateServiceInstanceRequest request, final ModelProviderEntity provider) {
        if (isBlank(request.getBaseUrl())) {
            request.setBaseUrl(provider.getBaseUrl());
        }
        if (isBlank(request.getPath())) {
            request.setPath(provider.getPath());
        }
        if (isBlank(request.getAdapter())) {
            request.setAdapter(provider.getAdapter());
        }
    }

    /**
     * 合并供应商 API Key 到请求头：请求头已显式带 Authorization（大小写不敏感）时保留原值，
     * 否则在供应商配置了 Key 时补 {@code Authorization: Bearer <key>}。
     */
    private Map<String, String> mergeProviderAuthorization(final Map<String, String> headers,
            final ModelProviderEntity provider) {
        final Map<String, String> merged = new HashMap<>();
        if (headers != null) {
            merged.putAll(headers);
        }
        final boolean hasAuth = merged.keySet().stream()
                .anyMatch(k -> k != null && k.equalsIgnoreCase("Authorization"));
        if (!hasAuth && !isBlank(provider.getApiKey())) {
            merged.put("Authorization", "Bearer " + provider.getApiKey().trim());
        }
        return merged;
    }

    private boolean isBlank(final String value) {
        return value == null || value.trim().isEmpty();
    }

    // ==================== 转换方法 ====================

    private ServiceInstanceDTO convertToDTO(final ServiceInstanceEntity entity) {
        ServiceInstanceDTO dto = ServiceInstanceDTO.builder()
                .id(entity.getId())
                .serviceConfigId(entity.getServiceConfigId())
                .runtimeInstanceId(entity.getInstanceId())
                .providerId(entity.getProviderId())
                .name(entity.getInstanceName())
                .baseUrl(entity.getBaseUrl())
                .path(entity.getPath())
                .weight(entity.getWeight())
                .status(entity.getStatus())
                .healthStatus(entity.getHealthStatus())
                .errorMessage(entity.getErrorMessage())
                .adapter(entity.getAdapter())
                .headers(entity.getHeaders())
                .tags(entity.getTags())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();

        // v2.11.0: 带出供应商名称（列表展示「所属供应商」）；供应商已删时静默跳过
        if (entity.getProviderId() != null) {
            modelProviderRepository.findById(entity.getProviderId())
                    .ifPresent(p -> dto.setProviderName(p.getName()));
        }

        // 2026-10-22: 带出服务类型（模型分类）权威值——由 service_config 反查，
        // 不再让前端靠「当前在哪个 Tab」推断（那是伪字段，会与真实归属脱节）
        if (entity.getServiceConfigId() != null) {
            serviceConfigRepository.findById(entity.getServiceConfigId())
                    .ifPresent(cfg -> dto.setServiceType(cfg.getServiceType()));
        }

        // 加载限流器配置
        rateLimitRepository.findByInstanceId(entity.getId())
                .ifPresent(rateLimit -> dto.setRateLimit(convertRateLimitToDTO(rateLimit)));

        // 加载熔断器配置
        circuitBreakerRepository.findByInstanceId(entity.getId())
                .ifPresent(cb -> dto.setCircuitBreaker(convertCircuitBreakerToDTO(cb)));

        return dto;
    }

    private InstanceRateLimitDTO convertRateLimitToDTO(final InstanceRateLimitEntity entity) {
        return InstanceRateLimitDTO.builder()
                .id(entity.getId())
                .instanceId(entity.getInstanceId())
                .enabled(entity.getEnabled())
                .algorithm(entity.getAlgorithm())
                .capacity(entity.getCapacity())
                .rate(entity.getRate())
                .scope(entity.getScope())
                .key(entity.getRateLimitKey())
                .clientIpEnable(entity.getClientIpEnable())
                .build();
    }

    private InstanceCircuitBreakerDTO convertCircuitBreakerToDTO(final InstanceCircuitBreakerEntity entity) {
        return InstanceCircuitBreakerDTO.builder()
                .id(entity.getId())
                .instanceId(entity.getInstanceId())
                .enabled(entity.getEnabled())
                .failureThreshold(entity.getFailureThreshold())
                .timeout(entity.getTimeoutMs())
                .successThreshold(entity.getSuccessThreshold())
                .build();
    }

    /**
     * 保存版本
     * v1.5.3: 在实例变更后保存版本
     * v1.5.4: 添加元数据字段用于版本列表显示
     * 从数据库构建完整配置（包含实例数据），然后保存到 config_data 表
     */
    private void saveVersion(final String description) {
        try {
            // 从数据库构建完整配置
            Map<String, Object> fullConfig = buildFullConfigFromDatabase();

            // 添加元数据（用于版本列表显示操作类型和操作详情）
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("operation", "instanceChange");
            metadata.put("operationDetail", description);
            metadata.put("timestamp", System.currentTimeMillis());
            fullConfig.put("_metadata", metadata);

            // 保存为新版本 - 使用 ConfigVersionManager 替代废弃方法
            String userId = SecurityUtils.getCurrentUserId();
            configVersionManager.saveAsNewVersion(fullConfig, description, userId);
            log.info("版本已保存: {}", description);

            // 刷新运行时配置，确保实例变更立即生效
            modelServiceRegistry.refreshFromMergedConfig();
            log.info("运行时配置已刷新");
        } catch (Exception e) {
            log.warn("保存版本失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 从数据库构建完整配置
     * 包含所有服务配置和实例数据
     */
    private Map<String, Object> buildFullConfigFromDatabase() {
        // 获取基础配置
        Map<String, Object> baseConfig = configurationService.getAllConfigurations();
        Map<String, Object> result = new HashMap<>(baseConfig);
        
        // 获取所有服务配置
        List<ServiceConfigEntity> serviceConfigs = serviceConfigRepository.findAllByIsLatestTrue();
        
        // 构建服务配置 Map
        Map<String, Object> servicesMap = new HashMap<>();
        
        for (ServiceConfigEntity serviceConfig : serviceConfigs) {
            Map<String, Object> serviceMap = new HashMap<>();
            serviceMap.put("adapter", serviceConfig.getAdapter());
            
            // 负载均衡配置
            Map<String, Object> loadBalanceMap = new HashMap<>();
            loadBalanceMap.put("type", serviceConfig.getLoadBalanceType() != null
                    ? serviceConfig.getLoadBalanceType() : "round-robin");
            loadBalanceMap.put("hashAlgorithm", "md5");
            serviceMap.put("loadBalance", loadBalanceMap);
            
            // 获取该服务的所有实例
            List<ServiceInstanceEntity> instances = serviceInstanceRepository
                    .findByServiceConfigId(serviceConfig.getId());
            
            List<Map<String, Object>> instancesList = new ArrayList<>();
            for (ServiceInstanceEntity instance : instances) {
                Map<String, Object> instanceMap = new HashMap<>();
                instanceMap.put("name", instance.getInstanceName());
                instanceMap.put("baseUrl", instance.getBaseUrl());
                instanceMap.put("path", instance.getPath());
                instanceMap.put("weight", instance.getWeight());
                instanceMap.put("status", instance.getStatus());
                // 使用实体的 instanceId（UUID），而非数据库 ID
                instanceMap.put("instanceId", instance.getInstanceId() != null
                        ? instance.getInstanceId() : String.valueOf(instance.getId()));
                instanceMap.put("adapter", instance.getAdapter());
                instanceMap.put("headers", instance.getHeaders());
                instanceMap.put("tags", instance.getTags());

                // 添加限流器配置
                rateLimitRepository.findByInstanceId(instance.getId())
                        .ifPresent(rateLimit -> {
                            Map<String, Object> rateLimitMap = new HashMap<>();
                            rateLimitMap.put("enabled", rateLimit.getEnabled());
                            rateLimitMap.put("algorithm", rateLimit.getAlgorithm());
                            rateLimitMap.put("capacity", rateLimit.getCapacity());
                            rateLimitMap.put("rate", rateLimit.getRate());
                            rateLimitMap.put("scope", rateLimit.getScope());
                            rateLimitMap.put("key", rateLimit.getRateLimitKey());
                            rateLimitMap.put("clientIpEnable", rateLimit.getClientIpEnable());
                            instanceMap.put("rateLimit", rateLimitMap);
                        });
                
                // 添加熔断器配置
                circuitBreakerRepository.findByInstanceId(instance.getId())
                        .ifPresent(cb -> {
                            Map<String, Object> cbMap = new HashMap<>();
                            cbMap.put("enabled", cb.getEnabled());
                            cbMap.put("failureThreshold", cb.getFailureThreshold());
                            cbMap.put("timeout", cb.getTimeoutMs());
                            cbMap.put("successThreshold", cb.getSuccessThreshold());
                            instanceMap.put("circuitBreaker", cbMap);
                        });
                
                instancesList.add(instanceMap);
            }
            
            serviceMap.put("instances", instancesList);
            servicesMap.put(serviceConfig.getServiceType(), serviceMap);
        }
        
        result.put("services", servicesMap);
        return result;
    }
}