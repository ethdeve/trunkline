package org.unreal.modelrouter.config.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.dto.CircuitBreakerConfig;
import org.unreal.modelrouter.common.dto.LoadBalanceConfig;
import org.unreal.modelrouter.common.dto.RateLimitConfig;
import org.unreal.modelrouter.config.dto.UpdateServiceConfigRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1088：服务配置 PUT 清空未提交字段修复测试。
 *
 * <p>修复前 {@code buildServiceConfigUpdate} 用空 Map 从零构建，PUT 一个只带
 * {@code rateLimit} 的请求会把 existing 里的 {@code fallback} /
 * {@code loadBalance.hashAlgorithm} / 自定义键整体清空；且
 * {@code buildRateLimitMap} 无条件 put 全部子键（含 null），合并后会用 null
 * 覆盖旧值。修复后以 existing 为基线浅合并 + 子键只产出非 null（null=不修改，
 * 「显式清空」走版本回滚）。</p>
 */
@DisplayName("issue #1088: 服务配置更新以现有配置为基线浅合并")
class ServiceConfigUpdateServiceTest {

    private final ServiceConfigUpdateService service = new ServiceConfigUpdateService();

    private Map<String, Object> richExisting() {
        Map<String, Object> existing = new HashMap<>();
        existing.put("adapter", "openai");
        existing.put("instances", List.of(Map.of("name", "inst-1", "baseUrl", "http://10.0.0.5:8080")));
        existing.put("fallback", Map.of("enabled", true, "strategy", "static"));
        existing.put("customKey", "custom-value");
        existing.put("loadBalance", new HashMap<>(Map.of(
                "type", "consistent-hash", "hashAlgorithm", "ketama")));
        existing.put("rateLimit", new HashMap<>(Map.of(
                "enabled", true, "algorithm", "sliding-window",
                "capacity", 100, "rate", 50, "scope", "client-ip")));
        existing.put("circuitBreaker", new HashMap<>(Map.of(
                "enabled", true, "failureThreshold", 5, "timeout", 30, "successThreshold", 3)));
        return existing;
    }

    @Test
    @DisplayName("existing 含 fallback+hashAlgorithm+instances，request 只带 rateLimit → 三者全保留且 rateLimit 更新（反证旧清空）")
    void unsubmittedFieldsArePreserved() {
        UpdateServiceConfigRequest request = new UpdateServiceConfigRequest();
        RateLimitConfig rl = new RateLimitConfig();
        rl.setCapacity(200);
        rl.setRate(100);
        request.setRateLimit(rl);

        Map<String, Object> result = service.buildServiceConfigUpdate(richExisting(), request);

        // 修复前这三项会整体消失（空 Map 从零构建）
        assertEquals(Map.of("enabled", true, "strategy", "static"), result.get("fallback"),
                "未提交的 fallback 必须保留");
        assertEquals("ketama", ((Map<?, ?>) result.get("loadBalance")).get("hashAlgorithm"),
                "未提交的 loadBalance 子键必须保留");
        assertEquals("inst-1", ((Map<?, ?>) ((List<?>) result.get("instances")).get(0)).get("name"),
                "instances 必须保留");
        assertEquals("custom-value", result.get("customKey"), "自定义键必须保留");

        // rateLimit 按提交更新
        Map<?, ?> updatedRl = (Map<?, ?>) result.get("rateLimit");
        assertEquals(200, updatedRl.get("capacity"));
        assertEquals(100, updatedRl.get("rate"));
    }

    @Test
    @DisplayName("request 全 null → 结果与 existing 逐键相等")
    void allNullRequestEqualsExisting() {
        UpdateServiceConfigRequest request = new UpdateServiceConfigRequest();

        Map<String, Object> result = service.buildServiceConfigUpdate(richExisting(), request);

        assertEquals(richExisting(), result, "全 null 请求 = 不修改，逐键相等");
    }

    @Test
    @DisplayName("子 Map 浅合并：request 只带 type 时旧 hashAlgorithm 保留")
    void loadBalanceSubKeysMerged() {
        UpdateServiceConfigRequest request = new UpdateServiceConfigRequest();
        LoadBalanceConfig lb = new LoadBalanceConfig();
        lb.setType("round-robin");
        request.setLoadBalance(lb);

        Map<String, Object> result = service.buildServiceConfigUpdate(richExisting(), request);

        Map<?, ?> updatedLb = (Map<?, ?>) result.get("loadBalance");
        assertEquals("round-robin", updatedLb.get("type"), "提交的子键更新");
        assertEquals("ketama", updatedLb.get("hashAlgorithm"),
                "修复前 buildLoadBalanceMap 整体替换导致 hashAlgorithm 消失");
    }

    @Test
    @DisplayName("buildRateLimitMap 只产出非 null 子键：null 不覆盖基线旧值")
    void rateLimitNullSubKeysDoNotOverwrite() {
        UpdateServiceConfigRequest request = new UpdateServiceConfigRequest();
        RateLimitConfig rl = new RateLimitConfig();
        rl.setCapacity(500);
        // 其余字段全 null（scope/key/clientIpEnable/enabled 等）
        request.setRateLimit(rl);

        Map<String, Object> result = service.buildServiceConfigUpdate(richExisting(), request);

        Map<?, ?> updatedRl = (Map<?, ?>) result.get("rateLimit");
        assertEquals(500, updatedRl.get("capacity"), "提交的字段更新");
        assertEquals(50, updatedRl.get("rate"), "未提交的 rate 保留基线值（修复前被 null 覆盖）");
        assertEquals("client-ip", updatedRl.get("scope"), "未提交的 scope 保留基线值");
        assertEquals("sliding-window", updatedRl.get("algorithm"), "未提交的 algorithm 保留基线值");
        assertNull(updatedRl.get("clientIpEnable"), "基线本身没有该键时不产生 null 条目");
    }

    @Test
    @DisplayName("熔断子 Map 浅合并：只带 failureThreshold 时其余基线子键保留")
    void circuitBreakerSubKeysMerged() {
        UpdateServiceConfigRequest request = new UpdateServiceConfigRequest();
        CircuitBreakerConfig cb = new CircuitBreakerConfig();
        cb.setFailureThreshold(10);
        request.setCircuitBreaker(cb);

        Map<String, Object> result = service.buildServiceConfigUpdate(richExisting(), request);

        Map<?, ?> updatedCb = (Map<?, ?>) result.get("circuitBreaker");
        assertEquals(10, updatedCb.get("failureThreshold"));
        assertEquals(30, updatedCb.get("timeout"), "未提交的 timeout 保留基线值（修复前被 null 覆盖）");
        assertEquals(3, updatedCb.get("successThreshold"), "未提交的 successThreshold 保留基线值");
    }

    @Test
    @DisplayName("adapter 提交时覆盖基线值")
    void submittedAdapterOverridesBaseline() {
        UpdateServiceConfigRequest request = new UpdateServiceConfigRequest();
        request.setAdapter("openai-compatible");

        Map<String, Object> result = service.buildServiceConfigUpdate(richExisting(), request);

        assertEquals("openai-compatible", result.get("adapter"));
    }

    @Test
    @DisplayName("existing 为 null 时按空基线构建，不抛异常")
    void nullExistingBaseline() {
        UpdateServiceConfigRequest request = new UpdateServiceConfigRequest();
        LoadBalanceConfig lb = new LoadBalanceConfig();
        lb.setType("ip-hash");
        request.setLoadBalance(lb);

        Map<String, Object> result = service.buildServiceConfigUpdate(null, request);

        assertEquals("ip-hash", ((Map<?, ?>) result.get("loadBalance")).get("type"));
        assertTrue(((Map<?, ?>) result.get("loadBalance")).containsKey("type"));
    }
}
