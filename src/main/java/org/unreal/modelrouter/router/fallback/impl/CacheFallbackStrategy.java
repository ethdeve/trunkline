package org.unreal.modelrouter.router.fallback.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.reactivestreams.Publisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.unreal.modelrouter.router.fallback.FallbackStrategy;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.common.util.IpUtils;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于缓存的降级策略实现
 * 当服务不可用时，返回最近的缓存结果
 */
public class CacheFallbackStrategy implements FallbackStrategy<ResponseEntity<?>> {
    private static final Logger logger = LoggerFactory.getLogger(CacheFallbackStrategy.class);

    /**
     * #872：缓存值包装——响应 + 写入时刻。TTL 过期判定基于注入的 {@link Clock}，
     * 避免直接读墙钟受时钟回拨影响；注入也便于单测用固定时钟反证过期行为。
     */
    private record CacheEntry(ResponseEntity<?> response, long createdAtMillis) {
    }

    private final String serviceType;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final int maxCacheSize;
    /**
     * #872：TTL（毫秒）。定案（2026-11-12）：{@code <=0} 表示<b>永不过期</b>，
     * 依赖容量上限兜底防无限增长——此前该配置从未被消费，缓存条目实际永不过期且无任何清理。
     */
    private final long cacheTtlMillis;
    private final Clock clock;

    /**
     * 兼容旧两参签名（既有 M-24 测试使用）：TTL 取 0 = 永不过期，行为与旧版一致。
     */
    public CacheFallbackStrategy(final String serviceType, final int maxCacheSize) {
        this(serviceType, maxCacheSize, 0L);
    }

    /**
     * 生产构造器（ComponentFactory 唯一入口）：TTL 来自 {@code FallbackConfig.cacheTtl}。
     */
    public CacheFallbackStrategy(final String serviceType, final int maxCacheSize, final long cacheTtlMillis) {
        this(serviceType, maxCacheSize, cacheTtlMillis, Clock.systemUTC());
    }

    /**
     * 包可见：注入 Clock 供 TTL 反证单测。
     */
    CacheFallbackStrategy(final String serviceType, final int maxCacheSize,
            final long cacheTtlMillis, final Clock clock) {
        this.serviceType = serviceType;
        this.maxCacheSize = maxCacheSize;
        this.cacheTtlMillis = cacheTtlMillis;
        this.clock = clock;
    }

    @Override
    public ResponseEntity<?> fallback(final Exception cause) {
        // M-24：无请求键时不再"任意取一条缓存"——那会把别的请求的模型输出串给当前调用方。
        // 保留此方法供无请求上下文的调用方（如 FallbackManager.fallback）使用：一律走无缓存降级。
        return noCacheResponse(cause);
    }

    @Override
    public ResponseEntity<?> fallback(final Exception cause, final String requestKey) {
        // M-24：只按本次请求的精确键命中；命中不到即无可用降级，绝不遍历 cache 取任意值。
        if (requestKey != null && !requestKey.isEmpty()) {
            CacheEntry entry = cache.get(requestKey);
            if (entry != null) {
                if (isExpired(entry)) {
                    // #872：命中但已过期——移除死条目并走无缓存降级，绝不返回陈旧响应。
                    cache.remove(requestKey);
                    logger.warn("Service {} fallback: cached response for requestKey={} expired (ttl={}ms), "
                            + "serving no-cache degraded response", serviceType, requestKey, cacheTtlMillis);
                    return noCacheResponse(cause);
                }
                logger.info("Returning request-scoped cached response for service {}", serviceType);
                return entry.response();
            }
            logger.warn("Service {} fallback: no cache entry for requestKey={}, refusing to serve "
                    + "another request's cached response (M-24 cross-request isolation)",
                    serviceType, requestKey);
        } else {
            logger.warn("Service {} fallback: request key unavailable, no request-scoped cache (M-24)", serviceType);
        }
        return noCacheResponse(cause);
    }

    /**
     * #872：TTL 过期判定。{@code cacheTtlMillis <= 0} = 永不过期（2026-11-12 定案）。
     */
    private boolean isExpired(final CacheEntry entry) {
        if (cacheTtlMillis <= 0) {
            return false;
        }
        return clock.millis() - entry.createdAtMillis() > cacheTtlMillis;
    }

    /**
     * 无可用缓存时的降级响应（OpenAI error 包裹形态，带 serviceType 与 Retry-After）。
     */
    private ResponseEntity<?> noCacheResponse(final Exception cause) {
        logger.warn("Service {} fallback triggered due to: {}", serviceType,
                cause != null ? cause.getMessage() : "unknown");
        return ResponseEntity.status(503)
                .header("Content-Type", "application/json")
                .header("Retry-After", "60")
                .body("{\"error\":{\"message\":\"Service is currently degraded and no cached data available\","
                        + "\"type\":\"service_unavailable\",\"code\":\"SERVICE_DEGRADED_NO_CACHE\","
                        + "\"service\":\"" + serviceType + "\"}}");
    }

    /**
     * 缓存响应结果
     *
     * @param serviceType
     * @param modelName
     * @param httpRequest
     * @param response    响应结果
     */
    public synchronized void cacheResponse(
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName, final ServerHttpRequest httpRequest,
            final ResponseEntity<?> response) {
        if (response == null) {
            return;
        }
        // #892：流式响应（Publisher body 或 SSE contentType）是一次性流，缓存后重放会得到
        // 空/报错/串味的"一次性 Flux"。BaseAdapter 写入桥已过滤，此处为防御性第二道闸门。
        if (isStreamingBody(response)) {
            logger.debug("Skip fallback cache write: streaming response cannot be replayed (service {})",
                    serviceType);
            return;
        }
        String cacheKey = generateCacheKeySync(serviceType, modelName, httpRequest);
        // M-24：拿不到请求级精确键就不写——避免所有请求挤在同一个桶里互相覆盖/串扰。
        if (cacheKey == null) {
            logger.debug("Skip fallback cache write: no request-scoped key for service {}", serviceType);
            return;
        }
        // #872：写入前先清扫过期条目，避免死条目长期占位挤占容量。
        if (cacheTtlMillis > 0) {
            final long now = clock.millis();
            cache.entrySet().removeIf(e -> now - e.getValue().createdAtMillis() > cacheTtlMillis);
        }
        // 边界淘汰与写入在同一同步块内完成：既保证容量不超限，又避免在
        // ConcurrentHashMap.compute 的映射函数内 remove 同一 map 触发 Recursive update。
        // 降级缓存仅成功响应时写入，频率低， synchronized 成本可忽略。
        // #890 修正：原实现在 maxCacheSize<=0 时 `0>=0` 恒真但空表 victim=null，
        // 仍无条件 put → 退化成"始终保留最近 1 条"，与"配 0=关闭"相反。
        // 现在 victim 为空（含 size<=0 的空表）直接放弃写入：cacheSize<=0 恒定空表。
        if (!cache.containsKey(cacheKey) && cache.size() >= maxCacheSize) {
            String victim = cache.keySet().stream().findFirst().orElse(null);
            if (victim == null) {
                logger.debug("Skip fallback cache write: cacheSize={} effectively disables caching (service {})",
                        maxCacheSize, serviceType);
                return;
            }
            cache.remove(victim);
        }
        cache.put(cacheKey, new CacheEntry(response, clock.millis()));
    }

    /**
     * #892：判定响应是否为流式（不可重放）——body 为响应式 Publisher，
     * 或 contentType 兼容 SSE。
     */
    private static boolean isStreamingBody(final ResponseEntity<?> response) {
        if (response.getBody() instanceof Publisher) {
            return true;
        }
        final MediaType contentType = response.getHeaders().getContentType();
        return contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType);
    }

    /**
     * 包可见观测口：当前缓存条目数（供反证单测断言容量/禁用行为，免反射）。
     */
    int cachedEntryCount() {
        return cache.size();
    }

    /**
     * 同步生成请求级缓存键（M-24），委托给 {@link #resolveRequestKey} 这一唯一权威来源。
     */
    protected String generateCacheKeySync(
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final ServerHttpRequest httpRequest) {
        return resolveRequestKey(serviceType, modelName, httpRequest);
    }

    /**
     * M-24：请求级降级键的唯一权威来源——写入（cacheResponse）与读取（fallback）共用本方法，
     * 保证两侧键完全一致，避免"写了 A 键却查 B 键"导致的降级永久未命中。
     *
     * <p>键的来源按优先级：
     * <ol>
     *   <li>网关认证阶段写入的响应缓存键（含租户+服务+请求体 SHA-256）——最精确；</li>
     *   <li>否则用"租户 + 服务 + 模型 + 原始请求 DTO 的 SHA-256"现场构造。
     *       DTO 由各入口 Controller 在认证前放入 httpRequest attributes（与 exchange 共享 map），
     *       record 的 toString() 稳定且覆盖全部字段。这样即便响应缓存默认关闭，
     *       {@code strategy: cache} 仍能按请求维度安全命中，而非退化成永远 503。</li>
     * </ol>
     * 仅当连原始 DTO 都拿不到时才返回 null（该请求不参与缓存降级），绝不回退到固定字面量。</p>
     *
     * @return 请求级键；无法定位到请求维度时返回 null
     */
    public static String resolveRequestKey(
            final ModelServiceRegistry.ServiceType serviceType,
            final String modelName,
            final ServerHttpRequest httpRequest) {
        if (serviceType == null) {
            return null;
        }
        if (httpRequest == null) {
            return null;
        }
        Map<String, Object> attrs = httpRequest.getAttributes();
        if (attrs == null) {
            return null;
        }
        // 1) 响应缓存键（启用响应缓存时由 ServiceRequestHandler 预生成）
        Object precise = attrs.get("TRUNKLINE_RESPONSE_CACHE_KEY");
        if (precise instanceof String key && !key.isEmpty()) {
            return serviceType.name() + ":" + key;
        }
        // 2) 现场按 租户 + 模型 + 请求体摘要 构造（不依赖响应缓存开关）
        Object dto = attrs.get("TRUNKLINE_REQUEST_DTO");
        if (dto == null) {
            return null;
        }
        Object tenant = attrs.get("API_KEY_ID");
        String tenantKey = tenant instanceof String s && !s.isEmpty()
                ? s : IpUtils.getClientIp(httpRequest);
        String bodyHash = sha256Hex(String.valueOf(dto));
        // 摘要失败（极罕见）时放弃缓存降级，绝不退化成可能碰撞的短键——安全优先。
        if (bodyHash == null) {
            return null;
        }
        return serviceType.name() + ":" + tenantKey + ":"
                + String.valueOf(modelName) + ":" + bodyHash;
    }

    private static String sha256Hex(final String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            logger.warn("Failed to compute request body digest; skip fallback cache (M-24)", e);
            return null;
        }
    }

}