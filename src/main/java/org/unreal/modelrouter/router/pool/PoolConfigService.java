package org.unreal.modelrouter.router.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.config.core.helper.ServiceTypeResolver;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 资源池「创建」的可复用服务（v0.2.0 场景模板服务下沉）。
 *
 * <p>把 {@code PoolConfigController.createPool} 的<strong>校验 + 重名 + 持久化 + 热刷新</strong>
 * 逻辑原样下沉于此，供 Controller 与场景模板 apply 共用——避免场景 apply 走 HTTP 自调用
 * （事务、权限上下文、限流都会出问题）。这是纯重构：<strong>逻辑与 Controller 原有实现逐字一致</strong>，
 * 对外行为不变。</p>
 *
 * <p>刻意只下沉 create；update/delete 还含 YAML 管理校验、依赖校验、游离实例校验等额外语义，
 * 与场景 apply 无关，保持原位以最小化改动面。</p>
 *
 * @author Trunkline Team
 */
@Service
public class PoolConfigService {

    private static final Logger logger = LoggerFactory.getLogger(PoolConfigService.class);
    private static final Set<String> SUPPORTED_STRATEGIES = Set.of(
            "weighted-random", "round-robin", "least-connections", "ip-hash", "consistent-hash");

    private final PoolDefinitionProperties poolProperties;
    private final PoolPersistenceService persistenceService;
    private final PoolSelector poolSelector;
    private final ServiceTypeResolver serviceTypeResolver;
    private final ModelServiceRegistry modelServiceRegistry;

    public PoolConfigService(final PoolDefinitionProperties poolProperties,
                             final PoolPersistenceService persistenceService,
                             final PoolSelector poolSelector,
                             final ServiceTypeResolver serviceTypeResolver,
                             final ModelServiceRegistry modelServiceRegistry) {
        this.poolProperties = poolProperties;
        this.persistenceService = persistenceService;
        this.poolSelector = poolSelector;
        this.serviceTypeResolver = serviceTypeResolver;
        this.modelServiceRegistry = modelServiceRegistry;
    }

    /**
     * 创建资源池（校验 + 重名 + 持久化 + 热刷新），与 Controller.createPool 行为一致。
     *
     * @return 已落库的池定义（含调用方传入的对象）
     * @throws ResponseStatusException 校验失败（400）或重名（409）
     */
    public PoolDefinition createValidated(final PoolDefinition pool) {
        validatePool(pool);
        // 重名校验按「当前租户的持久化视图」（loadAll 受 @Filter 约束，天然租户感知），
        // 不纳入 YAML 池，保留「持久化同名覆盖 YAML 默认池」的既有语义（issue #708）。
        if (persistenceService.loadAll().stream().anyMatch(p -> p.getPoolName() != null
                && p.getPoolName().equals(pool.getPoolName()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pool '" + pool.getPoolName() + "' already exists");
        }
        persistenceService.save(pool);
        refreshRouterPools();
        return pool;
    }

    /** 校验池定义——从 Controller 原样搬入，逐条一致。 */
    private void validatePool(final PoolDefinition pool) {
        if (pool == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pool body must not be null");
        }
        if (pool.getPoolName() == null || pool.getPoolName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pool name (virtual model name) must not be blank");
        }
        if (pool.getServiceType() == null || pool.getServiceType().isBlank()
                || serviceTypeResolver.parseServiceType(pool.getServiceType()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid serviceType: " + pool.getServiceType());
        }
        if (pool.getStrategy() != null && !SUPPORTED_STRATEGIES.contains(pool.getStrategy())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported strategy: " + pool.getStrategy()
                            + " (supported: " + SUPPORTED_STRATEGIES + ")");
        }
        if (pool.getMembers() != null) {
            for (PoolMember member : pool.getMembers()) {
                if (member.getInstanceId() == null || member.getInstanceId().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Pool member instanceId must not be blank");
                }
            }
            final List<String> badMembers = new ArrayList<>();
            final List<ModelRouterProperties.ModelInstance> instances =
                    instancesForServiceType(pool.getServiceType());
            for (PoolMember member : pool.getMembers()) {
                final String key = member.getInstanceId();
                final boolean resolved = instances.stream()
                        .anyMatch(i -> key.equals(i.getInstanceId()) || key.equals(i.getName()));
                if (!resolved) {
                    badMembers.add(key);
                }
            }
            if (!badMembers.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Pool members do not match any instance (by instanceId UUID or instance name): "
                                + badMembers);
            }
        }
    }

    private List<ModelRouterProperties.ModelInstance> instancesForServiceType(final String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return List.of();
        }
        return modelServiceRegistry.getAllInstances().entrySet().stream()
                .filter(e -> e.getKey().name().equalsIgnoreCase(serviceType.trim()))
                .flatMap(e -> e.getValue().stream())
                .collect(java.util.stream.Collectors.toList());
    }

    private synchronized void refreshRouterPools() {
        List<PoolDefinition> yamlPools = poolProperties.getPools() != null
                ? poolProperties.getPools() : new ArrayList<>();
        List<PoolDefinition> persistedPools = persistenceService.loadAll();
        List<PoolDefinition> merged = new ArrayList<>(yamlPools);
        for (PoolDefinition pool : persistedPools) {
            merged.removeIf(p -> p.getPoolName() != null && p.getPoolName().equals(pool.getPoolName()));
            merged.add(pool);
        }
        poolSelector.reloadPools(merged);
        logger.info("资源池路由已加载: {} 个（当前租户，createValidated 后）", merged.size());
    }
}
