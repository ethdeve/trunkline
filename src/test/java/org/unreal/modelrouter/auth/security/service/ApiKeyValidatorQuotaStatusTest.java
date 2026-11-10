/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.exception.AuthenticationException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 摘自上游 v3.3.4 #404 + 配额 429 修复的回归钉：
 * API Key 校验链上限流/配额类失败必须透出 429，认证类失败保持 401。
 * 注：{@code FullValidationResult.toException()} 是「返回异常对象」而非抛出，
 * 由调用方决定何时抛出（与 ApiKeyService 校验链用法一致）。
 * @author Trunkline Team
 */
@DisplayName("#404 摘取: 限流/配额失败 → 429，认证失败 → 401")
class ApiKeyValidatorQuotaStatusTest {

    private ApiKeyValidator.FullValidationResult quotaFailure(final String errorCode) {
        return ApiKeyValidator.FullValidationResult.validationError(
                "key-1", "quota/rate failure", errorCode);
    }

    @Test
    @DisplayName("API Key 每分钟限流（RATE_LIMITED_API_KEY）→ 429")
    void rateLimitedGives429() {
        AuthenticationException ex =
                quotaFailure(AuthenticationException.RATE_LIMITED_API_KEY).toException();
        assertEquals(429, ex.getHttpStatus().value());
    }

    @Test
    @DisplayName("每日请求配额（DAILY_QUOTA_EXCEEDED）→ 429")
    void dailyQuotaGives429() {
        AuthenticationException ex =
                quotaFailure(AuthenticationException.DAILY_QUOTA_EXCEEDED).toException();
        assertEquals(429, ex.getHttpStatus().value());
    }

    @Test
    @DisplayName("每日 Token 配额（TOKEN_QUOTA_EXCEEDED）→ 429")
    void tokenQuotaGives429() {
        AuthenticationException ex =
                quotaFailure(AuthenticationException.TOKEN_QUOTA_EXCEEDED).toException();
        assertEquals(429, ex.getHttpStatus().value());
    }

    @Test
    @DisplayName("认证类失败（INVALID_API_KEY）→ 保持 401")
    void authFailureKeeps401() {
        AuthenticationException ex =
                quotaFailure(AuthenticationException.INVALID_API_KEY).toException();
        assertEquals(401, ex.getHttpStatus().value());
    }

    @Test
    @DisplayName("工厂方法：dailyQuotaExceeded/tokenQuotaExceeded → 429")
    void factoryMethodsGive429() {
        assertEquals(429, AuthenticationException.dailyQuotaExceeded().getHttpStatus().value());
        assertEquals(429, AuthenticationException.tokenQuotaExceeded().getHttpStatus().value());
    }
}
