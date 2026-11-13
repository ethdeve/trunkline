package org.unreal.modelrouter.common.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * 出站 URL 安全校验：限制协议、拒绝危险主机/云元数据地址。
 *
 * <p>提供两种口径，避免「要么全网放行、要么把本地模型一起打死」：</p>
 * <ul>
 *   <li><b>常规口径</b> {@link #validateOutboundBaseUrl(String)}：用于管理员显式登记的供应商
 *       （Ollama / LocalAI 等本地或内网服务属于合法目标），仍只拦云元数据与链路本地地址。</li>
 *   <li><b>严格口径</b> {@link #validateOutboundBaseUrl(String, boolean)}：用于可由外部输入任意
 *       URL 的适配器测试端点，额外拒绝回环 / 私网 / CGNAT / ULA 等内网目标。</li>
 * </ul>
 *
 * <p>连接期 DNS 重绑定守卫 {@code SsrfGuardedAddressResolverGroup} 共用
 * {@link #isDangerousResolvedAddress(InetAddress)}，与常规口径一致，不受严格模式影响。</p>
 */
public final class UrlSecurityValidator {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "metadata.google.internal",
            "metadata",
            "instance-data",
            "169.254.169.254"
    );

    private UrlSecurityValidator() {
    }

    /**
     * 校验并规范化出站 base URL（常规口径：允许管理员登记的本地/内网服务）。
     *
     * @throws SecurityException URL 非法或命中危险目标
     */
    public static String validateOutboundBaseUrl(final String baseUrl) {
        return validateOutboundBaseUrl(baseUrl, false);
    }

    /**
     * 校验并规范化出站 base URL。
     *
     * @param baseUrl 待校验地址
     * @param blockPrivateNetworks true 时额外拒绝回环 / 私网 / 链路本地 / 任播 / 组播 /
     *                             CGNAT(100.64/10) / IPv6 ULA(fc00::/7) 及 localhost 字面量
     * @throws SecurityException URL 非法或命中危险目标
     */
    public static String validateOutboundBaseUrl(final String baseUrl,
                                                  final boolean blockPrivateNetworks) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new SecurityException("Base URL is required");
        }

        final URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new SecurityException("Invalid base URL syntax");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new SecurityException("Only http/https base URLs are allowed");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new SecurityException("Base URL must not contain user credentials");
        }

        // issue #703（定案 A，breaking change）：base URL 禁止携带 query——
        // 修复前 normalize 只拼 path、getRawQuery() 被静默丢弃（Azure ?api-version= 消失）；
        // 若改为保留，令牌会写进 currentConfig 并被日志/审计捕获（#695 必须覆盖），
        // 故定案校验期直接拒绝并引导把参数移到请求时传递。
        if (uri.getRawQuery() != null) {
            throw new SecurityException(
                    "Base URL must not contain a query string; pass query params (e.g. api-version) "
                    + "at request time instead");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("Base URL must contain a host");
        }

        if (isBlockedHostLiteral(host) || isDangerousIpLiteral(host)
                || (blockPrivateNetworks && isPrivateHostLiteral(host))) {
            throw new SecurityException("Base URL host is not allowed");
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isDangerousResolvedAddress(address)
                        || (blockPrivateNetworks && isPrivateResolvedAddress(address))) {
                    throw new SecurityException("Base URL resolves to a blocked address");
                }
            }
        } catch (UnknownHostException e) {
            // 解析失败留给连接阶段处理，保持现有超时/错误语义
        }

        return normalize(scheme, host, uri.getPort(), uri.getRawPath());
    }

    private static String normalize(final String scheme, final String host,
                                    final int port, final String rawPath) {
        StringBuilder normalized = new StringBuilder();
        normalized.append(scheme).append("://");
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            normalized.append('[').append(host).append(']');
        } else {
            normalized.append(host);
        }
        if (port > 0) {
            normalized.append(':').append(port);
        }
        if (rawPath != null && !rawPath.isBlank() && !"/".equals(rawPath)) {
            if (!rawPath.startsWith("/")) {
                normalized.append('/');
            }
            normalized.append(rawPath);
        }
        String result = normalized.toString();
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static boolean isBlockedHostLiteral(final String host) {
        String value = unwrapIpv6(host).toLowerCase(Locale.ROOT);
        return BLOCKED_HOSTS.contains(value) || isDangerousIpLiteral(value);
    }

    private static boolean isDangerousIpLiteral(final String host) {
        String value = unwrapIpv6(host).toLowerCase(Locale.ROOT);
        return "169.254.169.254".equals(value)
                || "fd00:ec2::254".equals(value)
                || "0.0.0.0".equals(value)
                || "::".equals(value);
    }

    private static String unwrapIpv6(final String host) {
        String value = host.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 严格口径下额外拒绝的主机名字面量：localhost / mDNS 名称不依赖 DNS 解析也应拦下。
     * IP 字面量会由 {@link #isPrivateResolvedAddress(InetAddress)} 在解析循环中覆盖。
     */
    private static boolean isPrivateHostLiteral(final String host) {
        String value = unwrapIpv6(host).toLowerCase(Locale.ROOT);
        if ("localhost".equals(value)
                || value.endsWith(".localhost")
                || value.endsWith(".local")) {
            return true;
        }
        // IPv6 ULA fc00::/7：不依赖 DNS，字面量直接判定
        if (value.matches("^(fc|fd)[0-9a-f]{2}:.*")) {
            return true;
        }
        // IPv4 字面量：回环 / 私网 / CGNAT / 链路本地。
        // 必须按字面量判一次，否则解析失败被 UnknownHostException 吞掉时严格口径会漏过。
        String[] parts = value.split("\\.", -1);
        if (parts.length == 4) {
            try {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                int c = Integer.parseInt(parts[2]);
                int d = Integer.parseInt(parts[3]);
                if (a >= 0 && a <= 255 && b >= 0 && b <= 255
                        && c >= 0 && c <= 255 && d >= 0 && d <= 255) {
                    return a == 0 || a == 10 || a == 127
                            || (a == 172 && b >= 16 && b <= 31)
                            || (a == 192 && b == 168)
                            || (a == 100 && b >= 64 && b <= 127)
                            || (a == 169 && b == 254);
                }
            } catch (NumberFormatException ignored) {
                // 非 IPv4 字面量（域名），交给解析循环判定
            }
        }
        return false;
    }

    /**
     * 严格口径下额外拒绝的已解析地址（内网 / 本机 / 特殊用途网段）。
     * 常规口径不使用本方法，保证管理员登记的 Ollama、LocalAI 等内网服务仍可访问。
     */
    public static boolean isPrivateResolvedAddress(final InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 0.0.0.0/8 与运营商级 NAT 100.64.0.0/10
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        if (bytes.length == 16) {
            // IPv6 唯一本地地址 fc00::/7（fc00–fdff）
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    /**
     * 判定一个<b>已解析</b>地址是否为必须拒绝的危险目标（链路本地 / 云元数据网段）。
     *
     * <p>写入侧校验（{@link #validateOutboundBaseUrl(String)}）与连接期解析守卫
     * （{@code SsrfGuardedAddressResolverGroup}）共用本判据，避免两处规则漂移。</p>
     *
     * @param address 已解析的地址
     * @return true 表示必须拒绝
     */
    public static boolean isDangerousResolvedAddress(final InetAddress address) {
        if (address.isLinkLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4 && (bytes[0] & 0xFF) == 169 && (bytes[1] & 0xFF) == 254) {
            return true;
        }
        // 云元数据 IPv6 fd00:ec2::/64
        return bytes.length == 16
                && (bytes[0] & 0xFF) == 0xfd
                && (bytes[1] & 0xFF) == 0x00
                && (bytes[2] & 0xFF) == 0x0e
                && (bytes[3] & 0xFF) == 0xc2;
    }
}
