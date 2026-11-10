/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

/**
 * 刷新令牌时的身份重算失败异常（fail-closed）。
 *
 * <p>语义：刷新时无法重算出权威的 roles / permissions —— 宁可让整个刷新失败，
 * 也不能签发一个仍带旧权限的令牌（越权复活）。因此本异常必须向上抛出，
 * 不允许被降级成「沿用旧 claim」。
 *
 * @author Trunkline Team
 */
public class JwtRefreshIdentityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JwtRefreshIdentityException(final String message) {
        super(message);
    }

    public JwtRefreshIdentityException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
