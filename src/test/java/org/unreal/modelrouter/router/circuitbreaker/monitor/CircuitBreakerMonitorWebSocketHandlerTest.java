/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.circuitbreaker.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.unreal.modelrouter.common.cluster.ClusterEventBus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【issue #916】熔断器监控 WS 控制消息垂直越权反证。
 *
 * <p>与 {@code RoutingMonitorWebSocketHandlerTest} 同口径：URL 闸门之外，handler 内必须有
 * 第二道 ADMIN 校验；绕过握手直调 {@code handleClientMessage} 也必须被拒。</p>
 */
@DisplayName("#916 熔断器监控 WS 控制消息 ADMIN 校验")
class CircuitBreakerMonitorWebSocketHandlerTest {

    private CircuitBreakerMonitorService monitorService;
    private CircuitBreakerMonitorWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        monitorService = mock(CircuitBreakerMonitorService.class);
        handler = new CircuitBreakerMonitorWebSocketHandler(
                monitorService, new ObjectMapper(), mock(ClusterEventBus.class));
    }

    /** role 为 null 表示匿名（握手 Principal 为空）。 */
    private static WebSocketSession sessionWithRole(final String role) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo info = mock(HandshakeInfo.class);
        Authentication auth = mock(Authentication.class);
        Collection<GrantedAuthority> authorities = role == null
                ? List.of() : List.of(new SimpleGrantedAuthority(role));
        doReturn(authorities).when(auth).getAuthorities();

        Mono<Principal> principal = role == null
                ? Mono.empty() : Mono.<Principal>just(auth);
        when(info.getPrincipal()).thenReturn(principal);
        when(session.getHandshakeInfo()).thenReturn(info);
        return session;
    }

    @Test
    @DisplayName("ADMIN 发 pause → 全局暂停生效")
    void adminPauseApplied() {
        StepVerifier.create(handler.handleClientMessage(
                "{\"type\":\"pause\"}", sessionWithRole("ROLE_ADMIN"))).verifyComplete();
        verify(monitorService).pause();
    }

    @Test
    @DisplayName("ADMIN 发 resume → 全局恢复生效")
    void adminResumeApplied() {
        StepVerifier.create(handler.handleClientMessage(
                "{\"type\":\"resume\"}", sessionWithRole("ROLE_ADMIN"))).verifyComplete();
        verify(monitorService).resume();
    }

    @Test
    @DisplayName("反证：普通 USER 直调控制消息不得触发 pause/resume")
    void userControlMessagesRejected() {
        WebSocketSession session = sessionWithRole("ROLE_USER");
        StepVerifier.create(handler.handleClientMessage("{\"type\":\"pause\"}", session))
                .verifyComplete();
        StepVerifier.create(handler.handleClientMessage("{\"type\":\"resume\"}", session))
                .verifyComplete();

        verify(monitorService, never()).pause();
        verify(monitorService, never()).resume();
    }

    @Test
    @DisplayName("反证：绕过握手（无 Principal）直调仍被拒")
    void anonymousBypassRejected() {
        StepVerifier.create(handler.handleClientMessage(
                "{\"type\":\"pause\"}", sessionWithRole(null))).verifyComplete();
        verify(monitorService, never()).pause();
    }

    @Test
    @DisplayName("反证：session 为 null / 握手信息异常时 fail-closed，且不抛异常")
    void missingSessionFailsClosed() {
        StepVerifier.create(handler.handleClientMessage("{\"type\":\"pause\"}", null))
                .verifyComplete();
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.getHandshakeInfo()).thenThrow(new IllegalStateException("no handshake"));
        StepVerifier.create(handler.handleClientMessage("{\"type\":\"pause\"}", broken))
                .verifyComplete();
        verify(monitorService, never()).pause();
    }

    @Test
    @DisplayName("ADMIN 发非法 JSON / 未知类型不得改变监控状态")
    void malformedAndUnknownMessagesAreHarmless() {
        WebSocketSession session = sessionWithRole("ROLE_ADMIN");
        StepVerifier.create(handler.handleClientMessage("not-a-json", session))
                .verifyComplete();
        StepVerifier.create(handler.handleClientMessage("{\"type\":\"shutdown\"}", session))
                .verifyComplete();

        verify(monitorService, never()).pause();
        verify(monitorService, never()).resume();
    }
}
