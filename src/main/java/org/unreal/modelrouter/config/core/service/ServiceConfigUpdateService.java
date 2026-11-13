package org.unreal.modelrouter.config.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.common.dto.CircuitBreakerConfig;
import org.unreal.modelrouter.common.dto.LoadBalanceConfig;
import org.unreal.modelrouter.common.dto.RateLimitConfig;
import org.unreal.modelrouter.config.dto.UpdateServiceConfigRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务配置更新服务
 *
 * 处理服务配置的 DTO 转换和更新逻辑。
 * 从 ConfigurationService 提取，实现单一职责原则。
 */
@Service
public class ServiceConfigUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(ServiceConfigUpdateService.class);

    /**
     * 构建服务配置更新
     *
     * <p>issue #1088：以 {@code existingServiceConfig} 为基线做<b>浅合并</b>——
     * 只覆盖请求中提交的字段，未提交字段（{@code fallback}、{@code loadBalance}
     * 子键、自定义键等）整体保留。修复前用空 Map 从零构建，PUT 一个只带
     * {@code rateLimit} 的请求会把 existing 里的 {@code fallback} /
     * {@code hashAlgorithm} 整体清空。</p>
     *
     * <p>语义定案（第七节）：DTO 各字段 {@code null} = 不修改；「显式清空」
     * 走配置版本回滚（{@code ConfigurationVersionController}）。子 Map
     * （{@code loadBalance}/{@code rateLimit}/{@code circuitBreaker}）同为浅合并，
     * 构建函数只产出非 null 子键，避免 null 覆盖旧值。</p>
     *
     * @param existingServiceConfig 现有服务配置（完整 Map，含 instances；可为 null）
     * @param request               更新请求
     * @return 更新后的服务配置
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildServiceConfigUpdate(final Map<String, Object> existingServiceConfig,
                                                         final UpdateServiceConfigRequest request) {
        // issue #1088: 基线 = 现有配置副本（含 instances / fallback / 自定义键）
        Map<String, Object> newConfig = existingServiceConfig != null
                ? new HashMap<>(existingServiceConfig) : new HashMap<>();

        // 更新传入的配置（null = 不修改，保留基线值）
        if (request.getAdapter() != null) {
            newConfig.put("adapter", request.getAdapter());
        }

        // 负载均衡配置（子 Map 浅合并：未提交子键如 hashAlgorithm 保留）
        LoadBalanceConfig lb = request.getLoadBalance();
        if (lb != null) {
            Map<String, Object> base = existingSubMap(newConfig, "loadBalance");
            base.putAll(buildLoadBalanceMap(lb));
            newConfig.put("loadBalance", base);
        }

        // 限流配置（子 Map 浅合并）
        RateLimitConfig rl = request.getRateLimit();
        if (rl != null) {
            Map<String, Object> base = existingSubMap(newConfig, "rateLimit");
            base.putAll(buildRateLimitMap(rl));
            // 落库兜底：算法缺省时写 token-bucket，避免运行时加载后 NPE（与 convert 默认值一致）。
            // 兜底只在「基线与请求都没给算法」时生效——修复前无条件写 token-bucket，
            // 浅合并下会用兜底值覆盖基线里已有的自定义算法（违背 null=不修改）。
            if (!base.containsKey("algorithm")) {
                base.put("algorithm", "token-bucket");
            }
            newConfig.put("rateLimit", base);
        }

        // 熔断配置（子 Map 浅合并）
        CircuitBreakerConfig cb = request.getCircuitBreaker();
        if (cb != null) {
            Map<String, Object> base = existingSubMap(newConfig, "circuitBreaker");
            base.putAll(buildCircuitBreakerMap(cb));
            newConfig.put("circuitBreaker", base);
        }

        logger.debug("构建服务配置更新完成，配置项: {}", newConfig.keySet());
        return newConfig;
    }

    /**
     * 取出 existing 中的子 Map 副本（不存在则返回新 Map）.
     *
     * <p>拷贝而非原地改：避免直接引用 existing 的内部 Map（防御性复制，
     * 调用方对返回配置做后续处理时不会污染基线）。</p>
     */
    private Map<String, Object> existingSubMap(final Map<String, Object> existing, final String key) {
        final Object value = existing != null ? existing.get(key) : null;
        if (value instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        return new HashMap<>();
    }

    /**
     * 构建负载均衡配置 Map（issue #1088：只产出非 null 子键，null=不修改）
     */
    private Map<String, Object> buildLoadBalanceMap(final LoadBalanceConfig lb) {
        Map<String, Object> lbMap = new HashMap<>();
        putIfNotNull(lbMap, "type", lb.getType());
        putIfNotNull(lbMap, "hashAlgorithm", lb.getHashAlgorithm());
        return lbMap;
    }

    /**
     * 构建限流配置 Map（issue #1088：只产出非 null 子键，null=不修改）
     */
    private Map<String, Object> buildRateLimitMap(final RateLimitConfig rl) {
        Map<String, Object> rlMap = new HashMap<>();
        putIfNotNull(rlMap, "enabled", rl.getEnabled());
        putIfNotNull(rlMap, "algorithm", rl.getAlgorithm());
        putIfNotNull(rlMap, "capacity", rl.getCapacity());
        putIfNotNull(rlMap, "rate", rl.getRate());
        putIfNotNull(rlMap, "scope", rl.getScope());
        putIfNotNull(rlMap, "key", rl.getKey());
        putIfNotNull(rlMap, "clientIpEnable", rl.getClientIpEnable());
        return rlMap;
    }

    /**
     * 构建熔断配置 Map（issue #1088：只产出非 null 子键，null=不修改）
     */
    private Map<String, Object> buildCircuitBreakerMap(final CircuitBreakerConfig cb) {
        Map<String, Object> cbMap = new HashMap<>();
        putIfNotNull(cbMap, "enabled", cb.getEnabled());
        putIfNotNull(cbMap, "failureThreshold", cb.getFailureThreshold());
        putIfNotNull(cbMap, "timeout", cb.getTimeout());
        putIfNotNull(cbMap, "successThreshold", cb.getSuccessThreshold());
        return cbMap;
    }

    /**
     * 非 null 才放入 Map（issue #1088：null 语义为「不修改」，不得覆盖基线值）.
     */
    private void putIfNotNull(final Map<String, Object> map, final String key, final Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
