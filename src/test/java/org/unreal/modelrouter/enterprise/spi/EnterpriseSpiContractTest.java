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
    @DisplayName("B5-out：NoopModerationProvider 输出侧会话默认空（社区版逐字节零行为变化）")
    void noopOutputSessionDisabledByDefault() {
        assertEquals(Optional.empty(), new NoopModerationProvider()
                .openOutputSession(new ModerationProvider.ModerationInput("m", "", "u", "t")));
    }

    @Test
    @DisplayName("B5-out：输出侧 SPI 为 default 方法扩展，VERSION 保持 v2（企业 jar 二进制兼容）")
    void outputSessionDoesNotBumpSpiVersion() {
        assertEquals(2, EnterpriseSpiVersion.VERSION);
        // 显式以接口类型调用，确认 default 方法在旧实现类上可用
        final ModerationProvider noop = new NoopModerationProvider();
        assertEquals(Optional.empty(), noop.openOutputSession(null));
    }

    @Test
    @DisplayName("NoopSsoConnector：providerName 空串、exchange 空、authorizationRedirect 默认空")
    void noopSsoContract() {
        final NoopSsoConnector noop = new NoopSsoConnector();
        assertEquals("", noop.providerName());
        assertEquals(Optional.empty(), noop.exchange("any-code"));
        // A2 补链：SPI 新增的是 default 方法，Noop 无需覆写即安全关闭跳转入口
        assertEquals(Optional.empty(), noop.authorizationRedirect("any-state"));
    }
}
