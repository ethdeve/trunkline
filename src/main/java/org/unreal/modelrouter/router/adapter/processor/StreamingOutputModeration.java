/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.ModerationInput;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.OutputChunk;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.OutputDecision;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.OutputSession;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.OutputVerdict;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * B5-out：输出侧（流式增量）审核算子。
 *
 * <p><b>挂载位置</b>：{@code StreamingRequestProcessor.decorateChunks} 的<b>原始 chunk 层</b>
 * （{@code Flux<String>}，下游统一为 OpenAI 风格的 SSE 数据块）。放在 SSE 包装之前，
 * 一处即可覆盖 /v1、legacy /api 以及 Anthropic translator 的下游链路，且拦截时违规帧
 * 连 {@code .map} 都到不了——缓存收集、内容累积、调用历史均不会接触到违规文本。</p>
 *
 * <p><b>先审后发</b>：会话返回 {@link OutputVerdict#HOLD} 的帧会暂存在本算子内，
 * 直到后续某个帧判为 ALLOW 时按原序一并下发（防止敏感词被 SSE 切分到两个 chunk 而漏放）。
 * ALLOW 的语义是「此前暂存的文本已确认安全，可以全部发出」。{@link OutputVerdict#BLOCK}
 * 时丢弃暂存帧、以 {@link OutputModerationBlockedException} 终止并 cancel 上游。</p>
 *
 * <p><b>社区版零行为变化</b>：{@code provider == null} 直接返回原 Flux；
 * {@link ModerationProvider#openOutputSession} 是 default 方法，Noop / 旧企业 jar 返回
 * {@code Optional.empty()}，主干不做任何 JSON 解析、拷贝或额外调度。</p>
 *
 * <p><b>日志脱敏</b>：本类只记录异常类名与帧数，绝不打印 chunk 内容、待审文本或命中词。</p>
 */
final class StreamingOutputModeration {

    private static final Logger logger = LoggerFactory.getLogger(StreamingOutputModeration.class);

    /** 暂存帧上限：防止会话实现长时间不给出判定导致流式退化为非流式 / 内存增长，超限按 fail-closed 拦截。 */
    static final int MAX_PENDING_CHUNKS = 256;

    private StreamingOutputModeration() {
    }

    /**
     * 给原始 chunk 流套上输出侧审核。
     *
     * @param source         上游原始 chunk 流（SSE 文本块，未包装成 ServerSentEvent）
     * @param provider       内容审核扩展点（可为 {@code null}）
     * @param input          本次请求的模型 / 调用者 / 租户信息
     * @param objectMapper   用于提取增量文本的 JSON 解析器（复用主链实例）
     * @return 审核后的原始 chunk 流；未启用输出侧审核时逐字节返回 {@code source}
     */
    static Flux<String> apply(final Flux<String> source,
                              final ModerationProvider provider,
                              final ModerationInput input,
                              final ObjectMapper objectMapper) {
        if (provider == null || source == null) {
            return source;
        }
        // 每次订阅新建会话（重试 / 重订阅不能复用上一次的文本缓冲）。
        return Flux.defer(() -> {
            final Optional<OutputSession> opened;
            try {
                opened = provider.openOutputSession(input);
            } catch (Exception e) {
                // 企业侧扩展点异常一律 fail-closed：不让未审核内容外发。
                logger.warn("输出侧审核会话开启失败，fail-closed 终止: {}", e.getClass().getSimpleName());
                return Flux.error(new OutputModerationBlockedException(null));
            }
            if (opened == null || opened.isEmpty()) {
                return source;
            }
            return moderate(source, opened.get(), objectMapper);
        });
    }

    private static Flux<String> moderate(final Flux<String> source,
                                         final OutputSession session,
                                         final ObjectMapper objectMapper) {
        final List<String> pending = new ArrayList<>();
        final AtomicBoolean terminated = new AtomicBoolean(false);

        return source
                .concatMap(raw -> {
                    if (terminated.get()) {
                        return Flux.empty();
                    }
                    final OutputChunk chunk = extractChunk(raw, objectMapper);
                    final OutputDecision decision = safeCheck(session, chunk);
                    if (decision.verdict() == OutputVerdict.HOLD) {
                        pending.add(raw);
                        if (pending.size() > MAX_PENDING_CHUNKS) {
                            logger.warn("输出侧审核暂存帧超出上限({}), fail-closed 终止", MAX_PENDING_CHUNKS);
                            terminated.set(true);
                            pending.clear();
                            return Flux.error(new OutputModerationBlockedException(null));
                        }
                        return Flux.empty();
                    }
                    if (decision.verdict() == OutputVerdict.BLOCK) {
                        terminated.set(true);
                        pending.clear();
                        return Flux.error(new OutputModerationBlockedException(decision.reason()));
                    }
                    // ALLOW：先按原序冲刷暂存帧，再下发当前帧
                    final List<String> out = new ArrayList<>(pending.size() + 1);
                    out.addAll(pending);
                    pending.clear();
                    out.add(raw);
                    return Flux.fromIterable(out);
                })
                .concatWith(Flux.defer(() -> {
                    if (terminated.get()) {
                        return Flux.empty();
                    }
                    final OutputDecision decision = safeFinish(session);
                    if (decision.verdict() == OutputVerdict.BLOCK) {
                        pending.clear();
                        return Flux.error(new OutputModerationBlockedException(decision.reason()));
                    }
                    if (pending.isEmpty()) {
                        return Flux.empty();
                    }
                    // finish 判 ALLOW：冲刷尾部尚未下发的帧（顺序不变）
                    final List<String> out = List.copyOf(pending);
                    pending.clear();
                    return Flux.fromIterable(out);
                }));
    }

    private static OutputDecision safeCheck(final OutputSession session, final OutputChunk chunk) {
        try {
            final OutputDecision decision = session.checkChunk(chunk);
            if (decision == null || decision.verdict() == null) {
                logger.warn("输出侧审核会话返回空判定，fail-closed 终止");
                return OutputDecision.block(null);
            }
            return decision;
        } catch (Exception e) {
            logger.warn("输出侧审核会话判定异常，fail-closed 终止: {}", e.getClass().getSimpleName());
            return OutputDecision.block(null);
        }
    }

    private static OutputDecision safeFinish(final OutputSession session) {
        try {
            final OutputDecision decision = session.finish();
            // finish 阶段不能再 HOLD：没有后续帧可等待，一律按 fail-closed 处理。
            if (decision == null || decision.verdict() == null
                    || decision.verdict() == OutputVerdict.HOLD) {
                logger.warn("输出侧审核会话 finish 未给出终局判定，fail-closed 终止");
                return OutputDecision.block(null);
            }
            return decision;
        } catch (Exception e) {
            logger.warn("输出侧审核会话 finish 异常，fail-closed 终止: {}", e.getClass().getSimpleName());
            return OutputDecision.block(null);
        }
    }

    /**
     * 从原始 SSE 文本块提取「可审增量文本 + 终止标记」。
     *
     * <p>下游统一为 OpenAI 风格（{@code choices[0].delta.content}）；同时兼容
     * Anthropic 事件（{@code delta.text} / {@code partial_json}）、老 completion 形态
     * （{@code choices[0].text} / {@code completion}）与多行 {@code event:} 块。
     * 解析失败（非 JSON 的注释行、心跳行等）视为「无可审文本」，交给会话决定
     * 是暂存放行还是冲刷——主干不擅自放行，避免漏审。</p>
     */
    static OutputChunk extractChunk(final String raw, final ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return OutputChunk.of(null);
        }
        String payload = null;
        boolean sawDone = false;
        boolean sawMessageStop = false;
        boolean multiline = false;
        for (final String line : raw.split("\\r?\\n")) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("data:") && !trimmed.startsWith("event:")) {
                continue;
            }
            multiline = true;
            if (trimmed.startsWith("event:")) {
                if ("message_stop".equals(trimmed.substring(6).trim())) {
                    sawMessageStop = true;
                }
                continue;
            }
            final String data = trimmed.substring(5).trim();
            if ("[DONE]".equals(data)) {
                sawDone = true;
            } else if (!data.isEmpty() && payload == null) {
                payload = data;
            }
        }
        if (!multiline) {
            // 主链既有形态：整块就是一个 JSON 对象，或 "data: {...}" 单行
            String single = raw.trim();
            if (single.startsWith("data:")) {
                single = single.substring(5).trim();
            }
            if ("[DONE]".equals(single)) {
                return OutputChunk.terminalFrame();
            }
            payload = single.isEmpty() ? null : single;
        }
        if (sawDone || sawMessageStop) {
            return OutputChunk.terminalFrame();
        }
        if (payload == null || payload.isEmpty() || payload.charAt(0) != '{') {
            return OutputChunk.of(null);
        }
        try {
            final JsonNode root = objectMapper.readTree(payload);
            final JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                final JsonNode choice = choices.get(0);
                final JsonNode delta = choice.path("delta");
                String text = textOf(delta.get("content"));
                if (text == null) {
                    text = textOf(choice.get("text"));
                }
                return new OutputChunk(text, isPresent(choice.get("finish_reason")));
            }
            final JsonNode delta = root.path("delta");
            String text = textOf(delta.get("text"));
            if (text == null) {
                text = textOf(delta.get("partial_json"));
            }
            if (text == null) {
                text = textOf(root.get("completion"));
            }
            final boolean terminal = "message_stop".equals(root.path("type").asText(""))
                    || isPresent(delta.get("stop_reason"));
            return new OutputChunk(text, terminal);
        } catch (Exception e) {
            // 注释行 / 心跳 / 非 JSON 帧：不记录内容，交会话决定
            return OutputChunk.of(null);
        }
    }

    /** 兼容字符串与分片数组两种 delta 形态（与 AnthropicStreamingTranslator.textOf 同口径）。 */
    private static String textOf(final JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            final StringBuilder sb = new StringBuilder();
            for (final JsonNode item : node) {
                if (item != null && item.isTextual()) {
                    sb.append(item.asText());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    private static boolean isPresent(final JsonNode node) {
        return node != null && !node.isNull();
    }
}
