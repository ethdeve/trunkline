/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

/**
 * API Key 不存在异常（2026-10-24 新增）。
 *
 * <p><b>为什么需要它：</b>密钥服务层用 {@link IllegalArgumentException} 同时表达
 * 「资源不存在」与「客户端输入不合法」两类语义，而控制器的错误映射必须区分二者：</p>
 * <ul>
 *   <li>资源不存在 → <b>404 NOT_FOUND</b></li>
 *   <li>客户端输入不合法 → <b>400 INVALID_REQUEST</b></li>
 * </ul>
 *
 * <p>2026-10-24 之前，{@code updateApiKey} 的 catch-all 把所有异常压成 404，
 * 于是「白名单与权限分类不一致」这种纯客户端错误也返回 404，前端拿不到真实原因。
 * 引入本类后，控制器可以按类型精确分派，不再依赖「看错误消息猜语义」。</p>
 *
 * <p>继承 {@link IllegalArgumentException} 以保持既有调用点
 * （{@code catch (IllegalArgumentException)} 与既有单测断言）行为不变。</p>
 *
 * @author Trunkline Team
 */
public class ApiKeyNotFoundException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 报错文案（通常包含被查找的 keyId）
     */
    public ApiKeyNotFoundException(final String message) {
        super(message);
    }
}
