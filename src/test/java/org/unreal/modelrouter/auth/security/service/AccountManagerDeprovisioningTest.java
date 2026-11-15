package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.dto.ApiKeyListVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyVO;
import org.unreal.modelrouter.enterprise.spi.SsoConnector;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A4 最小回收闭环（闸门② P0 红线）：停用 / 对账链路的单元验证。
 */
@DisplayName("A4 最小回收闭环（闸门②）")
class AccountManagerDeprovisioningTest {

    private JwtAccountRepository jwtAccountRepository;
    private AccountStatusGuard accountStatusGuard;
    private ApiKeyService apiKeyService;
    private AccountManager accountManager;

    @BeforeEach
    void setUp() {
        jwtAccountRepository = mock(JwtAccountRepository.class);
        accountStatusGuard = mock(AccountStatusGuard.class);
        apiKeyService = mock(ApiKeyService.class);
        accountManager = new AccountManager(
                mock(SecurityProperties.class),
                mock(PasswordEncoder.class),
                jwtAccountRepository,
                mock(ObjectMapper.class));
        ReflectionTestUtils.setField(accountManager, "accountStatusGuard", accountStatusGuard);
        ReflectionTestUtils.setField(accountManager, "apiKeyService", apiKeyService);
    }

    private static SsoConnector.EnterpriseUser user(final String ext, final String email) {
        return new SsoConnector.EnterpriseUser(ext, "Name", email, "Dept");
    }

    @Test
    @DisplayName("disableExternalAccount：禁用本地账号 + 清空角色 + 失效 JWT + 吊销密钥")
    void disablesLocalAccountAndRevokesKeys() {
        final JwtAccountEntity alice = JwtAccountEntity.builder()
                .id(1L).username("alice").externalId("emp_1")
                .password("x").roles("[\"USER\"]").enabled(true).build();
        when(jwtAccountRepository.findByExternalId("emp_1")).thenReturn(Optional.of(alice));
        when(jwtAccountRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(jwtAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        final ApiKeyVO key = new ApiKeyVO();
        key.setKeyId("kid-1");
        when(apiKeyService.getMineApiKeysVO("alice"))
                .thenReturn(reactor.core.publisher.Mono.just(
                        ApiKeyListVO.builder().items(List.of(key)).build()));
        when(apiKeyService.deleteApiKey(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        accountManager.disableExternalAccount(user("emp_1", "alice@corp.com")).block();

        assertFalse(alice.getEnabled(), "本地账号应被禁用");
        assertEquals("[]", alice.getRoles(), "组成员（角色）应被清空");
        verify(accountStatusGuard).invalidate("alice");
        verify(apiKeyService).getMineApiKeysVO("alice");
        verify(apiKeyService).deleteApiKey("kid-1", "deprovisioning");
    }

    @Test
    @DisplayName("disableExternalAccount 幂等：重复停用不抛错、不重复副作用")
    void idempotentOnRepeat() {
        final JwtAccountEntity alice = JwtAccountEntity.builder()
                .id(1L).username("alice").externalId("emp_1")
                .password("x").roles("[]").enabled(false).build();
        when(jwtAccountRepository.findByExternalId("emp_1")).thenReturn(Optional.of(alice));
        when(jwtAccountRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(jwtAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(apiKeyService.getMineApiKeysVO("alice"))
                .thenReturn(reactor.core.publisher.Mono.just(
                        ApiKeyListVO.builder().items(List.of()).build()));
        when(apiKeyService.deleteApiKey(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        assertDoesNotThrow(() ->
                accountManager.disableExternalAccount(user("emp_1", "alice@corp.com")).block());
        assertFalse(alice.getEnabled());
        verify(accountStatusGuard).invalidate("alice");
    }

    @Test
    @DisplayName("disableExternalAccount：外部身份无本地账号时安全跳过（无副作用）")
    void skipsWhenNoLocalAccount() {
        when(jwtAccountRepository.findByExternalId(anyString())).thenReturn(Optional.empty());
        when(jwtAccountRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        accountManager.disableExternalAccount(user("emp_x", "x@corp.com")).block();

        verify(jwtAccountRepository, never()).save(any());
        verify(accountStatusGuard, never()).invalidate(anyString());
    }

    @Test
    @DisplayName("reconcileExternalAccounts：停用不在活跃集合内的 SSO 账号，活跃账号不动")
    void reconcileDisablesInactive() {
        final JwtAccountEntity emp1 = JwtAccountEntity.builder()
                .id(1L).username("alice").externalId("emp_1")
                .password("x").enabled(true).roles("[\"USER\"]").build();
        final JwtAccountEntity emp2 = JwtAccountEntity.builder()
                .id(2L).username("bob").externalId("emp_2")
                .password("x").enabled(true).roles("[\"USER\"]").build();
        when(jwtAccountRepository.findAllByExternalIdIsNotNull()).thenReturn(List.of(emp1, emp2));
        when(jwtAccountRepository.findByExternalId("emp_2")).thenReturn(Optional.of(emp2));
        when(jwtAccountRepository.findByUsername("bob")).thenReturn(Optional.of(emp2));
        when(jwtAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(apiKeyService.getMineApiKeysVO("bob"))
                .thenReturn(reactor.core.publisher.Mono.just(
                        ApiKeyListVO.builder().items(List.of()).build()));
        when(apiKeyService.deleteApiKey(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Mono.empty());

        final DeprovisioningSummary summary =
                accountManager.reconcileExternalAccounts(Set.of("emp_1")).block();

        assertNotNull(summary);
        assertEquals(1, summary.getDisabledCount());
        assertTrue(summary.getDisabledUsernames().contains("bob"));
        assertFalse(emp2.getEnabled(), "emp_2 应被停用");
        assertTrue(emp1.getEnabled(), "emp_1 在活跃集合内，不应被停用");
        verify(accountStatusGuard).invalidate("bob");
    }
}
