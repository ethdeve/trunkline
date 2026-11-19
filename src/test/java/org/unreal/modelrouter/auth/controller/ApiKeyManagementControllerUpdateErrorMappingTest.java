/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.unreal.modelrouter.auth.security.dto.ApiKeyUpdateRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyVO;
import org.unreal.modelrouter.auth.security.service.ApiKeyNotFoundException;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.auth.security.service.ApiKeyWhitelistMismatchException;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.exception.ApiException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 更新 API 密钥时的异常 → HTTP 状态码映射测试（2026-10-24）。
 *
 * <p><b>本测试锁定的根因：</b>此前控制器的错误映射用的是<b>链式</b>
 * {@code onErrorResume}：</p>
 * <pre>
 *   .onErrorResume(ApiException.class, e -> Mono.error(e))
 *   .onErrorResume(IllegalArgumentException.class, e -&gt; Mono.error(ApiException.of("INVALID_REQUEST", ...)))
 *   .onErrorResume(e -&gt; Mono.error(ApiException.of("NOT_FOUND", ...)))   // ← 这个 catch-all 会吃掉上游的结果
 * </pre>
 *
 * <p>Reactor 的 {@code onErrorResume} 是「下游 catch-all 兜底」结构：
 * 上游算子用 {@code Mono.error(newEx)} 换出的新异常<b>会继续流向下游算子</b>，
 * 于是第 2 个算子刚转换好的 {@code INVALID_REQUEST} 又被第 3 个 catch-all
 * 改写成 {@code NOT_FOUND} —— 白名单分类不一致因此返回 404 而不是 400。</p>
 *
 * <p>修复后控制器只保留<b>单个</b> {@code onErrorResume}，在内部按类型显式分派。
 * 本测试逐条锁定各异常类型的正确状态码。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("ApiKeyManagementController.updateApiKey 异常映射测试")
class ApiKeyManagementControllerUpdateErrorMappingTest {

    /**
     * 用一个极小的假服务替代 Mockito（本机沙箱不允许 JVM self-attach，
     * Mockito 的 inline MockMaker 初始化会失败）。
     *
     * @param error 目标方法要抛出的异常
     * @return 控制器
     */
    private ApiKeyManagementController controllerThrowing(final Throwable error) {
        // ApiKeyManagementController 用 @RequiredArgsConstructor 构造，
        // 只有 ApiKeyService 与 ApiKeyQuotaService 两个依赖；
        // 本测试只走 updateApiKey，用不到的配额服务置 null 即可。
        final ApiKeyServiceStub service = new ApiKeyServiceStub(error);
        return new ApiKeyManagementController(service, null);
    }

    @Test
    @DisplayName("白名单与权限分类不一致 → 400 INVALID_REQUEST（不是 404）")
    void whitelistMismatchMapsTo400() {
        final ApiKeyManagementController controller = controllerThrowing(
                new ApiKeyWhitelistMismatchException("可访问模型/分组与「可调用的模型分类」不一致：[tts] m1"));

        final ApiKeyUpdateRequest request = ApiKeyUpdateRequest.builder().description("d").build();

        StepVerifier.create(controller.updateApiKey("k1", request, null))
                .expectErrorSatisfies(ex -> {
                    final ApiException apiEx = assertInstanceOf(ApiException.class, ex);
                    assertEquals("INVALID_REQUEST", apiEx.getErrorCode());
                    assertEquals(HttpStatus.BAD_REQUEST, apiEx.getStatus());
                })
                .verify();
    }

    @Test
    @DisplayName("API Key 不存在 → 404 NOT_FOUND")
    void notFoundMapsTo404() {
        final ApiKeyManagementController controller = controllerThrowing(
                new ApiKeyNotFoundException("API Key不存在: k1"));

        final ApiKeyUpdateRequest request = ApiKeyUpdateRequest.builder().description("d").build();

        StepVerifier.create(controller.updateApiKey("k1", request, null))
                .expectErrorSatisfies(ex -> {
                    final ApiException apiEx = assertInstanceOf(ApiException.class, ex);
                    assertEquals("NOT_FOUND", apiEx.getErrorCode());
                    assertEquals(HttpStatus.NOT_FOUND, apiEx.getStatus());
                })
                .verify();
    }

    @Test
    @DisplayName("其它 IllegalArgumentException（如配额阈值越界）→ 400 INVALID_REQUEST")
    void otherIllegalArgumentMapsTo400() {
        final ApiKeyManagementController controller = controllerThrowing(
                new IllegalArgumentException("quotaAlertThreshold 必须在 0.05~1.0 之间"));

        final ApiKeyUpdateRequest request = ApiKeyUpdateRequest.builder().description("d").build();

        StepVerifier.create(controller.updateApiKey("k1", request, null))
                .expectErrorSatisfies(ex -> {
                    final ApiException apiEx = assertInstanceOf(ApiException.class, ex);
                    assertEquals("INVALID_REQUEST", apiEx.getErrorCode());
                    assertEquals(HttpStatus.BAD_REQUEST, apiEx.getStatus());
                })
                .verify();
    }

    @Test
    @DisplayName("已是 ApiException → 原样透传（保留原始错误码与状态码）")
    void apiExceptionPassesThrough() {
        final ApiKeyManagementController controller = controllerThrowing(
                ApiException.of("CONFLICT_STATE", "冲突"));

        final ApiKeyUpdateRequest request = ApiKeyUpdateRequest.builder().description("d").build();

        StepVerifier.create(controller.updateApiKey("k1", request, null))
                .expectErrorSatisfies(ex -> {
                    final ApiException apiEx = assertInstanceOf(ApiException.class, ex);
                    assertEquals("CONFLICT_STATE", apiEx.getErrorCode());
                    assertEquals(HttpStatus.CONFLICT, apiEx.getStatus());
                })
                .verify();
    }

    @Test
    @DisplayName("未知运行时异常 → 404 NOT_FOUND（保持历史行为）")
    void unknownRuntimeExceptionMapsTo404() {
        final ApiKeyManagementController controller = controllerThrowing(
                new IllegalStateException("boom"));

        final ApiKeyUpdateRequest request = ApiKeyUpdateRequest.builder().description("d").build();

        StepVerifier.create(controller.updateApiKey("k1", request, null))
                .expectErrorSatisfies(ex -> {
                    final ApiException apiEx = assertInstanceOf(ApiException.class, ex);
                    assertEquals("NOT_FOUND", apiEx.getErrorCode());
                    assertEquals(HttpStatus.NOT_FOUND, apiEx.getStatus());
                })
                .verify();
    }

    /**
     * 只覆写 updateApiKey 的极简假服务。
     */
    private static final class ApiKeyServiceStub extends ApiKeyService {

        private final Throwable error;

        private ApiKeyServiceStub(final Throwable error) {
            // 父类只有带参构造器；本测试只用 updateApiKey，
            // 其余依赖（StoreManager / ObjectMapper / SecurityProperties）用不到，传 null。
            super(null, null, null);
            this.error = error;
        }

        @Override
        public Mono<ApiKeyVO> updateApiKey(final String kid, final ApiKeyUpdateRequest req, final String tenantHeader) {
            return Mono.error(error);
        }
    }

    /**
     * 保持 RouterResponse 类型的引用，避免未使用 import 告警。
     */
    @SuppressWarnings("unused")
    private static Class<?> responseType() {
        return RouterResponse.class;
    }
}
