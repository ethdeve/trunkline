package org.unreal.modelrouter.router.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.SelectInstanceOptimizer;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资源池选择器
 * 持有生效的池列表(YAML + 持久化合并);提供池命中解析与成员候选解析
 *
 * <p><b>v3.6 治理收口：</b>不再有「全局默认池 / auto-model」这类隐式系统分组——
 * 请求的 model 必须命中一个<b>显式创建</b>的模型分组(池)，否则一律 404。</p>
 */
@Component
public class PoolSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger(PoolSelector.class);

    // R17：由 Spring 注入共享单例；构造器内 new 仅作非 Spring 直构造场景的兜底
    private SelectInstanceOptimizer optimizer;

    /**
     * 按租户分桶的生效池列表：{@code tenantId -> 该租户可见的池}。
     *
     * <p><b>为什么必须分桶</b>：虚拟模型（资源池）按租户隔离——各租户自建、可重名。
     * 早期实现只有一个全局 {@code List}，后写入的租户会覆盖前一个，导致：
     * ① 网关按 {@code model} 名查池时命中别家租户的池（跨租户串用）；
     * ② 切换租户后下拉框仍显示上一个租户的模型，选中即报
     * {@code Model 'xxx' is not a model group}。</p>
     *
     * <p>键为租户 ID，社区版/单租户恒为 {@code default}，行为与改造前一致。</p>
     */
    private final Map<String, List<PoolDefinition>> poolsByTenant = new ConcurrentHashMap<>();

    /**
     * 惰性加载兜底：桶为空时按当前租户实时读库填充。
     *
     * <p><b>为什么需要</b>：{@link #reloadPools} 只在管理面 CRUD 时被调用。网关接到请求时
     * 该租户可能从未被 reload 过（重启后、或该租户从没进过池管理页），此时桶为空，
     * 所有路由都会 404。因此在读路径上按需回填。</p>
     *
     * <p>用 {@code ObjectProvider} 延迟注入，避免 PoolSelector ← PoolPersistenceService
     * 与 PoolConfigController ← PoolSelector 形成构造期循环依赖。</p>
     */
    private org.springframework.beans.factory.ObjectProvider<PoolPersistenceService> persistenceProvider;

    /**
     * 注入池持久化服务（可选），供 {@link #currentPools()} 惰性回填。
     *
     * @param provider 持久化服务提供者；未注入时按「无持久化池」处理
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPersistenceProvider(
            final org.springframework.beans.factory.ObjectProvider<PoolPersistenceService> provider) {
        this.persistenceProvider = provider;
    }

    /** YAML 默认池：所有租户共享（进程级配置，不随租户切分）。 */
    private org.springframework.beans.factory.ObjectProvider<PoolDefinitionProperties> yamlProvider;

    /**
     * 注入 YAML 默认池配置（可选），与持久化池合并后作为该租户的生效池。
     *
     * @param provider YAML 池配置提供者；未注入时只使用持久化池
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setYamlProvider(
            final org.springframework.beans.factory.ObjectProvider<PoolDefinitionProperties> provider) {
        this.yamlProvider = provider;
    }

    public PoolSelector(final ServiceStateManager serviceStateManager,
                        final CircuitBreakerManager circuitBreakerManager) {
        this.optimizer = new SelectInstanceOptimizer(serviceStateManager, circuitBreakerManager);
    }

    /**
     * R17：注入共享的实例选择优化器单例（消除 PoolSelector 与 ModelServiceRegistry
     * 各自 new 一份的重复构造；优化器本身无状态，共享无副作用）。
     * 运行期会覆盖构造器内创建的兜底实例。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOptimizer(final SelectInstanceOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    /**
     * v2.8.9: 热更新池列表(CRUD 后由 PoolConfigController 调用)——写入<b>当前租户</b>的桶。
     */
    public void reloadPools(final List<PoolDefinition> mergedPools) {
        final String tenantId = currentTenantId();
        final List<PoolDefinition> snapshot = mergedPools != null
                ? Collections.unmodifiableList(new ArrayList<>(mergedPools))
                : List.of();
        poolsByTenant.put(tenantId, snapshot);
        LOGGER.info("资源池已加载 {} 个（租户 {}）", snapshot.size(), tenantId);
    }

    /** 当前请求租户；无上下文时回落单租户 default（社区版行为不变）。 */
    private String currentTenantId() {
        return org.unreal.modelrouter.enterprise.config.TenantContextHolder.currentTenantId();
    }

    /** 当前租户的生效池快照（不可变）；桶为空时惰性回填一次。 */
    private List<PoolDefinition> currentPools() {
        final String tenantId = currentTenantId();
        final List<PoolDefinition> cached = poolsByTenant.get(tenantId);
        if (cached != null) {
            return cached;
        }
        return poolsByTenant.computeIfAbsent(tenantId, this::loadFromStore);
    }

    /**
     * 按当前租户从持久层读池，并与 YAML 默认池合并（持久化同 poolName 覆盖 YAML）。
     *
     * <p>依赖 {@code PoolPersistenceService.loadAll()} → {@code StoreManager.getConfig}
     * 走 {@code ConfigEntity} 的 {@code @Filter}，因此读到的天然是本租户的行。</p>
     */
    private List<PoolDefinition> loadFromStore(final String tenantId) {
        final List<PoolDefinition> merged = new ArrayList<>();
        final PoolDefinitionProperties yaml = yamlProvider != null ? yamlProvider.getIfAvailable() : null;
        if (yaml != null && yaml.getPools() != null) {
            merged.addAll(yaml.getPools());
        }
        final PoolPersistenceService persistence =
                persistenceProvider != null ? persistenceProvider.getIfAvailable() : null;
        if (persistence != null) {
            try {
                for (final PoolDefinition pool : persistence.loadAll()) {
                    merged.removeIf(p -> p.getPoolName() != null
                            && p.getPoolName().equals(pool.getPoolName()));
                    merged.add(pool);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("按租户 {} 加载资源池失败，仅用 YAML 默认池: {}", tenantId, e.getMessage());
            }
        }
        LOGGER.info("资源池惰性加载 {} 个（租户 {}）", merged.size(), tenantId);
        return Collections.unmodifiableList(merged);
    }

    /**
     * 获取当前生效的全部池定义(只读快照)。
     * 供模型目录({@code GET /v1/models})等场景枚举分组名——分组(虚拟模型)与实例名
     * 一样是对外可调用的模型名,必须出现在 OpenAI 兼容客户端的模型列表中。
     */
    public List<PoolDefinition> getAllPools() {
        return currentPools();
    }

    /**
     * 判断模型名是否为已配置的启用池名(供处理器决定是否改写下游请求/响应的 model 字段)
     *
     * <p><b>v3.6 治理收口：</b>只有<b>显式创建</b>的分组才算分组名，
     * 不再对任何约定名（如 {@code auto-model}）恒真。</p>
     */
    public boolean isPoolName(final String modelName) {
        if (modelName == null) {
            return false;
        }
        for (PoolDefinition pool : currentPools()) {
            if (pool.isEnabled() && pool.getPoolName() != null && pool.getPoolName().equals(modelName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 (serviceType, poolName) 匹配启用的池——<b>只在当前租户的池集合内匹配</b>。
     *
     * <p>这是路由命中的关键入口：若跨租户匹配，A 租户建的 {@code test} 池会被 B 租户
     * 的请求命中，等于把流量送进别家的上游配置。因此必须隔离到租户粒度。</p>
     */
    public PoolDefinition findPool(final ModelServiceRegistry.ServiceType serviceType, final String modelName) {
        if (serviceType == null || modelName == null) {
            return null;
        }
        for (PoolDefinition pool : currentPools()) {
            if (pool.isEnabled()
                    && pool.getPoolName() != null && pool.getPoolName().equals(modelName)
                    && serviceTypeMatches(pool, serviceType)) {
                return pool;
            }
        }
        return null;
    }

    /**
     * 池成员 → 实例候选列表
     * 成员按 instanceId 匹配(兼容 name 匹配);缺失成员(实例被删)跳过;
     * 池级 weight 覆盖实例 weight(浅拷贝副本,不污染共享实例);最终过状态/健康/熔断过滤
     */
    public List<ModelInstance> resolveCandidates(final PoolDefinition pool,
                                                 final List<ModelInstance> instances) {
        List<ModelInstance> resolved = new ArrayList<>();
        if (pool == null || pool.getMembers() == null || instances == null || instances.isEmpty()) {
            return resolved;
        }
        for (PoolMember member : pool.getMembers()) {
            ModelInstance instance = findInstance(instances, member.getInstanceId());
            if (instance == null) {
                LOGGER.warn("池 {} 成员实例不存在,跳过: {}", pool.getPoolName(), member.getInstanceId());
                continue;
            }
            ModelInstance copy = instance.copy();
            copy.setWeight(member.getWeight() > 0 ? member.getWeight() : 1);
            resolved.add(copy);
        }
        return optimizer.filterHealthyInstances(resolved, parseServiceType(pool));
    }

    private ModelInstance findInstance(final List<ModelInstance> instances, final String instanceId) {
        if (instanceId == null) {
            return null;
        }
        return instances.stream()
                .filter(i -> instanceId.equals(i.getInstanceId()) || instanceId.equals(i.getName()))
                .findFirst()
                .orElse(null);
    }

    private boolean serviceTypeMatches(final PoolDefinition pool,
                                       final ModelServiceRegistry.ServiceType serviceType) {
        return pool.getServiceType() != null
                && pool.getServiceType().equalsIgnoreCase(serviceType.name());
    }

    private ModelServiceRegistry.ServiceType parseServiceType(final PoolDefinition pool) {
        for (ModelServiceRegistry.ServiceType st : ModelServiceRegistry.ServiceType.values()) {
            if (st.name().equalsIgnoreCase(pool.getServiceType())) {
                return st;
            }
        }
        return null;
    }
}
