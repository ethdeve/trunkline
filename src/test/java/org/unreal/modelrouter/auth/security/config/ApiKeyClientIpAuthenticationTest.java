package org.unreal.modelrouter.auth.security.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.unreal.modelrouter.auth.filter.DefaultAuthenticationConverter;
import org.unreal.modelrouter.auth.security.authentication.JwtTokenValidator;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【issue #693】API Key IP 白名单全量 401 的端到端反证。
 *
 * <p>旧链路：DefaultAuthenticationConverter 不带 IP → CustomReactiveAuthenticationManager
 * 调单参 validateApiKey → ApiKeyValidator 拿到 null → 白名单非空时 contains(null)=false。
 * 现在断言真实 IP 从 exchange 一路传到三参重载。</p>
 */
@DisplayName("#693 API Key 认证链携带真实客户端 IP")
class ApiKeyClientIpAuthenticationTest {

    private static final String PEER = "203.0.113.9";

    private static SecurityProperties properties() {
        SecurityProperties props = new SecurityProperties();
        props.getApiKey().setEnabled(true);
        props.getJwt().setEnabled(false);
        return props;
    }

    private static MockServerWebExchange exchange(final String forwardedFor, final boolean withPeer) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/v1/models")
                .header("X-API-Key", "sk-test-key");
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        if (withPeer) {
            builder.remoteAddress(new InetSocketAddress(PEER, 51234));
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    @DisplayName("转换器把直连客户端 IP 写进 ApiKeyAuthentication")
    void converterCarriesPeerIp() {
        Authentication auth = new DefaultAuthenticationConverter(properties())
                .convert(exchange(null, true)).block();
        assertNotNull(auth);
        assertTrue(auth instanceof ApiKeyAuthentication);
        assertEquals(PEER, ((ApiKeyAuthentication) auth).getClientIp());
    }

    @Test
    @DisplayName("反证：对端不可信时伪造 XFF 不得改变白名单判定依据")
    void forgedXffIgnored() {
        Authentication auth = new DefaultAuthenticationConverter(properties())
                .convert(exchange("198.51.100.7", true)).block();
        assertNotNull(auth);
        assertEquals(PEER, ((ApiKeyAuthentication) auth).getClientIp());
    }

    @Test
    @DisplayName("无法判定对端时保留 unknown（白名单非空即拒绝，不退化成 null 放行）")
    void unresolvablePeerIsUnknown() {
        Authentication auth = new DefaultAuthenticationConverter(properties())
                .convert(exchange(null, false)).block();
        assertNotNull(auth);
        assertEquals("unknown", ((ApiKeyAuthentication) auth).getClientIp());
    }

    @Test
    @DisplayName("认证管理器调用三参重载并透传 IP，绝不再回落单参")
    void managerUsesThreeArgValidation() {
        ApiKeyService service = mock(ApiKeyService.class);
        ApiKey info = new ApiKey();
        info.setKeyId("key-1");
        info.setPermissions(List.of("USER"));
        when(service.validateApiKey(eq("sk-test-key"), isNull(), eq(PEER)))
                .thenReturn(Mono.just(info));

        CustomReactiveAuthenticationManager manager = new CustomReactiveAuthenticationManager(
                service, mock(JwtTokenValidator.class), properties());
        ApiKeyAuthentication input = new ApiKeyAuthentication("sk-test-key");
        input.setClientIp(PEER);

        Authentication result = manager.authenticate(input).block();
        assertNotNull(result);
        verify(service).validateApiKey("sk-test-key", null, PEER);
        verify(service, never()).validateApiKey(anyString());
        assertEquals(PEER, ((ApiKeyAuthentication) result).getClientIp());
    }

    @Test
    @DisplayName("ApiKey.isIpAllowed 契约：空白名单放行，非空时 null/unknown 一律拒绝")
    void isIpAllowedContract() {
        ApiKey key = new ApiKey();
        assertTrue(key.isIpAllowed(null), "空白名单应放行任意 IP");
        assertTrue(key.isIpAllowed("unknown"));

        key.setAllowedIpAddresses(List.of(PEER));
        assertTrue(key.isIpAllowed(PEER));
        assertFalse(key.isIpAllowed(null), "旧单参链路正是死在这里");
        assertFalse(key.isIpAllowed("unknown"));
        assertFalse(key.isIpAllowed("198.51.100.7"));
    }
}
