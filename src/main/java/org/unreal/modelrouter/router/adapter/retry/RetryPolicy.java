package org.unreal.modelrouter.router.adapter.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 重试策略
 *
 * 负责定义和管理重试策略，支持指数退避和最大延迟限制。
 *
 * @author JAiRouter Team
 */
@Component
public class RetryPolicy {

    private static final Logger logger = LoggerFactory.getLogger(RetryPolicy.class);

    /**
     * 默认最大重试次数
     */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * 默认初始延迟 (毫秒)
     */
    public static final long DEFAULT_INITIAL_DELAY_MS = 100;

    /**
     * 默认最大延迟 (毫秒)
     */
    public static final long DEFAULT_MAX_DELAY_MS = 5000;

    /**
     * 默认退避乘数
     */
    public static final double DEFAULT_MULTIPLIER = 2.0;

    /**
     * 最大延迟上限 (毫秒) - v2.26.5
     */
    public static final long MAX_DELAY_CAP_MS = 10000;

    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;

    /**
     * 构造函数 - 使用默认配置
     */
    public RetryPolicy() {
        this(DEFAULT_MAX_RETRIES,
             Duration.ofMillis(DEFAULT_INITIAL_DELAY_MS),
             Duration.ofMillis(DEFAULT_MAX_DELAY_MS),
             DEFAULT_MULTIPLIER);
    }

    /**
     * 构造函数 - 自定义配置
     *
     * @param maxRetries 最大重试次数
     * @param initialDelay 初始延迟
     * @param maxDelay 最大延迟
     * @param multiplier 退避乘数
     */
    public RetryPolicy(
            final int maxRetries, final Duration initialDelay,
            final Duration maxDelay, final double multiplier) {
        this.maxRetries = maxRetries;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
    }

    /**
     * 计算下次重试延迟（指数退避）
     *
     * @param retryCount 当前重试次数（从 0 开始）
     * @return 下次重试延迟
     */
    public Duration getNextDelay(final int retryCount) {
        long delayMs = (long) (initialDelay.toMillis() * Math.pow(multiplier, retryCount));
        delayMs = Math.min(delayMs, maxDelay.toMillis());
        return Duration.ofMillis(delayMs);
    }

    /**
     * 判断是否可以重试（使用本策略实例的全局 maxRetries）。
     *
     * @param retryCount 当前重试次数
     * @param error 异常
     * @return 是否可以重试
     */
    public boolean canRetry(final int retryCount, final Throwable error) {
        return canRetry(retryCount, error, maxRetries);
    }

    /**
     * 判断是否可以重试（C-06：使用调用方传入的“本次请求实际重试预算”）。
     *
     * <p>网关按服务类型收敛重试次数（chat/embedding=2、rerank/tts/stt/imgGen=1），
     * 而本策略实例的 {@code maxRetries} 是全局默认值（3）。旧实现 {@code canRetry}
     * 只看字段值，导致 rerank 这类“注释声明只重试 1 次”的服务实际重试到 3 次——
     * 预算口径分叉，且流式/大 body 服务重试会重复读取请求体、重复计费。
     * 传入 {@code effectiveMaxRetries} 后，计数上限与记录上限（recordRetry）统一到同一来源。</p>
     *
     * @param retryCount         已经发生的重试次数
     * @param error              触发重试的异常（保留参数以兼容扩展判断）
     * @param effectiveMaxRetries 本次请求的实际重试预算；&lt;=0 表示禁止重试
     * @return 是否可以重试
     */
    public boolean canRetry(final int retryCount, final Throwable error, final int effectiveMaxRetries) {
        if (retryCount >= effectiveMaxRetries) {
            logger.debug("达到最大重试次数：{}/{}", retryCount, effectiveMaxRetries);
            return false;
        }
        return true;
    }

    /**
     * 创建带重试的 Mono
     *
     * @param operation 操作
     * @param retryCondition 重试条件
     * @param <T> 结果类型
     * @return 带重试的 Mono
     */
    public <T> Mono<T> withRetry(final Supplier<Mono<T>> operation, final Predicate<Throwable> retryCondition) {
        Retry retry = Retry.backoff(maxRetries, initialDelay)
                .maxBackoff(maxDelay)
                .filter(retryCondition);

        logger.debug("创建带重试的操作：maxRetries={}, initialDelay={}, maxDelay={}",
                maxRetries, initialDelay, maxDelay);

        return Mono.fromSupplier(operation)
                .flatMap(Mono::from)
                .retryWhen(retry);
    }

    /**
     * 创建带重试的 Mono（使用默认重试条件）
     *
     * @param operation 操作
     * @param <T> 结果类型
     * @return 带重试的 Mono
     */
    public <T> Mono<T> withRetry(final Supplier<Mono<T>> operation) {
        return withRetry(operation, this::isRetryable);
    }

    /**
     * 判断错误是否可重试
     *
     * @param error 异常
     * @return 是否可重试
     */
    public boolean isRetryable(final Throwable error) {
        if (error == null) {
            return false;
        }

        // 明确的 HTTP 4xx（400/401/403/404 ...）属于请求本身的问题，重试只会重复生成、
        // 重复计费，且会对上游造成无意义的压力。仅 408（超时）与 429（限流）可重试。
        // 旧实现只排除 RestTemplate 的 HttpClientErrorException，WebFlux 的
        // WebClientResponseException / ResponseStatusException 全部落到「默认重试」。
        if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            return isRetryableStatus(ex.getStatusCode().value());
        }
        if (error instanceof org.springframework.web.server.ResponseStatusException ex) {
            return isRetryableStatus(ex.getStatusCode().value());
        }
        if (error instanceof org.springframework.web.client.HttpClientErrorException ex) {
            return isRetryableStatus(ex.getStatusCode().value());
        }

        // 瞬时网络故障保持原有重试行为
        if (error instanceof java.util.concurrent.TimeoutException
            || error instanceof java.net.ConnectException
            || error instanceof java.net.SocketTimeoutException
            || error instanceof org.unreal.modelrouter.common.exception.DownstreamServiceException) {
            logger.debug("可重试错误：{}", error.getClass().getSimpleName());
            return true;
        }

        // 其余未知异常沿用原有容错语义（默认重试），不削弱既有故障转移能力
        logger.debug("重试错误：{}", error.getClass().getSimpleName());
        return true;
    }

    private static boolean isRetryableStatus(final int status) {
        if (status == 408 || status == 429) {
            return true;
        }
        return status < 400 || status >= 500;
    }

    /**
     * issue #647：提取异常的 HTTP 状态码，供重试侧与熔断/统计侧共用同一分类口径。
     *
     * <p>直接按异常类型取状态，<b>不要</b>用 {@code AdapterErrorHandler.classifyError} 的
     * errorCode 判——它把下游异常一律归 "503"，会掩盖真实的 4xx。
     * 包装异常沿 cause 链向下找（最多 5 层）；取不到返回 {@code -1}（按普通失败处理）。</p>
     *
     * @param error 异常，可为 {@code null}
     * @return HTTP 状态码；未知时 {@code -1}
     */
    public static int statusOf(final Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (current instanceof org.springframework.web.reactive.function.client.WebClientResponseException ex) {
                return ex.getStatusCode().value();
            }
            if (current instanceof org.springframework.web.server.ResponseStatusException ex) {
                return ex.getStatusCode().value();
            }
            if (current instanceof org.springframework.web.client.HttpClientErrorException ex) {
                return ex.getStatusCode().value();
            }
            if (current instanceof org.unreal.modelrouter.common.exception.DownstreamServiceException ex
                    && ex.getStatusCode() != null) {
                return ex.getStatusCode().value();
            }
            current = current.getCause();
        }
        return -1;
    }

    /**
     * issue #647：是否「客户端侧 4xx」——请求本身的问题，不应计入实例熔断失败。
     *
     * <p>400~499 且排除 408/429：408 请求超时、429 上游限流都按失败计熔断，
     * 与重试侧 {@link #isRetryableStatus(int)} 口径一致（可重试状态必属失败）。
     * 5xx、连接/超时异常、未知异常一律不是客户端错误。</p>
     *
     * @param error 异常，可为 {@code null}
     * @return 是否客户端侧 4xx
     */
    public static boolean isClientSide4xx(final Throwable error) {
        final int status = statusOf(error);
        return status >= 400 && status < 500 && !isRetryableStatus(status);
    }

    /**
     * 获取最大重试次数
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * 获取初始延迟
     */
    public Duration getInitialDelay() {
        return initialDelay;
    }

    /**
     * 获取最大延迟
     */
    public Duration getMaxDelay() {
        return maxDelay;
    }

    /**
     * 获取退避乘数
     */
    public double getMultiplier() {
        return multiplier;
    }

    // ============ v2.26.5: 从 BaseAdapter 迁移的方法 ============

    /**
     * 根据服务类型获取最大重试次数
     *
     * @param serviceType 服务类型
     * @return 最大重试次数
     */
    public int getMaxRetriesByServiceType(final ModelServiceRegistry.ServiceType serviceType) {
        if (serviceType == null) {
            return 1;
        }
        switch (serviceType) {
            case chat:
                return 2; // 聊天服务重试2次
            case embedding:
                return 2; // 嵌入服务重试2次
            case rerank:
                return 1; // 重排序服务重试1次（避免body重读问题）
            case tts:
                return 1; // TTS服务重试1次（文件较大）
            case stt:
                return 1; // STT服务重试1次（文件较大）
            case imgGen:
                return 1; // 图像生成重试1次（耗时较长）
            case imgEdit:
                return 1; // 图像编辑重试1次（耗时较长）
            default:
                return 1; // 默认重试1次
        }
    }

    /**
     * 计算重试延迟（指数退避）
     *
     * @param retryCount 当前重试次数
     * @return 延迟毫秒数
     */
    public long calculateRetryDelay(final int retryCount) {
        // 指数退避：基础延迟 * 2^重试次数，最大不超过10秒
        long baseDelay = 1000; // 1秒基础延迟
        long delay = baseDelay * (long) Math.pow(2, retryCount);
        return Math.min(delay, MAX_DELAY_CAP_MS);
    }
}
