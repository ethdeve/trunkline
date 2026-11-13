package org.unreal.modelrouter.enterprise.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.enterprise.noop.NoopModerationProvider;
import org.unreal.modelrouter.enterprise.noop.NoopSsoConnector;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业 SPI 契约测试（O11 / O7 配套）。
 *
 * <p>守住三件事：版本号口径、record 兼容构造不破坏存量编译、Noop 兜底语义。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("企业 SPI 契约（O11）")
class EnterpriseSpiContractTest {

    @Test
    @DisplayName("SPI 版本 = 2（v2：ModerationInput 扩 tenantId）")
    void spiVersionIs2() {
        assertEquals(2, EnterpriseSpiVersion.VERSION);
    }

    @Test
    @DisplayName("ModerationInput 四参 canonical 构造：tenantId 可携带")
    void moderationInputFourArg() {
        final ModerationProvider.ModerationInput input =
                new ModerationProvider.ModerationInput("gpt", "hello", "alice", "tenant-a");
        assertEquals("gpt", input.model());
        assertEquals("hello", input.content());
        assertEquals("alice", input.username());
        assertEquals("tenant-a", input.tenantId());
    }

    @Test
    @DisplayName("ModerationInput 三参兼容构造：tenantId=null，存量调用不破坏")
    void moderationInputThreeArgCompat() {
        final ModerationProvider.ModerationInput input =
                new ModerationProvider.ModerationInput("gpt", "hello", "alice");
        assertNull(input.tenantId());
    }

    @Test
    @DisplayName("NoopModerationProvider 恒放行（社区版零行为影响）")
    void noopModerationAlwaysAllows() {
        assertTrue(new NoopModerationProvider()
                .check(new ModerationProvider.ModerationInput("m", "x", "u", "t")).allowed());
    }

    @Test
    @DisplayName("NoopSsoConnector：providerName 空串（O7-a 判别依据）、exchange 返回空")
    void noopSsoContract() {
        final NoopSsoConnector noop = new NoopSsoConnector();
        assertEquals("", noop.providerName());
        assertEquals(Optional.empty(), noop.exchange("any-code"));
    }
}
