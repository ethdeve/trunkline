/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

/**
 * 首次部署初始化接口。
 *
 * <p>这三个接口在「尚未创建任何管理员」时必须匿名可达，否则会陷入
 * 「没有管理员 → 无法登录 → 无法初始化」的死循环。安全边界由本服务保证：
 * 一旦库中已有 JWT 账户，数据源修改与管理员创建都会返回 409。</p>
 *
 * <p>同步 ResponseEntity 方法不添加 {@code @PreAuthorize}：本仓库开启了响应式方法安全，
 * 该注解只作用于返回 Publisher 的方法，加到同步方法上会在运行时 500。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(final SetupService setupService) {
        this.setupService = setupService;
    }

    /** 前端每次启动探测：是否已经初始化、是否需要重启切换数据源。 */
    @GetMapping("/status")
    public ResponseEntity<RouterResponse<SetupService.SetupStatus>> status() {
        return ResponseEntity.ok(RouterResponse.success(setupService.status()));
    }

    /** 步骤 1/2：选择本地 H2 或远程 PostgreSQL，并提交远程连接信息。 */
    @PostMapping("/datasource")
    public ResponseEntity<RouterResponse<SetupService.DatasourceResult>> configureDatasource(
            @RequestBody final DatasourceRequest request) {
        try {
            final SetupMode mode = parseMode(request == null ? null : request.mode());
            final SetupService.DatasourceResult result = setupService.configureDatasource(
                    mode, request == null ? null : request.datasourceUrl(),
                    request == null ? null : request.username(),
                    request == null ? null : request.password());
            return ResponseEntity.ok(RouterResponse.success(result));
        } catch (SetupException e) {
            return ResponseEntity.status(e.status())
                    .body(RouterResponse.error(e.getMessage(), e.errorCode()));
        } catch (RuntimeException e) {
            log.error("初始化向导保存数据源失败", e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("保存数据源配置失败", "SETUP_DATASOURCE_ERROR"));
        }
    }

    /** 步骤 3：创建首个管理员账户。 */
    @PostMapping("/admin")
    public ResponseEntity<RouterResponse<SetupService.AdminResult>> initializeAdmin(
            @RequestBody final AdminRequest request) {
        try {
            final SetupService.AdminResult result = setupService.initializeAdmin(
                    request == null ? null : request.username(),
                    request == null ? null : request.password());
            return ResponseEntity.ok(RouterResponse.success(result));
        } catch (SetupException e) {
            return ResponseEntity.status(e.status())
                    .body(RouterResponse.error(e.getMessage(), e.errorCode()));
        } catch (RuntimeException e) {
            log.error("初始化向导创建管理员失败", e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("创建管理员失败", "SETUP_ADMIN_ERROR"));
        }
    }

    private static SetupMode parseMode(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw SetupException.invalid("请选择数据源模式");
        }
        try {
            return SetupMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw SetupException.invalid("未知的数据源模式: " + raw);
        }
    }

    /** 数据源配置请求。 */
    public record DatasourceRequest(String mode, String datasourceUrl, String username, String password) {
    }

    /** 管理员初始化请求。 */
    public record AdminRequest(String username, String password) {
    }
}
