package org.unreal.modelrouter.auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.unreal.modelrouter.auth.security.audit.AuditMetricsService;
import org.unreal.modelrouter.auth.security.audit.EnhancedSecurityAuditService;
import org.unreal.modelrouter.auth.security.service.ApiKeyExpirationScheduler;
import org.unreal.modelrouter.auth.security.service.ApiKeyRotationScheduler;
import org.unreal.modelrouter.auth.security.service.SecurityLogArchiveService;
import org.unreal.modelrouter.common.scheduling.ScheduledTaskLock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * O3 · 安全开关化验收：开源主干的安全组件统一口径为「基础默认关（matchIfMissing=false）
 * + production 显式开」。本测试用 {@link ApplicationContextRunner} 验证四个受 @ConditionalOnProperty
 * 控制的组件在「无任何开关」与「四个开关均显式 true」两种情形下的装配/不装配行为，
 * 等价于 production profile（application-prod.yml）打开这些开关后的装配结果。
 * 按类型（而非 bean 名）断言，可顺带验证实例化的依赖注入（如归档服务依赖审计服务）成功。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("O3 · 安全开关化：production 默认开 / 基础默认关")
class SecurityHardeningProdProfileTest {

    @Mock
    private AuditMetricsService auditMetricsService;
    @Mock
    private ScheduledTaskLock taskLock;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AuditMetricsService.class, () -> auditMetricsService)
            .withBean(ScheduledTaskLock.class, () -> taskLock)
            .withUserConfiguration(SecurityComponentsConfig.class);

    @Test
    @DisplayName("基础默认关：无任何 security 开关时，4 个安全组件均不装配（dev 零配置不破坏）")
    void baseline_default_off() {
        runner.run(ctx -> {
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> ctx.getBean(EnhancedSecurityAuditService.class), "增强审计应默认不装配");
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> ctx.getBean(SecurityLogArchiveService.class), "安全日志归档应默认不装配");
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> ctx.getBean(ApiKeyExpirationScheduler.class), "密钥过期清理应默认不装配");
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> ctx.getBean(ApiKeyRotationScheduler.class), "密钥轮换应默认不装配");
        });
    }

    @Test
    @DisplayName("production 默认开：4 个开关显式 true 时全部装配（等同 application-prod.yml）")
    void prod_profile_switches_on() {
        runner.withPropertyValues(
                "trunkline.security.audit.enhanced=true",
                "trunkline.security.archive.enabled=true",
                "trunkline.security.api-key.expiration-cleanup.enabled=true",
                "trunkline.security.api-key.rotation.enabled=true"
        ).run(ctx -> {
            assertDoesNotThrow(() -> ctx.getBean(EnhancedSecurityAuditService.class), "增强审计应在 prod 装配");
            assertDoesNotThrow(() -> ctx.getBean(SecurityLogArchiveService.class), "安全日志归档应在 prod 装配");
            assertDoesNotThrow(() -> ctx.getBean(ApiKeyExpirationScheduler.class), "密钥过期清理应在 prod 装配");
            assertDoesNotThrow(() -> ctx.getBean(ApiKeyRotationScheduler.class), "密钥轮换应在 prod 装配");
        });
    }

    @Configuration
    @Import({
            EnhancedSecurityAuditService.class,
            SecurityLogArchiveService.class,
            ApiKeyExpirationScheduler.class,
            ApiKeyRotationScheduler.class
    })
    static class SecurityComponentsConfig {
    }
}
