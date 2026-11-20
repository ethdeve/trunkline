/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.unreal.modelrouter.enterprise.config.TenantJpaFilterDefinition;
import org.unreal.modelrouter.persistence.jpa.entity.ConfigEntity;
import org.unreal.modelrouter.persistence.jpa.entity.GlobalConfigKeys;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;

/**
 * 全局配置跨租户可见性验收（真实 H2）。
 *
 * <p><b>要守住的语义</b>：同一张 {@code config_data} 表里混放两类配置——
 * <ul>
 *   <li>业务配置（{@code pool_definitions} / {@code rule_definitions}）→ 各租户互不可见；</li>
 *   <li>全局配置（{@code console.*}）→ 任何租户都能读到同一份。</li>
 * </ul>
 * 过滤器条件放宽成 {@code tenant_id in (:tenantIds)} 后，必须同时满足这两条，
 * 否则要么切租户看不见配置，要么跨租户泄露模型/规则。</p>
 *
 * @author Trunkline Team
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("全局配置哨兵：console.* 跨租户可见，池/规则仍隔离")
class GlobalConfigVisibilityIntegrationTest {

    private static final String SHARED = TenantJpaFilterDefinition.SHARED_TENANT_ID;

    @Autowired
    private ConfigRepository configRepository;

    @Autowired
    private ServiceConfigRepository serviceConfigRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private ConfigEntity row(final String key, final String tenantId) {
        return ConfigEntity.builder()
                .configKey(key)
                .configValue("{}")
                .version(0)
                .isLatest(true)
                .tenantId(tenantId)
                .build();
    }

    /** 模拟请求进入某租户：过滤器可见集 = 本租户 + 全局哨兵 */
    private void enterTenant(final String tenantId) {
        entityManager.unwrap(Session.class)
                .enableFilter(TenantJpaFilterDefinition.NAME)
                .setParameterList(TenantJpaFilterDefinition.PARAM_TENANT_IDS,
                        List.of(tenantId, SHARED));
    }

    @AfterEach
    void clearContext() {
        org.unreal.modelrouter.enterprise.config.TenantContextHolder.restoreDefault();
    }

    /**
     * 临时把全局租户上下文切到多租户桩，使 {@code TenantContextHolder.currentTenantId()}
     * 返回指定租户。
     *
     * <p>测试环境默认装配的是单租户 {@code SingleTenantContext}——它恒返回 {@code default}、
     * {@code resolve()} 为 no-op（这是社区版应有的行为）。要验证"业务键跟随当前租户"，
     * 必须先换成会真正记住租户的实现，否则测的永远是 default。</p>
     */
    private void useMultiTenantContext(final String tenantId) {
        org.unreal.modelrouter.enterprise.config.TenantContextHolder.setDelegate(
                new StubTenantContext(tenantId));
    }

    /** 多租户上下文桩：记住被 resolve 的租户，模拟企业版按请求头取值的行为。 */
    private static final class StubTenantContext
            implements org.unreal.modelrouter.enterprise.spi.TenantContext {

        private String current;

        StubTenantContext(final String initial) {
            this.current = initial;
        }

        @Override
        public String getTenantId() {
            return current;
        }

        @Override
        public boolean isMultiTenant() {
            return true;
        }

        @Override
        public void resolve(final String tenantId) {
            if (tenantId != null && !tenantId.isBlank()) {
                this.current = tenantId;
            }
        }
    }

    @Test
    @DisplayName("console.* 全局配置：tenant-a 与 tenant-b 都能读到同一份")
    void globalConsoleKeyVisibleToEveryTenant() {
        // 直接以哨兵租户落库，模拟 ConfigEntity#prePersist 的判定结果
        configRepository.save(row("console.prompt-templates", SHARED));

        enterTenant("tenant-a");
        final ConfigEntity seenByA = configRepository
                .findByConfigKeyAndVersion("console.prompt-templates", 0).orElse(null);
        assertNotNull(seenByA, "tenant-a 必须能读到全局提示词模板");
        assertEquals(SHARED, seenByA.getTenantId());

        entityManager.clear();
        enterTenant("tenant-b");
        final ConfigEntity seenByB = configRepository
                .findByConfigKeyAndVersion("console.prompt-templates", 0).orElse(null);
        assertNotNull(seenByB, "tenant-b 必须能读到同一份全局提示词模板");
        assertEquals(SHARED, seenByB.getTenantId(), "两租户读到的必须是同一行（同一租户归属）");
    }

    @Test
    @DisplayName("pool_definitions 仍严格隔离：tenant-a 写的不被 tenant-b 看见")
    void tenantScopedPoolStaysIsolated() {
        configRepository.save(row("pool_definitions", "tenant-a"));

        enterTenant("tenant-a");
        assertNotNull(configRepository.findByConfigKeyAndVersion("pool_definitions", 0).orElse(null),
                "tenant-a 应看到自己的虚拟模型");

        entityManager.clear();
        enterTenant("tenant-b");
        assertNull(configRepository.findByConfigKeyAndVersion("pool_definitions", 0).orElse(null),
                "tenant-b 绝不能看到 tenant-a 的虚拟模型（跨租户泄露）");
    }

    @Test
    @DisplayName("rule_definitions 仍严格隔离：tenant-a 的规则不作用于 tenant-b")
    void tenantScopedRuleStaysIsolated() {
        configRepository.save(row("rule_definitions", "tenant-a"));

        enterTenant("tenant-b");
        assertNull(configRepository.findByConfigKeyAndVersion("rule_definitions", 0).orElse(null),
                "tenant-b 绝不能看到 tenant-a 的路由规则");
    }

    @Test
    @DisplayName("ConfigEntity#prePersist 把 console.* 强制改写成哨兵租户")
    void prePersistForcesGlobalKeyToSharedTenant() {
        // 模拟"在 tenant-a 上下文里保存提示词模板"
        useMultiTenantContext("tenant-a");
        final ConfigEntity entity = row("console.api-key-requests", "tenant-a");
        entity.prePersist();
        assertEquals(SHARED, entity.getTenantId(),
                "全局键在任意租户下保存都必须落哨兵租户，否则会产生 N 份互相不可见的副本");
    }

    @Test
    @DisplayName("ConfigEntity#prePersist 不改变隔离键的归属")
    void prePersistKeepsTenantScopedKeyOwner() {
        useMultiTenantContext("tenant-a");
        final ConfigEntity entity = row("pool_definitions", "default");
        entity.prePersist();
        assertEquals("tenant-a", entity.getTenantId(),
                "隔离键必须跟随当前租户，不能被哨兵逻辑吞掉");
    }

    @Test
    @DisplayName("模型分类（service_config）全局共享：不挂过滤器，任何租户都读到全量")
    void serviceConfigIsGlobal() {
        serviceConfigRepository.save(ServiceConfigEntity.builder()
                .configKey("classification")
                .serviceType("audio")
                .adapter("openai-compatible")
                .loadBalanceType("round-robin")
                .version(0)
                .isLatest(true)
                .tenantId("tenant-a")
                .build());

        enterTenant("tenant-b");
        final List<ServiceConfigEntity> visible = serviceConfigRepository.findAllByIsLatestTrue();
        assertEquals(1, visible.size(),
                "模型分类是协议事实，tenant-b 必须能读到 tenant-a 建的音频分类定义");
    }

    @Test
    @DisplayName("模型分类写入固化到哨兵租户，不随请求租户漂移")
    void serviceConfigWriteGoesToSharedTenant() {
        final ServiceConfigEntity entity = ServiceConfigEntity.builder()
                .configKey("classification")
                .serviceType("chat")
                .tenantId("tenant-a")
                .build();
        entity.prePersist();
        assertEquals(SHARED, entity.getTenantId(),
                "模型分类全平台一份，写入必须落同一处");
    }

    @Test
    @DisplayName("判定名单自检：console.* 判全局，pool/rule 判隔离")
    void keyClassificationMatchesDesign() {
        assertEquals(true, GlobalConfigKeys.isGlobal("console.prompt-templates"));
        assertEquals(false, GlobalConfigKeys.isGlobal("pool_definitions"));
        assertEquals(false, GlobalConfigKeys.isGlobal("rule_definitions"));
    }
}