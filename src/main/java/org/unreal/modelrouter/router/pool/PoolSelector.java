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

    private volatile List<PoolDefinition> pools = new ArrayList<>();

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
     * v2.8.9: 热更新池列表(CRUD 后由 PoolConfigController 调用)
     */
    public void reloadPools(final List<PoolDefinition> mergedPools) {
        this.pools = mergedPools != null
                ? Collections.unmodifiableList(new ArrayList<>(mergedPools))
                : new ArrayList<>();
        LOGGER.info("资源池已加载 {} 个", pools.size());
    }

    /**
     * 获取当前生效的全部池定义(只读快照)。
     * 供模型目录({@code GET /v1/models})等场景枚举分组名——分组(虚拟模型)与实例名
     * 一样是对外可调用的模型名,必须出现在 OpenAI 兼容客户端的模型列表中。
     */
    public List<PoolDefinition> getAllPools() {
        return Collections.unmodifiableList(new ArrayList<>(pools));
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
        for (PoolDefinition pool : pools) {
            if (pool.isEnabled() && pool.getPoolName() != null && pool.getPoolName().equals(modelName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 (serviceType, poolName) 匹配启用的池
     */
    public PoolDefinition findPool(final ModelServiceRegistry.ServiceType serviceType, final String modelName) {
        if (serviceType == null || modelName == null) {
            return null;
        }
        for (PoolDefinition pool : pools) {
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
