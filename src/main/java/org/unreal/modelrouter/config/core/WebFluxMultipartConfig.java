package org.unreal.modelrouter.config.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * WebFlux Multipart 配置
 * 确保multipart请求能够正确处理
 * 兼容各个Spring版本的简化配置
 *
 * <p>上限统一接 {@code trunkline.response.max-body-size}（issue #700，默认 10MB），
 * 与入站请求体缓存（{@code CachedBodyWebFilter}）、出站 WebClient 缓冲
 * （{@code WebClientPool}）同口径。</p>
 */
@Configuration
public class WebFluxMultipartConfig implements WebFluxConfigurer {

    private final DataSize maxBodySize;

    public WebFluxMultipartConfig(final ResponseLimitsProperties responseLimitsProperties) {
        this.maxBodySize = responseLimitsProperties.getMaxBodySize() != null
                ? responseLimitsProperties.getMaxBodySize()
                : DataSize.ofMegabytes(10);
    }

    @Override
    public void configureHttpMessageCodecs(final ServerCodecConfigurer configurer) {
        // 配置内存缓冲区大小 - 这是最重要的配置
        // #700：统一接 trunkline.response.max-body-size（默认 10MB），不再硬编码
        configurer.defaultCodecs().maxInMemorySize(
                (int) Math.min(maxBodySize.toBytes(), Integer.MAX_VALUE));

        // 启用请求详情日志记录（仅用于调试，生产环境建议关闭）
        configurer.defaultCodecs().enableLoggingRequestDetails(true);
    }
}
