package org.unreal.modelrouter.config.service;

import org.unreal.modelrouter.persistence.store.ConfigKeyCompatStoreManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.unreal.modelrouter.common.constants.ServiceTypeConstants;
import org.unreal.modelrouter.common.dto.ModelProviderDTO;
import org.unreal.modelrouter.common.dto.ProviderCatalogDTO;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.config.core.ServiceInstanceManager;
import org.unreal.modelrouter.config.dependency.DependencyCheckService;
import org.unreal.modelrouter.config.core.helper.SsrfGuard;
import org.unreal.modelrouter.config.dto.BatchAddModelsResult;
import org.unreal.modelrouter.config.dto.CreateModelProviderRequest;
import org.unreal.modelrouter.config.dto.CreateServiceInstanceRequest;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.handler.ServiceEndpoint;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.WebClientCacheManager;
import org.unreal.modelrouter.common.exception.ApiException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 模型供应商服务（v2.11.0）
 *
 * <p>供应商层 = 「同一上游只配一次」：baseUrl / path / adapter / 上游 API Key 收敛到
 * {@link ModelProviderEntity}，模型（实例）通过 providerId 继承。核心能力：
 * CRUD、启停、换 Key 级联刷新实例 Authorization、发现模型（代理上游 /models）、
 * 按勾选清单批量创建模型实例。
 */
@Slf4j
@Service
public class ModelProviderService {

    /**
     * 纯版本路径（如 {@code /v1}、{@code /api/paas/v4}）：只有版本段、没有端点段，
     * 换算模型分类端点时直接在其后拼接。
     */
    private static final Pattern VERSION_ONLY_PATH = Pattern.compile("^.*/v\\d+$");

    private final ModelProviderRepository providerRepository;
    private final ServiceConfigRepository serviceConfigRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final ServiceInstanceManager serviceInstanceManager;
    private final WebClientCacheManager webClientCacheManager;
    /** 2026-10-26: 删除前依赖检测（其下已接入模型存在时拒绝删除） */
    private final DependencyCheckService dependencyCheckService;

    public ModelProviderService(
            final ModelProviderRepository providerRepository,
            final ServiceConfigRepository serviceConfigRepository,
            final ServiceInstanceRepository serviceInstanceRepository,
            final ServiceInstanceManager serviceInstanceManager,
            final WebClientCacheManager webClientCacheManager,
            final DependencyCheckService dependencyCheckService) {
        this.providerRepository = providerRepository;
        this.serviceConfigRepository = serviceConfigRepository;
        this.serviceInstanceRepository = serviceInstanceRepository;
        this.serviceInstanceManager = serviceInstanceManager;
        this.webClientCacheManager = webClientCacheManager;
        this.dependencyCheckService = dependencyCheckService;
    }

    // ==================== 查询 ====================

    /**
     * 全部供应商（卡片列表，含「已接入模型数」；apiKey 不回传，仅 hasApiKey）
     */
    public List<ModelProviderDTO> listAll() {
        final List<ModelProviderEntity> entities = providerRepository.findAllByOrderBySortOrderAscIdAsc();
        final List<ModelProviderDTO> result = new ArrayList<>(entities.size());
        for (ModelProviderEntity entity : entities) {
            result.add(toDTO(entity, serviceInstanceRepository.countByProviderId(entity.getId())));
        }
        return result;
    }

    /**
     * 单个供应商详情
     */
    public ModelProviderDTO get(final Long id) {
        final ModelProviderEntity entity = providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));
        return toDTO(entity, serviceInstanceRepository.countByProviderId(entity.getId()));
    }

    /**
     * 供应商下已接入的模型实例列表（管理模型抽屉的「已接入」区）
     */
    public List<ServiceInstanceDTO> listModels(final Long id) {
        providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));
        return serviceInstanceRepository.findByProviderId(id).stream()
                .map(inst -> serviceInstanceManager.getInstance(inst.getId()))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(java.util.stream.Collectors.toList());
    }

    // ==================== 创建 / 更新 / 删除 ====================

    /**
     * 供应商目录：供应商 + 其下已接入模型名（合并视图，供「接入厂商」表格直接渲染）。
     *
     * <p>一次性装配，避免前端为每个供应商再发一次模型列表请求。</p>
     */
    public List<ProviderCatalogDTO> listCatalog() {
        final List<ModelProviderEntity> providers =
                providerRepository.findAllByOrderBySortOrderAscIdAsc();
        // 按供应商聚合实例：模型名 + 健康状态
        final Map<Long, List<ServiceInstanceEntity>> byProvider = new java.util.LinkedHashMap<>();
        for (final ServiceInstanceEntity instance : serviceInstanceRepository.findAll()) {
            if (instance.getProviderId() != null) {
                byProvider.computeIfAbsent(instance.getProviderId(), k -> new ArrayList<>()).add(instance);
            }
        }

        final List<ProviderCatalogDTO> result = new ArrayList<>(providers.size());
        for (final ModelProviderEntity provider : providers) {
            final List<ServiceInstanceEntity> instances = byProvider.getOrDefault(provider.getId(), List.of());
            final List<String> models = new ArrayList<>(instances.size());
            int unhealthy = 0;
            for (final ServiceInstanceEntity instance : instances) {
                if (instance.getInstanceName() != null) {
                    models.add(instance.getInstanceName());
                }
                if (instance.getHealthStatus() != null
                        && "UNHEALTHY".equalsIgnoreCase(instance.getHealthStatus())) {
                    unhealthy++;
                }
            }
            result.add(ProviderCatalogDTO.builder()
                    .id(provider.getId())
                    .providerUid(provider.getProviderUid())
                    .name(provider.getName())
                    .baseUrl(provider.getBaseUrl())
                    .serviceType(provider.getServiceType())
                    .status(provider.getStatus())
                    .hasApiKey(!isBlank(provider.getApiKey()))
                    .modelCount(instances.size())
                    .models(models)
                    .unhealthyCount(unhealthy)
                    .build());
        }
        return result;
    }

    /**
     * 创建供应商
     */
    @Transactional
    public ModelProviderDTO create(final CreateModelProviderRequest request) {
        if (isBlank(request.getName())) {
            throw new ApiException("PROVIDER_NAME_REQUIRED", "供应商名称不能为空");
        }
        if (isBlank(request.getBaseUrl())) {
            throw new ApiException("PROVIDER_BASEURL_REQUIRED", "供应商地址不能为空");
        }
        if (isBlank(request.getApiKey())) {
            throw new ApiException("PROVIDER_APIKEY_REQUIRED", "请填写上游 API Key（同一供应商只需填一次）");
        }
        providerRepository.findByName(request.getName().trim()).ifPresent(p -> {
            throw new ApiException("PROVIDER_NAME_DUPLICATED", "供应商名称已存在: " + request.getName());
        });
        // 预设目录一键启用按 providerUid 幂等去重：传了 uid 且已存在则直接报冲突，由前端转为「更新 Key」流程
        final String providerUid = isBlank(request.getProviderUid())
                ? UUID.randomUUID().toString()
                : request.getProviderUid().trim();
        providerRepository.findByProviderUid(providerUid).ifPresent(p -> {
            throw new ApiException("PROVIDER_UID_DUPLICATED", "该供应商预设已添加过: " + p.getName());
        });

        final ModelProviderEntity entity = ModelProviderEntity.builder()
                .providerUid(providerUid)
                .name(request.getName().trim())
                .serviceType(isBlank(request.getServiceType()) ? "chat" : request.getServiceType().trim())
                // R3-P0 SSRF：供应商地址与实例同等校验
                .baseUrl(SsrfGuard.validateOutboundBaseUrl(request.getBaseUrl().trim()))
                .path(defaultIfBlank(request.getPath(), "/v1/chat/completions"))
                .adapter(trimToNull(request.getAdapter()))
                .apiKey(request.getApiKey().trim())
                .modelsPath(trimToNull(request.getModelsPath()))
                .status(normalizeStatus(request.getStatus()))
                .description(trimToNull(request.getDescription()))
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();

        final ModelProviderEntity saved = providerRepository.save(entity);
        log.info("Created model provider: {} ({})", saved.getName(), saved.getId());
        return toDTO(saved, 0L);
    }

    /**
     * 更新供应商（null 字段不修改；apiKey 留空=保持原值，换 Key 走 {@link #rotateKey}）
     */
    @Transactional
    public ModelProviderDTO update(final Long id, final CreateModelProviderRequest request) {
        final ModelProviderEntity entity = providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));

        if (!isBlank(request.getName()) && !request.getName().trim().equals(entity.getName())) {
            providerRepository.findByName(request.getName().trim()).ifPresent(p -> {
                throw new ApiException("PROVIDER_NAME_DUPLICATED", "供应商名称已存在: " + request.getName());
            });
            entity.setName(request.getName().trim());
        }
        if (!isBlank(request.getServiceType())) {
            entity.setServiceType(request.getServiceType().trim());
        }
        if (!isBlank(request.getBaseUrl())) {
            entity.setBaseUrl(SsrfGuard.validateOutboundBaseUrl(request.getBaseUrl().trim()));
        }
        if (request.getPath() != null) {
            entity.setPath(request.getPath());
        }
        if (request.getAdapter() != null) {
            entity.setAdapter(trimToNull(request.getAdapter()));
        }
        if (!isBlank(request.getModelsPath())) {
            entity.setModelsPath(request.getModelsPath().trim());
        }
        if (!isBlank(request.getStatus())) {
            entity.setStatus(normalizeStatus(request.getStatus()));
        }
        if (request.getDescription() != null) {
            entity.setDescription(trimToNull(request.getDescription()));
        }
        if (request.getSortOrder() != null) {
            entity.setSortOrder(request.getSortOrder());
        }

        final ModelProviderEntity saved = providerRepository.save(entity);
        log.info("Updated model provider: {} ({})", saved.getName(), saved.getId());
        return toDTO(saved, serviceInstanceRepository.countByProviderId(saved.getId()));
    }

    /**
     * 删除供应商：其下仍有已接入模型时拒绝删除（须先删除这些模型）。
     *
     * <p>2026-10-26 起由「静默解绑实例」改为「拒绝删除」——本库无数据库外键约束，
     * 解绑后实例仍保留旧的供应商语义却已无供应商可依，形成悬空引用，
     * 表现为「模型在库里但路由不到」。</p>
     */
    @Transactional
    public void delete(final Long id) {
        providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));
        dependencyCheckService.assertDeletable(dependencyCheckService.checkProvider(id));
        final int unlinked = serviceInstanceManager.unlinkProvider(id);
        providerRepository.deleteById(id);
        log.info("Deleted model provider: {} (unlinked {} instances)", id, unlinked);
    }

    /**
     * 更换上游 API Key：更新供应商并级联刷新其下所有实例的 Authorization 头。
     */
    @Transactional
    public ModelProviderDTO rotateKey(final Long id, final String apiKey) {
        if (isBlank(apiKey)) {
            throw new ApiException("PROVIDER_APIKEY_REQUIRED", "新 API Key 不能为空");
        }
        final ModelProviderEntity entity = providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));
        entity.setApiKey(apiKey.trim());
        final ModelProviderEntity saved = providerRepository.save(entity);
        final int cascaded = serviceInstanceManager.cascadeProviderKeyChange(saved.getId(), saved.getApiKey());
        log.info("Rotated key for provider {} ({}), cascaded to {} instances",
                saved.getName(), saved.getId(), cascaded);
        return toDTO(saved, serviceInstanceRepository.countByProviderId(saved.getId()));
    }

    /**
     * 启用 / 禁用供应商
     */
    @Transactional
    public ModelProviderDTO updateStatus(final Long id, final String status) {
        final ModelProviderEntity entity = providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));
        final String normalized = normalizeStatus(status);
        entity.setStatus(normalized);
        final ModelProviderEntity saved = providerRepository.save(entity);
        // 级联：停用供应商 → 其下实例全部停用；启用 → 全部恢复
        final int cascaded = serviceInstanceManager.cascadeProviderStatus(saved.getId(), "ACTIVE".equals(normalized));
        log.info("Provider {} status -> {} (cascaded to {} instances)",
                saved.getName(), saved.getStatus(), cascaded);
        return toDTO(saved, serviceInstanceRepository.countByProviderId(saved.getId()));
    }

    // ==================== 发现模型 ====================

    /**
     * 从上游拉取模型列表（OpenAI 兼容 {@code GET {baseUrl}{modelsPath}/models}）。
     *
     * <p>modelsPath 为空时按 path 启发式推导：取 path 中的版本前缀
     * （{@code /v1/chat/completions → /v1/models}；无版本前缀则 {@code /models}）。
     *
     * @return 上游返回的模型 ID 列表（保持上游顺序，去重）
     */
    @SuppressWarnings("unchecked")
    public Mono<List<String>> discoverModels(final Long id) {
        final ModelProviderEntity entity = providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));

        final String modelsPath = resolveModelsPath(entity);
        final String url = entity.getBaseUrl().replaceAll("/+$", "") + modelsPath;
        log.info("Discovering models from provider {} ({}): {}", entity.getName(), entity.getId(), url);

        // 响应式链路：不能在 parallel 调度线程上 block()（IllegalStateException），
        // 故整体返回 Mono，由 Controller 直接回写（同 AdapterTestService 模式）。
        return webClientCacheManager.getOrCreate(entity.getBaseUrl())
                .get()
                .uri(modelsPath)
                .headers(httpHeaders -> {
                    if (!isBlank(entity.getApiKey())) {
                        httpHeaders.set("Authorization", "Bearer " + entity.getApiKey().trim());
                    }
                })
                .retrieve()
                // ParameterizedTypeReference 保持泛型（Map.class 会把链尾传染成 raw List）
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(body -> extractModelIds(body, url))
                // extractModelIds 内抛的 ApiException 原样透传（map lambda 抛出的异常会成为 onError 信号）；
                // 显式泛型参数避免链尾被推断成原始 List
                .onErrorResume(ApiException.class, ex -> Mono.<List<String>>error(ex))
                .onErrorResume(WebClientResponseException.class, ex -> Mono.error(new ApiException(
                        "PROVIDER_DISCOVER_FAILED",
                        "上游返回 " + ex.getStatusCode().value() + "（" + url + "）：请检查 API Key 与地址，或改用「单个添加」")))
                .onErrorResume(TimeoutException.class, ex -> Mono.error(new ApiException(
                        "PROVIDER_DISCOVER_TIMEOUT", "连接上游超时（" + url + "），请检查网络或 Base URL")))
                .onErrorResume(ConnectException.class, ex -> Mono.error(new ApiException(
                        "PROVIDER_DISCOVER_FAILED", "无法连接上游（" + url + "）：" + ex.getMessage())))
                .onErrorResume(ex -> {
                    // 已是 ApiException（如上游 401 分支新抛的）直接透传，避免消息被重复包装
                    if (ex instanceof ApiException) {
                        return Mono.error(ex);
                    }
                    log.warn("Discover models failed for provider {} ({}): {}", entity.getName(), id, ex.getMessage());
                    return Mono.error(new ApiException("PROVIDER_DISCOVER_FAILED",
                            "获取上游模型列表失败：" + ex.getMessage() + "（" + url + "），请检查地址与 Key，或改用「单个添加」"));
                })
                .timeout(Duration.ofSeconds(15));
    }

    /**
     * 从 OpenAI 兼容 /models 响应体提取模型 ID（保持上游顺序、去重）。
     * 空列表视为失败，引导用户改用「单个添加」。
     */
    @SuppressWarnings("unchecked")
    private List<String> extractModelIds(final Map<String, Object> body, final String url) {
        if (body == null || !(body.get("data") instanceof List<?> data) || data.isEmpty()) {
            throw new ApiException("PROVIDER_DISCOVER_EMPTY",
                    "上游未返回模型列表（" + url + "），请检查地址与 Key 后重试，或改用下方「单个添加」");
        }
        final Set<String> ids = new LinkedHashSet<>();
        for (Object item : data) {
            if (item instanceof Map<?, ?> model) {
                final Object modelId = model.get("id");
                if (modelId != null && !modelId.toString().isBlank()) {
                    ids.add(modelId.toString());
                }
            }
        }
        if (ids.isEmpty()) {
            throw new ApiException("PROVIDER_DISCOVER_EMPTY", "上游返回的模型列表为空，请改用「单个添加」");
        }
        return new ArrayList<>(ids);
    }

    /**
     * 取指定模型分类的服务配置；<b>不存在时自动补建</b>。
     *
     * <p><b>为什么补建而不是报错：</b>实例的归属完全依赖 {@code service_config} 外键
     * （{@code service_instance.service_config_id}），而分类目录的权威来源是
     * {@code config_data} 里持久化的 {@code services} 映射——两者本应一一对应，
     * 但历史上初始化条件是「{@code config_data} 与 {@code service_config} 都为空」
     * 才从 YAML 灌数据，导致「配置已有分类、外键表却空」的库：接入厂商时
     * 按分类查服务配置必然落空（报「服务配置不存在: chat」），而分类本身是用户
     * 在界面上选的合法值。接入流程是自助式的，因此这里选择自愈补建，
     * 让「选了分类就能接入」。</p>
     *
     * @param serviceType 模型分类（chat / embedding / imgGen …）
     * @return 该分类的服务配置（新建或已有）
     */
    private ServiceConfigEntity ensureServiceConfig(final String serviceType) {
        final String type = serviceType == null ? "" : serviceType.trim();
        if (type.isEmpty()) {
            throw new ApiException("SERVICE_TYPE_REQUIRED", "请先选择模型分类");
        }
        return serviceConfigRepository.findFirstByServiceTypeAndIsLatestTrue(type)
                .orElseGet(() -> {
                    final ServiceConfigEntity saved = serviceConfigRepository.save(
                            ServiceConfigEntity.builder()
                                    .configKey(ConfigKeyCompatStoreManager.CURRENT_CONFIG_KEY)
                                    .serviceType(type)
                                    .version(1)
                                    .isLatest(true)
                                    .build());
                    log.info("自动补建模型分类配置（实例归属外键）：serviceType={}, id={}",
                            type, saved.getId());
                    return saved;
                });
    }

    /**
     * 批量添加模型：按清单在该供应商下创建实例（继承地址与 Key）。
     *
     * <p><b>逐模型分类（v3.5）</b>：同一厂商常同时提供聊天 / 向量 / 图片模型，
     * 因此分类以<b>模型级</b>为准（{@code items[].serviceType}），批次级
     * {@code request.serviceType} 与供应商级 {@code provider.serviceType} 仅作兜底。
     * 实现上按分类分组：每组各自解析服务配置（不存在则自愈补建）、各自在<b>该分类内</b>
     * 按同名实例去重，并按分类计算实例的<b>上游调用路径</b>——上游路径取自实例自身的
     * path 字段（{@code ModelServiceRegistry#getModelPath}），不随分类实时换算，
     * 因此图片模型若沿用聊天路径会打到 /chat/completions。</p>
     *
     * <p>已存在同名实例的模型自动跳过——同名去重范围是该模型分类下全部实例（跨供应商，
     * 因实例名在分类内唯一，否则会撞 CONFLICT_INSTANCE_NAME 409），跳过时返回原因明细
     * （同名实例挂在哪个供应商名下），供前端逐条提示，避免「点了添加却没反应」的静默失败。</p>
     *
     * @return 创建数量 + 跳过明细
     */
    @Transactional
    public BatchAddModelsResult batchAddModels(final Long id, final CreateModelProviderRequest.BatchAddModelsRequest request) {
        final ModelProviderEntity provider = providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new ApiException("PROVIDER_BATCH_EMPTY", "请先勾选或填写要添加的模型");
        }
        final String fallbackType = isBlank(request.getServiceType())
                ? provider.getServiceType() : request.getServiceType().trim();

        // 按模型自身的分类分组（保持请求顺序）
        final Map<String, List<CreateModelProviderRequest.BatchModelItem>> groups = new LinkedHashMap<>();
        for (CreateModelProviderRequest.BatchModelItem item : request.getItems()) {
            groups.computeIfAbsent(resolveItemServiceType(item, fallbackType), key -> new ArrayList<>())
                    .add(item);
        }

        int created = 0;
        final List<BatchAddModelsResult.SkippedModel> skipped = new ArrayList<>();
        for (Map.Entry<String, List<CreateModelProviderRequest.BatchModelItem>> group : groups.entrySet()) {
            final String serviceType = group.getKey();
            final ServiceConfigEntity serviceConfig = ensureServiceConfig(serviceType);

            // 同名去重：该服务配置（分类）下已存在的实例名直接跳过
            final Map<String, ServiceInstanceDTO> existingMap = new LinkedHashMap<>();
            for (ServiceInstanceDTO inst : serviceInstanceManager.getInstancesByServiceConfigId(serviceConfig.getId())) {
                existingMap.putIfAbsent(inst.getName(), inst);
            }
            // 路径随分类走：同一供应商下的不同分类要打到各自的上游端点
            final String instancePath = resolveInstancePath(provider, serviceType);

            for (CreateModelProviderRequest.BatchModelItem item : group.getValue()) {
                final String modelId = item.getModelId() == null ? "" : item.getModelId().trim();
                if (modelId.isEmpty()) {
                    skipped.add(BatchAddModelsResult.SkippedModel.builder()
                            .modelId("").reason("无效的模型 ID").serviceType(serviceType).build());
                    continue;
                }
                if (existingMap.containsKey(modelId)) {
                    skipped.add(buildSkippedItem(modelId, existingMap.get(modelId), provider.getId(), serviceType));
                    continue;
                }
                final CreateServiceInstanceRequest instanceRequest = CreateServiceInstanceRequest.builder()
                        .name(modelId)
                        .providerId(provider.getId())
                        .path(instancePath)
                        .weight(1)
                        .status("active")
                        .tags(buildTags(item.getDisplayName()))
                        .build();
                serviceInstanceManager.createInstance(serviceConfig.getId(), instanceRequest);
                existingMap.put(modelId, null);
                created++;
            }
        }
        log.info("Batch added {} models (skipped {}) under provider {} (serviceTypes={})",
                created, skipped.size(), provider.getName(), groups.keySet());
        return BatchAddModelsResult.builder().created(created).skipped(skipped).build();
    }

    /**
     * 解析单个模型的分类：模型级 &gt; 批次级 &gt; 供应商级，并归一化到分类枚举的规范值。
     *
     * <p>归一化（大小写不敏感 → 驼峰规范值）的原因：分类同时用于查 {@code service_config}
     * 外键与路由枚举，存成 {@code IMGGEN} 之类的大小写变体会重复建行。</p>
     */
    private String resolveItemServiceType(final CreateModelProviderRequest.BatchModelItem item, final String fallbackType) {
        final String raw = (item != null && !isBlank(item.getServiceType()))
                ? item.getServiceType().trim() : fallbackType;
        final String canonical = ServiceTypeConstants.ServiceType.normalizeValue(raw);
        if (canonical != null) {
            return canonical;
        }
        return raw == null ? "" : raw.trim();
    }

    /**
     * 按模型分类计算实例的上游调用路径。
     *
     * <p>上游路径取自实例自身的 path 字段，不随分类实时换算，因此这里必须把路径改写为
     * 该分类对应的端点（图片生成 → /images/generations），否则图片模型会打到聊天端点。
     * 换写时保留供应商路径里的版本前缀：</p>
     * <ul>
     *   <li>{@code /v1/chat/completions} → {@code /v1/images/generations}（前缀 /v1）</li>
     *   <li>{@code /chat/completions} → {@code /images/generations}（baseUrl 已含 /v1，无前缀）</li>
     *   <li>{@code /api/paas/v4/chat/completions} → {@code /api/paas/v4/images/generations}</li>
     *   <li>非聊天端点的自定义路径（如 {@code /v1/messages}）保持原样，不做猜测</li>
     * </ul>
     */
    private String resolveInstancePath(final ModelProviderEntity provider, final String serviceType) {
        final String providerPath = provider.getPath() == null ? "" : provider.getPath().trim();
        final String endpoint = endpointPathOf(serviceType);
        if (endpoint == null) {
            // 未知分类：沿用供应商路径（保持既有行为）
            return providerPath;
        }
        if (providerPath.isEmpty()) {
            return "/v1" + endpoint;
        }
        if (providerPath.endsWith(endpoint)) {
            return providerPath;
        }
        final String chatEndpoint = ServiceEndpoint.CHAT.getPath();
        if (providerPath.endsWith(chatEndpoint)) {
            return providerPath.substring(0, providerPath.length() - chatEndpoint.length()) + endpoint;
        }
        if (VERSION_ONLY_PATH.matcher(providerPath).matches()) {
            return providerPath + endpoint;
        }
        return providerPath;
    }

    /**
     * 取模型分类对应的上游端点相对段（chat → /chat/completions）；未知分类返回 null。
     */
    private String endpointPathOf(final String serviceType) {
        final String canonical = ServiceTypeConstants.ServiceType.normalizeValue(serviceType);
        if (canonical == null) {
            return null;
        }
        try {
            return ServiceEndpoint.fromServiceType(ModelServiceRegistry.ServiceType.valueOf(canonical)).getPath();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 构造跳过明细：指明同名实例挂在哪个供应商名下。
     * 注意 {@link ServiceInstanceManager#convertToDTO} 不填充 providerName，这里按 providerId 反查供应商名。
     */
    private BatchAddModelsResult.SkippedModel buildSkippedItem(
            final String modelId,
            final ServiceInstanceDTO existing,
            final Long currentProviderId,
            final String serviceType) {
        String owner;
        if (existing == null || existing.getProviderId() == null) {
            owner = null; // 手工创建或历史迁移的实例，无供应商归属
        } else if (existing.getProviderId().equals(currentProviderId)) {
            owner = "当前供应商";
        } else {
            owner = providerRepository.findById(existing.getProviderId())
                    .map(ModelProviderEntity::getName)
                    .orElse("ID=" + existing.getProviderId());
        }
        final String reason = owner == null
                ? "模型分类「" + serviceType + "」下已存在同名实例"
                : "已由「" + owner + "」接入模型分类「" + serviceType + "」";
        return BatchAddModelsResult.SkippedModel.builder()
                .modelId(modelId).reason(reason).serviceType(serviceType).build();
    }

    // ==================== 内部工具 ====================

    private Map<String, String> buildTags(final String displayName) {
        if (isBlank(displayName)) {
            return null;
        }
        return Map.of("alias", displayName.trim());
    }

    /**
     * 推导模型列表路径：优先供应商显式配置；否则取 path 的版本前缀 + /models。
     * 例：/v1/chat/completions → /v1/models；/v4/chat/completions → /v4/models；
     * 无版本前缀（如 /chat/completions）→ /models。
     */
    private String resolveModelsPath(final ModelProviderEntity entity) {
        if (!isBlank(entity.getModelsPath())) {
            return entity.getModelsPath().trim();
        }
        final String path = entity.getPath() == null ? "" : entity.getPath();
        final int versionStart = path.indexOf("/v");
        if (versionStart >= 0) {
            final int nextSlash = path.indexOf('/', versionStart + 1);
            final String prefix = nextSlash > 0 ? path.substring(0, nextSlash) : path;
            return prefix + "/models";
        }
        return "/models";
    }

    private ModelProviderDTO toDTO(final ModelProviderEntity entity, final long modelCount) {
        return ModelProviderDTO.builder()
                .id(entity.getId())
                .providerUid(entity.getProviderUid())
                .name(entity.getName())
                .serviceType(entity.getServiceType())
                .baseUrl(entity.getBaseUrl())
                .path(entity.getPath())
                .adapter(entity.getAdapter())
                .modelsPath(entity.getModelsPath())
                .status(entity.getStatus())
                .description(entity.getDescription())
                .sortOrder(entity.getSortOrder())
                .hasApiKey(!isBlank(entity.getApiKey()))
                .modelCount(modelCount)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private String normalizeStatus(final String status) {
        if (isBlank(status)) {
            return "ACTIVE";
        }
        final String normalized = status.trim().toUpperCase();
        return normalized.startsWith("INACTIVE") ? "INACTIVE" : "ACTIVE";
    }

    private boolean isBlank(final String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimToNull(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String defaultIfBlank(final String value, final String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
