/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.router.handler.ServiceRequestHandler;

/**
 * 调用者身份解析器（v3.5 Trunkline）
 *
 * <p>回答「这次调用是谁发起的」——供「调用观测」与「团队用量」两个页面共用，
 * 保证同一个请求在两处的归属口径<b>完全一致</b>。</p>
 *
 * <p>两种来源：</p>
 * <ul>
 *   <li><b>控制台 JWT 登录态</b>：{@code CALLER_ID_ATTRIBUTE} 形如 {@code jwt:<用户名>}，
 *       去前缀即员工用户名；</li>
 *   <li><b>API Key 调用</b>：按 {@code apiKeyId} 查密钥的 {@code createdBy}（持有人），
 *       与「全部密钥」页的「持有人」列同源。</li>
 * </ul>
 *
 * <p>取不到时返回 {@code null}（密钥已删除 / 系统密钥 / 缓存未同步），
 * 调用方据此把该行归入「未知」或「未归类」，不丢数据。</p>
 * @author Trunkline Team
 */
@Component
public class CallerIdentityResolver {

    private static final Logger logger = LoggerFactory.getLogger(CallerIdentityResolver.class);

    private final ApiKeyService apiKeyService;

    public CallerIdentityResolver(final ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * 解析调用者身份。
     *
     * @param httpRequest 原始请求（读取 JWT 调用主体标记），可为 {@code null}
     * @param apiKeyId    本次请求使用的 API Key ID，可为 {@code null}
     * @return 员工用户名；未知时为 {@code null}
     */
    public String resolve(final ServerHttpRequest httpRequest, final String apiKeyId) {
        final String jwtUser = resolveFromJwt(httpRequest);
        if (jwtUser != null) {
            return jwtUser;
        }
        return resolveFromApiKey(apiKeyId);
    }

    /** JWT 登录态：{@code jwt:<用户名>} → 用户名 */
    private String resolveFromJwt(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        try {
            final Object callerId = httpRequest.getAttributes().get(ServiceRequestHandler.CALLER_ID_ATTRIBUTE);
            if (callerId instanceof String value && value.startsWith(ServiceRequestHandler.JWT_CALLER_ID_PREFIX)) {
                final String username = value.substring(ServiceRequestHandler.JWT_CALLER_ID_PREFIX.length()).trim();
                if (!username.isEmpty()) {
                    return username;
                }
            }
        } catch (Exception e) {
            logger.debug("解析调用者身份失败（忽略）: {}", e.getMessage());
        }
        return null;
    }

    /** API Key 调用：密钥持有人（{@code ApiKey.createdBy}） */
    private String resolveFromApiKey(final String apiKeyId) {
        if (apiKeyId == null || apiKeyId.isBlank() || apiKeyService == null) {
            return null;
        }
        try {
            return apiKeyService.findHolderByKeyId(apiKeyId);
        } catch (Exception e) {
            logger.debug("按 API Key 查持有人失败（忽略）: {}", e.getMessage());
            return null;
        }
    }
}
