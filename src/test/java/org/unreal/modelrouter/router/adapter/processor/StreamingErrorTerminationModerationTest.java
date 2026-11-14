/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5-out：输出侧审核拦截必须沿用流式终止语义——/v1 OpenAI 面补可解析的
 * CONTENT_BLOCKED error 帧、<b>不补 [DONE]</b>、仍以原异常终止；
 * Anthropic 面与 legacy 面沿用各自既有语义（不补帧）。
 */
@DisplayName("B5-out 拦截的流式终止语义")
class StreamingErrorTerminationModerationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ServerHttpRequest request(final String path) {
        return MockServerHttpRequest.post(path).build();
    }

    private static Flux<ServerSentEvent<String>> terminate(final String path) {
        return StreamingErrorTermination.terminate(
                new OutputModerationBlockedException("输出内容未通过审核"), request(path), MAPPER);
    }

    @Test
    @DisplayName("/v1 OpenAI 面：补 CONTENT_BLOCKED / 400 错误帧，且不补 [DONE]")
    void openAiFaceCarriesContentBlockedCode() {
        StepVerifier.create(terminate("/v1/chat/completions"))
                .assertNext(sse -> {
                    assertThat(sse.data()).contains("CONTENT_BLOCKED");
                    assertThat(sse.data()).contains("invalid_request_error");
                    assertThat(sse.data()).contains("输出内容未通过审核");
                    assertThat(sse.data()).doesNotContain("[DONE]");
                })
                .expectError(OutputModerationBlockedException.class)
                .verify();
    }

    @Test
    @DisplayName("错误帧仍以原异常终止（doOnComplete 不触发 → 不写缓存 / 不记成功）")
    void terminatesWithErrorSignal() {
        StepVerifier.create(terminate("/v1/chat/completions"))
                .expectNextCount(1)
                .expectError(OutputModerationBlockedException.class)
                .verify();
    }

    @Test
    @DisplayName("Anthropic 面与 legacy 面：沿用既有语义，不注入 OpenAI 形状错误帧")
    void nonOpenAiFacesDoNotInjectFrame() {
        StepVerifier.create(terminate("/v1/messages"))
                .expectError(OutputModerationBlockedException.class)
                .verify();
        StepVerifier.create(terminate("/api/chat/stream"))
                .expectError(OutputModerationBlockedException.class)
                .verify();
    }
}
