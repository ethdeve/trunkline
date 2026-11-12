package org.unreal.modelrouter.router.circuitbreaker.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.unreal.modelrouter.common.cluster.ClusterEventBus;
import org.unreal.modelrouter.common.util.InstanceIdentity;
import org.unreal.modelrouter.common.util.WebSocketAdminGuard;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * 熔断器监控 WebSocket 处理器
 * 实时推送熔断器事件到前端
 *
 * @author JAiRouter Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerMonitorWebSocketHandler implements WebSocketHandler {

    /** 跨副本广播通道名（#164） */
    private static final String EVENT_CHANNEL = "circuit-breaker-monitor";

    private final CircuitBreakerMonitorService monitorService;
    private final ObjectMapper objectMapper;
    private final ClusterEventBus clusterEventBus;

    /** 监控事件：有界多播缓冲（256），溢出由 emit 失败路径丢弃，避免无界内存增长 */
    private final Sinks.Many<String> eventSink = Sinks.many().multicast()
            .onBackpressureBuffer(256, false);

    public void registerEventCallback() {
        monitorService.getEventRecorder().setEventCallback(event -> {
            try {
                String json = objectMapper.writeValueAsString(event);
                Sinks.EmitResult result = eventSink.tryEmitNext(json);
                if (result.isFailure()) {
                    log.debug("Failed to emit circuit breaker event: {}", result);
                }
                // 同时广播给其它副本（#164）；单副本部署下为无操作
                clusterEventBus.publish(EVENT_CHANNEL, json);
            } catch (Exception e) {
                log.warn("Failed to serialize circuit breaker event: {}", e.getMessage());
            }
        });
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("WebSocket client connected to circuit breaker monitor: {}", session.getId());

        registerEventCallback();

        Mono<String> initialState = Mono.fromCallable(() -> {
            try {
                InitialState state = new InitialState(
                        "connected",
                        monitorService.getStatusSummary()
                );
                return objectMapper.writeValueAsString(state);
            } catch (Exception e) {
                return "{\"type\":\"error\",\"message\":\"Failed to serialize initial state\"}";
            }
        });

        Flux<String> heartbeat = Flux.interval(Duration.ofSeconds(30))
                .map(seq -> "{\"type\":\"heartbeat\",\"seq\":" + seq + "}");

        // 事件流 = 本副本事件 + 其它副本广播来的事件（广播侧已过滤回环，不会重复）。
        // 两类事件都补出来源副本标识，前端/运维才能判断某条事件来自哪个副本（#181）。
        final Flux<String> localEvents = eventSink.asFlux()
                .map(json -> ClusterEventBus.withSourceInstance(
                        json, InstanceIdentity.id(), objectMapper));
        final Flux<String> remoteEvents = clusterEventBus.subscribe(EVENT_CHANNEL)
                .map(event -> ClusterEventBus.withSourceInstance(
                        event.payload(), event.sourceInstance(), objectMapper));
        Flux<String> eventStream = Flux.merge(localEvents, remoteEvents)
                .onBackpressureLatest();

        Flux<String> output = Flux.concat(
                initialState.flux(),
                Flux.merge(heartbeat, eventStream)
        );

        return session.send(
                output.map(session::textMessage)
        ).and(
                session.receive()
                        .doOnNext(message -> log.debug("Received from client: {}",
                                message.getPayloadAsText()))
                        // 【issue #916】控制消息必须串进响应式链做 ADMIN 校验；
                        // 绝不能 block() 握手 Principal（WebFlux event loop 会死锁）。
                        .flatMap(message -> handleClientMessage(message.getPayloadAsText(), session))
                        .doOnError(e -> log.warn("WebSocket error: {}", e.getMessage()))
                        .doOnTerminate(() -> log.info("WebSocket client disconnected: {}", session.getId()))
                        .then()
        );
    }

    /**
     * 处理客户端控制消息（#916）。
     *
     * <p>WS 握手不是 {@code @RequestMapping}，{@code @PreAuthorize} 拿不到；URL 闸门之外必须在
     * handler 内做第二道校验。pause / resume 会改变全局监控状态，属管理员操作，非 ADMIN 一律
     * 忽略并告警。包可见便于单测绕过握手直调反证。</p>
     */
    Mono<Void> handleClientMessage(final String payload, final WebSocketSession session) {
        return WebSocketAdminGuard.isAdmin(session).flatMap(admin -> {
            if (!admin) {
                log.warn("拒绝非 ADMIN 的 WS 控制消息: session={}",
                        session == null ? "n/a" : session.getId());
                return Mono.empty();
            }
            return Mono.fromRunnable(() -> applyControlMessage(payload)).then();
        });
    }

    /** 仅由已通过 ADMIN 校验的路径调用，保留原有控制消息语义。 */
    private void applyControlMessage(final String payload) {
        try {
            ClientMessage message = objectMapper.readValue(payload, ClientMessage.class);

            switch (message.type()) {
                case "pause" -> monitorService.pause();
                case "resume" -> monitorService.resume();
                default -> log.debug("Unknown message type: {}", message.type());
            }
        } catch (Exception e) {
            log.warn("Failed to parse client message: {}", e.getMessage());
        }
    }

    public record InitialState(
            String type,
            CircuitBreakerMonitorService.MonitorStatusSummary status
    ) {}

    public record ClientMessage(String type) {}
}
