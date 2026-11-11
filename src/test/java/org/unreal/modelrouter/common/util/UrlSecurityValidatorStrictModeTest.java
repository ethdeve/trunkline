/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双口径 SSRF 校验的回归网：严格口径必须挡住内网探测，
 * 常规口径必须继续允许管理员登记的本地/内网模型服务（Ollama、LocalAI 等）。
 */
@DisplayName("出站 URL 双口径 SSRF 校验")
class UrlSecurityValidatorStrictModeTest {

    @Test
    @DisplayName("严格口径拒绝回环、私网、localhost、CGNAT、IPv6 ULA")
    void strictModeRejectsPrivateTargets() {
        for (String url : new String[]{
                "http://127.0.0.1:8080",
                "http://10.0.0.1:11434",
                "http://192.168.1.10",
                "http://172.16.5.4",
                "http://100.64.0.1",
                "http://localhost:11434",
                "http://service.local",
                "http://[fc00::1]:11434",
                "http://169.254.169.254"
        }) {
            String target = url;
            assertThrows(SecurityException.class,
                    () -> UrlSecurityValidator.validateOutboundBaseUrl(target, true),
                    "严格口径应拒绝: " + target);
        }
    }

    @Test
    @DisplayName("常规口径仍允许本地与内网服务（不破坏已登记供应商）")
    void normalModeStillAllowsPrivateTargets() {
        assertEquals("http://127.0.0.1:11434",
                UrlSecurityValidator.validateOutboundBaseUrl("http://127.0.0.1:11434"));
        assertEquals("http://192.168.1.10:8080",
                UrlSecurityValidator.validateOutboundBaseUrl("http://192.168.1.10:8080"));
        assertEquals("http://localhost:11434",
                UrlSecurityValidator.validateOutboundBaseUrl("http://localhost:11434"));
    }

    @Test
    @DisplayName("两种口径都允许公网地址、都拒绝云元数据")
    void publicTargetsAndMetadata() {
        assertEquals("https://8.8.8.8",
                UrlSecurityValidator.validateOutboundBaseUrl("https://8.8.8.8", true));
        assertThrows(SecurityException.class,
                () -> UrlSecurityValidator.validateOutboundBaseUrl("http://169.254.169.254", true));
        assertThrows(SecurityException.class,
                () -> UrlSecurityValidator.validateOutboundBaseUrl("http://169.254.169.254"));
    }

    @Test
    @DisplayName("私网地址判据覆盖 CGNAT 与 IPv6 ULA，公网地址不误判")
    void privateAddressPredicate() throws Exception {
        assertTrue(UrlSecurityValidator.isPrivateResolvedAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(UrlSecurityValidator.isPrivateResolvedAddress(InetAddress.getByName("10.1.2.3")));
        assertTrue(UrlSecurityValidator.isPrivateResolvedAddress(InetAddress.getByName("100.64.0.1")));
        assertTrue(UrlSecurityValidator.isPrivateResolvedAddress(InetAddress.getByName("fc00::1")));
        assertFalse(UrlSecurityValidator.isPrivateResolvedAddress(InetAddress.getByName("8.8.8.8")));
    }
}
