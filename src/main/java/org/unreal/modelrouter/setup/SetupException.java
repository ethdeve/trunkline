/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import org.springframework.http.HttpStatus;

/**
 * 初始化向导可预期失败（映射为明确的 HTTP 状态与错误码）。
 *
 * @author Trunkline Team
 */
public class SetupException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public SetupException(final HttpStatus status, final String errorCode, final String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    /** 参数不合法 */
    public static SetupException invalid(final String message) {
        return new SetupException(HttpStatus.BAD_REQUEST, "SETUP_INVALID_INPUT", message);
    }

    /** 已经初始化，不允许重复执行 */
    public static SetupException alreadyInitialized() {
        return new SetupException(HttpStatus.CONFLICT, "SETUP_ALREADY_INITIALIZED",
                "系统已完成初始化，不能重复创建管理员或修改数据源");
    }

    /** 远程数据源已保存但服务尚未切换到该数据源 */
    public static SetupException restartRequired() {
        return new SetupException(HttpStatus.CONFLICT, "SETUP_RESTART_REQUIRED",
                "远程数据源配置已保存，但服务尚未切换到该数据源。请重启服务后继续初始化管理员。");
    }
}
