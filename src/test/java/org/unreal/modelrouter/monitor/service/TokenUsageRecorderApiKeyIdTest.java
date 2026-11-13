package org.unreal.modelrouter.monitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #713: {@link TokenUsageRecorder} 计费归因（apiKeyId）回归测试。
 *
 * <p>缺陷背景：{@code TokenUsageRecordDTO.apiKeyId} 与落库列 {@code token_usage.api_key_id}
 * 早已存在，但 {@code recordTokenUsageNoAuth} 全链路构造 DTO 时从未写 {@code apiKeyId}，
 * 导致用量行无法归因到具体 API Key，「按 Key 统计」报表恒为空。</p>
 *
 * <p>修复语义：全参重载末尾追加 {@code apiKeyId} 并透传到 DTO；旧重载委托传 {@code null}
 * 保持兼容；控制台 JWT 登录态/匿名调用没有 API Key，{@code null} 是正确语义
 * （不得用用户名顶替）。</p>
 *
 * <p>被测对象是真实 {@link TokenUsageRecorder} + 手写存根 {@link CapturingTokenUsageService}
 * （与 {@code TokenUsageRecorderThreadingTest} 同款），不使用 Mockito；
 * 普通线程路径内联落库，断言确定性由「方法返回即已落库」契约保证。</p>
 *
 * @author JAiRouter Team
 */
@DisplayName("TokenUsageRecorder 计费归因 apiKeyId（issue #713）")
class TokenUsageRecorderApiKeyIdTest {

    /** 异步落库的等待上限（毫秒） */
    private static final long AWAIT_TIMEOUT_MILLIS = 5000L;

    /**
     * 手写存根：捕获 {@code recordTokenUsage} 收到的 DTO（不落库）。
     */
    private static final class CapturingTokenUsageService extends TokenUsageService {

        private final AtomicReference<TokenUsageRecordDTO> captured = new AtomicReference<>();
        private final CountDownLatch latch = new CountDownLatch(1);

        CapturingTokenUsageService() {
            super(null);
        }

        @Override
        public void recordTokenUsage(final TokenUsageRecordDTO record) {
            captured.set(record);
            latch.countDown();
        }
    }

    @Test
    @DisplayName("全参重载传 apiKeyId：DTO 必须带上（反证修复前恒 null）")
    void fullOverloadWithApiKeyId_shouldLandInDto() {
        final CapturingTokenUsageService service = new CapturingTokenUsageService();
        final TokenUsageRecorder recorder = new TokenUsageRecorder(service);

        // 17 参全量重载（普通线程内联落库，返回即已捕获）
        recorder.recordTokenUsageNoAuth(
                "CHAT", "gpt-4", "openai", "instance-1", null,
                10L, 20L, 30L, "trace-1", "1.2.3.4",
                true, null, null, null,
                "team-a", "holder", "key-1");

        assertEquals(0L, service.latch.getCount(), "普通线程路径应内联落库");
        final TokenUsageRecordDTO dto = service.captured.get();
        assertEquals("key-1", dto.getApiKeyId(), "issue #713: 用量行必须归因到命中的 API Key");
        assertEquals("holder", dto.getUserId(), "userId 归因不受本修复影响");
        assertEquals("team-a", dto.getVirtualModel(), "virtualModel 归因不受本修复影响");
    }

    @Test
    @DisplayName("旧 16 参重载（virtualModel+userId）委托：apiKeyId 为 null，兼容不变")
    void legacyOverloadWithUserId_shouldDelegateNullApiKeyId() {
        final CapturingTokenUsageService service = new CapturingTokenUsageService();
        final TokenUsageRecorder recorder = new TokenUsageRecorder(service);

        recorder.recordTokenUsageNoAuth(
                "CHAT", "gpt-4", "openai", "instance-1", null,
                10L, 20L, 30L, "trace-1", "1.2.3.4",
                true, null, null, null,
                "team-a", "holder");

        final TokenUsageRecordDTO dto = service.captured.get();
        assertEquals("holder", dto.getUserId(), "旧重载 userId 语义不变");
        assertNull(dto.getApiKeyId(), "旧重载未传 apiKeyId，委托后应保持 null");
    }

    @Test
    @DisplayName("旧 14 参重载（clientIp）委托：apiKeyId 为 null，兼容不变")
    void legacyOverloadWithClientIp_shouldDelegateNullApiKeyId() {
        final CapturingTokenUsageService service = new CapturingTokenUsageService();
        final TokenUsageRecorder recorder = new TokenUsageRecorder(service);

        recorder.recordTokenUsageNoAuth(
                "CHAT", "gpt-4", "openai", "instance-1", null,
                10L, 20L, 30L, "trace-1", "1.2.3.4",
                true, null, null, null);

        final TokenUsageRecordDTO dto = service.captured.get();
        assertEquals("1.2.3.4", dto.getClientIp(), "旧重载 clientIp 语义不变");
        assertNull(dto.getApiKeyId(), "旧重载未传 apiKeyId，委托后应保持 null");
    }

    @Test
    @DisplayName("非阻塞线程路径：apiKeyId 必须透传到 boundedElastic 落库载荷")
    void nonBlockingThreadPath_shouldThreadApiKeyIdThrough() throws Exception {
        final CapturingTokenUsageService service = new CapturingTokenUsageService();
        final TokenUsageRecorder recorder = new TokenUsageRecorder(service);

        final AtomicReference<Throwable> error = new AtomicReference<>();
        Mono.fromRunnable(() -> {
            try {
                recorder.recordTokenUsageNoAuth(
                        "CHAT", "gpt-4", "openai", "instance-1", null,
                        10L, 20L, 30L, "trace-1", null,
                        true, null, null, null,
                        null, "holder", "key-1");
            } catch (Throwable t) {
                error.set(t);
            }
        }).subscribeOn(Schedulers.parallel()).subscribe();

        assertTrue(service.latch.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                "boundedElastic 异步落库必须最终完成");
        assertNull(error.get(), "「绝不抛出」契约在非阻塞线程路径上成立");
        assertEquals("key-1", service.captured.get().getApiKeyId(),
                "issue #713: 异步分支不得丢失 apiKeyId");
    }

    @Test
    @DisplayName("无密钥调用（JWT/匿名）：apiKeyId 为 null 是正确语义，userId 照常落库")
    void noApiKey_shouldKeepNullApiKeyIdButRecordUserId() {
        final CapturingTokenUsageService service = new CapturingTokenUsageService();
        final TokenUsageRecorder recorder = new TokenUsageRecorder(service);

        recorder.recordTokenUsageNoAuth(
                "CHAT", "gpt-4", "openai", "instance-1", null,
                10L, 20L, 30L, "trace-1", null,
                true, null, null, null,
                null, "jwt:alice", null);

        final TokenUsageRecordDTO dto = service.captured.get();
        assertNull(dto.getApiKeyId(), "无密钥调用不得臆造 apiKeyId（用户名不是 Key ID）");
        assertEquals("jwt:alice", dto.getUserId(), "调用者身份照常落库");
    }
}
