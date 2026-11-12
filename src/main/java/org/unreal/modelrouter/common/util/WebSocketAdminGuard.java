/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.util;

import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * 【issue #916】WebSocket 控制消息的 ADMIN 判定（两个监控 WS 共用）。
 *
 * <p>WS 握手不是 {@code @RequestMapping}，{@code @PreAuthorize} 拿不到；URL 闸门之外必须在
 * handler 内做第二道校验。只读握手 Principal，绝不 {@code block()}（WebFlux event loop 会死锁）。
 * 任何缺失/异常一律按「非管理员」处理（fail-closed）。</p>
 */
public final class WebSocketAdminGuard {

    private WebSocketAdminGuard() {
    }

    public static Mono<Boolean> isAdmin(final WebSocketSession session) {
        if (session == null) {
            return Mono.just(false);
        }
        return Mono.defer(() -> {
                    final var handshakeInfo = session.getHandshakeInfo();
                    if (handshakeInfo == null) {
                        return Mono.just(false);
                    }
                    return handshakeInfo.getPrincipal()
                            .map(principal -> principal instanceof Authentication authentication
                                    && authentication.getAuthorities().stream()
                                    .anyMatch(granted -> "ROLE_ADMIN".equals(granted.getAuthority())))
                            .defaultIfEmpty(false);
                })
                .onErrorReturn(false);
    }
}
