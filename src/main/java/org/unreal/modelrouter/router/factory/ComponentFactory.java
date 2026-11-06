package org.unreal.modelrouter.router.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.config.core.ConfigurationValidator;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.router.fallback.FallbackStrategy;
import org.unreal.modelrouter.router.fallback.impl.CacheFallbackStrategy;
import org.unreal.modelrouter.router.fallback.impl.DefaultFallbackStrategy;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.impl.ConsistentHashLoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.impl.IpHashLoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.impl.LatencyAwareLoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.impl.LeastConnectionsLoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.impl.RandomLoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.impl.RoundRobinLoadBalancer;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.ratelimit.RateLimitConfig;
import org.unreal.modelrouter.router.ratelimit.RateLimiter;
import org.unreal.modelrouter.router.ratelimit.ScopedRateLimiterWrapper;
import org.unreal.modelrouter.router.ratelimit.impl.LeakyBucketRateLimiter;
import org.unreal.modelrouter.router.ratelimit.impl.SlidingWindowRateLimiter;
import org.unreal.modelrouter.router.ratelimit.impl.TokenBucketRateLimiter;
import org.unreal.modelrouter.monitor.tracing.wrapper.TracingWrapperFactory;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.checker.ServiceStateManager;

@Component
public class ComponentFactory {
    private static final Logger logger = LoggerFactory.getLogger(ComponentFactory.class);
    
    private final ConfigurationValidator configurationValidator;
    private final TracingWrapperFactory tracingWrapperFactory;
    // R2: 由 Spring 注入(AsyncMetricsCollector 标 @Primary)，下传给 new 出的 LB 实例，
    // 否则 LB 上的 @Autowired 字段在 new 出的实例上恒为 null → 指标死代码 / 健康判定退化
    private final MetricsCollector metricsCollector;
    private final ServiceStateManager serviceStateManager;
    // B1: 单一熔断注册表，下传给 new 出的 LB 实例（原 RandomLoadBalancer 私有熔断表已删除）
    private final CircuitBreakerManager circuitBreakerManager;

    @Autowired
    public ComponentFactory(final ConfigurationValidator configurationValidator,
                           @Autowired(required = false) final TracingWrapperFactory tracingWrapperFactory,
                           @Autowired(required = false) final MetricsCollector metricsCollector,
                           @Autowired(required = false) final ServiceStateManager serviceStateManager,
                           @Autowired(required = false) final CircuitBreakerManager circuitBreakerManager) {
        this.configurationValidator = configurationValidator;
        this.tracingWrapperFactory = tracingWrapperFactory;
        this.metricsCollector = metricsCollector;
        this.serviceStateManager = serviceStateManager;
        this.circuitBreakerManager = circuitBreakerManager;

        if (tracingWrapperFactory != null) {
            logger.info("追踪包装器工厂已启用，将为组件添加追踪功能");
        } else {
            logger.debug("追踪包装器工厂未启用，组件将不包含追踪功能");
        }
    }

    /* ---------------- LoadBalancer ---------------- */

    public LoadBalancer createLoadBalancer(final ModelRouterProperties.LoadBalanceConfig config) {
        if (config == null) {
            logger.warn("Load balance config null, fallback to random");
            LoadBalancer loadBalancer = new RandomLoadBalancer(metricsCollector, circuitBreakerManager);
            return wrapWithTracing(loadBalancer);
        }

        LoadBalancer loadBalancer = switch (config.getType().toLowerCase()) {
            case "random" -> new RandomLoadBalancer(metricsCollector, circuitBreakerManager);
            case "round-robin" -> new RoundRobinLoadBalancer(metricsCollector);
            case "least-connections" -> new LeastConnectionsLoadBalancer(metricsCollector);
            case "ip-hash" -> new IpHashLoadBalancer(config.getHashAlgorithm(), metricsCollector, circuitBreakerManager);
            case "consistent-hash" -> new ConsistentHashLoadBalancer(
                    config.getVirtualNodes() != null ? config.getVirtualNodes() : 150,
                    metricsCollector, serviceStateManager);
            case "latency" -> new LatencyAwareLoadBalancer(config.getEwmaAlpha(), metricsCollector);
            default -> {
                logger.warn("Unsupported load balancer: {}, fallback to random", config.getType());
                yield new RandomLoadBalancer(metricsCollector, circuitBreakerManager);
            }
        };
        
        return wrapWithTracing(loadBalancer);
    }

    /* ---------------- RateLimiter Core ---------------- */

    public RateLimiter createRateLimiter(final RateLimitConfig cfg) {
        if (cfg == null || !cfg.isEnabled()) return null;
        
        RateLimiter rateLimiter = switch (cfg.getAlgorithm().toLowerCase()) {
            case "token-bucket" -> new TokenBucketRateLimiter(cfg);
            case "leaky-bucket" -> new LeakyBucketRateLimiter(cfg);
            case "sliding-window" -> new SlidingWindowRateLimiter(cfg);
            case "warm-up" -> new org.unreal.modelrouter.router.ratelimit.impl.WarmUpRateLimiter(cfg);
            default -> {
                logger.warn("Unknown algorithm: {}, fallback to token-bucket", cfg.getAlgorithm());
                yield new TokenBucketRateLimiter(cfg);
            }
        };

        // B4 修复：限流器经 new 创建，其 @Autowired 字段恒为 null；
        // 由工厂在创建后注入 MetricsCollector，避免限流指标静默丢失（与 R2 同源）。
        if (rateLimiter != null) {
            rateLimiter.setMetricsCollector(metricsCollector);
        }
        
        return wrapWithTracing(rateLimiter);
    }

    /* ---------------- Scoped RateLimiter ---------------- */

    public RateLimiter createScopedRateLimiter(final RateLimitConfig cfg) {
        RateLimiter scopedRateLimiter = new ScopedRateLimiterWrapper(cfg, this::createRateLimiter);
        return wrapWithTracing(scopedRateLimiter);
    }

    /* ---------------- Validation ---------------- */

    public boolean validateRateLimitConfig(final RateLimitConfig cfg) {
        return configurationValidator.validateRateLimitConfig(cfg);
    }

    public boolean validateCircuitBreakerConfig(final ModelRouterProperties.CircuitBreakerConfig cfg) {
        if (cfg == null) {
            return false;
        }
        
        if (cfg.getEnabled() == null) {
            return false;
        }
        
        if (cfg.getFailureThreshold() != null && cfg.getFailureThreshold() <= 0) {
            return false;
        }
        
        if (cfg.getTimeout() != null && cfg.getTimeout() <= 0) {
            return false;
        }
        
        if (cfg.getSuccessThreshold() != null && cfg.getSuccessThreshold() <= 0) {
            return false;
        }
        
        return true;
    }

    public boolean validateLoadBalanceConfig(final ModelRouterProperties.LoadBalanceConfig cfg) {
        return configurationValidator.validateLoadBalanceConfig(cfg);
    }
    
    public boolean validateFallbackConfig(final ModelRouterProperties.FallbackConfig cfg) {
        return cfg != null && cfg.getEnabled() != null;
    }
    
    /**
     * 创建降级策略
     * @param config 降级配置
     * @param serviceType 服务类型
     * @return 降级策略实现
     */
    public FallbackStrategy<ResponseEntity<?>> createFallbackStrategy(
            final ModelRouterProperties.FallbackConfig config, 
            final String serviceType) {
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return null;
        }
        
        return switch (config.getStrategy().toLowerCase()) {
            case "cache" -> new CacheFallbackStrategy(serviceType, config.getCacheSize());
            case "default" -> new DefaultFallbackStrategy(serviceType);
            default -> new DefaultFallbackStrategy(serviceType);
        };
    }
    
    /* ---------------- 追踪包装器集成方法 ---------------- */
    
    /**
     * 为LoadBalancer添加追踪包装器
     * 
     * @param loadBalancer 原始LoadBalancer
     * @return 包装后的LoadBalancer
     */
    private LoadBalancer wrapWithTracing(final LoadBalancer loadBalancer) {
        if (tracingWrapperFactory != null && loadBalancer != null) {
            try {
                return tracingWrapperFactory.wrapLoadBalancer(loadBalancer);
            } catch (Exception e) {
                logger.warn("为LoadBalancer添加追踪包装器失败: {}", e.getMessage(), e);
            }
        }
        return loadBalancer;
    }
    
    /**
     * 为RateLimiter添加追踪包装器
     * 
     * @param rateLimiter 原始RateLimiter
     * @return 包装后的RateLimiter
     */
    private RateLimiter wrapWithTracing(final RateLimiter rateLimiter) {
        if (tracingWrapperFactory != null && rateLimiter != null) {
            try {
                return tracingWrapperFactory.wrapRateLimiter(rateLimiter);
            } catch (Exception e) {
                logger.warn("为RateLimiter添加追踪包装器失败: {}", e.getMessage(), e);
            }
        }
        return rateLimiter;
    }
    
}
