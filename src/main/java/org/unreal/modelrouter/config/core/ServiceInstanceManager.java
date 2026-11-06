package org.unreal.modelrouter.config.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;
import org.unreal.modelrouter.config.core.manager.ConfigVersionManager;
import org.unreal.modelrouter.config.dependency.DependencyCheckService;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.common.dto.InstanceRateLimitDTO;
import org.unreal.modelrouter.common.dto.InstanceCircuitBreakerDTO;
import org.unreal.modelrouter.config.dto.CreateServiceInstanceRequest;
import org.unreal.modelrouter.config.dto.EmployeeServiceInstanceVO;
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
import org.unreal.modelrouter.common.util.SensitiveHeaderSanitizer;
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
    /** 2026-10-26: 删除前依赖检测（模型分组 / 密钥白名单引用检查） */
    private final DependencyCheckService dependencyCheckService;

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
            @Lazy final org.unreal.modelrouter.router.pool.PoolPersistenceService poolPersistenceService,
            @Lazy final DependencyCheckService dependencyCheckService) {
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
        this.dependencyCheckService = dependencyCheckService;
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
     * 获取所有实例（员工自助面出参，敏感 header 已脱敏）
     *
     * <p><b>P0-L 凭据泄漏防护</b>：上游厂商的 API Key 以
     * {@code Authorization: Bearer <key>} 形式存在于实例 headers
     * （见 {@link #mergeProviderAuthorization}）。本方法供
     * {@code GET /api/instance/mine}（{@code config:instances:self}）使用，
     * 敏感 header 的值一律替换为 {@code "***"}，普通员工因此拿不到公司上游密钥。</p>
     *
     * <p><b>为什么管理面不脱敏</b>：管理面需要看到并编辑真实 Key ——
     * 运维在「模型配置」页编辑实例时，前端 {@code UpstreamInstanceForm} 会把
     * {@code headers.Authorization} 回填进表单并原样提交（{@code buildPayload}），
     * 若此处统一脱敏，运维改任意字段都会把库中的真实 Key 覆盖成 {@code "***"}，
     * 而真实 Key 只存在于上游厂商控制台，<b>无法恢复</b>。
     * 管理面已有 {@code config:instances:read} 权限闸门兜底（USER 已撤销）。</p>
     *
     * <p><b>本类刻意不提供「全量实例（明文 headers）」的公开访问器</b>：
     * 管理面走 {@link #getInstancesByServiceConfigId(Long)}（按服务类型取），
     * 其权限码已随 P0-L 从 USER 撤销；留一个明文全量访问器只会成为
     * 「哪天被接到员工可达路径上就重新泄漏」的隐患。</p>
     *
     * @return 脱敏后的实例列表（每次调用新建 DTO，不影响任何共享状态）
     */
    public List<ServiceInstanceDTO> getAllInstancesSanitized() {
        return serviceInstanceRepository.findAll()
                .stream()
                .map(this::convertToDTO)
                .map(dto -> {
                    dto.setHeaders(SensitiveHeaderSanitizer.sanitize(dto.getHeaders()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * 按服务类型（模型分类）查询实例（P0-L 员工端点 N+1 消除）。
     *
     * <p><b>为什么需要它</b>：员工端点 {@code GET /api/instance/mine} 此前
     * {@code findAll()} 全表扫再内存过滤，每次进测试场触发一次全表扫。
     * 本方法先按 {@code serviceType} 反查 service_config（取最新版本），
     * 再按 {@code serviceConfigId} 查实例，避免全表扫。</p>
     *
     * <p><b>大小写归一化</b>：serviceType 由前端传入，大小写敏感会导致
     * 「chat」与「CHAT」静默空白无提示，因此统一小写比较。</p>
     *
     * @param serviceType 服务类型（chat / embedding / …）；大小写无关
     * @return 该服务类型下的实例列表（明文 headers，仅供管理面内部复用）
     * @since P0-L
     */
    public List<ServiceInstanceDTO> findByServiceType(final String serviceType) {
        if (serviceType == null || serviceType.trim().isEmpty()) {
            return List.of();
        }
        final String wanted = serviceType.trim();
        // serviceType 由 service_config 决定，先反查 config
        final List<ServiceConfigEntity> configs = serviceConfigRepository
                .findAllByIsLatestTrue();
        final Long configId = configs.stream()
                .filter(cfg -> cfg != null && cfg.getServiceType() != null
                        && wanted.equalsIgnoreCase(cfg.getServiceType()))
                .map(ServiceConfigEntity::getId)
                .findFirst()
                .orElse(null);
        if (configId == null) {
            return List.of();
        }
        return serviceInstanceRepository.findByServiceConfigId(configId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有实例（员工自助面出参，裁剪为员工可见字段）。
     *
     * <p><b>P0-L 凭据泄漏防护</b>：复用 {@link #convertToDTO} 后映射到
     * {@link org.unreal.modelrouter.config.dto.EmployeeServiceInstanceVO}，
     * 只保留前端试验场真正读取的字段（instanceId / name / serviceType /
     * status / healthStatus / adapter / weight / tags），
     * <b>剔除</b> headers / errorMessage / baseUrl / path / rateLimit /
     * circuitBreaker / providerId / providerName / serviceConfigId /
     * id / createdAt / updatedAt。</p>
     *
     * <p><b>为什么剔除 headers</b>：员工拿到 headers 后可直调上游厂商 API；
     * 且前端编辑时若回填 {@code Authorization: "***"} 会覆盖真实 Key。
     * 员工 VO 不含 headers → 前端无法覆盖 → 审查项4 后端部分由本方法完全覆盖。</p>
     *
     * @return 员工裁剪后的实例列表
     * @since P0-L
     */
    public List<EmployeeServiceInstanceVO> getAllInstancesForEmployee() {
        return serviceInstanceRepository.findAll()
                .stream()
                .map(this::convertToDTO)
                .map(this::toEmployeeVO)
                .collect(Collectors.toList());
    }

    /**
     * ServiceInstanceDTO → 员工 VO（裁剪字段）。
     *
     * @param dto 管理面全量 DTO
     * @return 员工裁剪 VO
     */
    private EmployeeServiceInstanceVO toEmployeeVO(
            final ServiceInstanceDTO dto) {
        return EmployeeServiceInstanceVO.builder()
                .instanceId(dto.getRuntimeInstanceId())
                .name(dto.getName())
                .serviceType(dto.getServiceType())
                .status(dto.getStatus())
                .healthStatus(dto.getHealthStatus())
                .adapter(dto.getAdapter())
                .weight(dto.getWeight())
                .tags(dto.getTags())
                .build();
    }

    /**
     * 按服务类型获取员工可见实例（P0-L N+1 消除 + 大小写归一化）。
     *
     * @param serviceType 服务类型（大小写无关）
     * @return 员工裁剪后的实例列表
     * @since P0-L
     */
    public List<EmployeeServiceInstanceVO> findByServiceTypeForEmployee(
            final String serviceType) {
        return findByServiceType(serviceType)
                .stream()
                .map(this::toEmployeeVO)
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

        // v3.5 治理收口:新增实例后,同类下上一轮探活结论(可能全是 false)不再适用于新实例 ——
        // 服务级健康快照只由 ServerChecker 每 30s 刷新,期间该分类会被整体判为不可用。
        // 管理员「新增实例」是显式意图,不应被上一轮结论否决。
        resetInstanceHealthStateAfterChange(entity);

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

        // v3.5 治理收口:实例地址/路径变更后,上一轮的探活结论已不适用 ——
        // 实例健康位的键是 serviceType:instanceId(UPDATE 不改 instanceId),所以旧地址
        // 探活得到的 false 会一直挂在该实例上,直到下一轮探活(最长 30s)才被覆盖。
        // 期间该实例被路由层 filterHealthyInstances 直接过滤掉,表现为「改完地址还是 503」。
        // 这里主动重置该实例的健康判定(回落为「未检查」),让下一轮探活重新给出结论。
        resetInstanceHealthStateAfterChange(entity);

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
     * 删除实例：被模型分组引用、或被启用中的密钥白名单关联时拒绝删除。
     *
     * <p>2026-10-26 新增依赖校验——本库无数据库外键，删掉实例后分组成员与密钥白名单
     * 都会指向不存在的对象（路由静默跳过、密钥调用 403），且极难定位。</p>
     */
    @Transactional
    public void deleteInstance(final Long id) {
        // 获取实例名称用于版本描述
        ServiceInstanceEntity entity = serviceInstanceRepository.findById(id)
                .orElse(null);
        String instanceName = entity != null ? entity.getInstanceName() : String.valueOf(id);

        // 删除前依赖校验：分组成员 / 密钥白名单仍引用该实例时拒绝删除
        dependencyCheckService.assertDeletable(dependencyCheckService.checkInstance(id));

        // 同时删除关联的限流器和熔断器配置
        rateLimitRepository.deleteByInstanceId(id);
        circuitBreakerRepository.deleteByInstanceId(id);
        serviceInstanceRepository.deleteById(id);
        log.info("Deleted service instance: {}", id);

        // v3.5 治理收口:删除实例后清理它的健康位与同类快照 ——
        // ① 实例健康位:被删实例的健康记录会一直留在位图里,占用位槽且永不复用;
        // ② 服务级快照:若该实例是同类最后一个,ServerChecker 不再为该类建检查任务,
        //    上一轮的 false 会永久残留,导致该分类后续新增实例时仍被判不可用。
        resetInstanceHealthStateAfterChange(entity);

        // 保存版本
        saveVersion("删除服务实例: " + instanceName);
    }

    /**
     * 实例增删改后重置该实例的健康判定，并失效同类的服务级健康快照。
     *
     * <p><b>为什么需要它：</b>健康状态有两层，都由 {@code ServerChecker} 每 30s 刷新：</p>
     * <ul>
     *   <li><b>实例级</b>（{@code instanceHealthBits}）：键是 {@code serviceType:instanceId}。
     *       instanceId 是 UUID，<b>改上游地址不会改变键</b>，于是旧地址探活得到的
     *       {@code false} 会一直挂在该实例上，直到下一轮探活才被覆盖。期间该实例被
     *       路由层 {@code filterHealthyInstances} 直接过滤掉 → 「改完地址还是 503」。</li>
     *   <li><b>服务级</b>（{@code serviceHealthStatus}）：只对「有实例」的分类建检查任务，
     *       所以分类下实例被删光后，缓存里的 {@code false} 会<b>永久残留</b>；
     *       分类下新增实例时，缓存也停留在上一轮结论 → 最长 30s 内整个分类被判不可用。</li>
     * </ul>
     *
     * <p><b>为什么不削弱可用性保护：</b>清掉的只是<b>陈旧快照</b>，不是判定本身。
     * 真正的可用性判定在路由层——实例级健康位 + 状态 + 熔断三重过滤，
     * 候选为空时路由层会抛出带逐成员明细的 503。</p>
     *
     * @param entity 变更后的实例实体；为 {@code null} 时只做分类级快照失效
     * @since 2026-10-27
     */
    private void resetInstanceHealthStateAfterChange(final ServiceInstanceEntity entity) {
        if (entity == null) {
            return;
        }
        // B1: 实例生命周期淘汰——实例增删改时同步摘除其熔断器，避免已删除/已换址实例的
        // 熔断状态残留在单一注册表里（键为 instanceId，重新创建同 ID 实例时继承旧熔断计数）。
        // 与健康位重置同源：清除的只是陈旧状态，真实判定由下一轮调用结果重建。
        final String cbInstanceId = entity.getInstanceId();
        if (cbInstanceId != null && !cbInstanceId.isBlank()) {
            final String cbInstanceUrl = entity.getBaseUrl() + (entity.getPath() != null ? entity.getPath() : "");
            circuitBreakerManager.resetCircuitBreaker(cbInstanceId, cbInstanceUrl);
        }
        // 服务级：失效该实例所属分类的快照（分类名从 service_config 反查）。
        // service_config.service_type 落库值与 ModelServiceRegistry.ServiceType 枚举名一致
        // （chat / embedding / rerank / tts / stt / imgGen / imgEdit），
        // 与探活写入侧的健康键前缀口径相同，可直接用于构造键。
        final String serviceType = serviceConfigRepository.findById(entity.getServiceConfigId())
                .map(ServiceConfigEntity::getServiceType)
                .orElse(null);
        if (serviceType == null || serviceType.isBlank()) {
            log.debug("跳过健康状态重置：无法反查实例 {} 的模型分类", entity.getId());
            return;
        }
        // 实例级：清掉「健康 / 已知」标志位但保留位索引（位槽不回收，避免频繁增删耗尽位槽）
        final String instanceId = entity.getInstanceId();
        if (instanceId != null && !instanceId.isBlank()) {
            serviceStateManager.resetInstanceHealthStatusById(serviceType, instanceId);
        }
        serviceStateManager.resetServiceHealthStatus(serviceType);
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

        // 注入实例级配置到运行时熔断器（修复「配置→生效」断链），并重置内存状态使其按新阈值重新累积
        serviceInstanceRepository.findById(instanceId).ifPresent(inst -> {
            // 使用 inst.getInstanceId()（UUID）而不是数据库 ID
            final String circuitBreakerKey = inst.getInstanceId() != null ? inst.getInstanceId() : String.valueOf(instanceId);
            final String instUrl = inst.getBaseUrl() != null
                    ? inst.getBaseUrl() + (inst.getPath() != null ? inst.getPath() : "") : null;
            circuitBreakerManager.applyInstanceConfig(
                    circuitBreakerKey, instUrl,
                    saved.getEnabled() != null && saved.getEnabled(),
                    saved.getFailureThreshold() != null ? saved.getFailureThreshold() : 5,
                    saved.getTimeoutMs() != null ? saved.getTimeoutMs() : 60000L,
                    saved.getSuccessThreshold() != null ? saved.getSuccessThreshold() : 2);
            circuitBreakerManager.resetCircuitBreaker(circuitBreakerKey, instUrl);
            log.info("Applied + reset circuit breaker after config update: dbId={}, instanceId={}, url={}",
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
        // 同步清除运行时实例级配置，回落全局默认阈值
        serviceInstanceRepository.findById(instanceId).ifPresent(inst -> {
            final String circuitBreakerKey = inst.getInstanceId() != null ? inst.getInstanceId() : String.valueOf(instanceId);
            final String instUrl = inst.getBaseUrl() != null
                    ? inst.getBaseUrl() + (inst.getPath() != null ? inst.getPath() : "") : null;
            circuitBreakerManager.clearInstanceConfig(circuitBreakerKey, instUrl);
        });
        log.info("Deleted circuit breaker config for instance: {}", instanceId);
    }

    /**
     * 启动加载：将 {@code instance_circuit_breaker} 表中既有配置注入运行时熔断器管理器。
     *
     * <p>修复「配置→生效」断链——否则历史配置仅在保存后才生效，需重启才能见效。
     * 包在 try/catch 中：若 Flyway 尚未完成迁移（表不存在），仅记录告警，待下次保存时再注入。</p>
     */
    @PostConstruct
    public void loadCircuitBreakerConfigsIntoManager() {
        try {
            circuitBreakerRepository.findAll().forEach(entity ->
                serviceInstanceRepository.findById(entity.getInstanceId()).ifPresent(inst -> {
                    final String circuitBreakerKey = inst.getInstanceId() != null
                            ? inst.getInstanceId() : String.valueOf(entity.getInstanceId());
                    final String instUrl = inst.getBaseUrl() != null
                            ? inst.getBaseUrl() + (inst.getPath() != null ? inst.getPath() : "") : null;
                    circuitBreakerManager.applyInstanceConfig(
                            circuitBreakerKey, instUrl,
                            entity.getEnabled() != null && entity.getEnabled(),
                            entity.getFailureThreshold() != null ? entity.getFailureThreshold() : 5,
                            entity.getTimeoutMs() != null ? entity.getTimeoutMs() : 60000L,
                            entity.getSuccessThreshold() != null ? entity.getSuccessThreshold() : 2);
                }));
            log.info("Loaded existing instance circuit breaker configs into runtime manager");
        } catch (final Exception e) {
            log.warn("Skipped loading circuit breaker configs at startup (will apply on next save): {}",
                    e.getMessage());
        }
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
                // P0-L：本转换服务于「管理面」，headers 保留明文。
                // 管理面仅对持 config:instances:read 的运维/管理员开放（USER 已撤销该码），
                // 而运维的职责就是管理上游 Key —— 若在此统一脱敏，运维在「模型配置」页编辑实例时
                // 会把真实 Key 覆盖成 "***"（UpstreamInstanceForm.vue 回填 → buildPayload 提交 → 落库），
                // 真实 Key 只存在于上游厂商控制台，**无法恢复**。
                // 员工侧走 GET /api/instance/mine（config:instances:self），该端点出参单独脱敏。
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