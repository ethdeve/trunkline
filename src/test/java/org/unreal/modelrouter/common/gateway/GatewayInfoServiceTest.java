package org.unreal.modelrouter.common.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayInfoService} 的纯逻辑断言。
 *
 * <p>不使用 Mockito（本机沙箱禁 self-attach，Mockito 会 ERROR）：
 * 环境用 Spring 自带 {@link MockEnvironment}，请求上下文用
 * {@link MockServerWebExchange}，均为真实实现。</p>
 *
 * @author JAiRouter Team
 * @since 3.5.0
 */
@DisplayName("网关运行形态解析（GET /api/gateway/info）")
class GatewayInfoServiceTest {

    /** 构造只带指定配置项的环境 */
    private static GatewayInfoService serviceWith(final String key, final String value) {
        final MockEnvironment env = new MockEnvironment();
        if (key != null) {
            env.setProperty(key, value);
        }
        return new GatewayInfoService(env);
    }

    /** 构造带 Host 头的请求上下文 */
    private static MockServerWebExchange exchangeOf(final String host) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/gateway/info").header(
                HttpHeaders.HOST, host));
    }

    @Nested
    @DisplayName("接入地址推导")
    class BaseUrlTests {

        @Test
        @DisplayName("按 Host 头推导，并补齐 /v1 后缀")
        void derivesFromHostHeader() {
            final GatewayInfoService service = serviceWith(null, null);
            final GatewayInfo info = service.resolve(exchangeOf("ai.example.com"));

            assertEquals("http://ai.example.com/v1", info.baseUrl());
        }

        @Test
        @DisplayName("反代场景优先取 X-Forwarded-Proto / X-Forwarded-Host")
        void honoursForwardedHeaders() {
            final GatewayInfoService service = serviceWith(null, null);
            final MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/gateway/info")
                            .header(HttpHeaders.HOST, "internal-svc:8080")
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", "ai.example.com"));

            assertEquals("https://ai.example.com/v1", service.resolve(exchange).baseUrl());
        }

        @Test
        @DisplayName("多级代理的逗号列表取首个分量")
        void takesFirstForwardedComponent() {
            final GatewayInfoService service = serviceWith(null, null);
            final MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/gateway/info")
                            .header(HttpHeaders.HOST, "internal-svc:8080")
                            .header("X-Forwarded-Proto", "https, http")
                            .header("X-Forwarded-Host", "ai.example.com, internal-svc:8080"));

            assertEquals("https://ai.example.com/v1", service.resolve(exchange).baseUrl());
        }

        @Test
        @DisplayName("显式配置 trunkline.gateway.base-url 时不再由请求推导")
        void explicitOverrideWins() {
            final GatewayInfoService service = serviceWith(
                    GatewayInfoService.BASE_URL_OVERRIDE_KEY, "https://fixed.example.com");
            final GatewayInfo info = service.resolve(exchangeOf("ignored.example.com"));

            assertEquals("https://fixed.example.com/v1", info.baseUrl());
        }

        @Test
        @DisplayName("配置值已带 /v1 或尾斜杠时不重复追加")
        void normalisesTrailingSlashAndSuffix() {
            final GatewayInfoService service = serviceWith(
                    GatewayInfoService.BASE_URL_OVERRIDE_KEY, "https://fixed.example.com/v1/");

            assertEquals("https://fixed.example.com/v1", service.resolve(null).baseUrl());
        }

        @Test
        @DisplayName("上下文缺失且无配置时退化为相对地址 /v1，不抛异常")
        void degradesToRelativeWhenNoContext() {
            final GatewayInfoService service = serviceWith(null, null);

            assertEquals("/v1", service.resolve(null).baseUrl());
        }
    }

    @Nested
    @DisplayName("部署形态判定")
    class DeploymentModeTests {

        @Test
        @DisplayName("replicas 未配置（0）且非 K8s 环境 → SINGLE，且共享态视为齐备")
        void singleWhenReplicasUnknown() {
            final GatewayInfoService service = serviceWith(null, null);
            final GatewayInfo info = service.resolve(exchangeOf("ai.example.com"));

            // 沙箱/本机无 KUBERNETES_SERVICE_HOST 且无 POD_NAME
            assertEquals(GatewayInfo.MODE_SINGLE, info.deploymentMode());
            assertEquals(0, info.replicas(), "未配置时副本数为 0=未知");
            assertTrue(info.sharedStateReady(), "单机没有跨副本共享概念，应视为齐备");
            assertTrue(info.sharedStateMissing().isEmpty(),
                    "单机不应报告任何缺失开关，否则前端会误报");
        }

        @Test
        @DisplayName("replicas=1 → SINGLE（显式单副本跳过共享态检查）")
        void singleWhenReplicasExplicitlyOne() {
            final GatewayInfoService service = serviceWith(
                    GatewayInfoService.REPLICAS_KEY, "1");
            final GatewayInfo info = service.resolve(exchangeOf("ai.example.com"));

            assertEquals(GatewayInfo.MODE_SINGLE, info.deploymentMode());
            assertEquals(1, info.replicas());
            assertTrue(info.sharedStateReady());
        }

        @Test
        @DisplayName("replicas=3 且共享态开关未开 → CLUSTER、sharedStateReady=false 并列出缺失项")
        void clusterWithMissingSwitches() {
            final GatewayInfoService service = serviceWith(
                    GatewayInfoService.REPLICAS_KEY, "3");
            final GatewayInfo info = service.resolve(exchangeOf("ai.example.com"));

            assertEquals(GatewayInfo.MODE_CLUSTER, info.deploymentMode());
            assertEquals(3, info.replicas());
            assertFalse(info.sharedStateReady(), "共享态开关全未开时必须报未就绪");
            assertEquals(5, info.sharedStateMissing().size(),
                    "五个共享态开关全未配置，应全部列出");
            assertTrue(info.sharedStateMissing().contains(
                            "trunkline.security.cache.redis.enabled"),
                    "应包含 API Key 缓存开关（多副本下不跨副本收敛的根因）");
        }
    }

    @Nested
    @DisplayName("响应体安全性")
    class SafetyTests {

        @Test
        @DisplayName("响应体不含密钥/上游地址字段（防止把内部信息漏给员工）")
        void noSensitiveFieldsOnRecord() {
            final List<String> componentNames = java.util.Arrays.stream(
                            GatewayInfo.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();

            assertEquals(List.of("baseUrl", "deploymentMode", "replicas",
                            "sharedStateReady", "sharedStateMissing"),
                    componentNames,
                    "字段集必须稳定且仅含非敏感信息；新增字段需同步审查泄漏面");
        }
    }
}
