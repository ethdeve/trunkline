package org.unreal.modelrouter.enterprise.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.enterprise.noop.NoopBillingProvider;
import org.unreal.modelrouter.enterprise.noop.NoopModerationProvider;
import org.unreal.modelrouter.enterprise.noop.NoopSsoConnector;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业 SPI 契约测试（O11 / O7 / B2 配套）。
 *
 * <p>守住三件事：版本号口径、record 兼容构造不破坏存量编译、Noop 兜底语义。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("企业 SPI 契约（O11）")
class EnterpriseSpiContractTest {

    @Test
    @DisplayName("SPI 版本 = 3（v3：BillingEntry 扩 traceId，B2 [BREAKING-SPI]）")
    void spiVersionIs3() {
        assertEquals(3, EnterpriseSpiVersion.VERSION);
    }

    @Test
    @DisplayName("B2：BillingEntry 六组件，五参兼容构造器 traceId=null")
    void billingEntryTraceIdCompat() {
        final BillingProvider.BillingEntry traced = new BillingProvider.BillingEntry(
                "trace-1", "acme", "alice", "gpt-4o", new BigDecimal("0.05"), 100L);
        assertEquals("trace-1", traced.traceId());
        assertEquals(6, BillingProvider.BillingEntry.class.getRecordComponents().length);

        final BillingProvider.BillingEntry legacy = new BillingProvider.BillingEntry(
                "acme", "alice", "gpt-4o", BigDecimal.ONE, 100L);
        assertNull(legacy.traceId(), "存量调用方走兼容构造器时账本幂等键降级");
        assertEquals("acme", legacy.tenantId());
    }

    @Test
    @DisplayName("B2：precheck 为 default 方法，社区 Noop 恒放行")
    void noopBillingPrecheckAllows() throws NoSuchMethodException {
        final Method method = BillingProvider.class.getMethod(
                "precheck", BillingProvider.BillingPrecheck.class);
        assertTrue(method.isDefault(), "precheck 必须是 default，存量商业实现二进制兼容");
        assertTrue(new NoopBillingProvider()
                .precheck(new BillingProvider.BillingPrecheck("acme", "alice", "gpt-4o")).allowed());
        assertTrue(BillingProvider.PrecheckResult.allow().allowed());
        assertTrue(!BillingProvider.PrecheckResult.deny("预算已耗尽").allowed());
        assertEquals("预算已耗尽", BillingProvider.PrecheckResult.deny("预算已耗尽").reason());
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
    @DisplayName("B5-out：输出侧 SPI 为 default 方法（当前 v3 由 B2 的 BillingEntry 引起）")
    void outputSessionIsDefaultMethod() throws NoSuchMethodException {
        final Method openOutputSession = ModerationProvider.class.getMethod(
                "openOutputSession", ModerationProvider.ModerationInput.class);
        assertTrue(openOutputSession.isDefault());
        // B5-out 自身没有推版本号；当前 v3 的破坏性来源是 B2 给 BillingEntry 加 traceId
        assertEquals(3, EnterpriseSpiVersion.VERSION);
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
