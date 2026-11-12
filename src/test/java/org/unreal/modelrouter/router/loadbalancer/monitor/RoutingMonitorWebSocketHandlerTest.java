package org.unreal.modelrouter.router.loadbalancer.monitor;

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
 * 【issue #916】WS 控制消息垂直越权反证：绕过握手直调也必须校验 ROLE_ADMIN。
 */
@DisplayName("#916 路由监控 WS 控制消息 ADMIN 校验")
class RoutingMonitorWebSocketHandlerTest {

    private RoutingMonitorService monitorService;
    private RoutingMonitorWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        monitorService = mock(RoutingMonitorService.class);
        handler = new RoutingMonitorWebSocketHandler(
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
    @DisplayName("ADMIN 发 pause（serviceType=null）→ 走全局 pause")
    void adminPauseApplied() {
        StepVerifier.create(handler.handleClientMessage(
                "{\"type\":\"pause\"}", sessionWithRole("ROLE_ADMIN"))).verifyComplete();
        verify(monitorService).pause();
    }

    @Test
    @DisplayName("ADMIN 发带 serviceType 的 pause → 走服务级 pauseService")
    void adminServicePauseApplied() {
        StepVerifier.create(handler.handleClientMessage(
                "{\"type\":\"pause\",\"serviceType\":\"CHAT\"}",
                sessionWithRole("ROLE_ADMIN"))).verifyComplete();
        verify(monitorService).pauseService("CHAT");
    }

    @Test
    @DisplayName("ADMIN 发 updateConfig → 采样率生效")
    void adminUpdateConfigApplied() {
        StepVerifier.create(handler.handleClientMessage(
                "{\"type\":\"updateConfig\",\"sampleRate\":0.5}",
                sessionWithRole("ROLE_ADMIN"))).verifyComplete();
        verify(monitorService).updateSampleRate(0.5);
    }

    @Test
    @DisplayName("反证：普通 USER 直调控制消息不得触发 pause/resume/updateConfig")
    void userControlMessagesRejected() {
        WebSocketSession session = sessionWithRole("ROLE_USER");
        StepVerifier.create(handler.handleClientMessage("{\"type\":\"pause\"}", session))
                .verifyComplete();
        StepVerifier.create(handler.handleClientMessage("{\"type\":\"resume\"}", session))
                .verifyComplete();
        StepVerifier.create(handler.handleClientMessage(
                "{\"type\":\"updateConfig\",\"sampleRate\":0.9}", session)).verifyComplete();

        verify(monitorService, never()).pause();
        verify(monitorService, never()).resume();
        verify(monitorService, never()).updateSampleRate(0.9);
    }

    @Test
    @DisplayName("反证：绕过握手（无 Principal）直调仍被拒")
    void anonymousBypassRejected() {
        WebSocketSession session = sessionWithRole(null);
        StepVerifier.create(handler.handleClientMessage("{\"type\":\"pause\"}", session))
                .verifyComplete();
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
}
