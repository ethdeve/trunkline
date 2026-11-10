package org.unreal.modelrouter.monitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

/**
 * Token 使用量记录服务
 * 用于在请求处理完成后异步记录 Token 使用量
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageRecorder {

    private final TokenUsageService tokenUsageService;

    /**
     * 异步记录 Token 使用量
     */
    public void recordTokenUsageAsync(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceName,
            final String instanceUrl,
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final String traceId,
            final Boolean isSuccess,
            final String errorCode,
            final String errorMessage,
            final Long responseTimeMs) {

        // 异步获取当前用户信息并记录
        ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(auth -> auth instanceof JwtAuthentication)
            .map(auth -> (JwtAuthentication) auth)
            .map(JwtAuthentication::getPrincipal)
            .map(principal -> principal != null ? principal.toString() : null)
            .defaultIfEmpty(null)
            .publishOn(Schedulers.boundedElastic())
            .subscribe(
                userId -> {
                    // 阻塞写入（JPA）不得落在非阻塞线程上（issue #115）
                    if (Schedulers.isInNonBlockingThread()) {
                        Schedulers.boundedElastic().schedule(() -> doRecordTokenUsage(
                            serviceType, modelName, provider, instanceName, instanceUrl,
                            promptTokens, completionTokens, totalTokens, traceId,
                            userId, isSuccess, errorCode, errorMessage, responseTimeMs
                        ));
                        return;
                    }
                    doRecordTokenUsage(
                        serviceType, modelName, provider, instanceName, instanceUrl,
                        promptTokens, completionTokens, totalTokens, traceId,
                        userId, isSuccess, errorCode, errorMessage, responseTimeMs
                    );
                },
                error -> log.error("获取用户信息失败", error)
            );
    }

    /**
     * 执行 Token 使用量记录（无用户认证）
     *
     * <p><b>线程语义（issue #115）</b>：落库链路（JPA {@code @Transactional} 写入）是阻塞调用，
     * 而调用方位于 {@code doOnComplete} 等 Reactor 非阻塞线程（Netty 事件循环）上时，
     * 把工作整体交给 {@link Schedulers#boundedElastic()} 执行后立即返回；
     * 非 Reactor 线程仍内联执行，保持“方法返回即已落库”的既有语义。</p>
     */
    public void recordTokenUsageNoAuth(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceName,
            final String instanceUrl,
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final String traceId,
            final String clientIp,
            final Boolean isSuccess,
            final String errorCode,
            final String errorMessage,
            final Long responseTimeMs) {
        recordTokenUsageNoAuth(serviceType, modelName, provider, instanceName, instanceUrl,
                promptTokens, completionTokens, totalTokens, traceId, clientIp,
                isSuccess, errorCode, errorMessage, responseTimeMs, null);
    }

    /**
     * 执行 Token 使用量记录（无用户认证，带虚拟模型归属与调用者身份）。
     *
     * <p>v3.5 Trunkline：{@code virtualModel} 记录请求命中的虚拟模型（模型分组 / 池名），
     * 是「团队用量」按虚拟模型统计的归属依据；为 {@code null} 时该行归入「未归类」。</p>
     *
     * <p>v3.5 Trunkline 修复：{@code userId} 记录调用者（JWT 用户名 / 密钥持有人）。
     * <b>这一项是「团队用量」能否出数的前提</b>——
     * {@code aggregateByMonthUserVirtualModel} 的 WHERE 里有 {@code userId IS NOT NULL}，
     * 此前本方法把 userId 硬编码为 {@code null}，导致所有用量行都被那条 WHERE 过滤掉，
     * 团队用量恒为 0（调用观测读的是另一张表，所以不受影响）。</p>
     */
    public void recordTokenUsageNoAuth(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceName,
            final String instanceUrl,
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final String traceId,
            final String clientIp,
            final Boolean isSuccess,
            final String errorCode,
            final String errorMessage,
            final Long responseTimeMs,
            final String virtualModel) {
        recordTokenUsageNoAuth(serviceType, modelName, provider, instanceName, instanceUrl,
                promptTokens, completionTokens, totalTokens, traceId, clientIp,
                isSuccess, errorCode, errorMessage, responseTimeMs, virtualModel, null);
    }

    /**
     * 执行 Token 使用量记录（无用户认证，带虚拟模型归属与调用者身份）。
     *
     * @param virtualModel 请求命中的虚拟模型（模型分组 / 池名），可为 {@code null}
     * @param userId       调用者（JWT 用户名 / 密钥持有人），可为 {@code null}（未知调用方）
     */
    public void recordTokenUsageNoAuth(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceName,
            final String instanceUrl,
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final String traceId,
            final String clientIp,
            final Boolean isSuccess,
            final String errorCode,
            final String errorMessage,
            final Long responseTimeMs,
            final String virtualModel,
            final String userId) {

        // 阻塞 JPA 写入不得阻塞 EventLoop（issue #115）
        if (Schedulers.isInNonBlockingThread()) {
            Schedulers.boundedElastic().schedule(() -> doRecordTokenUsageWithClientIp(
                serviceType, modelName, provider, instanceName, instanceUrl,
                promptTokens, completionTokens, totalTokens, traceId,
                clientIp, userId, isSuccess, errorCode, errorMessage, responseTimeMs, virtualModel
            ));
            return;
        }
        doRecordTokenUsageWithClientIp(
            serviceType, modelName, provider, instanceName, instanceUrl,
            promptTokens, completionTokens, totalTokens, traceId,
            clientIp, userId, isSuccess, errorCode, errorMessage, responseTimeMs, virtualModel
        );
    }

    /**
     * 执行 Token 使用量记录
     */
    private void doRecordTokenUsage(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceName,
            final String instanceUrl,
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final String traceId,
            final String userId,
            final Boolean isSuccess,
            final String errorCode,
            final String errorMessage,
            final Long responseTimeMs) {

        try {
            TokenUsageRecordDTO record = TokenUsageRecordDTO.builder()
                    .serviceType(serviceType)
                    .modelName(modelName)
                    .provider(provider)
                    .instanceName(instanceName)
                    .instanceUrl(instanceUrl)
                    .promptTokens(promptTokens != null ? promptTokens : 0L)
                    .completionTokens(completionTokens != null ? completionTokens : 0L)
                    .totalTokens(totalTokens != null ? totalTokens : 0L)
                    .traceId(traceId)
                    .userId(userId)
                    .isSuccess(isSuccess)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .responseTimeMs(responseTimeMs)
                    .occurredAt(LocalDateTime.now())
                    .build();

            tokenUsageService.recordTokenUsage(record);
            log.debug("Token usage recorded: model={}, totalTokens={}, serviceType={}, userId={}",
                    modelName, totalTokens, serviceType, userId);
        } catch (Exception e) {
            log.error("Failed to record token usage for model: {}", modelName, e);
        }
    }

    /**
     * 执行 Token 使用量记录（带客户端 IP）
     */
    private void doRecordTokenUsageWithClientIp(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceName,
            final String instanceUrl,
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final String traceId,
            final String clientIp,
            final String userId,
            final Boolean isSuccess,
            final String errorCode,
            final String errorMessage,
            final Long responseTimeMs,
            final String virtualModel) {

        try {
            TokenUsageRecordDTO record = TokenUsageRecordDTO.builder()
                    .serviceType(serviceType)
                    .modelName(modelName)
                    .provider(provider)
                    .instanceName(instanceName)
                    .instanceUrl(instanceUrl)
                    .promptTokens(promptTokens != null ? promptTokens : 0L)
                    .completionTokens(completionTokens != null ? completionTokens : 0L)
                    .totalTokens(totalTokens != null ? totalTokens : 0L)
                    .traceId(traceId)
                    .clientIp(clientIp)
                    .userId(userId)
                    .virtualModel(virtualModel)
                    .isSuccess(isSuccess)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .responseTimeMs(responseTimeMs)
                    .occurredAt(LocalDateTime.now())
                    .build();

            tokenUsageService.recordTokenUsage(record);
            log.debug("Token usage recorded: model={}, totalTokens={}, serviceType={}, clientIp={}",
                    modelName, totalTokens, serviceType, clientIp);
        } catch (Exception e) {
            log.error("Failed to record token usage for model: {}", modelName, e);
        }
    }
}
