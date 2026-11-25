package org.unreal.modelrouter.auth.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.auth.security.audit.ExtendedSecurityAuditService;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.dto.CreateJwtAccountRequest;
import org.unreal.modelrouter.common.dto.JwtAccountDTO;
import org.unreal.modelrouter.auth.security.service.JwtAccountService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT 账户控制器
 * v1.5.2: 使用 JPA 实现，使用 DTO 替代 Map
 * v1.5.3: 使用 RouterResponse 包装响应
 * v1.5.4: 身份全生命周期举证 —— 增删改/启停/凭据校验写安全审计
 *         （ACCOUNT_CREATED / ACCOUNT_UPDATED / ACCOUNT_DELETED / ACCOUNT_VERIFIED），
 *         经 {@code persist()} 统一出口进 B1 哈希链；审计失败不阻断业务主流程。
 *         绝不记录密码明文。
 */
@Slf4j
@RestController
@RequestMapping("/api/security/jwt/accounts")
@RequiredArgsConstructor
public class JwtAccountController {

    private final JwtAccountService jwtAccountService;
    private final ObjectProvider<ExtendedSecurityAuditService> auditServiceProvider;

    /**
     * 获取所有账户
     */
    @GetMapping
    public ResponseEntity<RouterResponse<List<JwtAccountDTO>>> getAllAccounts() {
        log.debug("Getting all JWT accounts");
        List<JwtAccountDTO> accounts = jwtAccountService.getAllAccounts();
        return ResponseEntity.ok(RouterResponse.success(accounts, "获取账户列表成功"));
    }

    /**
     * 获取全部部门列表（去重排序）。
     *
     * <p>供账号页部门下拉与「团队用量」部门维度筛选使用（前端不硬编码部门清单）。</p>
     */
    @GetMapping("/departments")
    public ResponseEntity<RouterResponse<List<String>>> getDepartments() {
        return ResponseEntity.ok(RouterResponse.success(jwtAccountService.getDepartments()));
    }

    /**
     * 获取单个账户
     */
    @GetMapping("/{username}")
    public ResponseEntity<RouterResponse<JwtAccountDTO>> getAccount(@PathVariable final String username) {
        log.debug("Getting JWT account: {}", username);
        JwtAccountDTO account = jwtAccountService.getAccount(username);
        return ResponseEntity.ok(RouterResponse.success(account, "获取账户信息成功"));
    }

    /**
     * 创建账户
     */
    @PostMapping
    public ResponseEntity<RouterResponse<JwtAccountDTO>> createAccount(
            @RequestBody final CreateJwtAccountRequest request) {
        log.info("Creating JWT account: {}", request.getUsername());
        JwtAccountDTO created = jwtAccountService.createAccount(request);
        audit("ACCOUNT_CREATED", request.getUsername(),
                Map.of("roles", String.valueOf(request.getRoles()),
                        "department", nullSafe(request.getDepartment()),
                        "enabled", String.valueOf(request.getEnabled())),
                true);
        return ResponseEntity.ok(RouterResponse.success(created, "账户创建成功"));
    }

    /**
     * 更新账户
     */
    @PutMapping("/{username}")
    public ResponseEntity<RouterResponse<JwtAccountDTO>> updateAccount(
            @PathVariable final String username,
            @RequestBody final CreateJwtAccountRequest request) {
        log.info("Updating JWT account: {}", username);
        JwtAccountDTO updated = jwtAccountService.updateAccount(username, request);
        audit("ACCOUNT_UPDATED", username,
                Map.of("roles", String.valueOf(request.getRoles()),
                        "department", nullSafe(request.getDepartment()),
                        "passwordChanged", String.valueOf(request.getPassword() != null
                                && !request.getPassword().isBlank())),
                true);
        return ResponseEntity.ok(RouterResponse.success(updated, "账户更新成功"));
    }

    /**
     * 删除账户
     */
    @DeleteMapping("/{username}")
    public ResponseEntity<RouterResponse<Void>> deleteAccount(@PathVariable final String username) {
        log.info("Deleting JWT account: {}", username);
        jwtAccountService.deleteAccount(username);
        audit("ACCOUNT_DELETED", username, Map.of(), true);
        return ResponseEntity.ok(RouterResponse.success(null, "账户删除成功"));
    }

    /**
     * 验证密码
     */
    @PostMapping("/{username}/verify")
    public ResponseEntity<RouterResponse<Boolean>> verifyPassword(
            @PathVariable final String username,
            @RequestBody final Map<String, String> credentials) {
        String password = credentials.get("password");
        boolean valid = jwtAccountService.verifyPassword(username, password);
        audit("ACCOUNT_VERIFIED", username, Map.of("result", String.valueOf(valid)), valid);
        return ResponseEntity.ok(RouterResponse.success(valid, "密码验证完成"));
    }

    /**
     * 切换账户状态
     */
    @PatchMapping("/{username}/status")
    public ResponseEntity<RouterResponse<JwtAccountDTO>> toggleAccountStatus(
            @PathVariable final String username,
            @RequestParam final boolean enabled) {
        log.info("Toggling JWT account status: {} -> {}", username, enabled);
        JwtAccountDTO updated = jwtAccountService.toggleAccountStatus(username, enabled);
        audit("ACCOUNT_UPDATED", username, Map.of("statusChanged", enabled ? "ENABLED" : "DISABLED"), true);
        String message = enabled ? "账户已启用" : "账户已禁用";
        return ResponseEntity.ok(RouterResponse.success(updated, message));
    }

    /**
     * 写账号生命周期审计：fire-and-forget（业务不受审计故障影响），落库经统一 persist 出口进 B1。
     * 只记录目标账号与变更摘要，绝不落密码明文。
     */
    private void audit(final String eventType, final String targetUser,
                       final Map<String, Object> extra, final boolean success) {
        try {
            final ExtendedSecurityAuditService svc = auditServiceProvider.getIfAvailable();
            if (svc == null) {
                return;
            }
            final Map<String, Object> data = new LinkedHashMap<>(extra);
            data.put("targetAccount", targetUser);
            final String details = eventType + " target=" + targetUser
                    + (extra.isEmpty() ? "" : " " + extra);
            svc.auditSecurityEvent(eventType, details, currentUser(), null)
                    .subscribe(null, err -> log.warn("账号审计写入失败（业务不受影响）: {}", err.getMessage()));
        } catch (final RuntimeException e) {
            log.warn("账号审计构造失败（业务不受影响）: {}", e.getMessage());
        }
    }

    private static String currentUser() {
        try {
            final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null ? "unknown" : auth.getName();
        } catch (final RuntimeException e) {
            return "unknown";
        }
    }

    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }
}
