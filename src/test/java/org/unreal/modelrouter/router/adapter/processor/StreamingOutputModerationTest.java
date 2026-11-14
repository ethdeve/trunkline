/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.ModerationDecision;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.ModerationInput;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.OutputChunk;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.OutputDecision;
import org.unreal.modelrouter.enterprise.spi.ModerationProvider.OutputSession;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5-out：输出侧增量审核算子单测。
 *
 * <p>覆盖社区版零行为变化、先审后发的三种判定、fail-closed、尾部冲刷与
 * SSE 增量文本提取（OpenAI / Anthropic / 终止帧 / 心跳行）。</p>
 */
@DisplayName("B5-out 流式增量审核算子")
class StreamingOutputModerationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ModerationInput INPUT = new ModerationInput("gpt", "", "alice", "tenant-a");

    private static ModerationProvider providerOf(final OutputSession session) {
        return new ModerationProvider() {
            @Override
            public ModerationDecision check(final ModerationInput input) {
                return ModerationDecision.allow();
            }

            @Override
            public Optional<OutputSession> openOutputSession(final ModerationInput input) {
                return Optional.of(session);
            }
        };
    }

    /** 可编排的会话桩：按帧序给出判定，finish 可自定义。 */
    private static final class StubSession implements OutputSession {
        private final Function<OutputChunk, OutputDecision> policy;
        private final OutputDecision finishDecision;
        private final AtomicInteger calls = new AtomicInteger();

        private StubSession(final Function<OutputChunk, OutputDecision> policy,
                            final OutputDecision finishDecision) {
            this.policy = policy;
            this.finishDecision = finishDecision;
        }

        @Override
        public OutputDecision checkChunk(final OutputChunk chunk) {
            return policy.apply(chunk);
        }

        @Override
        public OutputDecision finish() {
            calls.incrementAndGet();
            return finishDecision;
        }
    }

    @Test
    @DisplayName("provider 为 null：原样返回同一个 Flux（零解析、零拷贝）")
    void nullProviderReturnsOriginalFlux() {
        final Flux<String> source = Flux.just("a", "b");
        assertThat(StreamingOutputModeration.apply(source, null, INPUT, MAPPER)).isSameAs(source);
    }

    @Test
    @DisplayName("Noop / 旧企业 jar 返回空会话：逐字节原样下发，不创建会话")
    void emptySessionReturnsOriginalFlux() {
        final AtomicInteger opened = new AtomicInteger();
        final ModerationProvider provider = new ModerationProvider() {
            @Override
            public ModerationDecision check(final ModerationInput input) {
                return ModerationDecision.allow();
            }

            @Override
            public Optional<OutputSession> openOutputSession(final ModerationInput input) {
                opened.incrementAndGet();
                return Optional.empty();
            }
        };
        StepVerifier.create(StreamingOutputModeration.apply(Flux.just("a", "b", "[DONE]"), provider, INPUT, MAPPER))
                .expectNext("a", "b", "[DONE]")
                .verifyComplete();
        assertThat(opened.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("全 ALLOW：帧内容与顺序不变")
    void allAllowKeepsOrder() {
        final StubSession session = new StubSession(chunk -> OutputDecision.allow(), OutputDecision.allow());
        StepVerifier.create(StreamingOutputModeration.apply(Flux.just("a", "b", "c"), providerOf(session), INPUT, MAPPER))
                .expectNext("a", "b", "c")
                .verifyComplete();
        assertThat(session.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("HOLD 暂存：后续 ALLOW 按原序一并冲刷（防跨块切词漏放）")
    void holdThenAllowFlushesPendingInOriginalOrder() {
        final AtomicInteger n = new AtomicInteger();
        final StubSession session = new StubSession(
                chunk -> n.incrementAndGet() <= 2 ? OutputDecision.hold() : OutputDecision.allow(),
                OutputDecision.allow());
        StepVerifier.create(StreamingOutputModeration.apply(
                        Flux.just("a", "b", "c", "d"), providerOf(session), INPUT, MAPPER))
                .expectNext("a", "b", "c")
                .expectNext("d")
                .verifyComplete();
    }

    @Test
    @DisplayName("全部 HOLD 时由 finish 冲刷尾部残留帧")
    void finishFlushesTrailingPending() {
        final StubSession session = new StubSession(chunk -> OutputDecision.hold(), OutputDecision.allow());
        StepVerifier.create(StreamingOutputModeration.apply(
                        Flux.just("a", "b"), providerOf(session), INPUT, MAPPER))
                .expectNext("a", "b")
                .verifyComplete();
        assertThat(session.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BLOCK：丢弃暂存帧、零帧下发、以 CONTENT_BLOCKED 终止且不调用 finish")
    void blockDropsPendingAndTerminates() {
        final AtomicInteger n = new AtomicInteger();
        final StubSession session = new StubSession(
                chunk -> n.incrementAndGet() == 1 ? OutputDecision.hold() : OutputDecision.block("固定文案"),
                OutputDecision.allow());
        StepVerifier.create(StreamingOutputModeration.apply(
                        Flux.just("a", "b", "c"), providerOf(session), INPUT, MAPPER))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(OutputModerationBlockedException.class);
                    assertThat(err.getMessage()).isEqualTo("固定文案");
                    assertThat(err.getMessage()).doesNotContain("a", "b", "c");
                })
                .verify();
        assertThat(session.calls.get()).isZero();
    }

    @Test
    @DisplayName("BLOCK 前已 ALLOW 的帧保留，后续暂存帧不再下发")
    void blockKeepsOnlySafePrefix() {
        final AtomicInteger n = new AtomicInteger();
        final StubSession session = new StubSession(chunk -> {
            final int i = n.incrementAndGet();
            return switch (i) {
                case 1 -> OutputDecision.allow();
                case 2 -> OutputDecision.hold();
                default -> OutputDecision.block(null);
            };
        }, OutputDecision.allow());
        StepVerifier.create(StreamingOutputModeration.apply(
                        Flux.just("a", "b", "c"), providerOf(session), INPUT, MAPPER))
                .expectNext("a")
                .expectError(OutputModerationBlockedException.class)
                .verify();
    }

    @Test
    @DisplayName("openOutputSession 抛异常 → fail-closed 终止（不让未审核内容外发）")
    void providerThrowsFailsClosed() {
        final ModerationProvider provider = new ModerationProvider() {
            @Override
            public ModerationDecision check(final ModerationInput input) {
                return ModerationDecision.allow();
            }

            @Override
            public Optional<OutputSession> openOutputSession(final ModerationInput input) {
                throw new IllegalStateException("engine down");
            }
        };
        StepVerifier.create(StreamingOutputModeration.apply(Flux.just("a"), provider, INPUT, MAPPER))
                .expectError(OutputModerationBlockedException.class)
                .verify();
    }

    @Test
    @DisplayName("会话判定抛异常 / 返回 null → fail-closed 终止")
    void sessionFailureFailsClosed() {
        final ModerationProvider throwing = providerOf(new OutputSession() {
            @Override
            public OutputDecision checkChunk(final OutputChunk chunk) {
                throw new IllegalStateException("secret text must not leak");
            }

            @Override
            public OutputDecision finish() {
                return OutputDecision.allow();
            }
        });
        StepVerifier.create(StreamingOutputModeration.apply(Flux.just("a"), throwing, INPUT, MAPPER))
                .expectError(OutputModerationBlockedException.class)
                .verify();

        final ModerationProvider nullDecision = providerOf(new OutputSession() {
            @Override
            public OutputDecision checkChunk(final OutputChunk chunk) {
                return null;
            }

            @Override
            public OutputDecision finish() {
                return OutputDecision.allow();
            }
        });
        StepVerifier.create(StreamingOutputModeration.apply(Flux.just("a"), nullDecision, INPUT, MAPPER))
                .expectError(OutputModerationBlockedException.class)
                .verify();
    }

    @Test
    @DisplayName("finish 返回 HOLD → 无后续帧可等待，按 fail-closed 终止")
    void finishHoldFailsClosed() {
        final StubSession session = new StubSession(chunk -> OutputDecision.hold(), OutputDecision.hold());
        StepVerifier.create(StreamingOutputModeration.apply(Flux.just("a"), providerOf(session), INPUT, MAPPER))
                .expectError(OutputModerationBlockedException.class)
                .verify();
    }

    @Test
    @DisplayName("暂存帧超上限 → fail-closed 终止（防止流式退化为非流式 / 内存增长）")
    void pendingOverLimitFailsClosed() {
        final Flux<String> source = Flux.range(0, StreamingOutputModeration.MAX_PENDING_CHUNKS + 2)
                .map(i -> "chunk-" + i);
        final StubSession session = new StubSession(chunk -> OutputDecision.hold(), OutputDecision.allow());
        StepVerifier.create(StreamingOutputModeration.apply(source, providerOf(session), INPUT, MAPPER))
                .expectError(OutputModerationBlockedException.class)
                .verify();
    }

    @Test
    @DisplayName("extractChunk：OpenAI delta / [DONE] / finish_reason")
    void extractOpenAiShapes() {
        final OutputChunk delta = StreamingOutputModeration.extractChunk(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}", MAPPER);
        assertThat(delta.delta()).isEqualTo("你好");
        assertThat(delta.terminal()).isFalse();

        final OutputChunk done = StreamingOutputModeration.extractChunk("data: [DONE]", MAPPER);
        assertThat(done.delta()).isNull();
        assertThat(done.terminal()).isTrue();

        final OutputChunk finish = StreamingOutputModeration.extractChunk(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", MAPPER);
        assertThat(finish.delta()).isNull();
        assertThat(finish.terminal()).isTrue();
    }

    @Test
    @DisplayName("extractChunk：Anthropic 事件行 / message_stop / 心跳行 / 分片数组")
    void extractAnthropicAndNoiseShapes() {
        final OutputChunk textDelta = StreamingOutputModeration.extractChunk(
                "event: content_block_delta\ndata: {\"delta\":{\"text\":\"abc\"}}", MAPPER);
        assertThat(textDelta.delta()).isEqualTo("abc");

        final OutputChunk messageStop = StreamingOutputModeration.extractChunk(
                "event: message_stop\ndata: {\"type\":\"message_stop\"}", MAPPER);
        assertThat(messageStop.terminal()).isTrue();

        final OutputChunk ping = StreamingOutputModeration.extractChunk(": ping", MAPPER);
        assertThat(ping.delta()).isNull();
        assertThat(ping.terminal()).isFalse();

        final OutputChunk arrayDelta = StreamingOutputModeration.extractChunk(
                "data: {\"choices\":[{\"delta\":{\"content\":[\"a\",\"b\"]}}]}", MAPPER);
        assertThat(arrayDelta.delta()).isEqualTo("ab");
    }

    @Test
    @DisplayName("拦截原因超长 / 含控制字符一律回落固定脱敏文案")
    void blockedReasonIsSanitized() {
        final OutputModerationBlockedException tooLong =
                new OutputModerationBlockedException("x".repeat(200));
        assertThat(tooLong.getMessage()).isEqualTo(OutputModerationBlockedException.DEFAULT_REASON);
        final OutputModerationBlockedException withControl =
                new OutputModerationBlockedException("secret\npassword");
        assertThat(withControl.getMessage()).isEqualTo(OutputModerationBlockedException.DEFAULT_REASON);
        assertThat(new OutputModerationBlockedException(null).reason())
                .isEqualTo(OutputModerationBlockedException.DEFAULT_REASON);
    }

    @Test
    @DisplayName("会话按订阅新建：重订阅不复用上一次的文本缓冲")
    void sessionIsPerSubscription() {
        final AtomicInteger opened = new AtomicInteger();
        final ModerationProvider provider = new ModerationProvider() {
            @Override
            public ModerationDecision check(final ModerationInput input) {
                return ModerationDecision.allow();
            }

            @Override
            public Optional<OutputSession> openOutputSession(final ModerationInput input) {
                opened.incrementAndGet();
                return Optional.of(new StubSession(c -> OutputDecision.allow(), OutputDecision.allow()));
            }
        };
        final Flux<String> moderated = StreamingOutputModeration.apply(Flux.just("a"), provider, INPUT, MAPPER);
        moderated.blockLast();
        moderated.blockLast();
        assertThat(opened.get()).isEqualTo(2);
    }
}
