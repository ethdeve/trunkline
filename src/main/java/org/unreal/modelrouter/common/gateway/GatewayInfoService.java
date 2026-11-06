package org.unreal.modelrouter.common.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.common.cluster.MultiReplicaSharedStateGate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析网关运行形态（供 {@code GET /api/gateway/info} 使用）。
 *
 * <p>设计约束：<b>只读现有状态，不新增任何持久化</b>。</p>
 * <ul>
 *   <li>部署形态 ← {@link MultiReplicaSharedStateGate#detectMultiReplica(int, java.util.Map)}
 *       （仓库既有权威判定，与启动自检同一口径）</li>
 *   <li>副本数 ← {@code trunkline.cluster.replicas}（{@code 0} 表示未知）</li>
 *   <li>共享态开关 ← {@link MultiReplicaSharedStateGate#SHARED_STATE_SWITCHES}</li>
 *   <li>接入地址 ← 本次请求的 scheme/host/port（含反代头），可用
 *       {@code trunkline.gateway.base-url} 显式覆盖</li>
 * </ul>
 *
 * <p><b>为什么 baseUrl 用请求推导而不是写配置</b>：同一份部署既可能被内网用户
 * 以 {@code http://10.0.0.5:8080} 访问，也可能被外网用户以
 * {@code https://ai.example.com} 访问。写死任一值都会让另一侧用户拿到不可达地址。
 * 用请求上下文推导，天然对每个访问者给出「他自己能连通的那个地址」。</p>
 *
 * @author JAiRouter Team
 * @since 3.5.0
 */
@Slf4j
@Service
public class GatewayInfoService {

    /** 副本数配置键（与 MultiReplicaSharedStateChecker.REPLICAS_KEY 同源） */
    static final String REPLICAS_KEY = "trunkline.cluster.replicas";

    /** 显式覆盖的接入地址；配置后不再由请求推导 */
    static final String BASE_URL_OVERRIDE_KEY = "trunkline.gateway.base-url";

    /** OpenAI 兼容路径后缀 */
    private static final String OPENAI_SUFFIX = "/v1";

    private final Environment environment;

    public GatewayInfoService(final Environment environment) {
        this.environment = environment;
    }

    /**
     * 解析当前请求视角下的网关信息。
     *
     * @param exchange 当前请求上下文（可为 null，为 null 时仅能给出配置或相对地址）
     * @return 网关信息，绝不返回 null
     */
    public GatewayInfo resolve(final ServerWebExchange exchange) {
        final int replicas = MultiReplicaSharedStateGate.parseReplicas(
                environment.getProperty(REPLICAS_KEY));
        final boolean multiReplica = MultiReplicaSharedStateGate.detectMultiReplica(
                replicas, System.getenv());

        final List<String> missing = multiReplica
                ? MultiReplicaSharedStateGate.assess(multiReplica, readSwitches()).disabledSwitches()
                : List.of();

        return new GatewayInfo(
                resolveBaseUrl(exchange),
                multiReplica ? GatewayInfo.MODE_CLUSTER : GatewayInfo.MODE_SINGLE,
                replicas,
                // 单机没有「跨副本共享」概念，视为齐备，避免前端误报
                !multiReplica || missing.isEmpty(),
                missing);
    }

    /**
     * 推导 OpenAI 兼容接入地址。
     *
     * <p>优先取 {@code trunkline.gateway.base-url} 显式配置；否则按
     * {@code X-Forwarded-Proto → X-Forwarded-Host → Host} 的顺序还原用户实际访问的源站，
     * 并补齐 {@code /v1} 后缀（已带则不重复追加）。</p>
     *
     * @param exchange 当前请求上下文
     * @return 接入地址；上下文缺失且无配置时返回 {@code /v1}（相对地址，前端仍可展示）
     */
    String resolveBaseUrl(final ServerWebExchange exchange) {
        final String override = environment.getProperty(BASE_URL_OVERRIDE_KEY);
        if (override != null && !override.trim().isEmpty()) {
            return normalise(override.trim());
        }
        if (exchange == null) {
            return OPENAI_SUFFIX;
        }

        final var request = exchange.getRequest();
        String proto = firstHeader(request, "X-Forwarded-Proto");
        if (proto == null || proto.isEmpty()) {
            proto = firstHeader(request, "X-Forwarded-Ssl");
            proto = "on".equalsIgnoreCase(proto) ? "https" : null;
        }
        if (proto == null || proto.isEmpty()) {
            proto = request.getURI().getScheme();
        }
        if (proto == null || proto.isEmpty()) {
            proto = "http";
        }

        String host = firstHeader(request, "X-Forwarded-Host");
        if (host == null || host.isEmpty()) {
            // 用 Host 头原文而非 getHost()：后者是 InetSocketAddress，会丢掉
            // 「是否为默认端口」的信息，拼回去容易产出 https://a.com:443 这类冗余地址。
            host = request.getHeaders().getFirst(HttpHeaders.HOST);
            if (host != null) {
                host = host.trim();
            }
        }
        if (host == null || host.isEmpty()) {
            return OPENAI_SUFFIX;
        }

        // X-Forwarded-Host 可能已是 "host:port"（含默认端口与否由反代决定），不再补端口，
        // 避免拼出 https://a.com:443 这类冗余地址。
        return normalise(proto + "://" + host);
    }

    /** 取首个非空头分量（多级代理会以逗号追加） */
    private static String firstHeader(final ServerHttpRequest request, final String name) {
        final String raw = request.getHeaders().getFirst(name);
        if (raw == null) {
            return null;
        }
        final int comma = raw.indexOf(',');
        final String head = comma >= 0 ? raw.substring(0, comma) : raw;
        return head.trim();
    }

    /**
     * 归一化接入地址：去尾部斜杠、补 {@code /v1} 后缀。
     *
     * <p>之所以必须补齐后缀：员工在 Cherry Studio / Cursor 里填的是
     * 「Base URL」，各工具对是否自带 {@code /v1} 的处理不一致，统一给出带
     * {@code /v1} 的完整地址是社区通行的兼容写法。</p>
     */
    private static String normalise(final String raw) {
        String value = raw;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith(OPENAI_SUFFIX)) {
            return value;
        }
        return value + OPENAI_SUFFIX;
    }

    private Map<String, Boolean> readSwitches() {
        final Map<String, Boolean> switches = new LinkedHashMap<>();
        for (MultiReplicaSharedStateGate.SharedStateSwitch item
                : MultiReplicaSharedStateGate.SHARED_STATE_SWITCHES) {
            switches.put(item.key(), environment.getProperty(item.key(), Boolean.class, Boolean.FALSE));
        }
        return switches;
    }
}
