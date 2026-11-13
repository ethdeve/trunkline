package org.unreal.modelrouter.router.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #892：BaseAdapter 写入桥的流式判定契约（static 方法，无需容器/子类实例）。
 *
 * <p>流式响应（Publisher body 或 SSE contentType）是一次性流，写入降级缓存后
 * 重放会得到空/报错/串味的内容，必须被过滤。</p>
 */
@DisplayName("BaseAdapter.isStreamingResponse 契约（#892 流式不写降级缓存）")
class BaseAdapterStreamingBodyTest {

    @Test
    @DisplayName("null 响应不是流式")
    void nullResponseIsNotStreaming() {
        assertFalse(BaseAdapter.isStreamingResponse(null));
    }

    @Test
    @DisplayName("Flux body 是流式（所有 serviceType 的流式路径）")
    void fluxBodyIsStreaming() {
        assertTrue(BaseAdapter.isStreamingResponse(ResponseEntity.ok(Flux.just("a", "b"))));
    }

    @Test
    @DisplayName("Mono body 是流式")
    void monoBodyIsStreaming() {
        assertTrue(BaseAdapter.isStreamingResponse(ResponseEntity.ok(Mono.just("a"))));
    }

    @Test
    @DisplayName("普通 body 不是流式（非流式写入回归）")
    void plainBodyIsNotStreaming() {
        assertFalse(BaseAdapter.isStreamingResponse(ResponseEntity.ok("text")));
    }

    @Test
    @DisplayName("SSE contentType 是流式（body 判空后按 contentType 兜底）")
    void sseContentTypeIsStreaming() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        assertTrue(BaseAdapter.isStreamingResponse(new ResponseEntity<>("body", headers, 200)));
    }
}
