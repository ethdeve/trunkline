package org.unreal.modelrouter.config.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 响应体上限统一配置（issue #700）。
 *
 * <p>修复前三处上限各自硬编码、语义割裂：服务端 codec 10MB（{@code WebFluxMultipartConfig}）、
 * 入站请求体缓存 10MB（{@code CachedBodyWebFilter.MAX_CACHE_SIZE}）、
 * 出站 WebClient 用默认 256KB（下游大响应被静默截断）。</p>
 *
 * <p>定案口径：默认 10MB，三处统一接本属性；出站超限定 502（BAD_GATEWAY），
 * 不再放行被消费空的 body。运维需评估内存预算：出站可缓存 10MB/并发，
 * 容器 limit 留余量；与 #681 入站 413 分开（服务端 multipart 解析上限）。</p>
 *
 * @author Trunkline Team
 */
@Data
@ConfigurationProperties(prefix = "trunkline.response")
public final class ResponseLimitsProperties {

    /**
     * 响应/请求体统一上限（默认 10MB）
     *
     * <p>同时约束：入站请求体缓存（{@code CachedBodyWebFilter}）、
     * 服务端编解码器（{@code WebFluxMultipartConfig}）、出站 WebClient
     * 响应体内存缓冲（{@code WebClientPool}）。配置示例：{@code 10MB} / {@code 1MB}。</p>
     */
    private DataSize maxBodySize = DataSize.ofMegabytes(10);
}
