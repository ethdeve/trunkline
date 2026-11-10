package org.unreal.modelrouter.router.cache;

import java.util.List;

/**
 * v2.9.10: 流式 SSE 缓存值.
 *
 * <p>缓存完整的流式 SSE 响应，用于命中时的逐块回放。
 * 存储 {@code transformStreamChunk} 后的出站 data 串列表（不含 {@code data: } 前缀），
 * 回放时逐块包装为 {@code ServerSentEvent} 重新 SSE 序列化，保证字节级一致。
 *
 * <p>chunks 列表包含全部变换后块（含 {@code [DONE]} 标记），
 * 回放时按序发射即可还原完整 SSE 流。
 *
 * @param chunks         变换后的 SSE data 串列表（含 {@code [DONE]}）
 * @param model          模型名称
 * @param promptTokens   prompt token 使用量（后端未提供时为 null）
 * @param completionTokens completion token 使用量（后端未提供时为 null）
 * @param totalTokens    total token 使用量（后端未提供时为 null）
 * @param finishReason   完成原因（如 {@code stop}、{@code length}，未获取时为 null）
 * @author JAiRouter Team
 */
public record CachedStreamingResponse(
        List<String> chunks,
        String model,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        String finishReason
) {
}
