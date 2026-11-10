package org.unreal.modelrouter.config.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 流式累积上界与上游空闲看门狗配置（#126, #127）。
 *
 * <p>目标：在不截断合法长流的前提下，为「累积内容 / 流式缓存 / 上游空闲」三处
 * 提供可配置的宽松上界，默认值对正常部署透明（低于上界的响应行为逐字节不变）。
 *
 * <ul>
 *   <li>{@code max-content-chars}：单次流式响应累积内容（contentBuilder、
 *       Anthropic 翻译器 text/toolArguments）的字符上限。超出后保留前缀并显式标记截断；
 *       token 记账/配额结算不读该缓冲（独立计数），截断不影响账单。</li>
 *   <li>{@code max-cache-chars}：流式缓存 chunks 总字符预算。超过预算的响应
 *       <b>不写缓存</b>（缓存是优化，不是正确性）。</li>
 *   <li>{@code idle-timeout}：上游 SSE <b>块间</b>空闲超时（非总时长）。
 *       超时后向下游发出终止错误并向上游 cancel。{@code PT0S} 表示禁用。</li>
 * </ul>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Data
@ConfigurationProperties(prefix = "trunkline.streaming")
public final class StreamingSafetyProperties {

    /**
     * 累积内容字符上限（默认 1Mi chars，宽松；正常 LLM 响应远低于此）。
     */
    private int maxContentChars = 1_048_576;

    /**
     * 流式缓存 chunks 总字符预算（默认 256Ki chars）。超出则整条流不缓存。
     */
    private int maxCacheChars = 262_144;

    /**
     * 上游 SSE 块间空闲超时（默认 5 分钟，宽松以兼容长思考间隔）。
     * {@code PT0S} / 负值表示禁用看门狗。
     */
    private Duration idleTimeout = Duration.ofMinutes(5);
}
