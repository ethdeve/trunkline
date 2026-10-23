package org.unreal.modelrouter.router.fallback.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.router.fallback.FallbackStrategy;

/**
 * 默认降级策略实现
 */
public class DefaultFallbackStrategy implements FallbackStrategy<ResponseEntity<?>> {
    private static final Logger logger = LoggerFactory.getLogger(DefaultFallbackStrategy.class);

    private final String serviceType;

    public DefaultFallbackStrategy(final String serviceType) {
        this.serviceType = serviceType;
    }

    @Override
    public ResponseEntity<?> fallback(final Exception cause) {
        logger.warn("Service {} fallback triggered due to: {}", serviceType, cause.getMessage());

        // P2-10: 降级响应改 OpenAI error 包裹形态的 JSON,带 serviceType 与 Retry-After,
        // 替换原纯文本 body(客户端/运维无法程序化识别降级原因)
        return ResponseEntity.status(503)
                .header("Content-Type", "application/json")
                .header("Retry-After", "60")
                .body("{\"error\":{\"message\":\"Service is currently degraded, please try again later\","
                        + "\"type\":\"service_unavailable\",\"code\":\"SERVICE_DEGRADED\","
                        + "\"service\":\"" + serviceType + "\"}}");
    }
}
