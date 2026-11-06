package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.dto.ApiKeyModelScopeVO;
import org.unreal.modelrouter.persistence.jpa.entity.ModelPriceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelPriceRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.pool.PoolDefinitionProperties;
import org.unreal.modelrouter.router.pool.PoolPersistenceService;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 密钥可见分组（资源池）扩展信息解析器（P0-L 越权泄漏收敛的补偿能力）。
 *
 * <p><b>为什么需要它</b>：收紧 {@code config:pools:read} 后，员工不能再调用
 * {@code GET /api/config/pools/list} 拉取全公司资源池。但「我的密钥」页仍需要展示
 * 分组友好名与加权均价 —— 这些信息<b>只与员工自己白名单内的分组有关</b>，
 * 因此改由服务端按白名单解析后随「我的密钥」一起下发。</p>
 *
 * <p><b>合并口径</b>：与 {@code PoolConfigController.reloadActivePools()} 完全一致 ——
 * YAML 默认池 + 持久化池，按 {@code poolName} 去重（持久化覆盖同名 YAML）。
 * 口径不一致会导致「页面显示的分组」与「实际生效的分组」分叉。</p>
 *
 * <p><b>加权均价口径</b>：按分组成员权重对「(输入价 + 输出价) / 2」加权平均；
 * 成员价格以<b>实例名</b>为键对齐 {@code model_price.model_name}（与
 * {@code BillingService} 的计价口径一致），实例名由 {@code instanceId} 解析而来
 * （兼容「instanceId 或 name 匹配」，见 {@code PoolSelector#resolveCandidates}）。
 * 组内成员均未配价时返回 {@code null}。</p>
 *
 * <p><b>依赖缺失容错</b>：全部依赖均以 {@link ObjectProvider} 注入，缺失时降级为
 * 「无分组信息」而不是抛异常 —— 极简测试上下文与降级部署下接口仍可返回密钥列表。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@Slf4j
@Component
public class ApiKeyModelScopeResolver {

    /** 单价的中间精度（避免除法丢精度；最终按同一精度收敛） */
    private static final int PRICE_SCALE = 6;

    /** 「不限制模型」时用于代表全部成员的虚拟权重基数 */
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final ObjectProvider<PoolDefinitionProperties> poolPropertiesProvider;
    private final ObjectProvider<PoolPersistenceService> poolPersistenceServiceProvider;
    private final ObjectProvider<ModelPriceRepository> modelPriceRepositoryProvider;
    private final ObjectProvider<ModelServiceRegistry> modelServiceRegistryProvider;

    /**
     * 构造解析器。
     *
     * @param poolPropertiesProvider        YAML 默认池属性（可选）
     * @param poolPersistenceServiceProvider 持久化池服务（可选）
     * @param modelPriceRepositoryProvider   模型价格仓库（可选）
     * @param modelServiceRegistryProvider   实例注册表（可选，用于 instanceId → 实例名解析）
     */
    public ApiKeyModelScopeResolver(
            final ObjectProvider<PoolDefinitionProperties> poolPropertiesProvider,
            final ObjectProvider<PoolPersistenceService> poolPersistenceServiceProvider,
            final ObjectProvider<ModelPriceRepository> modelPriceRepositoryProvider,
            final ObjectProvider<ModelServiceRegistry> modelServiceRegistryProvider) {
        this.poolPropertiesProvider = poolPropertiesProvider;
        this.poolPersistenceServiceProvider = poolPersistenceServiceProvider;
        this.modelPriceRepositoryProvider = modelPriceRepositoryProvider;
        this.modelServiceRegistryProvider = modelServiceRegistryProvider;
    }

    /**
     * 批量解析多把密钥的分组扩展信息。
     *
     * <p>索引（池 / 价格 / 实例）只构建一次，供本批所有密钥复用，避免逐密钥重复扫表。</p>
     *
     * @param allowedGroupsList 每把密钥的白名单分组列表（可为 {@code null} 元素）
     * @return 与入参等长的结果列表，每项为该密钥可见分组的扩展信息
     */
    public List<List<ApiKeyModelScopeVO>> resolveAll(final List<List<String>> allowedGroupsList) {
        if (allowedGroupsList == null || allowedGroupsList.isEmpty()) {
            return List.of();
        }
        final Map<String, PoolDefinition> pools = poolIndex();
        final Map<String, ModelPriceEntity> priceIndex = priceIndex();
        final Map<String, String> instanceNameIndex = instanceNameIndex();

        // 应修9：依赖缺失时置 modelScopeAvailable=false，前端据此显示提示
        final boolean scopeAvailable = !pools.isEmpty() || !priceIndex.isEmpty() || !instanceNameIndex.isEmpty();

        final List<List<ApiKeyModelScopeVO>> result = new ArrayList<>(allowedGroupsList.size());
        for (final List<String> groups : allowedGroupsList) {
            result.add(resolveOne(groups, pools, priceIndex, instanceNameIndex, scopeAvailable));
        }
        return result;
    }

    /**
     * 批量解析分组扩展信息 <b>+ 密钥级合计均价</b>（应修8）。
     *
     * <p>与 {@link #resolveAll} 的唯一区别：额外返回每把密钥的密钥级合计均价，
     * 让调用方（{@code ApiKeyService}）无需再逐密钥重复构建索引即可拿到金额口径。</p>
     *
     * @param allowedGroupsList 每把密钥的白名单分组列表（可为 {@code null} 元素）
     * @return 与入参等长的结果，每项含该密钥的分组扩展信息与密钥级均价
     */
    public List<ResolvedKeyScope> resolveAllWithPrices(final List<List<String>> allowedGroupsList) {
        if (allowedGroupsList == null || allowedGroupsList.isEmpty()) {
            return List.of();
        }
        final Map<String, PoolDefinition> pools = poolIndex();
        final Map<String, ModelPriceEntity> priceIndex = priceIndex();
        final Map<String, String> instanceNameIndex = instanceNameIndex();

        // 应修9：依赖缺失时置 modelScopeAvailable=false，前端据此显示提示
        final boolean scopeAvailable = !pools.isEmpty() || !priceIndex.isEmpty() || !instanceNameIndex.isEmpty();

        final List<ResolvedKeyScope> result = new ArrayList<>(allowedGroupsList.size());
        for (final List<String> groups : allowedGroupsList) {
            final List<ApiKeyModelScopeVO> scope =
                    resolveOne(groups, pools, priceIndex, instanceNameIndex, scopeAvailable);
            final Double keyPrice = keyLevelMixedPrice(groups, pools, priceIndex, instanceNameIndex);
            result.add(new ResolvedKeyScope(scope, keyPrice));
        }
        return result;
    }

    /** 单把密钥的分组扩展信息 + 密钥级均价的解析结果。 */
    public record ResolvedKeyScope(List<ApiKeyModelScopeVO> scope, Double keyLevelMixedPrice) {
    }

    /**
     * 解析单把密钥的分组扩展信息。
     *
     * @param allowedGroups 该密钥的白名单分组（{@code null} / 空 = 不限制模型）
     * @return 可见分组的扩展信息
     */
    public List<ApiKeyModelScopeVO> resolve(final List<String> allowedGroups) {
        // 用 Collections.singletonList 而非 List.of：后者拒绝 null 元素，
        // 而白名单里出现 null 分组名是历史脏数据的可能形态，不应因此抛 NPE。
        final List<List<ApiKeyModelScopeVO>> resolved =
                resolveAll(Collections.singletonList(allowedGroups == null ? List.of() : allowedGroups));
        return resolved.isEmpty() ? List.of() : resolved.get(0);
    }

    /**
     * 解析单个白名单对应的分组扩展信息。
     *
     * <p><b>白名单为空 = 后端语义「不限制模型」</b>（{@code ApiKey#isModelAllowed}）：
     * 该密钥本就可以调用全部分组，因此返回全部<b>启用中</b>分组的展示信息。
     *
     * <p><b>分组字段收敛</b>（应修7）：不限制情形下<b>不下发</b> {@code displayName}
     * 与 {@code mixedPrice} —— 这些字段属于「全公司成本水平」，员工不应看到
     * 其他员工的成本阈值。仅下发 {@code poolName} 与 {@code serviceType}，
     * 让员工知道自己的可调模型名与分类即可。</p>
     */
    private List<ApiKeyModelScopeVO> resolveOne(final List<String> allowedGroups,
                                                final Map<String, PoolDefinition> pools,
                                                final Map<String, ModelPriceEntity> priceIndex,
                                                final Map<String, String> instanceNameIndex,
                                                final boolean scopeAvailable) {
        final List<ApiKeyModelScopeVO> result = new ArrayList<>();
        final boolean unrestricted = allowedGroups == null || allowedGroups.isEmpty();
        if (unrestricted) {
            for (final PoolDefinition pool : pools.values()) {
                if (pool == null || !pool.isEnabled() || pool.getPoolName() == null) {
                    continue;
                }
                result.add(toScopeVOUnrestricted(pool, scopeAvailable));
            }
            return result;
        }

        for (final String group : allowedGroups) {
            if (group == null || group.isBlank()) {
                continue;
            }
            // 大小写归一化查找（应修5）：poolIndex 键为小写，白名单分组名也转小写
            final PoolDefinition pool = pools.get(group.toLowerCase());
            // 分组不存在（已被删除 / 拼写不一致）→ 跳过，不臆造展示信息
            if (pool == null) {
                continue;
            }
            result.add(toScopeVO(pool, priceIndex, instanceNameIndex, scopeAvailable));
        }
        return result;
    }

    /**
     * 池 → 对外展示 VO（不限制分支：仅 poolName + serviceType，displayName/mixedPrice 为 null）。
     *
     * <p><b>应修7</b>：白名单为空（不限制模型）时，员工看到的是全公司所有分组，
     * 此时下发 {@code displayName} 与 {@code mixedPrice} 会泄露全公司成本水平
     * （如「主力池均价 ¥X/千token」）。这里刻意置 null，前端回落展示 poolName。</p>
     *
     * <p><b>应修9</b>：{@code modelScopeAvailable} 由调用方传入，依赖缺失时置 false。</p>
     */
    private ApiKeyModelScopeVO toScopeVOUnrestricted(final PoolDefinition pool,
                                                      final boolean scopeAvailable) {
        return ApiKeyModelScopeVO.builder()
                .poolName(pool.getPoolName())
                .displayName(null)
                .serviceType(pool.getServiceType())
                .mixedPrice(null)
                .modelScopeAvailable(scopeAvailable)
                .build();
    }

    /**
     * 池 → 对外展示 VO。
     *
     * <p><b>应修9</b>：{@code modelScopeAvailable} 由调用方传入，依赖缺失时置 false。</p>
     */
    private ApiKeyModelScopeVO toScopeVO(final PoolDefinition pool,
                                          final Map<String, ModelPriceEntity> priceIndex,
                                          final Map<String, String> instanceNameIndex,
                                          final boolean scopeAvailable) {
        return ApiKeyModelScopeVO.builder()
                .poolName(pool.getPoolName())
                .displayName(pool.getName())
                .serviceType(pool.getServiceType())
                .mixedPrice(mixedPrice(pool, priceIndex, instanceNameIndex))
                .modelScopeAvailable(scopeAvailable)
                .build();
    }

    /**
     * 计算密钥级合计均价（¥ / 千 token）—— 应修8 口径统一。
     *
     * <p><b>口径</b>：对白名单内<b>已配置价格</b>的分组，把所有可达成员的单价
     * {@code (输入价 + 输出价) / 2} 按成员权重加权平均（与 {@code BillingService}
     * 计价口径一致）。这与 {@link #mixedPrice(PoolDefinition, Map, Map)}（分组级均价）
     * 的<b>成员权重加权</b>算法一致，只是聚合范围从「单个分组」扩大到「白名单内所有分组」。</p>
     *
     * <p><b>白名单为空（= 不限制模型）时返回 {@code null}</b> —— 此时聚合的是全公司所有
     * 分组的成本水平，属于应修7 界定的「全公司成本水平」，不下发（前端显示「—」）。</p>
     *
     * @param allowedGroups 该密钥的白名单分组（{@code null} / 空 = 不限制模型）
     * @param pools 生效资源池索引（小写池名 → 池定义）
     * @param priceIndex 已启用价格索引（实例名 → 价格行）
     * @param instanceNameIndex 实例索引（instanceId 或实例名 → 实例名）
     * @return 密钥级合计均价；无法计算时返回 {@code null}
     */
    public Double keyLevelMixedPrice(final List<String> allowedGroups,
                                     final Map<String, PoolDefinition> pools,
                                     final Map<String, ModelPriceEntity> priceIndex,
                                     final Map<String, String> instanceNameIndex) {
        // 白名单为空 = 不限制模型 → 不下发（应修7）
        if (allowedGroups == null || allowedGroups.isEmpty()) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (final String group : allowedGroups) {
            if (group == null || group.isBlank()) {
                continue;
            }
            final PoolDefinition pool = pools.get(group.toLowerCase());
            if (pool == null || !pool.isEnabled()) {
                continue;
            }
            final List<PoolMember> members = pool.getMembers();
            if (members == null || members.isEmpty()) {
                continue;
            }
            for (final PoolMember member : members) {
                if (member == null) {
                    continue;
                }
                final String instanceName = instanceNameIndex.get(member.getInstanceId());
                if (instanceName == null) {
                    continue;
                }
                final ModelPriceEntity price = priceIndex.get(instanceName);
                if (price == null) {
                    continue;
                }
                final BigDecimal priceInput = price.getPriceInput();
                final BigDecimal priceOutput = price.getPriceOutput();
                if (priceInput == null || priceOutput == null) {
                    continue;
                }
                // 输入价与输出价都为 0 视为「未配价」——与 mixedPrice 口径一致
                if (priceInput.signum() <= 0 && priceOutput.signum() <= 0) {
                    continue;
                }
                final BigDecimal unit = priceInput.add(priceOutput)
                        .divide(TWO, PRICE_SCALE, RoundingMode.HALF_UP);
                final BigDecimal weight = BigDecimal.valueOf(member.getWeight() > 0 ? member.getWeight() : 1);
                weighted = weighted.add(unit.multiply(weight));
                weightSum = weightSum.add(weight);
            }
        }
        if (weightSum.signum() == 0) {
            return null;
        }
        return weighted.divide(weightSum, PRICE_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 分组的加权均价（¥ / 千 token）。
     *
     * <p>算法（与前端「全部密钥」页同口径）：</p>
     * <pre>
     *   weighted   = Σ (unit_i × weight_i)
     *   weightSum  = Σ weight_i
     *   mixedPrice = weighted / weightSum        （weightSum == 0 → null）
     *   unit_i     = (priceInput_i + priceOutput_i) / 2
     *   weight_i   = member.weight > 0 ? member.weight : 1
     * </pre>
     *
     * <p>成员价格缺失（未配价）或实例已不存在时跳过该成员，不计入权重。</p>
     */
    private Double mixedPrice(final PoolDefinition pool,
                               final Map<String, ModelPriceEntity> priceIndex,
                               final Map<String, String> instanceNameIndex) {
        final List<PoolMember> members = pool.getMembers();
        if (members == null || members.isEmpty() || priceIndex.isEmpty()) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (final PoolMember member : members) {
            if (member == null) {
                continue;
            }
            final String instanceName = instanceNameIndex.get(member.getInstanceId());
            if (instanceName == null) {
                continue;
            }
            final ModelPriceEntity price = priceIndex.get(instanceName);
            if (price == null) {
                continue;
            }
            final BigDecimal priceInput = price.getPriceInput();
            final BigDecimal priceOutput = price.getPriceOutput();
            if (priceInput == null || priceOutput == null) {
                continue;
            }
            // 输入价与输出价都为 0 视为「未配价」——与前端 hasPrice 口径一致
            if (priceInput.signum() <= 0 && priceOutput.signum() <= 0) {
                continue;
            }
            final BigDecimal unit = priceInput.add(priceOutput)
                    .divide(TWO, PRICE_SCALE, RoundingMode.HALF_UP);
            final BigDecimal weight = BigDecimal.valueOf(member.getWeight() > 0 ? member.getWeight() : 1);
            weighted = weighted.add(unit.multiply(weight));
            weightSum = weightSum.add(weight);
        }
        if (weightSum.signum() == 0) {
            return null;
        }
        return weighted.divide(weightSum, PRICE_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 生效资源池索引（poolName 小写 → 池定义）。
     *
     * <p>合并口径与 {@code PoolConfigController.reloadActivePools()} 一致：
     * YAML 默认池 + 持久化池，按 {@code poolName} 去重（持久化覆盖同名 YAML）。</p>
     *
     * <p><b>键为小写</b>（应修5）：poolName 大小写敏感会导致白名单里的
     * 「main」与池定义的「Main」匹配不上，员工分组下拉静默空白无提示。
     * 统一用 {@code toLowerCase} 作键，{@link #resolveOne} 查找时也转小写。</p>
     */
    private Map<String, PoolDefinition> poolIndex() {
        final Map<String, PoolDefinition> pools = new LinkedHashMap<>();

        final PoolDefinitionProperties poolProperties = poolPropertiesProvider.getIfAvailable();
        if (poolProperties != null && poolProperties.getPools() != null) {
            for (final PoolDefinition pool : poolProperties.getPools()) {
                if (pool != null && pool.getPoolName() != null) {
                    pools.put(pool.getPoolName().toLowerCase(), pool);
                }
            }
        }

        final PoolPersistenceService persistenceService = poolPersistenceServiceProvider.getIfAvailable();
        if (persistenceService != null) {
            try {
                final List<PoolDefinition> persisted = persistenceService.loadAll();
                if (persisted != null) {
                    for (final PoolDefinition pool : persisted) {
                        if (pool != null && pool.getPoolName() != null) {
                            // 同名以持久化为准（覆盖 YAML 默认池）
                            pools.put(pool.getPoolName().toLowerCase(), pool);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("加载持久化资源池失败，分组扩展信息将只基于 YAML 默认池: {}", e.getMessage());
            }
        }
        return pools;
    }

    /**
     * 已启用价格索引（实例名 → 价格行）。
     *
     * <p>键 = {@code model_price.model_name} = 厂商模型名 = 实例名，
     * 与 {@code BillingService} 的计价口径一致。</p>
     */
    private Map<String, ModelPriceEntity> priceIndex() {
        final Map<String, ModelPriceEntity> index = new HashMap<>();
        final ModelPriceRepository repository = modelPriceRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return index;
        }
        try {
            final List<ModelPriceEntity> prices = repository.findAllByEnabledTrue();
            if (prices != null) {
                for (final ModelPriceEntity price : prices) {
                    if (price != null && price.getModelName() != null) {
                        index.put(price.getModelName(), price);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("加载模型价格失败，分组加权均价将不可用: {}", e.getMessage());
        }
        return index;
    }

    /**
     * 实例索引（instanceId 或实例名 → 实例名）。
     *
     * <p>池成员的 {@code instanceId} 可能是 UUID，也可能是历史数据里的实例名，
     * 两种写法都要能解析（见 {@code PoolSelector#resolveCandidates} 的 {@code findInstance}）。</p>
     */
    private Map<String, String> instanceNameIndex() {
        final Map<String, String> index = new HashMap<>();
        final ModelServiceRegistry registry = modelServiceRegistryProvider.getIfAvailable();
        if (registry == null) {
            return index;
        }
        try {
            final Map<ModelServiceRegistry.ServiceType,
                    List<ModelRouterProperties.ModelInstance>> all = registry.getAllInstances();
            if (all == null) {
                return index;
            }
            for (final List<ModelRouterProperties.ModelInstance> instances : all.values()) {
                if (instances == null) {
                    continue;
                }
                for (final ModelRouterProperties.ModelInstance instance : instances) {
                    if (instance == null) {
                        continue;
                    }
                    if (instance.getInstanceId() != null) {
                        index.putIfAbsent(instance.getInstanceId(), instance.getName());
                    }
                    if (instance.getName() != null) {
                        index.putIfAbsent(instance.getName(), instance.getName());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("加载运行时实例列表失败，分组加权均价将不可用: {}", e.getMessage());
        }
        return index;
    }
}
