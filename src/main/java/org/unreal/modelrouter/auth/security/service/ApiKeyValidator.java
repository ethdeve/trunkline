package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.common.exception.AuthenticationException;

import java.util.Map;

/**
 * API Key 验证组件
 * 负责 API Key 的格式验证、安全检查和权限验证
 *
 * @since v2.14.0
 */
@Slf4j
@Component
public class ApiKeyValidator {

    @Autowired(required = false)
    private TokenBucketRateLimiter rateLimiter;

    /** O(1) 查找索引（C6 整改）；为 null 时退化为 O(n) 全表扫描 */
    @Autowired(required = false)
    private ApiKeyLookupIndex apiKeyLookupIndex;

    /**
     * 验证 API Key 格式
     * 检查 keyValue 是否符合基本格式要求
     *
     * @param keyValue 原始 API Key 值
     * @return 验证结果
     */
    public ValidationResult validateFormat(final String keyValue) {
        if (keyValue == null || keyValue.trim().isEmpty()) {
            return ValidationResult.failure("API Key 不能为空");
        }

        if (keyValue.length() < 10) {
            return ValidationResult.failure("API Key 长度不足");
        }

        if (!keyValue.startsWith("sk-")) {
            return ValidationResult.failure("API Key 格式无效，必须以 'sk-' 开头");
        }

        // 字符集校验：仅允许字母、数字、连字符、下划线，防止注入/控制字符混入
        if (!keyValue.matches("[A-Za-z0-9_-]+")) {
            return ValidationResult.failure("API Key 包含非法字符，仅允许字母、数字、- 和 _");
        }

        return ValidationResult.success();
    }

    /**
     * 在缓存中查找匹配的 API Key
     * 使用哈希验证方式匹配
     *
     * @param keyValue 原始 API Key 值
     * @param apiKeyCache API Key 缓存（keyHash -> ApiKey）
     * @return 匹配的 ApiKey，如果未找到返回 null
     */
    public ApiKey findMatchingKey(final String keyValue, final Map<String, ApiKey> apiKeyCache) {
        if (apiKeyLookupIndex != null) {
            return apiKeyLookupIndex.find(keyValue, apiKeyCache);
        }
        for (ApiKey apiKey : apiKeyCache.values()) {
            if (apiKey.verifyKey(keyValue)) {
                return apiKey;
            }
        }
        return null;
    }

    /**
     * 验证 API Key 启用状态
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateEnabled(final ApiKey apiKey) {
        if (!apiKey.isEnabled()) {
            return ValidationResult.failure("API Key 已被禁用", AuthenticationException.INVALID_API_KEY);
        }
        return ValidationResult.success();
    }

    /**
     * 验证 API Key 是否过期
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateExpiration(final ApiKey apiKey) {
        if (apiKey.isExpired()) {
            return ValidationResult.failure("API Key 已过期", AuthenticationException.EXPIRED_API_KEY);
        }
        return ValidationResult.success();
    }

    /**
     * 验证 IP 白名单
     *
     * @param apiKey 要验证的 ApiKey
     * @param ipAddress 客户端 IP 地址
     * @return 验证结果
     */
    public ValidationResult validateIpWhitelist(final ApiKey apiKey, final String ipAddress) {
        if (!apiKey.isIpAllowed(ipAddress)) {
            return ValidationResult.failure("IP 地址不允许访问: " + ipAddress,
                    AuthenticationException.INVALID_API_KEY);
        }
        return ValidationResult.success();
    }

    /**
     * 验证每日请求限制
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateDailyLimit(final ApiKey apiKey) {
        if (apiKey.isDailyLimitExceeded()) {
            return ValidationResult.failure("超过每日请求限制", AuthenticationException.DAILY_QUOTA_EXCEEDED);
        }
        return ValidationResult.success();
    }

    /**
     * 验证每日 Token 使用限制
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateTokenLimit(final ApiKey apiKey) {
        if (apiKey.isDailyTokenLimitExceeded()) {
            return ValidationResult.failure("超过每日 Token 使用限制", AuthenticationException.TOKEN_QUOTA_EXCEEDED);
        }
        return ValidationResult.success();
    }

    /**
     * 验证请求速率限制
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateRateLimit(final ApiKey apiKey) {
        if (apiKey.getRateLimitPerMinute() <= 0) {
            return ValidationResult.success();
        }
        if (rateLimiter == null) {
            return ValidationResult.success();
        }
        if (!rateLimiter.tryAcquire(apiKey.getKeyId(), apiKey.getRateLimitPerMinute())) {
            // 摘自上游 v3.3.4 #404：API Key 限流用专用码（429），不再与认证失败共用语义
            return ValidationResult.failure("请求频率超过限制，请稍后再试",
                    AuthenticationException.RATE_LIMITED_API_KEY);
        }
        return ValidationResult.success();
    }

    // ==================== P0-A：小时 / 月窗口配额校验 ====================
    //
    // 此前 hourly*/monthly* 四个限额「配了不生效」——认证链只校验日配额。
    // 这里补齐校验，并使用独立错误码，便于客户端区分是哪个窗口超限。
    //
    // 【多副本一致性局限（P0-A 必读）】
    // 本组校验读的是 ApiKey.usage 里的「单副本内存计数 + 持久化」用量统计，不是全局精确配额：
    //   ① 多副本部署时各副本各自计数，实际用量会被低估约 N 倍（N = 副本数），
    //      因此这里是「尽力而为」的软拦截，而非全局精确配额；
    //   ② 精确拦截必须启用配额账本（trunkline.quota.enabled=true）——
    //      账本路径（QuotaLedgerService / QuotaEnforcementService）的 QuotaLimitSet
    //      已包含 monthTokens 等月度额度字段，账本开启时月额度本就支持；
    //   ③ 本组校验解决的是「账本关闭（默认）时配了额度完全不生效」这个更严重的问题。
    // 详见 ApiKey 新判定方法与 UsageStatistics 类注释（同段说明）。

    /**
     * 验证每小时请求限制（P0-A）
     *
     * <p><b>判定口径</b>：自然小时窗口（本地时区、整点对齐，与配额账本
     * {@code QuotaWindow.HOUR} 同口径）；{@code hourlyRequestLimit <= 0} 表示不限制；
     * 本小时累计请求数 {@code >= } 限额即视为超限。</p>
     *
     * <p><b>一致性局限</b>：用量来自单副本内存计数，多副本部署下会被低估约 N 倍（N = 副本数），
     * 非全局精确配额；需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）。</p>
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateHourlyLimit(final ApiKey apiKey) {
        if (apiKey.isHourlyRequestLimitExceeded()) {
            return ValidationResult.failure("超过每小时请求限制",
                    AuthenticationException.HOURLY_QUOTA_EXCEEDED);
        }
        return ValidationResult.success();
    }

    /**
     * 验证每小时 Token 使用限制（P0-A）
     *
     * <p><b>判定口径</b>：自然小时窗口（本地时区、整点对齐，与配额账本
     * {@code QuotaWindow.HOUR} 同口径）；{@code hourlyTokenLimit <= 0} 表示不限制；
     * 本小时累计 Token 数 {@code >= } 限额即视为超限。</p>
     *
     * <p><b>一致性局限</b>：用量来自单副本内存计数，多副本部署下会被低估约 N 倍（N = 副本数），
     * 非全局精确配额；需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）。</p>
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateHourlyTokenLimit(final ApiKey apiKey) {
        if (apiKey.isHourlyTokenLimitExceeded()) {
            return ValidationResult.failure("超过每小时 Token 使用限制",
                    AuthenticationException.HOURLY_TOKEN_QUOTA_EXCEEDED);
        }
        return ValidationResult.success();
    }

    /**
     * 验证每月请求限制（P0-A）
     *
     * <p><b>判定口径</b>：自然月窗口（本地时区、当月 1 日 0 点，与配额账本
     * {@code QuotaWindow.MONTH} 同口径）；{@code monthlyRequestLimit <= 0} 表示不限制；
     * 本月累计请求数 {@code >= } 限额即视为超限。</p>
     *
     * <p><b>一致性局限</b>：用量来自单副本内存计数，多副本部署下会被低估约 N 倍（N = 副本数），
     * 非全局精确配额；需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）。</p>
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateMonthlyLimit(final ApiKey apiKey) {
        if (apiKey.isMonthlyRequestLimitExceeded()) {
            return ValidationResult.failure("超过每月请求限制",
                    AuthenticationException.MONTHLY_QUOTA_EXCEEDED);
        }
        return ValidationResult.success();
    }

    /**
     * 验证每月 Token 使用限制（P0-A）
     *
     * <p><b>判定口径</b>：自然月窗口（本地时区、当月 1 日 0 点，与配额账本
     * {@code QuotaWindow.MONTH} 同口径）；{@code monthlyTokenLimit <= 0} 表示不限制；
     * 本月累计 Token 数 {@code >= } 限额即视为超限。</p>
     *
     * <p><b>一致性局限</b>：用量来自单副本内存计数，多副本部署下会被低估约 N 倍（N = 副本数），
     * 非全局精确配额；需要精确拦截必须启用配额账本（{@code trunkline.quota.enabled=true}）。</p>
     *
     * @param apiKey 要验证的 ApiKey
     * @return 验证结果
     */
    public ValidationResult validateMonthlyTokenLimit(final ApiKey apiKey) {
        if (apiKey.isMonthlyTokenLimitExceeded()) {
            return ValidationResult.failure("超过每月 Token 使用限制",
                    AuthenticationException.MONTHLY_TOKEN_QUOTA_EXCEEDED);
        }
        return ValidationResult.success();
    }

    /**
     * 执行完整的 API Key 验证流程
     *
     * @param keyValue 原始 API Key 值
     * @param apiKeyCache API Key 缓存
     * @param ipAddress 客户端 IP 地址
     * @return 验证结果，包含匹配的 ApiKey（如果验证成功）
     */
    public FullValidationResult validateFully(final String keyValue,
                                               final Map<String, ApiKey> apiKeyCache,
                                               final String ipAddress) {
        // 1. 格式验证
        ValidationResult formatResult = validateFormat(keyValue);
        if (!formatResult.isSuccess()) {
            return FullValidationResult.formatError(formatResult.getMessage());
        }

        // 2. 查找匹配的 Key
        ApiKey matchedKey = findMatchingKey(keyValue, apiKeyCache);
        if (matchedKey == null) {
            return FullValidationResult.notFound();
        }

        // 3. 启用状态验证
        ValidationResult enabledResult = validateEnabled(matchedKey);
        if (!enabledResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    enabledResult.getMessage(), enabledResult.getErrorCode());
        }

        // 4. 过期验证
        ValidationResult expirationResult = validateExpiration(matchedKey);
        if (!expirationResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    expirationResult.getMessage(), expirationResult.getErrorCode());
        }

        // 5. IP 白名单验证
        ValidationResult ipResult = validateIpWhitelist(matchedKey, ipAddress);
        if (!ipResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    ipResult.getMessage(), ipResult.getErrorCode());
        }

        // 6. 每日请求限制验证（先于限流：被配额拒绝的请求不应消耗限流窗口）
        ValidationResult limitResult = validateDailyLimit(matchedKey);
        if (!limitResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    limitResult.getMessage(), limitResult.getErrorCode());
        }

        // 7. 每日 Token 使用限制验证
        ValidationResult tokenResult = validateTokenLimit(matchedKey);
        if (!tokenResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    tokenResult.getMessage(), tokenResult.getErrorCode());
        }

        // 8. P0-A：小时窗口配额校验（此前 hourly* 限额「配了不生效」）
        ValidationResult hourlyResult = validateHourlyLimit(matchedKey);
        if (!hourlyResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    hourlyResult.getMessage(), hourlyResult.getErrorCode());
        }

        // 9. P0-A：小时窗口 Token 配额校验
        ValidationResult hourlyTokenResult = validateHourlyTokenLimit(matchedKey);
        if (!hourlyTokenResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    hourlyTokenResult.getMessage(), hourlyTokenResult.getErrorCode());
        }

        // 10. P0-A：月窗口配额校验（此前 monthly* 限额「配了不生效」）
        ValidationResult monthlyResult = validateMonthlyLimit(matchedKey);
        if (!monthlyResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    monthlyResult.getMessage(), monthlyResult.getErrorCode());
        }

        // 11. P0-A：月窗口 Token 配额校验
        ValidationResult monthlyTokenResult = validateMonthlyTokenLimit(matchedKey);
        if (!monthlyTokenResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    monthlyTokenResult.getMessage(), monthlyTokenResult.getErrorCode());
        }

        // 12. 速率限制验证（置于配额之后：配额拒绝已提前短路，避免白扣一次限流令牌）
        ValidationResult rateResult = validateRateLimit(matchedKey);
        if (!rateResult.isSuccess()) {
            return FullValidationResult.validationError(matchedKey.getKeyId(),
                    rateResult.getMessage(), rateResult.getErrorCode());
        }

        // 所有验证通过
        log.debug("API Key 验证成功: {}", matchedKey.getKeyId());
        return FullValidationResult.success(matchedKey.createSecureCopy());
    }
    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final boolean success;
        private final String message;
        private final String errorCode;

        private ValidationResult(final boolean success, final String message, final String errorCode) {
            this.success = success;
            this.message = message;
            this.errorCode = errorCode;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult failure(final String message) {
            return new ValidationResult(false, message, AuthenticationException.INVALID_API_KEY);
        }

        public static ValidationResult failure(final String message, final String errorCode) {
            return new ValidationResult(false, message, errorCode);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * 完整验证结果（包含 ApiKey 信息）
     */
    public static class FullValidationResult {
        private final boolean success;
        private final ApiKey apiKey;
        private final String keyId;
        private final String errorMessage;
        private final String errorCode;
        private final ValidationFailureType failureType;

        private FullValidationResult(final boolean success, final ApiKey apiKey,
                                     final String keyId, final String errorMessage,
                                     final String errorCode, final ValidationFailureType failureType) {
            this.success = success;
            this.apiKey = apiKey;
            this.keyId = keyId;
            this.errorMessage = errorMessage;
            this.errorCode = errorCode;
            this.failureType = failureType;
        }

        public static FullValidationResult success(final ApiKey apiKey) {
            return new FullValidationResult(true, apiKey, apiKey.getKeyId(), null, null, null);
        }

        public static FullValidationResult formatError(final String message) {
            return new FullValidationResult(false, null, null, message,
                    AuthenticationException.INVALID_API_KEY, ValidationFailureType.FORMAT_ERROR);
        }

        public static FullValidationResult notFound() {
            return new FullValidationResult(false, null, null, "无效的 API Key",
                    AuthenticationException.INVALID_API_KEY, ValidationFailureType.NOT_FOUND);
        }

        public static FullValidationResult validationError(final String keyId,
                                                           final String message,
                                                           final String errorCode) {
            return new FullValidationResult(false, null, keyId, message, errorCode,
                    ValidationFailureType.VALIDATION_ERROR);
        }

        public boolean isSuccess() {
            return success;
        }

        public ApiKey getApiKey() {
            return apiKey;
        }

        public String getKeyId() {
            return keyId;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public ValidationFailureType getFailureType() {
            return failureType;
        }

        /**
         * 转换为 AuthenticationException
         *
         * <p>摘自上游 v3.3.4 #404：限流与配额类失败不是认证失败，透出 429 而非 401
         * （双参构造默认 UNAUTHORIZED，会让客户端把限流当认证错误处理）。</p>
         */
        public AuthenticationException toException() {
            if (success) {
                throw new IllegalStateException("验证成功，无需转换为异常");
            }
            if (isQuotaOrRateLimitCode(errorCode)) {
                return new AuthenticationException(errorMessage, errorCode,
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
            }
            return new AuthenticationException(errorMessage, errorCode);
        }

        /** 限流/配额类错误码（这些码客户端可见状态为 429） */
        private static boolean isQuotaOrRateLimitCode(final String code) {
            return AuthenticationException.RATE_LIMITED_API_KEY.equals(code)
                    || AuthenticationException.RATE_LIMIT_EXCEEDED.equals(code)
                    || AuthenticationException.DAILY_QUOTA_EXCEEDED.equals(code)
                    || AuthenticationException.TOKEN_QUOTA_EXCEEDED.equals(code)
                    // P0-A：小时 / 月窗口配额同样是「服务端拒绝」，返回 429 而非 401
                    || AuthenticationException.HOURLY_QUOTA_EXCEEDED.equals(code)
                    || AuthenticationException.HOURLY_TOKEN_QUOTA_EXCEEDED.equals(code)
                    || AuthenticationException.MONTHLY_QUOTA_EXCEEDED.equals(code)
                    || AuthenticationException.MONTHLY_TOKEN_QUOTA_EXCEEDED.equals(code);
        }
    }

    /**
     * 验证失败类型
     */
    public enum ValidationFailureType {
        FORMAT_ERROR,
        NOT_FOUND,
        VALIDATION_ERROR
    }
}