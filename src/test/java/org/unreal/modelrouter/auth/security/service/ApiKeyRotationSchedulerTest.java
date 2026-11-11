/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.common.scheduling.ScheduledTaskLock;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 轮换收敛：每日任务只统计，真正轮换只发生在带跨副本锁的 hourly 入口。 */
@DisplayName("API Key 轮换调度收敛")
@ExtendWith(MockitoExtension.class)
class ApiKeyRotationSchedulerTest {

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private ScheduledTaskLock taskLock;

    private ApiKeyRotationScheduler scheduler() {
        ApiKeyRotationScheduler scheduler = new ApiKeyRotationScheduler(taskLock);
        ReflectionTestUtils.setField(scheduler, "apiKeyService", apiKeyService);
        return scheduler;
    }

    @Test
    @DisplayName("每日 3:00 任务不再无锁轮换，只拉统计")
    void dailyCheckNeverRotates() {
        when(apiKeyService.getRotationStats()).thenReturn(Mono.empty());

        scheduler().dailyRotationStatusCheck();

        verify(apiKeyService).getRotationStats();
        verify(apiKeyService, never()).rotateExpiredKeys();
    }

    @Test
    @DisplayName("hourly 带锁入口仍执行轮换")
    void hourlyStillRotatesUnderLock() {
        when(taskLock.tryAcquire(eq("apikey.rotation"), any(Duration.class))).thenReturn(true);
        when(apiKeyService.rotateExpiredKeys()).thenReturn(Mono.empty());

        scheduler().checkRotationNeeded();

        verify(apiKeyService).rotateExpiredKeys();
    }
}
