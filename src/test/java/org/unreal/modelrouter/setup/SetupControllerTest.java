/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 初始化接口 HTTP 状态映射测试。
 *
 * <p>验证 SetupException 被正确翻译为 400 / 409，且匿名探测状态返回 200 不带凭据。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("SetupController 公开初始化接口")
class SetupControllerTest {

    private SetupService service;
    private SetupController controller;

    @BeforeEach
    void setUp() {
        service = mock(SetupService.class);
        controller = new SetupController(service);
    }

    @Test
    @DisplayName("GET /status 返回状态体，initialized=false")
    void status_ok() {
        SetupService.SetupStatus status = new SetupService.SetupStatus(false, "LOCAL", false, "H2", false, "尚未初始化");
        when(service.status()).thenReturn(status);

        ResponseEntity<RouterResponse<SetupService.SetupStatus>> resp = controller.status();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isSuccess());
        assertFalse(resp.getBody().getData().initialized());
        assertFalse(resp.getBody().toString().contains("password"));
    }

    @Test
    @DisplayName("POST /datasource 参数非法 → 400 + SETUP_INVALID_INPUT")
    void datasource_invalid() {
        when(service.configureDatasource(any(), any(), any(), any()))
                .thenThrow(SetupException.invalid("远程数据源仅支持 PostgreSQL"));

        ResponseEntity<RouterResponse<SetupService.DatasourceResult>> resp = controller.configureDatasource(
                new SetupController.DatasourceRequest("REMOTE", "jdbc:h2:mem:test", "u", "p"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
        assertEquals("SETUP_INVALID_INPUT", resp.getBody().getErrorCode());
    }

    @Test
    @DisplayName("POST /datasource 已初始化 → 409 + SETUP_ALREADY_INITIALIZED")
    void datasource_conflict() {
        when(service.configureDatasource(any(), any(), any(), any()))
                .thenThrow(SetupException.alreadyInitialized());

        ResponseEntity<RouterResponse<SetupService.DatasourceResult>> resp = controller.configureDatasource(
                new SetupController.DatasourceRequest("LOCAL", null, null, null));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("SETUP_ALREADY_INITIALIZED", resp.getBody().getErrorCode());
    }

    @Test
    @DisplayName("POST /admin 成功返回用户名")
    void admin_ok() {
        when(service.initializeAdmin(anyString(), anyString()))
                .thenReturn(new SetupService.AdminResult("admin", "管理员账户已创建"));

        ResponseEntity<RouterResponse<SetupService.AdminResult>> resp =
                controller.initializeAdmin(new SetupController.AdminRequest("admin", "Kd9#pR3!wM7@xQ2v"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().isSuccess());
        assertEquals("admin", resp.getBody().getData().username());
    }

    @Test
    @DisplayName("POST /admin 已初始化 → 409")
    void admin_conflict() {
        when(service.initializeAdmin(anyString(), anyString()))
                .thenThrow(SetupException.alreadyInitialized());

        ResponseEntity<RouterResponse<SetupService.AdminResult>> resp =
                controller.initializeAdmin(new SetupController.AdminRequest("admin", "Kd9#pR3!wM7@xQ2v"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("SETUP_ALREADY_INITIALIZED", resp.getBody().getErrorCode());
    }

    @Test
    @DisplayName("POST /admin 未重启 → 409 + SETUP_RESTART_REQUIRED")
    void admin_restartRequired() {
        when(service.initializeAdmin(anyString(), anyString()))
                .thenThrow(SetupException.restartRequired());

        ResponseEntity<RouterResponse<SetupService.AdminResult>> resp =
                controller.initializeAdmin(new SetupController.AdminRequest("admin", "Kd9#pR3!wM7@xQ2v"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("SETUP_RESTART_REQUIRED", resp.getBody().getErrorCode());
    }
}
