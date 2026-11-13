package org.unreal.modelrouter.router.model;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.common.exception.PoolUnavailableException;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreaker;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.config.core.ConfigMergeService;
import org.unreal.modelrouter.config.core.helper.ConfigConverterHelper;
import org.unreal.modelrouter.config.core.helper.ServiceTypeResolver;
import org.unreal.modelrouter.router.fallback.FallbackManager;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.SelectedInstanceHolder;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancerManager;
import org.unreal.modelrouter.router.loadbalancer.monitor.RoutingMonitorService;
import org.unreal.modelrouter.router.ratelimit.RateLimitConfig;
import org.unreal.modelrouter.router.ratelimit.RateLimitContext;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;
import org.unreal.modelrouter.router.ratelimit.ServiceRateLimitHolder;
import org.unreal.modelrouter.router.pool.PoolSelector;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;
import org.unreal.modelrouter.router.rule.RuleDecision;
import org.unreal.modelrouter.router.rule.RuleEngineService;
import org.unreal.modelrouter.router.rule.RuleStatsService;
import org.unreal.modelrouter.router.rule.model.RuleDefinition;
import org.unreal.modelrouter.monitor.tracing.wrapper.LoadBalancerTracingWrapper;
import org.unreal.modelrouter.router.loadbalancer.AffinityContextHolder;
import org.unreal.modelrouter.router.loadbalancer.AffinityKeyResolver;
import org.unreal.modelrouter.router.loadbalancer.impl.StickyLoadBalancer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模型服务注册表 - 重构版
 * 负责管理所有模型服务的注册、选择和状态管理
 * 支持动态配置更新和服务发现
 */
@Configuration
@EnableConfigurationProperties(ModelRouterProperties.class)
@org.springframework.context.annotation.DependsOn("jpaDatabaseInitializer")
public class ModelServiceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelServiceRegistry.class);

    /** v2.9.7: 请求级标签路由 header,值格式 key=value,key2=value2(逗号分隔,容忍空格,空项跳过) */
    private static final String TAGS_HEADER = "X-Trunkline-Tags";

    /**
     * N-4：池不可用（503）时给客户端的退避提示秒数。
     * 与 fallback 降级路径的 {@code Retry-After: 60} 取同一值，客户端可统一退避策略。
     */
    private static final int POOL_UNAVAILABLE_RETRY_AFTER_SECONDS = 60;

    /**
     * 获取所有服务类型
     * @return 服务类型集合
     */
    public Set<String> getAllServiceTypes() {
         return Arrays.stream(ServiceType.values()).map(Enum::name).collect(Collectors.toSet());
    }

    public enum ServiceType {
        chat, embedding, rerank, tts, stt, imgGen, imgEdit
    }

    // 依赖组件
    private final LoadBalancerManager loadBalancerManager;
    private final ServiceStateManager serviceStateManager;
    private final RateLimitManager rateLimitManager;
    private final CircuitBreakerManager circuitBreakerManager;
    private final FallbackManager fallbackManager;
    private final ConfigMergeService configMergeService;
    private final ServiceTypeResolver serviceTypeResolver;
    private final ConfigConverterHelper configConverterHelper;

    // v2.7.7: 实例选择优化器（R17：由 Spring 注入共享单例；
    // 构造器内 new 仅作非 Spring 直构造场景的兜底，运行期会被 setSelectInstanceOptimizer 覆盖）
    private SelectInstanceOptimizer selectInstanceOptimizer;

    // v3.5 治理(R3): 按基础 LB 实例缓存 StickyLoadBalancer 包装，复用其一致性哈希环，
    // 避免每请求 new 后 cachedFingerprint 从 "" 起步导致哈希环必重建(O(instances×virtualNodes) 浪费)。
    // 基础 LB 为 EnumMap / strategyBalancers 中的长生命周期实例，键稳定。
    private final Map<LoadBalancer, StickyLoadBalancer> stickyLoadBalancerCache = new ConcurrentHashMap<>();

    // v2.7.20: WebClient缓存管理器
    private final WebClientCacheManager webClientCacheManager;

    // v2.7.28: 辅助组件
    private final ServiceInstanceSelector instanceSelector;
    private final ServiceConfigBuilder configBuilder;

    // v2.7.0: 路由监控服务
    private final RoutingMonitorService routingMonitorService;

    // v2.8.5: 规则引擎(延迟注入,避免循环依赖)
    private RuleEngineService ruleEngine;

    // v2.8.7: 规则命中统计(延迟注入,避免循环依赖)
    private RuleStatsService ruleStatsService;

    // v2.8.9: 资源池选择器(延迟注入,避免循环依赖)
    private PoolSelector poolSelector;

    // 配置和缓存
    private final ModelRouterProperties originalProperties;
    private volatile Map<String, Object> currentConfig;
    private volatile Map<String, ServiceRuntimeConfig> serviceConfigCache;

    /**
     * v2.8.5: 规则引擎延迟注入
     * required=false:规则引擎不可用时路由行为与之前完全一致
     */
    @Autowired(required = false)
    public void setRuleEngine(final RuleEngineService ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    /**
     * v2.8.7: 规则命中统计延迟注入
     * required=false:统计不可用时路由行为不变
     */
    @Autowired(required = false)
    public void setRuleStatsService(final RuleStatsService ruleStatsService) {
        this.ruleStatsService = ruleStatsService;
    }

    /**
     * v2.8.9: 资源池选择器延迟注入
     * required=false:池不可用时路由行为不变（未命中任何显式分组即 404）
     */
    @Autowired(required = false)
    public void setPoolSelector(final PoolSelector poolSelector) {
        this.poolSelector = poolSelector;
    }

    /**
     * R17：注入共享的实例选择优化器单例（消除 ModelServiceRegistry 与 PoolSelector
     * 各自 new 一份的重复构造；优化器本身无状态，共享无副作用）。
     */
    @Autowired(required = false)
    public void setSelectInstanceOptimizer(final SelectInstanceOptimizer selectInstanceOptimizer) {
        this.selectInstanceOptimizer = selectInstanceOptimizer;
    }

    public ModelServiceRegistry(final ModelRouterProperties properties,
                                final ServiceStateManager serviceStateManager,
                                final RateLimitManager rateLimitManager,
                                final LoadBalancerManager loadBalancerManager,
                                final CircuitBreakerManager circuitBreakerManager,
                                final FallbackManager fallbackManager,
                                final ConfigMergeService configMergeService,
                                final ServiceTypeResolver serviceTypeResolver,
                                final ConfigConverterHelper configConverterHelper,
                                final WebClientCacheManager webClientCacheManager,
                                final RoutingMonitorService routingMonitorService) {
        this.originalProperties = properties;
        this.serviceStateManager = serviceStateManager;
        this.rateLimitManager = rateLimitManager;
        this.loadBalancerManager = loadBalancerManager;
        this.circuitBreakerManager = circuitBreakerManager;
        this.fallbackManager = fallbackManager;
        this.configMergeService = configMergeService;
        this.serviceTypeResolver = serviceTypeResolver;
        this.configConverterHelper = configConverterHelper;
        this.webClientCacheManager = webClientCacheManager;
        this.routingMonitorService = routingMonitorService;
        this.serviceConfigCache = new ConcurrentHashMap<>();
        this.selectInstanceOptimizer = new SelectInstanceOptimizer(serviceStateManager, circuitBreakerManager);
        this.instanceSelector = new ServiceInstanceSelector(
                serviceStateManager, rateLimitManager, circuitBreakerManager, routingMonitorService);
        this.configBuilder = new ServiceConfigBuilder(
                configConverterHelper, rateLimitManager, circuitBreakerManager, fallbackManager);
    }

    @PostConstruct
    public void initialize() {
        LOGGER.info("正在初始化ModelServiceRegistry...");

        try {
            LOGGER.debug("开始合并YAML和持久化配置");
            refreshFromMergedConfig();
            LOGGER.debug("配置合并完成，当前配置大小: {}", currentConfig != null ? currentConfig.size() : 0);

            LOGGER.debug("开始初始化各个管理器");
            initializeManagers();
            LOGGER.debug("所有管理器初始化完成");

            LOGGER.info("ModelServiceRegistry初始化完成");
            logCurrentConfiguration();
        } catch (Exception e) {
            LOGGER.error("ModelServiceRegistry初始化失败", e);
            throw new RuntimeException("Failed to initialize ModelServiceRegistry", e);
        }
    }

    public void refreshFromMergedConfig() {
        LOGGER.info("正在刷新运行时配置...");

        try {
            Map<String, Object> mergedConfig = configMergeService.getPersistedConfig();

            if (mergedConfig == null || mergedConfig.isEmpty()) {
                LOGGER.info("未找到合并配置，使用默认配置");
                if (originalProperties != null) {
                    mergedConfig = configConverterHelper.convertModelRouterPropertiesToMap(originalProperties);
                } else {
                    LOGGER.warn("原始配置也为空，使用空配置");
                    mergedConfig = new HashMap<>();
                }
            }

            this.currentConfig = mergedConfig;
            updateOriginalPropertiesFromConfig(mergedConfig);
            this.serviceConfigCache = configBuilder.rebuildServiceConfigCache(mergedConfig);
            reinitializeLoadBalancers();
            // v2.8.8: 合并配置中的限流配置应用到运行时限流器(重启后持久化配置生效;YAML 无覆盖时不变)
            applyPersistedRateLimits(mergedConfig);

            LOGGER.info("运行时配置刷新完成，当前包含 {} 个服务", serviceConfigCache.size());
        } catch (Exception e) {
            LOGGER.error("刷新运行时配置失败", e);
        }
    }

    /**
     * 选择服务实例(无请求头版本,内部调用路径)
     */
    public ModelRouterProperties.ModelInstance selectInstance(final ServiceType serviceType,
                                                              final String modelName,
                                                              final String clientIp) {
        return selectInstance(serviceType, modelName, clientIp, null);
    }

    /**
     * 选择服务实例
     * v2.8.5: 支持规则引擎(按 header 等条件路由);无规则/不匹配时行为与之前完全一致
     */
    public ModelRouterProperties.ModelInstance selectInstance(final ServiceType serviceType,
                                                              final String modelName,
                                                              final String clientIp,
                                                              final Map<String, String> headers) {
        if (serviceType == null) {
            throw new IllegalArgumentException("ServiceType cannot be null");
        }
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("ModelName cannot be null or empty");
        }

        String serviceKey = serviceTypeResolver.getServiceConfigKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig == null || runtimeConfig.getInstances().isEmpty()) {
            // 文案不带 modelName：此处是整个 serviceType 无实例,与具体模型名无关(避免误导排查方向)
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No instances configured for service type '" + serviceType + "'");
        }

        // v2.8.5: 规则引擎求值(命中后重写模型名/锁定实例/覆盖LB策略;未命中走原逻辑)
        RuleDecision ruleDecision = ruleEngine != null
                ? ruleEngine.evaluate(serviceType, modelName, clientIp, headers)
                : null;
        // v2.8.7: 命中统计(仅决策生效点计数,resolveRuleAdapterName/dry-run 不计数)
        if (ruleDecision != null && ruleStatsService != null) {
            ruleStatsService.recordHit(ruleDecision.getRule().getId(),
                    ruleDecision.getRule().getAction().getType().name());
        }
        // v2.8.8: RATE_LIMIT 动作 — 仅决策生效点执行(与命中统计同处,resolveRuleAdapterName 侧不执行,防双计)
        if (ruleDecision != null && rateLimitManager != null
                && ruleDecision.getRule().getAction() != null
                && ruleDecision.getRule().getAction().getType() == RuleDefinition.ActionType.RATE_LIMIT) {
            RuleDefinition.Action action = ruleDecision.getRule().getAction();
            RateLimitConfig ruleCfg = new RateLimitConfig();
            ruleCfg.setEnabled(true);
            ruleCfg.setAlgorithm(action.getAlgorithm() != null ? action.getAlgorithm() : "token-bucket");
            ruleCfg.setCapacity(action.getCapacity() != null ? action.getCapacity() : 100L);
            ruleCfg.setRate(action.getRate() != null ? action.getRate() : 10L);
            ruleCfg.setScope(action.getScope() != null ? action.getScope() : "rule");
            if (action.getWarmUpPeriod() != null) {
                ruleCfg.setWarmUpPeriod(action.getWarmUpPeriod());
            }
            RateLimitContext ruleContext = new RateLimitContext(
                    serviceType, modelName, clientIp, 1, null, null, ruleDecision.getRule().getId());
            if (!rateLimitManager.tryAcquireRule(ruleDecision.getRule().getId(), ruleCfg, ruleContext)) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Rate limit rule exceeded: " + ruleDecision.getRule().getName());
            }
        }

        String effectiveModelName = ruleDecision != null && ruleDecision.getTargetModelName() != null
                ? ruleDecision.getTargetModelName() : modelName;

        List<ModelRouterProperties.ModelInstance> candidates = runtimeConfig.getInstances();
        if (ruleDecision != null && ruleDecision.getTargetInstanceId() != null) {
            String targetInstanceId = ruleDecision.getTargetInstanceId();
            candidates = candidates.stream()
                    .filter(i -> targetInstanceId.equals(i.getInstanceId())
                            || targetInstanceId.equals(i.getName()))
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No instances found for rule target instance '" + targetInstanceId + "'");
            }
            // 锁定实例时按目标实例自身名称匹配,不受原 modelName 限制
            effectiveModelName = candidates.get(0).getName();
        }

        // v2.9.7: 标签过滤 — 规则 TARGET_TAGS 优先,否则解析请求 header X-Trunkline-Tags;
        // 过滤后空候选与 TARGET_INSTANCE 一致抛 404
        Map<String, String> requiredTags = ruleDecision != null ? ruleDecision.getTargetTags() : null;
        if (requiredTags == null) {
            requiredTags = parseTagsHeader(headers);
        }
        if (requiredTags != null && !requiredTags.isEmpty()) {
            candidates = selectInstanceOptimizer.filterByTags(candidates, requiredTags);
            if (candidates.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No instances found matching tags " + requiredTags
                                + " for model '" + effectiveModelName + "'");
            }
        }

        // v2.8.9: 资源池路由 — effectiveModelName 命中池名 → 池成员候选 + 池策略;
        // v3.5 治理收口:未命中池时**不再**走「按实例名过滤」旁路——
        //   对外调用的唯一入口是模型分组(虚拟模型名),实例不允许被直接调用,
        //   否则会绕过所属分组的授权与配额。
        //   唯一例外仍然放行(内部行为,客户端无法直接触达):
        //     规则引擎把模型名重写为某个实例名(TARGET_MODEL)或锁定实例(TARGET_INSTANCE)。
        //   v3.6 治理收口：auto-model 系统分组已移除——没有显式创建的分组就没有可调用的虚拟模型。
        // N-1: 池候选 = 池成员 ∩ candidates(candidates 已按请求标签过滤),
        // 池路由同样受 X-Trunkline-Tags 约束(此前用全量实例解析导致标签被忽略)
        List<ModelRouterProperties.ModelInstance> availableInstances;
        LoadBalancer loadBalancer;
        PoolDefinition matchedPool = poolSelector != null
                ? poolSelector.findPool(serviceType, effectiveModelName) : null;
        if (matchedPool != null) {
            availableInstances = poolSelector.resolveCandidates(matchedPool, candidates);
            loadBalancer = loadBalancerManager.getLoadBalancerByStrategy(matchedPool.getStrategy());
            if (loadBalancer == null) {
                loadBalancer = loadBalancerManager.getLoadBalancer(null);
            }
            // v2.8.5: 规则指定 LB 策略时覆盖（池策略之上，管理员配置优先）。
            // v3.5 治理收口后请求几乎全部走池分支，若此处不覆盖，
            // 规则的 LB_STRATEGY 动作将退化为「永不生效」——池分支原本就没有这段逻辑，
            // 收口前只有非池路径能触发，属于单线治理放大的历史缺口。
            if (ruleDecision != null && ruleDecision.getLbStrategy() != null) {
                LoadBalancer ruleLoadBalancer =
                        loadBalancerManager.getLoadBalancerByStrategy(ruleDecision.getLbStrategy());
                if (ruleLoadBalancer != null) {
                    loadBalancer = ruleLoadBalancer;
                }
            }
        } else {
            final boolean ruleResolvedToInstance = ruleDecision != null
                    && (ruleDecision.getTargetInstanceId() != null || ruleDecision.getTargetModelName() != null);
            if (ruleResolvedToInstance) {
                // ① 规则引擎显式指定了实例(管理员配置,非客户端可控)
                availableInstances = selectInstanceOptimizer.filterAvailableInstances(
                    candidates, effectiveModelName, serviceType);
            } else {
                // v3.5 治理收口:model 未命中任何分组 → 拒绝。
                // 报错带上「可用分组」提示,避免客户端只知道 404 不知怎么改。
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Model '" + effectiveModelName + "' is not a model group. "
                                + "Requests must target a model group (pool); "
                                + "direct model access is not allowed.");
            }
            loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
            if (loadBalancer == null) {
                loadBalancer = loadBalancerManager.getLoadBalancer(null);
            }
            // v2.8.5: 规则指定 LB 策略时覆盖
            if (ruleDecision != null && ruleDecision.getLbStrategy() != null) {
                LoadBalancer ruleLoadBalancer =
                        loadBalancerManager.getLoadBalancerByStrategy(ruleDecision.getLbStrategy());
                if (ruleLoadBalancer != null) {
                    loadBalancer = ruleLoadBalancer;
                }
            }
        }

        if (availableInstances.isEmpty()) {
            if (matchedPool != null) {
                // N-1: 池候选受请求标签约束后可能为空。若池成员在全量实例中可解析(仅被标签过滤掉),
                // 抛标签 404(与非池路径语义一致);否则才是池成员不健康/不存在,抛池感知异常
                // (createAppropriateException 按「池名==实例名」过滤,永远落 404 分支误导排查,
                // 故改为池感知异常,带逐成员状态明细,见 gateway-fix-plan P2-7/P2-8)
                if (requiredTags != null && !requiredTags.isEmpty()
                        && !poolSelector.resolveCandidates(matchedPool, runtimeConfig.getInstances()).isEmpty()) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "No instances found matching tags " + requiredTags
                                    + " for pool '" + matchedPool.getPoolName() + "'");
                }
                throw buildPoolUnavailableException(serviceType, matchedPool, runtimeConfig.getInstances());
            }
            throw instanceSelector.createAppropriateException(serviceType, effectiveModelName, candidates);
        }

        // v2.9.0: 会话粘性路由 — 按 sticky.scope 配置解析亲和性键，用 StickyLoadBalancer 包装原 LB
        String stickyScope = getStickyScope(serviceType);
        String affinityKey = stickyScope != null
                ? AffinityContextHolder.resolveKey(stickyScope)
                : AffinityContextHolder.get();
        // v2.9.x: 资源池(模型分组)路由不走粘性 — 池的亲和性键为 apiKeyId|serviceType|池名,
        // 对同一个池恒定不变,若套上 StickyLoadBalancer 会把该池所有请求(含不同会话)钉死到同一成员,
        // 直接废掉池自身的 round-robin/weighted 策略。池内成员选择交由池策略(matchedPool.getStrategy())负责。
        if (affinityKey != null && stickyScope != null && matchedPool == null) {
            // R3: 按基础 LB 实例缓存 StickyLoadBalancer，复用其一致性哈希环
            loadBalancer = stickyLoadBalancerCache.computeIfAbsent(loadBalancer,
                    base -> new StickyLoadBalancer(base, serviceStateManager, circuitBreakerManager));
        }

        // v2.8.8: 服务级限流检查(每请求恰一次;实例级在 selectWithRateLimit 内;无配置零开销)
        // v2.9.10: 缓存命中提前短路场景——handler 层已预扣服务级限流(见 ServiceRateLimitHolder),
        // 此处跳过以保持恰一次语义;非缓存路径(无 key/disabled)标志未设置,行为与现状完全一致
        if (!ServiceRateLimitHolder.isAcquired()
                && rateLimitManager != null && !rateLimitManager.tryAcquire(
                new RateLimitContext(serviceType, effectiveModelName, clientIp, 1, null, null))) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Service rate limit exceeded for service type '" + serviceType + "'");
        }

        ModelRouterProperties.ModelInstance selectedInstance =
                instanceSelector.selectWithRateLimit(
                        availableInstances, loadBalancer, affinityKey, clientIp, serviceType, effectiveModelName);

        if (selectedInstance == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "All instances rate limited for model '" + effectiveModelName + "'");
        }

        // v2.7.0: 记录路由选择事件
        String strategy = getActualStrategyName(loadBalancer);
        routingMonitorService.recordSelection(
                serviceType.name().toLowerCase(),
                effectiveModelName,
                strategy,
                selectedInstance,
                clientIp,
                availableInstances.size());

        loadBalancer.recordCall(selectedInstance);
        // R1: 记录选择侧实际使用的 LB 实例，供回调 recordCallComplete/Failure 复用同一实例，
        // 避免带状态策略(least-connections 的 connectionCounts)在"选择(策略LB)/回调(服务LB)"不同
        // 实例间单调泄漏。取不到时回调回退 LoadBalancerManager.getLoadBalancer(serviceType)（历史行为）。
        SelectedInstanceHolder.setLoadBalancer(loadBalancer);
        return selectedInstance;
    }

    /**
     * v2.8.5: 解析规则指定的目标适配器名(供 ServiceRequestHandler 消费 TARGET_ADAPTER 动作)
     * 必须传原始 modelName 与 selectInstance 求值输入一致,保证二次求值确定性
     *
     * @return 规则指定的适配器名;无规则/不匹配/非 TARGET_ADAPTER 动作返回 null
     */
    public String resolveRuleAdapterName(final ServiceType serviceType,
                                         final String modelName,
                                         final String clientIp,
                                         final Map<String, String> headers) {
        if (ruleEngine == null) {
            return null;
        }
        RuleDecision decision = ruleEngine.evaluate(serviceType, modelName, clientIp, headers);
        return decision != null ? decision.getTargetAdapterName() : null;
    }

    public String getModelPath(final ServiceType serviceType, final String modelName) {
        String serviceKey = serviceTypeResolver.getServiceConfigKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig == null || runtimeConfig.getInstances().isEmpty()) {
            return "";
        }

        Optional<ModelRouterProperties.ModelInstance> matchingInstance = runtimeConfig.getInstances().stream()
                .filter(instance -> modelName.equals(instance.getName()))
                .findFirst();
        if (matchingInstance.isPresent()) {
            return matchingInstance.get().getPath();
        }

        // v2.8.9 池路由补全 — model 为池名时按实例名匹配必然落空(原实现返回 "" 导致
        // 转发打到上游 baseUrl 根路径,上游回 405 METHOD_NOT_ALLOWED);
        // 回退为取池成员实例的 path(池内成员同属一个服务类型,path 约定一致,取首个非空)。
        // 匹配口径与 PoolSelector.resolveCandidates 保持一致:instanceId 或 name。
        if (poolSelector != null) {
            PoolDefinition pool = poolSelector.findPool(serviceType, modelName);
            if (pool != null && pool.getMembers() != null) {
                for (PoolMember member : pool.getMembers()) {
                    if (member == null || member.getInstanceId() == null) {
                        continue;
                    }
                    Optional<ModelRouterProperties.ModelInstance> memberInstance =
                            runtimeConfig.getInstances().stream()
                                    .filter(i -> member.getInstanceId().equals(i.getInstanceId())
                                            || member.getInstanceId().equals(i.getName()))
                                    .filter(i -> i.getPath() != null && !i.getPath().isEmpty())
                                    .findFirst();
                    if (memberInstance.isPresent()) {
                        return memberInstance.get().getPath();
                    }
                }
            }
        }
        return "";
    }

    public String getServiceAdapter(final ServiceType serviceType) {
        String serviceKey = serviceTypeResolver.getServiceConfigKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);
        return runtimeConfig != null ? runtimeConfig.getAdapter() : null;
    }

    public void recordCallComplete(final ServiceType serviceType, final ModelRouterProperties.ModelInstance instance) {
        LoadBalancer loadBalancer = SelectedInstanceHolder.getLoadBalancer();
        if (loadBalancer == null) {
            loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        }
        if (loadBalancer != null) {
            loadBalancer.recordCallComplete(instance);
        }
        circuitBreakerManager.recordSuccess(instance.getInstanceId(), instance.getBaseUrl());
    }

    public void recordCallFailure(final ServiceType serviceType, final ModelRouterProperties.ModelInstance instance) {
        LoadBalancer loadBalancer = SelectedInstanceHolder.getLoadBalancer();
        if (loadBalancer == null) {
            loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        }
        if (loadBalancer != null) {
            loadBalancer.recordCallFailure(instance);
        }
        circuitBreakerManager.recordFailure(instance.getInstanceId(), instance.getBaseUrl());
    }

    /**
     * v2.9.3: 记录实例调用完成（带调用时长和成功状态），将 durationMs 传递给负载均衡器钩子
     */
    public void recordCallComplete(final ServiceType serviceType,
                                   final ModelRouterProperties.ModelInstance instance,
                                   final long durationMs,
                                   final boolean success) {
        LoadBalancer loadBalancer = SelectedInstanceHolder.getLoadBalancer();
        if (loadBalancer == null) {
            loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        }
        if (loadBalancer != null) {
            loadBalancer.recordCallComplete(instance, durationMs, success);
        }
        circuitBreakerManager.recordSuccess(instance.getInstanceId(), instance.getBaseUrl());
    }

    /**
     * v2.9.3: 记录实例调用失败（带调用时长和错误码），将 durationMs 传递给负载均衡器钩子
     */
    public void recordCallFailure(final ServiceType serviceType,
                                  final ModelRouterProperties.ModelInstance instance,
                                  final long durationMs,
                                  final String errorCode) {
        LoadBalancer loadBalancer = SelectedInstanceHolder.getLoadBalancer();
        if (loadBalancer == null) {
            loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        }
        if (loadBalancer != null) {
            loadBalancer.recordCallFailure(instance, durationMs, errorCode);
        }
        circuitBreakerManager.recordFailure(instance.getInstanceId(), instance.getBaseUrl());
    }

    /**
     * issue #647：客户端侧 4xx（400/401/403/404 等，不含 408/429）完成记录。
     *
     * <p>一个坏请求不是实例故障，计入熔断会让连续几个 4xx 直接把健康实例熔断。
     * 因此只经 {@code loadBalancer.recordCallComplete(..., false)} 释放/衰减在途计数，
     * <b>不调用</b> {@code circuitBreakerManager.recordFailure}（也不调 recordSuccess）。
     * 注意普通 {@link #recordCallComplete(ServiceType, ModelRouterProperties.ModelInstance, long, boolean)}
     * 无条件调 recordSuccess，不能直接复用；失败统计由 AdapterMetricsRecorder 侧照记。</p>
     */
    public void recordClientErrorComplete(final ServiceType serviceType,
                                          final ModelRouterProperties.ModelInstance instance,
                                          final long durationMs) {
        LoadBalancer loadBalancer = SelectedInstanceHolder.getLoadBalancer();
        if (loadBalancer == null) {
            loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        }
        if (loadBalancer != null) {
            loadBalancer.recordCallComplete(instance, durationMs, false);
        }
    }

    public CircuitBreaker.State getInstanceCircuitBreakerState(final ModelRouterProperties.ModelInstance instance) {
        return circuitBreakerManager.getState(instance.getInstanceId(), instance.getBaseUrl());
    }

    /**
     * 构建池路由不可用的池感知异常（P2-7/P2-8）.
     *
     * <p>池成员解析失败/全不健康/熔断打开统一返回 503,message 附带逐成员状态明细:
     * {@code name(status=..., health=..., circuitBreaker=...)} 或 {@code memberKey(UNRESOLVED)},
     * 便于「模型分组」页排查哪个成员挂了。</p>
     *
     * <p>N-3：明细里的 {@code status} 是<strong>配置管理态</strong>（ACTIVE=已启用），
     * 真正决定成员能否参与分流的是<strong>探活态</strong>。原先只输出 status，
     * 运维会看到 {@code status=ACTIVE} 却收不到请求，误以为实例正常。
     * 现补出 {@code health} 字段（HEALTHY / UNHEALTHY / UNKNOWN）。</p>
     *
     * <p>N-4：改抛 {@link PoolUnavailableException}，由全局异常处理器补 {@code Retry-After} 头，
     * 与 fallback 降级路径的 {@code Retry-After: 60} 保持一致，客户端可统一退避。</p>
     */
    private PoolUnavailableException buildPoolUnavailableException(
            final ServiceType serviceType,
            final PoolDefinition pool,
            final List<ModelRouterProperties.ModelInstance> allInstances) {
        final List<String> memberStates = new ArrayList<>();
        boolean anyResolved = false;
        if (pool.getMembers() != null) {
            for (final PoolMember member : pool.getMembers()) {
                final String memberKey = member.getInstanceId();
                final ModelRouterProperties.ModelInstance resolved = allInstances.stream()
                        .filter(i -> memberKey != null
                                && (memberKey.equals(i.getInstanceId()) || memberKey.equals(i.getName())))
                        .findFirst()
                        .orElse(null);
                if (resolved == null) {
                    memberStates.add(memberKey + "(UNRESOLVED)");
                    continue;
                }
                anyResolved = true;
                memberStates.add(resolved.getName()
                        + "(status=" + resolved.getStatus()
                        + ", health=" + resolveHealthStatus(serviceType, resolved)
                        + ", circuitBreaker=" + getInstanceCircuitBreakerState(resolved) + ")");
            }
        }
        if (!anyResolved) {
            return new PoolUnavailableException(
                    "Pool '" + pool.getPoolName() + "' has no resolvable members (members: " + memberStates + ")",
                    POOL_UNAVAILABLE_RETRY_AFTER_SECONDS);
        }
        return new PoolUnavailableException(
                "Pool '" + pool.getPoolName() + "' has no available members (members: " + memberStates + ")",
                POOL_UNAVAILABLE_RETRY_AFTER_SECONDS);
    }

    /**
     * N-3：解析实例的探活态（HEALTHY / UNHEALTHY / UNKNOWN）。
     *
     * <p>优先读运行时的健康位图（{@link ServiceStateManager#getInstanceHealthStatus}）——
     * 那才是路由时真正使用的判定；运行态未记录（UNKNOWN）时回退到配置上的 {@code isHealthy} 字段。
     * 注意<strong>不能</strong>只输出配置的 {@code status}（ACTIVE=已启用），
     * 运维会误以为实例正常，实际却是探活失败被排除。</p>
     */
    private String resolveHealthStatus(
            final ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        if (serviceType != null && serviceStateManager != null) {
            try {
                final String runtimeStatus = serviceStateManager.getInstanceHealthStatus(
                        InstanceKeys.healthKey(serviceType.name(), instance));
                if (runtimeStatus != null && !"UNKNOWN".equals(runtimeStatus)) {
                    return runtimeStatus;
                }
            } catch (Exception ignored) {
                // 运行态不可查时退到配置字段
            }
        }
        final Boolean configured = instance.isHealthy();
        if (configured == null) {
            return "UNKNOWN";
        }
        return configured ? "HEALTHY" : "UNHEALTHY";
    }

    // ==================== 查询方法 ====================

    public Set<ServiceType> getAvailableServiceTypes() {
        return serviceConfigCache.entrySet().stream()
                .filter(entry -> !entry.getValue().getInstances().isEmpty())
                .map(entry -> serviceTypeResolver.parseServiceType(entry.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public Set<String> getAvailableModels(final ServiceType serviceType) {
        String serviceKey = serviceTypeResolver.getServiceConfigKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig == null) {
            return Collections.emptySet();
        }

        return runtimeConfig.getInstances().stream()
                .map(ModelRouterProperties.ModelInstance::getName)
                .collect(Collectors.toSet());
    }

    public Map<ServiceType, List<ModelRouterProperties.ModelInstance>> getAllInstances() {
        Map<ServiceType, List<ModelRouterProperties.ModelInstance>> result = new HashMap<>();

        for (Map.Entry<String, ServiceRuntimeConfig> entry : serviceConfigCache.entrySet()) {
            ServiceType serviceType = serviceTypeResolver.parseServiceType(entry.getKey());
            if (serviceType != null) {
                result.put(serviceType, new ArrayList<>(entry.getValue().getInstances()));
            }
        }

        return result;
    }

    public ModelRouterProperties.ServiceConfig getServiceConfig(final ServiceType serviceType) {
        String serviceKey = serviceTypeResolver.getServiceConfigKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig == null) {
            return null;
        }

        ModelRouterProperties.ServiceConfig serviceConfig = new ModelRouterProperties.ServiceConfig();
        serviceConfig.setInstances(new ArrayList<>(runtimeConfig.getInstances()));
        serviceConfig.setAdapter(runtimeConfig.getAdapter());
        serviceConfig.setLoadBalance(runtimeConfig.getLoadBalanceConfig());
        serviceConfig.setRateLimit(runtimeConfig.getRateLimitConfig());
        serviceConfig.setCircuitBreaker(runtimeConfig.getCircuitBreakerConfig());
        serviceConfig.setFallback(runtimeConfig.getFallbackConfig());

        return serviceConfig;
    }

    public String getLoadBalanceStrategy(final ServiceType serviceType) {
        // N-5: 返回策略语义名(配置 type,如 round-robin/random),而非 LoadBalancer 实现类名
        // (曾误返回 LoadBalancerTracingWrapper,对调用方无信息量)
        ServiceRuntimeConfig config = serviceConfigCache.get(serviceTypeResolver.getServiceConfigKey(serviceType));
        if (config == null || config.getLoadBalanceConfig() == null) {
            return null;
        }
        return config.getLoadBalanceConfig().getType();
    }

    /**
     * P3-11: 解析请求模型名命中的资源池名(池名即虚拟模型名);非池路由返回 {@code null}.
     * 仅供响应调试头(X-Trunkline-Pool)使用,与 selectInstance 的池匹配同源。
     */
    public String resolvePoolName(final ServiceType serviceType, final String modelName) {
        if (poolSelector == null || modelName == null) {
            return null;
        }
        PoolDefinition pool = poolSelector.findPool(serviceType, modelName);
        return pool != null ? pool.getPoolName() : null;
    }

    /**
     * P3-11: 解析请求实际生效的负载均衡策略名(池路由返回池策略,否则返回服务配置策略).
     * 仅供响应调试头(X-Trunkline-Strategy)使用。
     */
    public String resolveRoutingStrategy(final ServiceType serviceType, final String modelName) {
        if (poolSelector != null && modelName != null) {
            PoolDefinition pool = poolSelector.findPool(serviceType, modelName);
            if (pool != null) {
                return pool.getStrategy();
            }
        }
        return getLoadBalanceStrategy(serviceType);
    }

    public FallbackManager getFallbackManager() {
        return fallbackManager;
    }

    // ==================== 私有方法 ====================

    /**
     * v2.9.0: 检查指定服务类型是否启用粘性路由
     * 需要 sticky.enabled=true (默认true) 且实例数>1
     */
    private boolean isStickyEnabled(final ServiceType serviceType) {
        String serviceKey = serviceTypeResolver.getServiceConfigKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);
        if (runtimeConfig == null) {
            return false;
        }
        List<ModelRouterProperties.ModelInstance> instances = runtimeConfig.getInstances();
        if (instances == null || instances.size() <= 1) {
            return false;
        }
        // 从原始配置中读取 sticky 配置
        if (originalProperties != null && originalProperties.getServices() != null) {
            ModelRouterProperties.ServiceConfig svcConfig =
                    originalProperties.getServices().get(serviceKey);
            if (svcConfig != null && svcConfig.getSticky() != null) {
                return Boolean.TRUE.equals(svcConfig.getSticky().getEnabled());
            }
        }
        // 默认:实例>1时启用
        return true;
    }

    /**
     * v2.9.0: 获取指定服务类型的粘性亲和性粒度(scope)。
     * 粘性未启用时返回 null(调用方跳过粘性路由)。
     * scope 值: "tenant_model"(默认) 或 "tenant"。
     */
    private String getStickyScope(final ServiceType serviceType) {
        String serviceKey = serviceTypeResolver.getServiceConfigKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);
        if (runtimeConfig == null) {
            return null;
        }
        List<ModelRouterProperties.ModelInstance> instances = runtimeConfig.getInstances();
        if (instances == null || instances.size() <= 1) {
            return null;
        }
        if (originalProperties != null && originalProperties.getServices() != null) {
            ModelRouterProperties.ServiceConfig svcConfig =
                    originalProperties.getServices().get(serviceKey);
            if (svcConfig != null && svcConfig.getSticky() != null) {
                if (!Boolean.TRUE.equals(svcConfig.getSticky().getEnabled())) {
                    return null;
                }
                String scope = svcConfig.getSticky().getAffinityKeyScope();
                return scope != null ? scope : AffinityKeyResolver.SCOPE_TENANT_MODEL;
            }
        }
        // 默认: 实例>1时启用，使用 tenant_model 粒度
        return AffinityKeyResolver.SCOPE_TENANT_MODEL;
    }

    /**
     * v2.9.7: 解析请求级标签 header X-Trunkline-Tags
     * 格式: key=value,key2=value2;逗号分隔、容忍空格、键值等号分隔、空项/空值跳过
     *
     * @return 解析后的标签 Map;无 header 或解析为空返回 null
     */
    private Map<String, String> parseTagsHeader(final Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        String headerValue = headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(TAGS_HEADER))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        Map<String, String> tags = new HashMap<>();
        for (String item : headerValue.split(",")) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String trimmed = item.trim();
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eqIdx).trim();
            String value = trimmed.substring(eqIdx + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            tags.put(key, value);
        }
        return tags.isEmpty() ? null : tags;
    }

    /**
     * v2.8.8: 将合并配置中带 rateLimit 的服务应用到运行时限流器
     * 仅处理含 rateLimit 段的配置(YAML 无覆盖的服务保持构造时初始化的限流器不变)
     */
    @SuppressWarnings("unchecked")
    private void applyPersistedRateLimits(final Map<String, Object> mergedConfig) {
        if (mergedConfig == null || !(mergedConfig.get("services") instanceof Map)) {
            return;
        }
        Map<String, Object> services = (Map<String, Object>) mergedConfig.get("services");
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            ServiceType type = serviceTypeResolver.parseServiceType(entry.getKey());
            if (type == null || !(entry.getValue() instanceof Map)) {
                continue;
            }
            Object rateLimit = ((Map<String, Object>) entry.getValue()).get("rateLimit");
            if (rateLimit instanceof Map) {
                rateLimitManager.setRateLimiter(type, RateLimitConfig.fromMap((Map<String, Object>) rateLimit));
            }
        }
    }

    private void initializeManagers() {
        circuitBreakerManager.initialize(originalProperties);
        fallbackManager.initialize(originalProperties);
        LOGGER.debug("所有管理器初始化完成");
    }

    private void reinitializeLoadBalancers() {
        try {
            for (Map.Entry<String, ServiceRuntimeConfig> entry : serviceConfigCache.entrySet()) {
                ServiceType serviceType = serviceTypeResolver.parseServiceType(entry.getKey());
                if (serviceType != null && !entry.getValue().getInstances().isEmpty()) {
                    loadBalancerManager.reinitializeLoadBalancer(serviceType, entry.getValue().getLoadBalanceConfig());
                }
            }
            LOGGER.debug("负载均衡器重新初始化完成");
        } catch (Exception e) {
            LOGGER.warn("重新初始化负载均衡器时发生错误: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void updateOriginalPropertiesFromConfig(final Map<String, Object> mergedConfig) {
        if (mergedConfig == null || !mergedConfig.containsKey("services")) {
            return;
        }

        Map<String, Object> servicesMap = (Map<String, Object>) mergedConfig.get("services");
        if (servicesMap == null || originalProperties == null) {
            return;
        }

        Map<String, ModelRouterProperties.ServiceConfig> services = new HashMap<>();
        for (Map.Entry<String, Object> entry : servicesMap.entrySet()) {
            Map<String, Object> serviceConfigMap = (Map<String, Object>) entry.getValue();
            ModelRouterProperties.ServiceConfig serviceConfig =
                    configConverterHelper.convertMapToServiceConfig(serviceConfigMap);
            services.put(entry.getKey(), serviceConfig);
        }

        originalProperties.setServices(services);
    }

    private void logCurrentConfiguration() {
        LOGGER.info("当前服务配置概览:");
        for (Map.Entry<String, ServiceRuntimeConfig> entry : serviceConfigCache.entrySet()) {
            String serviceKey = entry.getKey();
            ServiceRuntimeConfig config = entry.getValue();
            LOGGER.info("  服务 {}: {} 个实例, 适配器={}, 负载均衡={}",
                    serviceKey,
                    config.getInstances().size(),
                    config.getAdapter(),
                    config.getLoadBalanceConfig() != null ? config.getLoadBalanceConfig().getType() : "default");
        }
    }

    /**
     * 获取实际的负载均衡策略名称
     * 如果 LoadBalancer 被 LoadBalancerTracingWrapper 包装，则获取被包装的实际策略名
     *
     * @param loadBalancer 负载均衡器实例
     * @return 实际的策略名称
     */
    private String getActualStrategyName(final LoadBalancer loadBalancer) {
        if (loadBalancer == null) {
            return "Unknown";
        }

        // 检查是否是 TracingWrapper
        if (loadBalancer instanceof LoadBalancerTracingWrapper wrapper) {
            LoadBalancer delegate = wrapper.getDelegate();
            if (delegate != null) {
                return delegate.getClass().getSimpleName();
            }
        }

        return loadBalancer.getClass().getSimpleName();
    }
}
