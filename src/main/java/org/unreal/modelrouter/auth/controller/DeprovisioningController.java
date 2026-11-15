package org.unreal.modelrouter.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.auth.security.service.AccountManager;
import org.unreal.modelrouter.auth.security.service.DeprovisioningSummary;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * A4 最小回收闭环（闸门② P0 红线）——离职/停用回收的「可复跑脚本」入口。
 *
 * <p>路径未登记于 {@link org.unreal.modelrouter.auth.security.access.FixedRoleAccessPolicy}，
 * 按 fail-closed 仅 {@code ADMIN} 直通；方法级 {@code @PreAuthorize("hasRole('ADMIN')")}
 * 双重保险。外部系统（HR / IdP 移除事件）以 curl / 定时脚本调用即「可复跑脚本」。</p>
 *
 * <p>闭环动作（详见 {@code AccountManager}）：
 * 禁用本地账号 + 清空组成员 + 立即使 JWT 校验失效（{@code AccountStatusGuard} 缓存失效）
 * + 吊销该用户持有的全部 API Key + 审计留痕，幂等可重复执行。</p>
 */
@RestController
@RequestMapping("api/auth/admin/deprovision")
@PreAuthorize("hasRole('ADMIN')")
public class DeprovisioningController {

    private final AccountManager accountManager;

    public DeprovisioningController(final AccountManager accountManager) {
        this.accountManager = accountManager;
    }

    /**
     * 单账号停用：给定外部身份（externalId 和/或 email），回收其本地账号 + JWT + API Key + 角色。
     *
     * <p>示例（脚本可复跑）：</p>
     * <pre>{@code
     * curl -X POST 'https://gw/api/auth/admin/deprovision/account' \
     *   -H 'Content-Type: application/json' -u admin:*** \
     *   -d '{"externalId":"emp_12345","email":"alice@corp.com"}'
     * }</pre>
     */
    @PostMapping("/account")
    public Mono<ResponseEntity<Void>> deprovisionAccount(@RequestBody final DeprovisionRequest request) {
        return accountManager
                .disableExternalAccountByIdentity(request.externalId(), request.email())
                .then(Mono.just(ResponseEntity.ok().build()));
    }

    /**
     * 全量对账：给定当前【仍活跃】的外部身份集合，停用所有不在集合内的 SSO 本地账号。
     *
     * <p>示例（每日定时跑）：</p>
     * <pre>{@code
     * curl -X POST 'https://gw/api/auth/admin/deprovision/reconcile' \
     *   -H 'Content-Type: application/json' -u admin:*** \
     *   -d '{"activeExternalIds":["emp_1","emp_2","emp_3"]}'
     * }</pre>
     */
    @PostMapping("/reconcile")
    public Mono<ResponseEntity<DeprovisioningSummary>> reconcile(
            @RequestBody final ReconcileRequest request) {
        return accountManager.reconcileExternalAccounts(request.activeExternalIds())
                .map(ResponseEntity::ok);
    }

    /** 单账号停用请求体。 */
    public record DeprovisionRequest(String externalId, String email) {
    }

    /** 全量对账请求体。 */
    public record ReconcileRequest(Set<String> activeExternalIds) {
    }
}
