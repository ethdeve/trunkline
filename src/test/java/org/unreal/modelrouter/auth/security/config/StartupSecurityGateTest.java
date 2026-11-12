package org.unreal.modelrouter.auth.security.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4-P1：生产环境密钥/密码检查必须 fail-fast，禁止“只打日志然后带着默认密钥上线”。
 */
@DisplayName("StartupSecurityGate 生产 fail-fast")
class StartupSecurityGateTest {

    @Test
    @DisplayName("生产 + 检查失败 + 未设置跳过 → 应 fail-fast")
    void production_failedChecks_throws() {
        assertTrue(StartupSecurityGate.shouldFailFast(true, false, null));
        assertThrows(IllegalStateException.class,
                () -> StartupSecurityGate.enforce(true, false, null));
    }

    @Test
    @DisplayName("生产 + 检查通过 → 不抛出")
    void production_passed_ok() {
        assertFalse(StartupSecurityGate.shouldFailFast(true, true, null));
        StartupSecurityGate.enforce(true, true, null);
    }

    @Test
    @DisplayName("非生产 + 检查失败 → 仅告警，不抛出")
    void nonProduction_failed_warnOnly() {
        assertFalse(StartupSecurityGate.shouldFailFast(false, false, null));
        StartupSecurityGate.enforce(false, false, null);
    }

    @Test
    @DisplayName("生产 + TRUNKLINE_SKIP_AUTH_WARNING=true → 允许启动（显式逃生舱）")
    void production_skipEnv_allows() {
        assertFalse(StartupSecurityGate.shouldFailFast(true, false, "true"));
        StartupSecurityGate.enforce(true, false, "true");
    }

    @Test
    @DisplayName("跳过开关的变量名单一来源（issue #201：文案与读取必须同源）")
    void skipEnvNameIsSingleSourced() {
        assertEquals("TRUNKLINE_SKIP_AUTH_WARNING", StartupSecurityGate.SKIP_ENV,
                "该常量是启动期安全检查跳过开关的唯一定义，文案与读取都引用它；"
                        + "改名会同时改变运维用法，故在此锁定");
    }

    @Test
    @DisplayName("默认管理员密码识别")
    void defaultAdminPassword_detected() {        assertTrue(StartupSecurityGate.isDefaultAdminPassword("ChangeMeOnFirstStartup123456"));
        assertFalse(StartupSecurityGate.isDefaultAdminPassword("S3cure!Admin#2026x"));
        assertFalse(StartupSecurityGate.isDefaultAdminPassword(null));
    }

    @Test
    @DisplayName("遗留管理员密码判定（不再参与启动检查，仅用于识别弱口令）")
    void production_missingAdminPassword_fails() {
        assertFalse(StartupSecurityGate.adminPasswordOk(true, null));
        assertFalse(StartupSecurityGate.adminPasswordOk(true, ""));
        assertTrue(StartupSecurityGate.adminPasswordOk(true, "Str0ng!Pass#2026"));
        // 开发环境缺省密码仅告警
        assertTrue(StartupSecurityGate.adminPasswordOk(false, null));
    }

    @Test
    @DisplayName("生产环境默认 JWT 密钥 → 检查失败")
    void production_defaultJwtSecret_fails() {
        assertFalse(StartupSecurityGate.jwtSecretOk(true,
                "ThisIsADefaultSecretKeyForDevOnly12345678"));
        assertFalse(StartupSecurityGate.jwtSecretOk(true, null));
        assertTrue(StartupSecurityGate.jwtSecretOk(true,
                "prod-only-random-secret-please-rotate-0001"));
        // 非生产：默认密钥仅告警
        assertTrue(StartupSecurityGate.jwtSecretOk(false, null));
    }

    @Test
    @DisplayName("#664 残余：文档/模板占位符密钥一律视为默认值")
    void placeholderSecretsDetected() {
        assertTrue(StartupSecurityGate.isDefaultJwtSecret(
                "your-very-secure-jwt-secret-key-at-least-32-characters-long"));
        assertTrue(StartupSecurityGate.isDefaultJwtSecret("replace-with-your-own-32-char-secret"));
        assertTrue(StartupSecurityGate.isDefaultJwtSecret("change-this-secret-before-going-live"));
        assertTrue(StartupSecurityGate.isDefaultJwtSecret("placeholder-abcdefghijklmnopqrstuv"));
        assertTrue(StartupSecurityGate.isDefaultJwtSecret("example-secret-value-long-enough-0001"));
        // 既有口径不能被削弱
        assertTrue(StartupSecurityGate.isDefaultJwtSecret(
                StartupSecurityGate.DEFAULT_JWT_SECRET));
        assertTrue(StartupSecurityGate.isDefaultJwtSecret("something-devonly-abcdefghijklmn"));
        // 真实强密钥不得误伤（含 example 字样但不是占位前缀）
        assertFalse(StartupSecurityGate.isDefaultJwtSecret(
                "Pr0d!Only#Random$Secret-Key-2026-abcdefghijklmnopqrst"));
    }

    @Test
    @DisplayName("占位符在生产同样被 jwtSecretOk 拒绝")
    void placeholderSecretRejectedInProduction() {
        assertFalse(StartupSecurityGate.jwtSecretOk(true, "your-very-secure-jwt-secret-key-0001"));
        assertTrue(StartupSecurityGate.jwtSecretOk(false, "your-very-secure-jwt-secret-key-0001"));
    }
}
