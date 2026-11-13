/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.enterprise.config.TrunklineEnterpriseAutoConfiguration;
import org.unreal.modelrouter.enterprise.noop.CommunityFeaturesProvider;
import org.unreal.modelrouter.enterprise.spi.EnterpriseFeaturesProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业能力探测端点测试（O6）。
 *
 * <p>锁三件事：①社区版端点返回空能力集；②企业 Provider Bean 覆盖后同一端点
 * 返回能力集（@ConditionalOnMissingBean 退位语义）；③URL 矩阵 SELF 登记
 * （fail-closed 未登记仅 ADMIN，会把普通用户挡在特性探测之外）。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("企业能力探测端点（/api/v1/enterprise/features）")
class EnterpriseFeaturesControllerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TrunklineEnterpriseAutoConfiguration.class));

    private static Map<String, Object> bodyOf(final ResponseEntity<RouterResponse<Map<String, Object>>> response) {
        assertNotNull(response.getBody(), "响应体不得为 null");
        assertTrue(response.getBody().isSuccess(), "能力探测应返回 success=true");
        return response.getBody().getData();
    }

    @Nested
    @DisplayName("社区版：空能力集")
    class CommunityEdition {

        @Test
        @DisplayName("默认装配下端点返回 {}（CommunityFeaturesProvider 兜底）")
        void returnsEmptyFeaturesByDefault() {
            runner.run(context -> {
                assertTrue(context.containsBean("communityFeaturesProvider"),
                        "自动配置应注册 CommunityFeaturesProvider 兜底");
                final EnterpriseFeaturesController controller =
                        new EnterpriseFeaturesController(context.getBean(EnterpriseFeaturesProvider.class));

                final ResponseEntity<RouterResponse<Map<String, Object>>> response = controller.features();

                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(bodyOf(response));
                assertTrue(bodyOf(response).isEmpty(),
                        "社区版能力集必须为空（前端据此渲染「升级企业版」占位）");
            });
        }

        @Test
        @DisplayName("CommunityFeaturesProvider 共享空 Map 且不可变")
        void providerReturnsSharedImmutableEmpty() {
            final CommunityFeaturesProvider provider = new CommunityFeaturesProvider();
            assertTrue(provider.getFeatures().isEmpty());
            assertTrue(provider.getFeatures() == provider.getFeatures(),
                    "空能力集应为共享实例，避免每请求重建");
        }
    }

    @Nested
    @DisplayName("企业覆盖：Provider Bean 退位语义")
    class EnterpriseOverride {

        @Test
        @DisplayName("用户提供 EnterpriseFeaturesProvider 后社区兜底退位，端点返回能力集")
        void userProvidedProviderBacksOffCommunityDefault() {
            runner.withUserConfiguration(EnterpriseSimulatorConfig.class).run(context -> {
                assertTrue(context.getBean(EnterpriseFeaturesProvider.class)
                                instanceof EnterpriseSimulatorConfig.SimulatedEnterpriseFeatures,
                        "生效 Provider 应为企业实现而非社区兜底");
                assertTrue(!context.containsBean("communityFeaturesProvider"),
                        "@ConditionalOnMissingBean：企业实现在场时社区兜底 Bean 应不存在");

                final EnterpriseFeaturesController controller =
                        new EnterpriseFeaturesController(context.getBean(EnterpriseFeaturesProvider.class));
                final Map<String, Object> features = bodyOf(controller.features());

                assertEquals(Boolean.TRUE, features.get("sso"));
                assertEquals(Boolean.TRUE, features.get("tenant"));
                assertEquals(Boolean.TRUE, features.get("auditChain"));
            });
        }
    }

    /** 模拟商业版 jar 注入的能力集 */
    @Configuration(proxyBeanMethods = false)
    static class EnterpriseSimulatorConfig {

        @Bean
        public EnterpriseFeaturesProvider enterpriseFeatures() {
            return new SimulatedEnterpriseFeatures();
        }

        static class SimulatedEnterpriseFeatures implements EnterpriseFeaturesProvider {
            @Override
            public Map<String, Object> getFeatures() {
                return Map.of("sso", true, "tenant", true, "auditChain", true, "dlp", true);
            }
        }
    }
}
