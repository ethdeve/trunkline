package org.unreal.modelrouter.router.adapter.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.common.util.UrlSecurityValidator;
import org.unreal.modelrouter.router.adapter.AdapterRegistry;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 适配器测试服务
 * 提供适配器连通性验证功能
 */
@Service
public class AdapterTestService {

    private static final Logger logger = LoggerFactory.getLogger(AdapterTestService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient.Builder webClientBuilder;
    private final AdapterRegistry adapterRegistry;

    /**
     * 适配器测试端点的 baseUrl 完全来自请求方输入，因此默认禁止把请求打向回环/私网。
     * 需要连通性测试内网服务时，由运维显式开启（仅影响测试端点，不影响已登记供应商的正常转发）。
     * 用字段注入而非改构造参数，避免破坏既有的构造器注入测试。
     */
    @Value("${trunkline.security.adapter-test.allow-private-network:false}")
    private boolean allowPrivateNetwork;

    public AdapterTestService(final WebClient.Builder webClientBuilder,
                              final AdapterRegistry adapterRegistry) {
        this.webClientBuilder = webClientBuilder;
        this.adapterRegistry = adapterRegistry;
    }

    /** 测试端点统一走严格口径；开关打开时退回常规口径。 */
    private String validateAdapterTestUrl(final String baseUrl) {
        return UrlSecurityValidator.validateOutboundBaseUrl(baseUrl, !allowPrivateNetwork);
    }

    /**
     * PING 测试：验证连通性和认证
     * 向目标 API 发送 GET /models 请求
     */
    public Mono<AdapterTestResult> testPing(final String baseUrl,
                                            final String authHeaderName,
                                            final String authHeaderValue) {
        long startTime = System.currentTimeMillis();
        final String safeBaseUrl;
        try {
            safeBaseUrl = validateAdapterTestUrl(baseUrl);
        } catch (SecurityException ex) {
            // 【issue #928】SSRF 拒绝是请求参数错误：必须以 HTTP 400 结束。
            // 旧实现包装成「正常完成的 Mono」+ AdapterTestResult.error，Controller 再统一
            // 返回 200 + success=true，等价于把安全拒绝伪装成一次成功的测试。
            // 校验细节只进 warn 日志，不把内网地址/拒绝理由回显给调用方。
            logger.warn("拒绝不合法的 Base URL: {}", ex.getMessage());
            return Mono.error(ApiException.of("INVALID_BASE_URL", "Base URL 不合法，禁止访问该地址"));
        }

        WebClient client = webClientBuilder.baseUrl(safeBaseUrl).build(); // lgtm[java/ssrf]

        return client.get()
                .uri("/models")
                .header(authHeaderName != null ? authHeaderName : "Authorization",
                        authHeaderValue != null ? authHeaderValue : "")
                // 部分 WAF（火山引擎等）会对默认 UA 的非浏览器客户端直接重置连接，声明 UA 降低被拦概率
                .header(HttpHeaders.USER_AGENT, "Trunkline/0.1.0 (provider connectivity check)")
                .retrieve()
                .toBodilessEntity()
                .map(response -> {
                    long latency = System.currentTimeMillis() - startTime;
                    AdapterTestResult result = AdapterTestResult.connected(latency,
                            "成功连接，延迟 " + latency + "ms");
                    result.setHttpStatusCode(response.getStatusCode().value());
                    return result;
                })
                .onErrorResume(WebClientResponseException.class, ex -> {
                    long latency = System.currentTimeMillis() - startTime;
                    int statusCode = ex.getStatusCode().value();
                    if (statusCode == 401 || statusCode == 403) {
                        return Mono.just(AdapterTestResult.authFailed(latency,
                                "认证失败 (" + statusCode + ")，请检查 API Key"));
                    }
                    // 收到任何 HTTP 响应都证明域名与服务可达（404/410/429/5xx 仅代表 /models
                    // 端点不存在、迁移或限流），按可达处理，避免把活着的供应商误判为不可用
                    AdapterTestResult reachable = AdapterTestResult.connected(latency,
                            "服务可达 (HTTP " + statusCode + ")：网络连通，/models 端点返回异常状态");
                    reachable.setHttpStatusCode(statusCode);
                    return Mono.just(reachable);
                })
                // 网络级瞬时错误（Connection reset 等）重试一次；HTTP 状态类错误与超时不重试
                .retryWhen(Retry.max(1)
                        .filter(ex -> !(ex instanceof WebClientResponseException)
                                && !(ex instanceof TimeoutException)))
                .onErrorResume(TimeoutException.class, ex ->
                        Mono.just(AdapterTestResult.timeout("连接超时，请检查网络或 Base URL")))
                .onErrorResume(UnknownHostException.class, ex ->
                        Mono.just(AdapterTestResult.error("DNS 解析失败: " + ex.getMessage())))
                .onErrorResume(ConnectException.class, ex ->
                        Mono.just(AdapterTestResult.error("连接被拒绝: " + ex.getMessage())))
                .onErrorResume(ex -> {
                    logger.warn("PING 测试异常: {}", ex.getMessage());
                    return Mono.just(AdapterTestResult.error("测试异常: " + ex.getMessage()));
                })
                .timeout(TIMEOUT, Mono.just(AdapterTestResult.timeout("连接超时（10秒）")));
    }

    /**
     * CHAT 测试：发送最小聊天请求验证完整链路
     */
    public Mono<AdapterTestResult> testChat(final String baseUrl,
                                            final String authHeaderName,
                                            final String authHeaderValue,
                                            final String model) {
        long startTime = System.currentTimeMillis();

        final String safeBaseUrl;
        try {
            safeBaseUrl = validateAdapterTestUrl(baseUrl);
        } catch (SecurityException ex) {
            // 【issue #928】SSRF 拒绝是请求参数错误：必须以 HTTP 400 结束。
            // 旧实现包装成「正常完成的 Mono」+ AdapterTestResult.error，Controller 再统一
            // 返回 200 + success=true，等价于把安全拒绝伪装成一次成功的测试。
            // 校验细节只进 warn 日志，不把内网地址/拒绝理由回显给调用方。
            logger.warn("拒绝不合法的 Base URL: {}", ex.getMessage());
            return Mono.error(ApiException.of("INVALID_BASE_URL", "Base URL 不合法，禁止访问该地址"));
        }

        WebClient client = webClientBuilder.baseUrl(safeBaseUrl).build(); // lgtm[java/ssrf]

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", "Hi")
        });
        requestBody.put("max_tokens", 5);

        return client.post()
                .uri("/chat/completions")
                .header(authHeaderName != null ? authHeaderName : "Authorization",
                        authHeaderValue != null ? authHeaderValue : "")
                .header(HttpHeaders.USER_AGENT, "Trunkline/0.1.0 (provider connectivity check)")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(responseBody -> {
                    long latency = System.currentTimeMillis() - startTime;
                    AdapterTestResult result = AdapterTestResult.connected(latency,
                            "成功连接并获取响应，延迟 " + latency + "ms");
                    Map<String, Object> details = new HashMap<>();
                    details.put("responsePreview",
                            responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody);
                    result.setDetails(details);
                    return result;
                })
                .onErrorResume(WebClientResponseException.class, ex -> {
                    long latency = System.currentTimeMillis() - startTime;
                    int statusCode = ex.getStatusCode().value();
                    if (statusCode == 401 || statusCode == 403) {
                        return Mono.just(AdapterTestResult.authFailed(latency,
                                "认证失败 (" + statusCode + ")，请检查 API Key"));
                    }
                    if (statusCode == 404) {
                        return Mono.just(AdapterTestResult.error(
                                "模型不存在或 API 路径错误 (" + statusCode + ")"));
                    }
                    return Mono.just(AdapterTestResult.error(
                            "请求失败 (" + statusCode + "): " + ex.getMessage()));
                })
                .onErrorResume(TimeoutException.class, ex ->
                        Mono.just(AdapterTestResult.timeout("请求超时，请检查网络或模型负载")))
                .onErrorResume(ex -> {
                    logger.warn("CHAT 测试异常: {}", ex.getMessage());
                    return Mono.just(AdapterTestResult.error("测试异常: " + ex.getMessage()));
                })
                .timeout(TIMEOUT, Mono.just(AdapterTestResult.timeout("请求超时（10秒）")));
    }

    /**
     * 测试已注册的适配器
     */
    public Mono<AdapterTestResult> testRegisteredAdapter(final String adapterName,
                                                          final String testType,
                                                          final String apiKey,
                                                          final String model,
                                                          final String baseUrl) {
        if (!adapterRegistry.isAdapterSupported(adapterName)) {
            throw new IllegalArgumentException("适配器不存在: " + adapterName);
        }

        final String effectiveBaseUrl = baseUrl;

        // 注册表不承载 baseUrl；此前的 localhost:8080 fallback 会让「测试已注册适配器」
        // 变成一次对本机端口的探测，既无业务意义也是 SSRF 入口。改为明确要求调用方提供地址。
        if (effectiveBaseUrl == null || effectiveBaseUrl.isBlank()) {
            return Mono.just(AdapterTestResult.error("测试已注册适配器需要提供 Base URL"));
        }

        final String safeBaseUrl;
        try {
            safeBaseUrl = validateAdapterTestUrl(effectiveBaseUrl);
        } catch (SecurityException ex) {
            // 【issue #928】SSRF 拒绝是请求参数错误：必须以 HTTP 400 结束。
            // 旧实现包装成「正常完成的 Mono」+ AdapterTestResult.error，Controller 再统一
            // 返回 200 + success=true，等价于把安全拒绝伪装成一次成功的测试。
            // 校验细节只进 warn 日志，不把内网地址/拒绝理由回显给调用方。
            logger.warn("拒绝不合法的 Base URL: {}", ex.getMessage());
            return Mono.error(ApiException.of("INVALID_BASE_URL", "Base URL 不合法，禁止访问该地址"));
        }

        String authHeader = apiKey != null && !apiKey.isBlank() ? "Bearer " + apiKey : "";

        if ("CHAT".equalsIgnoreCase(testType)) {
            return testChat(safeBaseUrl, "Authorization", authHeader, model);
        }
        return testPing(safeBaseUrl, "Authorization", authHeader);
    }

    /**
     * 预览测试（未注册的适配器配置）
     */
    public Mono<AdapterTestResult> testPreview(final String type,
                                                final String baseUrl,
                                                final String authHeaderName,
                                                final String authHeaderValue,
                                                final String testType,
                                                final String model) {
        final String safeBaseUrl;
        try {
            safeBaseUrl = validateAdapterTestUrl(baseUrl);
        } catch (SecurityException ex) {
            // 【issue #928】SSRF 拒绝是请求参数错误：必须以 HTTP 400 结束。
            // 旧实现包装成「正常完成的 Mono」+ AdapterTestResult.error，Controller 再统一
            // 返回 200 + success=true，等价于把安全拒绝伪装成一次成功的测试。
            // 校验细节只进 warn 日志，不把内网地址/拒绝理由回显给调用方。
            logger.warn("拒绝不合法的 Base URL: {}", ex.getMessage());
            return Mono.error(ApiException.of("INVALID_BASE_URL", "Base URL 不合法，禁止访问该地址"));
        }

        if ("CHAT".equalsIgnoreCase(testType)) {
            return testChat(safeBaseUrl, authHeaderName, authHeaderValue, model);
        }
        return testPing(safeBaseUrl, authHeaderName, authHeaderValue);
    }
}
