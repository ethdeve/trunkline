/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.unreal.modelrouter.enterprise.noop.NoopBillingProvider;
import org.unreal.modelrouter.enterprise.spi.BillingProvider;
import org.unreal.modelrouter.enterprise.spi.BillingProvider.BillingPrecheck;
import org.unreal.modelrouter.enterprise.spi.BillingProvider.PrecheckResult;

/**
 * 预算前置校验服务单测（B2 / 方案 3.7）。
 *
 * <p>核心：只有企业实现明确 {@code denied} 才拒绝；社区 Noop、Bean 缺失、
 * 实现抛异常、返回 {@code null} 一律放行（fail-open），商业账本故障不得拖垮网关。</p>
 *
 * @author Trunkline Team
 */
class BillingPrecheckServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BillingProvider> providerOf(final BillingProvider billing) {
        final ObjectProvider<BillingProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(billing);
        return provider;
    }

    private static BillingProvider denying(final String reason) {
        final BillingProvider billing = mock(BillingProvider.class);
        when(billing.precheck(any())).thenReturn(PrecheckResult.deny(reason));
        return billing;
    }

    @Test
    void missingProviderAlwaysAllows() {
        @SuppressWarnings("unchecked")
        final ObjectProvider<BillingProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertTrue(new BillingPrecheckService(provider)
                .evaluate("acme", "alice", "gpt-4o").isEmpty());
    }

    @Test
    void communityNoopAlwaysAllows() {
        assertTrue(new BillingPrecheckService(providerOf(new NoopBillingProvider()))
                .evaluate("acme", "alice", "gpt-4o").isEmpty());
    }

    @Test
    void explicitDenialIsPropagatedWithFullContext() {
        final BillingProvider billing = denying("预算已耗尽");
        final Optional<PrecheckResult> result = new BillingPrecheckService(providerOf(billing))
                .evaluate("acme", "alice", "gpt-4o");

        assertTrue(result.isPresent());
        assertFalse(result.get().allowed());
        assertEquals("预算已耗尽", result.get().reason());

        final ArgumentCaptor<BillingPrecheck> captor = ArgumentCaptor.forClass(BillingPrecheck.class);
        verify(billing).precheck(captor.capture());
        assertEquals("acme", captor.getValue().tenantId());
        assertEquals("alice", captor.getValue().username());
        assertEquals("gpt-4o", captor.getValue().model());
    }

    @Test
    void allowedResultIsMappedToEmpty() {
        final BillingProvider billing = mock(BillingProvider.class);
        when(billing.precheck(any())).thenReturn(PrecheckResult.allow());

        assertTrue(new BillingPrecheckService(providerOf(billing))
                .evaluate("acme", "alice", "gpt-4o").isEmpty());
    }

    @Test
    void providerExceptionFailsOpen() {
        final BillingProvider billing = mock(BillingProvider.class);
        when(billing.precheck(any())).thenThrow(new IllegalStateException("billing db down"));

        assertTrue(new BillingPrecheckService(providerOf(billing))
                .evaluate("acme", "alice", "gpt-4o").isEmpty());
    }

    @Test
    void nullResultFailsOpen() {
        final BillingProvider billing = mock(BillingProvider.class);
        when(billing.precheck(any())).thenReturn(null);

        assertTrue(new BillingPrecheckService(providerOf(billing))
                .evaluate("acme", "alice", "gpt-4o").isEmpty());
    }

    @Test
    void twoArgOverloadResolvesTenantAndStillPropagatesDenial() {
        final BillingProvider billing = denying("额度不足");
        final Optional<PrecheckResult> result = new BillingPrecheckService(providerOf(billing))
                .evaluate("alice", "gpt-4o");

        assertTrue(result.isPresent());
        final ArgumentCaptor<BillingPrecheck> captor = ArgumentCaptor.forClass(BillingPrecheck.class);
        verify(billing).precheck(captor.capture());
        assertNotNull(captor.getValue().tenantId(),
                "两参重载必须从 TenantContextHolder 解析出租户（无 Spring 上下文时回落 default）");
        assertEquals("alice", captor.getValue().username());
    }
}
