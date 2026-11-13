package org.unreal.modelrouter.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.unreal.modelrouter.common.dto.LoadBalanceConfig;
import org.unreal.modelrouter.common.dto.RateLimitConfig;
import org.unreal.modelrouter.common.dto.CircuitBreakerConfig;

/**
 * 更新服务配置请求 DTO
 * 用于前端更新服务配置（不含实例）
 *
 * <p>issue #1088 定案口径：<b>各字段（含嵌套子配置的字段）{@code null} = 不修改</b>，
 * 更新以现有配置为基线做浅合并，未提交字段全部保留。「显式清空」不通过本请求
 * 表达，走配置版本回滚（{@code ConfigurationVersionController}）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public final class UpdateServiceConfigRequest {

    /**
     * 适配器类型（null = 不修改）
     */
    private String adapter;

    /**
     * 负载均衡配置（null = 不修改）
     */
    private LoadBalanceConfig loadBalance;

    /**
     * 限流配置（null = 不修改）
     */
    private RateLimitConfig rateLimit;

    /**
     * 熔断器配置（null = 不修改）
     */
    private CircuitBreakerConfig circuitBreaker;

    /**
     * 回退配置（null = 不修改）
     */
    private Object fallback;
}