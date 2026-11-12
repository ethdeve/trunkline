package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【issue #704】验证期账户状态守卫：启用通过、删/禁拒绝、存储异常 fail-closed、缓存可即时失效。
 */
@DisplayName("#704 账户状态守卫")
class AccountStatusGuardTest {

    private AccountManager accountManager;
    private AccountStatusGuard guard;

    @BeforeEach
    void setUp() {
        accountManager = mock(AccountManager.class);
        guard = new AccountStatusGuard();
        ReflectionTestUtils.setField(guard, "accountManager", accountManager);
    }

    @Test
    @DisplayName("账户启用 → 通过，且 30 秒窗口内不重复查库")
    void activeAccountPassesAndIsCached() {
        when(accountManager.loadUserByUsername("alice")).thenReturn(mock(UserDetails.class));

        StepVerifier.create(guard.requireActive("alice")).verifyComplete();
        StepVerifier.create(guard.requireActive("alice")).verifyComplete();

        verify(accountManager, times(1)).loadUserByUsername("alice");
        assertEquals(1, guard.cachedAccountCount());
    }

    @Test
    @DisplayName("账户删除/禁用（UsernameNotFoundException）→ ACCOUNT_DISABLED")
    void disabledAccountRejected() {
        when(accountManager.loadUserByUsername("alice"))
                .thenThrow(new UsernameNotFoundException("用户不存在或已被禁用"));

        StepVerifier.create(guard.requireActive("alice"))
                .expectErrorSatisfies(error -> assertTrue(
                        error.getMessage().contains("账户已删除或禁用"), error.toString()))
                .verify();
    }

    @Test
    @DisplayName("存储异常不得被当成放行（fail-closed）")
    void storeFailureIsFailClosed() {
        when(accountManager.loadUserByUsername("alice"))
                .thenThrow(new IllegalStateException("database down"));

        StepVerifier.create(guard.requireActive("alice"))
                .expectErrorSatisfies(error -> assertTrue(
                        error.getMessage().contains("账户状态校验失败"), error.toString()))
                .verify();
    }

    @Test
    @DisplayName("守卫 Bean 存在但 AccountManager 未装配 → fail-closed")
    void missingAccountManagerFailsClosed() {
        AccountStatusGuard bare = new AccountStatusGuard();

        StepVerifier.create(bare.requireActive("alice"))
                .expectErrorSatisfies(error -> assertTrue(
                        error.getMessage().contains("账户存储不可用"), error.toString()))
                .verify();
    }

    @Test
    @DisplayName("空 subject → fail-closed")
    void blankSubjectFailsClosed() {
        StepVerifier.create(guard.requireActive(" "))
                .expectErrorSatisfies(error -> assertTrue(
                        error.getMessage().contains("令牌缺少账户标识"), error.toString()))
                .verify();
    }

    @Test
    @DisplayName("删/禁后 invalidate 立即生效，不等 30 秒缓存")
    void invalidateAppliesImmediately() {
        when(accountManager.loadUserByUsername("alice")).thenReturn(mock(UserDetails.class));
        StepVerifier.create(guard.requireActive("alice")).verifyComplete();

        guard.invalidate("alice");
        when(accountManager.loadUserByUsername("alice"))
                .thenThrow(new UsernameNotFoundException("deleted"));

        StepVerifier.create(guard.requireActive("alice"))
                .expectErrorSatisfies(error -> assertTrue(
                        error.getMessage().contains("账户已删除或禁用"), error.toString()))
                .verify();
    }
}
