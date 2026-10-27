package org.unreal.modelrouter.config.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.unreal.modelrouter.common.dto.ModelProviderDTO;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.config.core.ServiceInstanceManager;
import org.unreal.modelrouter.config.dependency.DependencyCheckService;
import org.unreal.modelrouter.config.core.helper.SsrfGuard;
import org.unreal.modelrouter.config.dto.CreateModelProviderRequest;
import org.unreal.modelrouter.config.dto.CreateServiceInstanceRequest;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.model.WebClientCacheManager;
import org.unreal.modelrouter.common.exception.ApiException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

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
     * 批量添加模型：按清单在该供应商下创建实例（继承地址与 Key）。
     * 已存在同名实例的模型自动跳过。
     *
     * @return 实际创建成功的数量
     */
    @Transactional
    public int batchAddModels(final Long id, final CreateModelProviderRequest.BatchAddModelsRequest request) {
        final ModelProviderEntity provider = providerRepository.findById(id)
                .orElseThrow(() -> new ApiException("PROVIDER_NOT_FOUND", "供应商不存在: " + id));
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new ApiException("PROVIDER_BATCH_EMPTY", "请先勾选或填写要添加的模型");
        }
        final String serviceType = isBlank(request.getServiceType())
                ? provider.getServiceType() : request.getServiceType().trim();
        final ServiceConfigEntity serviceConfig = serviceConfigRepository
                .findFirstByServiceTypeAndIsLatestTrue(serviceType)
                .orElseThrow(() -> new ApiException("SERVICE_CONFIG_NOT_FOUND",
                        "服务配置不存在: " + serviceType));

        // 同名去重：该服务配置下已存在的实例名直接跳过
        final Set<String> existing = new java.util.HashSet<>();
        for (ServiceInstanceDTO inst : serviceInstanceManager.getInstancesByServiceConfigId(serviceConfig.getId())) {
            existing.add(inst.getName());
        }

        int created = 0;
        for (CreateModelProviderRequest.BatchModelItem item : request.getItems()) {
            final String modelId = item.getModelId() == null ? "" : item.getModelId().trim();
            if (modelId.isEmpty() || existing.contains(modelId)) {
                continue;
            }
            final CreateServiceInstanceRequest instanceRequest = CreateServiceInstanceRequest.builder()
                    .name(modelId)
                    .providerId(provider.getId())
                    .weight(1)
                    .status("active")
                    .tags(buildTags(item.getDisplayName()))
                    .build();
            serviceInstanceManager.createInstance(serviceConfig.getId(), instanceRequest);
            existing.add(modelId);
            created++;
        }
        log.info("Batch added {} models under provider {} (serviceType={})", created, provider.getName(), serviceType);
        return created;
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
