package org.unreal.modelrouter.config.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * v2.9.9: 响应缓存配置属性.
 *
 * <p>配置 LLM 完整响应缓存（Response Cache）的各项参数。
 * 默认全部关闭/保守值，缓存为纯增量 opt-in 能力，不改变任何默认行为。
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Data
@ConfigurationProperties(prefix = "trunkline.response-cache")
public final class ResponseCacheProperties {

    /**
     * 是否启用响应缓存（默认关闭，opt-in）
     */
    private boolean enabled = false;

    /**
     * 缓存条目有效期（默认 1h，对齐 liteLLM 默认）
     */
    private Duration ttl = Duration.ofHours(1);

    /**
     * 缓存最大条目数（默认 10000）
     */
    private int maxSize = 10000;

    /**
     * 仅缓存确定性请求（chat: temperature 显式为 0 且 n==1/null；默认 true）
     *
     * <p>issue #723：未指定 temperature（跟随下游默认，通常非 0 贪心）不计入确定性。
     * 修复前 null 与 0 同判为确定性，OpenAI 客户端普遍不传 temperature，
     * 会把「跟随下游默认（非贪心）」的随机响应缓存下来被他人命中。</p>
     */
    private boolean onlyDeterministic = true;

    /**
     * temperature=null 是否视为确定性（issue #723，默认 false）
     *
     * <p>定案口径：判定仅「显式 0」为确定性。下游固定贪心解码的自建实例
     * （temperature 配置恒等价于 0）可按需开启本开关恢复旧口径——null 视为确定性、
     * 可缓存。关闭时 null 一律不缓存（命中率下降是正确性优先的预期代价）。</p>
     */
    private boolean cacheNullTemperature = false;

    /**
     * 跳过流式请求（默认 true；P0 仅支持非流式缓存）
     */
    private boolean skipStreaming = true;
}
