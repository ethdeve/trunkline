package org.unreal.modelrouter.router.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.config.core.helper.ServiceTypeResolver;
import org.unreal.modelrouter.config.dependency.DependencyCheckService;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.pool.PoolDefinitionProperties;
import org.unreal.modelrouter.router.pool.PoolPersistenceService;
import org.unreal.modelrouter.router.pool.PoolSelector;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 资源池配置管理控制器
 * 提供资源池的增删改查;YAML 默认池 + 持久化用户池,持久化同 poolName 覆盖
 */
@RestController
@RequestMapping("/api/config/pools")
@CrossOrigin(origins = "*")
@Tag(name = "资源池管理", description = "资源池(池名即虚拟模型名,如 company-chat)的增删改查")
public class PoolConfigController {

    private static final Logger logger = LoggerFactory.getLogger(PoolConfigController.class);
    private static final Set<String> SUPPORTED_STRATEGIES = Set.of(
            "weighted-random", "round-robin", "least-connections", "ip-hash", "consistent-hash");

    private final PoolDefinitionProperties poolProperties;
    private final PoolPersistenceService persistenceService;
    private final PoolSelector poolSelector;
    private final ServiceTypeResolver serviceTypeResolver;
    private final ModelServiceRegistry modelServiceRegistry;
    /** 2026-10-26: 删除前依赖检测（密钥白名单引用该分组时拒绝删除） */
    private final DependencyCheckService dependencyCheckService;

    // 注意：不再持有进程级 activePools 单例缓存。
    // 资源池（虚拟模型）属于「租户业务数据」平面，config_data 受 @Filter(tenantFilter) 行级隔离。
    // 若把某个请求线程的租户快照缓存到单例字段，切换租户后 GET /list 仍返回旧租户快照，
    // 表现为「切租户后虚拟模型没了 / 新建后看不到」。改为每次请求按当前租户实时读取。

    public PoolConfigController(final PoolDefinitionProperties poolProperties,
                                final PoolPersistenceService persistenceService,
                                final PoolSelector poolSelector,
                                final ServiceTypeResolver serviceTypeResolver,
                                final ModelServiceRegistry modelServiceRegistry,
                                final DependencyCheckService dependencyCheckService) {
        this.poolProperties = poolProperties;
        this.persistenceService = persistenceService;
        this.poolSelector = poolSelector;
        this.serviceTypeResolver = serviceTypeResolver;
        this.modelServiceRegistry = modelServiceRegistry;
        this.dependencyCheckService = dependencyCheckService;
        // 启动时预热一次路由选择器（仅用于运行时路由，与管理端列表数据源解耦）
        refreshRouterPools();
    }

    /**
     * 按「当前请求租户」加载生效资源池（YAML 默认 + 当前租户持久化用户池，持久化同 poolName 覆盖 YAML）。
     *
     * <p>{@link PoolPersistenceService#loadAll()} 经 {@code StoreManager.getConfig → ConfigRepository}
     * 访问 {@code config_data}，该实体挂 {@code @Filter(tenantFilter)}，在租户行级隔离开启时自动按
     * 当前请求租户过滤。因此本方法天然租户感知：切租户后立刻反映该租户自己的池。</p>
     */
    private List<PoolDefinition> loadPoolsForCurrentTenant() {
        List<PoolDefinition> yamlPools = poolProperties.getPools() != null
                ? poolProperties.getPools() : new ArrayList<>();
        List<PoolDefinition> persistedPools = persistenceService.loadAll();

        List<PoolDefinition> merged = new ArrayList<>(yamlPools);
        for (PoolDefinition pool : persistedPools) {
            merged.removeIf(p -> p.getPoolName() != null && p.getPoolName().equals(pool.getPoolName()));
            merged.add(pool);
        }
        return merged;
    }

    /**
     * 热更新运行时路由选择器（CRUD 后调用，仅服务于网关路由命中，不作为管理端列表数据源）。
     */
    private synchronized void refreshRouterPools() {
        List<PoolDefinition> merged = loadPoolsForCurrentTenant();
        poolSelector.reloadPools(merged);
        logger.info("资源池路由已加载: {} 个（当前租户）", merged.size());
    }

    /**
     * 获取所有资源池列表（按当前请求租户实时读取）
     */
    @GetMapping("/list")
    @Operation(summary = "获取所有资源池", description = "获取当前租户全部资源池(含 YAML 默认与持久化)")
    public ResponseEntity<RouterResponse<List<PoolDefinition>>> getAllPools() {
        List<PoolDefinition> sorted = new ArrayList<>(loadPoolsForCurrentTenant());
        sorted.sort((a, b) -> {
            String ka = a.getPoolName() != null ? a.getPoolName() : "";
            String kb = b.getPoolName() != null ? b.getPoolName() : "";
            return ka.compareTo(kb);
        });
        return ResponseEntity.ok(RouterResponse.success(sorted));
    }

    /**
     * 获取单条资源池
     */
    @GetMapping("/{poolName}")
    @Operation(summary = "获取单条资源池", description = "按池名获取资源池详情")
    public ResponseEntity<RouterResponse<PoolDefinition>> getPool(@PathVariable final String poolName) {
        return ResponseEntity.ok(RouterResponse.success(findPool(poolName)));
    }

    /**
     * 创建资源池
     */
    @PostMapping
    @Operation(summary = "创建资源池", description = "创建新资源池并热生效")
    public ResponseEntity<RouterResponse<PoolDefinition>> createPool(@RequestBody final PoolDefinition pool) {
        validatePool(pool);
        // 重名校验按「当前租户的持久化视图」（loadAll 受 @Filter 约束，天然租户感知）。
        // 不纳入 YAML 池，保留「持久化同名覆盖 YAML 默认池」的既有语义（issue #708）；
        // 跨租户同名不冲突（唯一键含 tenant_id）。
        if (persistenceService.loadAll().stream().anyMatch(p -> p.getPoolName() != null
                && p.getPoolName().equals(pool.getPoolName()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pool '" + pool.getPoolName() + "' already exists");
        }
        persistenceService.save(pool);
        refreshRouterPools();
        return ResponseEntity.status(HttpStatus.CREATED).body(RouterResponse.success(pool));
    }

    /**
     * 更新资源池
     */
    @PutMapping("/{poolName}")
    @Operation(summary = "更新资源池", description = "按池名更新资源池并热生效")
    public ResponseEntity<RouterResponse<PoolDefinition>> updatePool(@PathVariable final String poolName,
                                                                     @RequestBody final PoolDefinition pool) {
        PoolDefinition existing = findPool(poolName);
        // issue #708: YAML 来源的池"改了被 reload 覆盖"，与删除同源拒绝（409）
        if (isYamlManagedPool(poolName)) {
            return yamlPoolConflict();
        }
        validatePool(pool);
        // v3.5 治理收口 —— 强制实例先入组：移除成员后若某实例在其它分组已无归属，
        // 该实例将变成「游离实例」（无对外入口，等同死配置）→ 阻断变更。
        assertNoOrphanedInstances(existing, pool);
        pool.setPoolName(existing.getPoolName());
        persistenceService.save(pool);
        refreshRouterPools();
        return ResponseEntity.ok(RouterResponse.success(pool));
    }

    /**
     * 断言分组成员变更不会产生游离实例。
     *
     * <p>游离实例 = 不属于任何启用分组的实例。v3.5 治理收口后，实例只有作为分组成员
     * 才可被调用（对外入口是分组 / 虚拟模型名），游离实例等同死配置。</p>
     *
     * @param existing 分组当前定义
     * @param updated  分组更新后的定义
     */
    private void assertNoOrphanedInstances(final PoolDefinition existing, final PoolDefinition updated) {
        if (dependencyCheckService == null) {
            return;
        }
        final Set<String> oldKeys = memberKeys(existing);
        final Set<String> newKeys = memberKeys(updated);
        if (oldKeys.isEmpty()) {
            return;
        }
        final Set<String> removed = new LinkedHashSet<>(oldKeys);
        removed.removeAll(newKeys);
        if (removed.isEmpty()) {
            return;
        }
        final List<String> orphans = dependencyCheckService.findOrphanedInstances(
                existing.getPoolName(), removed);
        if (!orphans.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "以下已接入模型移除后不再属于任何模型分组，将无法对外提供调用："
                            + String.join("、", orphans)
                            + "。请先将其加入其它模型分组，或先删除该已接入模型。");
        }
    }

    /** 提取分组成员标识集合（UUID / 实例名 / 数字主键） */
    private Set<String> memberKeys(final PoolDefinition pool) {
        final Set<String> keys = new LinkedHashSet<>();
        if (pool == null || pool.getMembers() == null) {
            return keys;
        }
        for (final PoolMember member : pool.getMembers()) {
            if (member != null && member.getInstanceId() != null && !member.getInstanceId().isBlank()) {
                keys.add(member.getInstanceId());
            }
        }
        return keys;
    }

    /**
     * 删除资源池
     */
    @DeleteMapping("/{poolName}")
    @Operation(summary = "删除资源池", description = "按池名删除资源池并热生效")
    public ResponseEntity<RouterResponse<Void>> deletePool(@PathVariable final String poolName) {
        findPool(poolName);
        // issue #708: YAML 池删除后 reload 会把它重新合并进来（谎报成功）——校验期拒绝
        if (isYamlManagedPool(poolName)) {
            return yamlPoolConflict();
        }
        // 删除前依赖校验：密钥白名单仍引用该分组时拒绝删除（否则密钥调用将 403/404）
        // v3.5：成员实例将变成「游离实例」只提示不阻断——否则「删实例被成员关系阻断、
        // 删分组被游离阻断」会形成死锁，管理员无法清理配置。
        final org.unreal.modelrouter.config.dependency.DependencyReport report =
                dependencyCheckService.checkPool(poolName);
        dependencyCheckService.assertDeletable(report);
        persistenceService.remove(poolName);
        refreshRouterPools();
        // report 可为 null（未装配依赖校验的极简上下文）：与 assertDeletable 的容错口径保持一致
        final java.util.List<String> warnings = report != null ? report.getWarnings() : null;
        final String message = warnings == null || warnings.isEmpty()
                ? "删除资源池成功"
                : "删除资源池成功，但请注意：" + String.join("；", warnings);
        return ResponseEntity.ok(RouterResponse.success(null, message));
    }

    private PoolDefinition findPool(final String poolName) {
        return loadPoolsForCurrentTenant().stream()
                .filter(p -> p.getPoolName() != null && p.getPoolName().equals(poolName))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pool not found: " + poolName));
    }

    /**
     * 判定池是否由 YAML 配置文件管理（issue #708）。
     *
     * <p>{@code PoolDefinition} 无 source 字段，用「不在持久化集合即视为 YAML 管理」判定
     * （持久化同名池在 reload 时覆盖 YAML 项，判定结果与实际来源一致）。</p>
     */
    private boolean isYamlManagedPool(final String poolName) {
        if (poolName == null) {
            return false;
        }
        return persistenceService.loadAll().stream()
                .noneMatch(p -> poolName.equals(p.getPoolName()));
    }

    /**
     * YAML 管理池的统一 409 响应（issue #708：错误码 {@code YAML_POOL_ACTIVE}）。
     */
    private <T> ResponseEntity<RouterResponse<T>> yamlPoolConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(RouterResponse.error(
                        "该资源池由 YAML 配置文件定义，无法通过 API 删除/修改；请修改配置文件后重启生效",
                        "YAML_POOL_ACTIVE"));
    }

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
            // P2-6: 成员必须能按 instanceId(UUID)或实例名解析到现存实例。
            // 运行时 instanceId 是 UUID,数据库自增数字 id 两头都匹配不上,历史上有直连 API
            // 填数字 id 导致路由 404 的案例,故在入口强校验并列出全部坏成员。
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

    /**
     * 取指定服务类型的全部运行时实例(大小写不敏感).
     */
    private List<ModelRouterProperties.ModelInstance> instancesForServiceType(final String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return List.of();
        }
        return modelServiceRegistry.getAllInstances().entrySet().stream()
                .filter(e -> e.getKey().name().equalsIgnoreCase(serviceType.trim()))
                .flatMap(e -> e.getValue().stream())
                .collect(java.util.stream.Collectors.toList());
    }
}
