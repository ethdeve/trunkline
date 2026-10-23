package org.unreal.modelrouter.router.adapter.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.quota.QuotaReservation;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.common.dto.ChatDTO;
import org.unreal.modelrouter.common.dto.EmbeddingDTO;
import org.unreal.modelrouter.monitor.service.TokenUsageRecorder;
import org.unreal.modelrouter.monitor.tracing.TracingContextHolder;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.pool.PoolSelector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token 使用量提取与记录辅助类
 *
 * 从 NonStreamingRequestProcessor 提取的 token 使用量相关方法，包括：
 * - 从响应中提取 token 使用量并记录
 * - 更新 API Key 的 token 使用量配额
 * - 从请求中提取 API Key ID
 * - 提取原始请求的 model 字段
 * - 池路由后改写 model 字段
 *
 * @since v2.26.6
 */
@Component
public class TokenUsageExtractor {

    private static final Logger logger = LoggerFactory.getLogger(TokenUsageExtractor.class);

    private final ObjectMapper objectMapper;

    private final TokenUsageRecorder tokenUsageRecorder;

    private final ApiKeyService apiKeyService;

    private final PoolSelector poolSelector;

    private final MetricsCollector metricsCollector;

    public TokenUsageExtractor(
            final ObjectMapper objectMapper,
            final TokenUsageRecorder tokenUsageRecorder,
            final ApiKeyService apiKeyService,
            final PoolSelector poolSelector,
            final MetricsCollector metricsCollector) {
        this.objectMapper = objectMapper;
        this.tokenUsageRecorder = tokenUsageRecorder;
        this.apiKeyService = apiKeyService;
        this.poolSelector = poolSelector;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 从下游服务响应中提取 token 使用量并记录（不带配额结算，保留既有签名）。
     *
     * @param bodyStr      下游响应体
     * @param adapterType  适配器类型
     * @param instanceName 实例名
     * @param apiKeyId     API Key ID
     */
    void extractAndRecordTokenUsage(final String bodyStr,
                                              final String adapterType,
                                              final String instanceName,
                                              final String apiKeyId) {
        extractAndRecordTokenUsage(bodyStr, adapterType, instanceName, apiKeyId, null);
    }

    /**
     * 从下游服务响应中提取 token 使用量并记录，同时完成配额结算（v3.1 PR-2）。
     *
     * <p>配额结算发生在“实际 token 落库点”：按 {@code 实际 − 估算} 冲正预留
     * （{@link QuotaReservation#settleSuccess(QuotaReservation, long)}）。响应不含 usage 或
     * 总量为 0 时本方法提前返回，预留保持原估算值（记账口径退化为“预留即计费”），
     * 不会被错误冲正为 0。</p>
     *
     * @param bodyStr        下游响应体
     * @param adapterType    适配器类型
     * @param instanceName   实例名
     * @param apiKeyId       API Key ID
     * @param quotaReservation 配额预留凭据，{@code null}（未预扣 / 账本未启用）时空操作
     */
    void extractAndRecordTokenUsage(final String bodyStr,
                                              final String adapterType,
                                              final String instanceName,
                                              final String apiKeyId,
                                              final QuotaReservation quotaReservation) {
        extractAndRecordTokenUsage(bodyStr, adapterType, instanceName, apiKeyId, quotaReservation, null);
    }

    /**
     * 从下游服务响应中提取 token 使用量并记录，同时完成配额结算；支持透传响应耗时（P1-4）。
     *
     * <p>{@code responseTimeMs} 落库到 token_usage 表，供观测中心平均响应时间统计使用；
     * 传 {@code null} 时该字段保持 NULL（历史行为,AVG 统计自动忽略）。</p>
     *
     * @param responseTimeMs 下游响应耗时毫秒数，可为 {@code null}
     */
    void extractAndRecordTokenUsage(final String bodyStr,
                                              final String adapterType,
                                              final String instanceName,
                                              final String apiKeyId,
                                              final QuotaReservation quotaReservation,
                                              final Long responseTimeMs) {
        try {
            JsonNode jsonNode = objectMapper.readTree(bodyStr);

            if (!jsonNode.has("usage")) {
                return;
            }

            JsonNode usage = jsonNode.get("usage");
            long promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : 0;
            long completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : 0;
            long totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asLong() : 0;

            if (totalTokens <= 0) {
                return;
            }

            String model = jsonNode.has("model") ? jsonNode.get("model").asText() : "unknown";

            // v2.9.0: 提取 KV 缓存命中/未命中 token 数
            // DeepSeek 形态: prompt_cache_hit_tokens / prompt_cache_miss_tokens
            // OpenAI/vLLM 形态: prompt_tokens_details.cached_tokens
            long cacheHitTokens = 0;
            long cacheMissTokens = 0;
            if (usage.has("prompt_cache_hit_tokens")) {
                cacheHitTokens = usage.get("prompt_cache_hit_tokens").asLong();
            }
            if (usage.has("prompt_cache_miss_tokens")) {
                cacheMissTokens = usage.get("prompt_cache_miss_tokens").asLong();
            }
            // OpenAI 形态: prompt_tokens_details.cached_tokens → 视为 cacheHit
            if (cacheHitTokens == 0 && usage.has("prompt_tokens_details")) {
                JsonNode details = usage.get("prompt_tokens_details");
                if (details.has("cached_tokens")) {
                    cacheHitTokens = details.get("cached_tokens").asLong();
                }
            }
            // 如果只有 cacheHit 无 cacheMiss，估算 miss = promptTokens - cacheHit
            if (cacheHitTokens > 0 && cacheMissTokens == 0) {
                cacheMissTokens = Math.max(0, promptTokens - cacheHitTokens);
            }

            // 记录 KV 缓存指标
            if (metricsCollector != null && (cacheHitTokens > 0 || cacheMissTokens > 0)) {
                metricsCollector.recordCacheTokenUsage(adapterType, instanceName,
                        cacheHitTokens, cacheMissTokens);
            }

            // 记录到 TokenUsage 表
            if (tokenUsageRecorder != null) {
                String traceId = TracingContextHolder.getCurrentTraceId();
                tokenUsageRecorder.recordTokenUsageNoAuth(
                        "CHAT",
                        model,
                        adapterType,
                        instanceName,
                        null,
                        promptTokens,
                        completionTokens,
                        totalTokens,
                        traceId,
                        null,
                        true,
                        null,
                        null,
                        responseTimeMs
                );
            }

            // 更新 API Key 的每日 Token 使用量配额
            updateApiKeyTokenUsage(apiKeyId, totalTokens);

            // v3.1 PR-2: 配额结算 — 按实际用量冲正预留（恰一次）
            QuotaReservation.settleSuccess(quotaReservation, totalTokens);

            logger.debug("Non-streaming token usage recorded: adapter={}, instance={}, model={}, total={}",
                    adapterType, instanceName, model, totalTokens);

        } catch (Exception e) {
            logger.debug("Failed to extract token usage from response: {}", e.getMessage());
        }
    }

    /**
     * 更新 API Key 的 Token 使用量
     */
    void updateApiKeyTokenUsage(final String apiKeyId, final long totalTokens) {
        if (apiKeyService == null || totalTokens <= 0 || apiKeyId == null) {
            return;
        }
        try {
            apiKeyService.updateTokenUsage(apiKeyId, totalTokens);
            logger.debug("API Key token usage updated: keyId={}, tokens={}", apiKeyId, totalTokens);
        } catch (Exception e) {
            logger.debug("Failed to update API Key token usage: keyId={}, error={}", apiKeyId, e.getMessage());
        }
    }

    /**
     * 从 ServerHttpRequest 属性中获取 API Key ID
     */
    String extractKeyIdFromRequest(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        Object keyId = httpRequest.getAttributes().get(
                org.unreal.modelrouter.router.handler.ServiceRequestHandler.API_KEY_ID_ATTRIBUTE);
        if (keyId instanceof String) {
            return (String) keyId;
        }
        return null;
    }

    /**
     * v2.8.9: 提取原始请求的 model 字段(chat/embedding 形态)
     */
    String extractRequestModel(final Object request) {
        if (request instanceof ChatDTO.Request r) {
            return r.model();
        }
        if (request instanceof EmbeddingDTO.Request e) {
            return e.model();
        }
        return null;
    }

    /**
     * v2.8.9: 池路由后,将目标中的 model 字段改写为实际实例名。
     * 兼容 Map 与强类型 DTO/record 两种载体;record 不可变,故 DTO 经 Jackson 转为可变 Map 后替换,
     * 此时必须返回新对象并由调用方接收。仅当原始请求 model 是池名时触发,不影响普通请求。
     *
     * @return 改写后的请求载体(可能为新建的 Map);未触发改写时返回原对象
     */
    @SuppressWarnings("unchecked")
    Object rewriteModelField(final Object target, final String requestedModel, final String instanceName) {
        if (target == null || instanceName == null || requestedModel == null
                || poolSelector == null || !poolSelector.isPoolName(requestedModel)
                || requestedModel.equals(instanceName)) {
            return target;
        }
        if (target instanceof ObjectNode) {
            // Jackson ObjectNode 可变(如 GpuStackRequestTransformer 产出的请求),原地 put
            ((ObjectNode) target).put("model", instanceName);
            return target;
        }
        if (target instanceof Map) {
            final Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) target);
            copy.put("model", instanceName);
            return copy;
        }
        // DTO / record:不可变,序列化到可变 Map 后替换 model,返回新对象
        try {
            final Map<String, Object> map = objectMapper.convertValue(target, LinkedHashMap.class);
            map.put("model", instanceName);
            return map;
        } catch (final Exception e) {
            logger.debug("池名 model 改写失败,保持原请求向下游转发: {}", e.getMessage());
            return target;
        }
    }
}
