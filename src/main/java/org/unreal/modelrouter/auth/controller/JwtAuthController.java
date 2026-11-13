package org.unreal.modelrouter.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.dto.JwtTokenInfo;
import org.unreal.modelrouter.common.dto.LoginRequest;
import org.unreal.modelrouter.common.dto.LoginResponse;
import org.unreal.modelrouter.common.dto.SsoLoginRequest;
import org.unreal.modelrouter.common.dto.TokenRefreshRequest;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.service.AccountManager;
import org.unreal.modelrouter.auth.security.service.JwtTokenOwnershipValidator;
import org.unreal.modelrouter.auth.security.service.JwtTokenRefreshService;
import org.unreal.modelrouter.auth.security.service.JwtPersistenceService;
import org.unreal.modelrouter.enterprise.spi.SsoConnector;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import org.unreal.modelrouter.common.exception.ApiException;

/**
 * JWT认证控制器
 * 提供用户登录和令牌刷新功能
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/jwt")
@RequiredArgsConstructor
@Tag(name = "JWT Authentication", description = "JWT认证API")
@ConditionalOnProperty(name = "trunkline.security.jwt.enabled", havingValue = "true")
public class JwtAuthController {

    private final JwtTokenRefreshService tokenRefreshService;
    private final AccountManager accountManager;
    private final SecurityProperties securityProperties;

    @Autowired(required = false)
    private JwtPersistenceService jwtPersistenceService;

    /**
     * SSO 扩展点（O7-a）：Noop 恒注册（{@code @ConditionalOnMissingBean}），实际不会为 null；
     * 判空仅为防御。企业版覆盖后 {@code providerName()} 返回 {@code oidc|feishu|dingtalk|wecom}。
     */
    @Autowired(required = false)
    private SsoConnector ssoConnector;

    /**
     * 用户登录获取JWT令牌
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用用户名和密码登录获取JWT令牌")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "登录成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "用户名或密码错误")
    })
    public Mono<RouterResponse<LoginResponse>> login(
            @Parameter(description = "登录请求") @RequestBody final LoginRequest request,
            final org.springframework.web.server.ServerWebExchange exchange) {

        log.info("收到用户登录请求: username={}", request.getUsername());

        String clientIp = getClientIpAddress(exchange);
        String userAgent = exchange != null ? exchange.getRequest().getHeaders().getFirst("User-Agent") : null;

        return accountManager.authenticateAndGenerateToken(
                request.getUsername(), request.getPassword(),
                securityProperties, clientIp, userAgent)
                .flatMap(token -> {
                    LoginResponse response = new LoginResponse();
                    response.setToken(token);
                    response.setTokenType("Bearer");
                    response.setExpiresIn(securityProperties.getJwt().getExpirationMinutes() * 60L);

                    if (jwtPersistenceService != null) {
                        log.info("保存令牌元数据到H2数据库: username={}, ip={}", request.getUsername(), clientIp);
                        return tokenRefreshService.saveTokenMetadata(
                                token, request.getUsername(),
                                userAgent, clientIp, userAgent)
                                .doOnSuccess(v -> log.info("✓ 令牌元数据已成功保存到H2数据库: username={}", request.getUsername()))
                                .then(Mono.just(RouterResponse.success(response, "登录成功")))
                                .onErrorResume(ex -> {
                                    log.error("✗ 保存令牌元数据失败: {}", ex.getMessage(), ex);
                                    return Mono.just(RouterResponse.success(response, "登录成功"));
                                });
                    } else {
                        log.warn("!!! JwtPersistenceService不可用，令牌未持久化 !!!");
                        return Mono.just(RouterResponse.success(response, "登录成功"));
                    }
                })
                .onErrorResume(ex -> {
                    log.warn("用户登录失败: {}", ex.getMessage());
                    return Mono.error(ApiException.of("LOGIN_FAILED", "登录失败: " + ex.getMessage()));
                });
    }

    /**
     * SSO 登录（O7-a）：企业 IdP 授权码换取本地 JWT。
     *
     * <p>与本地登录 {@link #login} 并存，不改其行为。判别依据是
     * {@code ssoConnector.providerName()} 是否为空串——Noop 由开源装配恒注册，
     * <b>不以 null 判别</b>（null 判断是死代码）。企业版接入真实 IdP 后，
     * 映射与签发在 {@link AccountManager#authenticateExternal}。</p>
     */
    @PostMapping("/sso")
    @Operation(summary = "SSO登录", description = "使用企业 IdP 授权码登录获取JWT令牌（社区版返回 SSO_UNSUPPORTED）")
    public Mono<RouterResponse<LoginResponse>> ssoLogin(
            @Parameter(description = "SSO 登录请求") @RequestBody final SsoLoginRequest request,
            final org.springframework.web.server.ServerWebExchange exchange) {

        // v1.2 更正（O7-a 验收）：providerName() 为空串（Noop）→ SSO_UNSUPPORTED，不以 null 判别
        if (ssoConnector == null || ssoConnector.providerName() == null
                || ssoConnector.providerName().isBlank()) {
            return Mono.error(ApiException.of("SSO_UNSUPPORTED", "当前版本不支持 SSO",
                    org.springframework.http.HttpStatus.BAD_REQUEST));
        }
        final String clientIp = getClientIpAddress(exchange);
        final String userAgent = exchange != null ? exchange.getRequest().getHeaders().getFirst("User-Agent") : null;

        // IdP 交换是网络 IO，统一切弹性线程（与 O7-c 同一线程模型约定）
        return Mono.fromCallable(() -> ssoConnector.exchange(request.getCode()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> user
                        .map(u -> accountManager.authenticateExternal(u, clientIp, userAgent))
                        .orElseGet(() -> Mono.error(ApiException.of("SSO_FAILED", "SSO 凭证无效或未配置",
                                org.springframework.http.HttpStatus.UNAUTHORIZED))))
                .flatMap(token -> {
                    LoginResponse response = new LoginResponse();
                    response.setToken(token);
                    response.setTokenType("Bearer");
                    response.setExpiresIn(securityProperties.getJwt().getExpirationMinutes() * 60L);
                    // 注意：此处不做令牌元数据持久化——本地登录的 saveTokenMetadata 以
                    // username 为维度，而本方法手里只有授权码与 token，拿不到映射后的
                    // 本地用户名（不把授权码当 username 写库）。SSO 登录的元数据落库
                    // 随 A2（企业侧拿到映射用户名）一起补。
                    return Mono.just(RouterResponse.success(response, "登录成功"));
                });
    }

    /**
     * 刷新JWT令牌
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新JWT令牌", description = "使用当前令牌获取新的JWT令牌")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "令牌刷新成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "当前令牌无效或已过期"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数错误")
    })
    public Mono<RouterResponse<JwtTokenInfo>> refreshToken(
            @Parameter(description = "令牌刷新请求") @RequestBody final TokenRefreshRequest request,
            final Authentication authentication,
            final org.springframework.web.server.ServerWebExchange exchange) {

        log.debug("收到JWT令牌刷新请求: user={}", authentication != null ? authentication.getName() : "anonymous");

        String clientIp = getClientIpAddress(exchange);
        String userAgent = exchange != null ? exchange.getRequest().getHeaders().getFirst("User-Agent") : null;

        // P0-L 安全修复（漏洞 3）：校验「待刷新令牌」的归属。
        //
        // 旧实现从请求体取 token 去刷新，authentication 只用于审计 —— 任何已认证用户
        // 只要拿到别人的令牌串，就能把别人的令牌刷新成新的（实测：USER 刷出了
        // roles=[ADMIN,USER] 的令牌，属于权限提升）。
        //
        // 语义：刷新是「续期自己的会话」，不是「代签发」。因此主体必须一致，
        // **ADMIN 也不能刷新他人的令牌**。
        return tokenRefreshService.subjectOf(request.getToken())
                .flatMap(tokenSubject -> {
                    final JwtTokenOwnershipValidator.Result ownership =
                            JwtTokenOwnershipValidator.check(
                                    tokenSubject,
                                    authentication != null ? authentication.getName() : null);
                    return switch (ownership) {
                        case ALLOWED -> Mono.just(true);
                        // 显式指定状态码：resolveStatus 只按已知错误码规则推导，
                        // 未知码会落到 500 —— 越权刷新必须明确返回 403，不能是「服务器错误」。
                        case UNAUTHENTICATED -> Mono.error(ApiException.of(
                                "TOKEN_REFRESH_UNAUTHENTICATED", "未认证，无法刷新令牌",
                                org.springframework.http.HttpStatus.UNAUTHORIZED));
                        case INVALID_TOKEN -> Mono.error(ApiException.of(
                                "TOKEN_REFRESH_INVALID", "待刷新的令牌无效",
                                org.springframework.http.HttpStatus.UNAUTHORIZED));
                        case FORBIDDEN -> Mono.error(ApiException.of(
                                "TOKEN_REFRESH_FORBIDDEN", "只能刷新属于自己的令牌",
                                org.springframework.http.HttpStatus.FORBIDDEN));
                    };
                })
                .then(tokenRefreshService.refreshToken(request.getToken(), clientIp, userAgent))
                .flatMap(newToken -> {
                    JwtTokenInfo response = new JwtTokenInfo();
                    response.setToken(newToken);
                    response.setTokenType("Bearer");
                    response.setMessage("令牌刷新成功");
                    response.setTimestamp(LocalDateTime.now());

                    if (jwtPersistenceService != null && authentication != null) {
                        return tokenRefreshService.saveTokenOnRefreshWithContext(
                                request.getToken(), newToken,
                                userAgent, clientIp, userAgent)
                                .then(Mono.just(RouterResponse.success(response, "令牌刷新成功")))
                                .onErrorResume(ex -> {
                                    log.warn("刷新时保存令牌元数据失败: {}", ex.getMessage());
                                    return Mono.just(RouterResponse.success(response, "令牌刷新成功"));
                                });
                    } else {
                        return Mono.just(RouterResponse.success(response, "令牌刷新成功"));
                    }
                })
                .onErrorResume(ex -> {
                    log.warn("JWT令牌刷新失败: {}", ex.getMessage());

                    // 归属校验失败属于**业务拒绝**（401/403），不是服务端错误。
                    // 必须原样透出，不能被重包成 TOKEN_REFRESH_FAILED（500）——
                    // 否则「越权刷新」在客户端看是「服务器坏了」，语义完全错位。
                    if (ex instanceof ApiException apiException) {
                        return Mono.error(apiException);
                    }

                    JwtTokenInfo response = new JwtTokenInfo();
                    response.setMessage("令牌刷新失败: " + ex.getMessage());
                    response.setTimestamp(LocalDateTime.now());

                    return Mono.error(ApiException.of("TOKEN_REFRESH_FAILED", "令牌刷新失败: " + ex.getMessage()));
                });
    }

    private String getClientIpAddress(final org.springframework.web.server.ServerWebExchange exchange) {
        return org.unreal.modelrouter.auth.security.util.ClientIpUtils.getClientIpAddress(exchange);
    }
}
