/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;

import java.util.List;

/**
 * 存量账户角色迁移（P0-2：彻底移除访客角色 VIEWER）。
 *
 * <p><b>用户角色存在哪里</b>：账户的角色存在 {@code jwt_accounts.roles} 列
 * （JSON 数组字符串，如 {@code ["ADMIN","USER"]}），实体见
 * {@link JwtAccountEntity#getRoles()}。<b>不</b>是从 {@code role_permissions} 表反查的
 * ——那张表存的是「角色 → 权限码」映射，与账户持有的角色清单是两回事。
 * 因此移除 VIEWER 必须同时处理两处存量数据：
 * <ol>
 *   <li>{@code role_permissions} 里 roleName='VIEWER' 的行 → 由
 *       启动期清理任务删除（该清理已随 RBAC 移除而删除）；</li>
 *   <li>{@code jwt_accounts.roles} 里含 VIEWER 的账户 → 由本类迁移为 USER。</li>
 * </ol>
 *
 * <p><b>为什么迁移到 USER</b>：VIEWER 的语义是「纯只读员工」，三档角色中语义最接近的是
 * USER（同样是只读视角的员工账号）。迁移后账户持有的权限码按 USER 重新解析
 * （登录 / 刷新令牌都从 {@code role_permissions} 实时取），因此权限会同步收敛。
 *
 * <p><b>幂等性</b>：只有当某个账户的 roles 里确实含 VIEWER 时才写库；
 * 第二次启动时已无 VIEWER，全部跳过，无任何写入。多次执行结果一致。
 *
 * <p><b>与「不删用户手工定制角色」策略不冲突</b>：本类只改写<b>账户持有的角色清单</b>里
 * 废弃角色那一项，不触碰 {@code role_permissions} 里任何角色的权限码，
 * 管理员手工定制角色的权限码完全不受影响。
 *
 * <p><b>启动顺序</b>：本类 {@link Order} 低于原 RolePermissionSeeder（已删除），
 * 先把账户迁到 USER，再由种子器清理 VIEWER 的权限行；两步都不会失败回滚，
 * 且即使本步失败，{@link AccountManager} 签发 JWT 时仍会把 VIEWER 归一化为 USER
 * （见 {@link DeprecatedRoles}），不会出现「角色被删但账户仍持有」导致的锁死。
 *
 * @author Trunkline Team
 */
@Slf4j
@Component
@Order(0)
public class JwtAccountRoleMigration implements ApplicationRunner {

    private static final TypeReference<List<String>> ROLE_LIST_TYPE = new TypeReference<>() {
    };

    private final JwtAccountRepository jwtAccountRepository;

    private final ObjectMapper objectMapper;

    public JwtAccountRoleMigration(final JwtAccountRepository jwtAccountRepository,
                                   final ObjectMapper objectMapper) {
        this.jwtAccountRepository = jwtAccountRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(final ApplicationArguments args) {
        migrate();
    }

    /**
     * 把所有持有废弃角色 VIEWER 的账户迁移到替代角色 USER。
     *
     * <p>幂等：不含 VIEWER 的账户直接跳过（不写库）。</p>
     *
     * @return 实际被迁移的账户数
     */
    @Transactional
    public int migrate() {
        final List<JwtAccountEntity> accounts = jwtAccountRepository.findAll();
        int migrated = 0;
        for (final JwtAccountEntity account : accounts) {
            final List<String> roles = parseRoles(account.getRoles());
            if (roles.isEmpty() || roles.stream().noneMatch(DeprecatedRoles.VIEWER::equalsIgnoreCase)) {
                continue;
            }
            final List<String> normalized = DeprecatedRoles.migrateRoles(roles);
            try {
                account.setRoles(objectMapper.writeValueAsString(normalized));
            } catch (JsonProcessingException e) {
                log.warn("迁移账户 {} 的废弃角色失败（跳过该账户）: {}", account.getUsername(), e.getMessage());
                continue;
            }
            jwtAccountRepository.save(account);
            migrated++;
            log.info("账户 {} 的角色已从废弃角色迁移为 {}: {} → {}",
                    account.getUsername(), DeprecatedRoles.VIEWER_REPLACEMENT, roles, normalized);
        }
        if (migrated > 0) {
            log.info("JwtAccountRoleMigration: 共迁移 {} 个持有废弃角色 {} 的账户为 {}",
                    migrated, DeprecatedRoles.VIEWER, DeprecatedRoles.VIEWER_REPLACEMENT);
        } else {
            log.info("JwtAccountRoleMigration: 无账户持有废弃角色 {}，无需迁移", DeprecatedRoles.VIEWER);
        }
        return migrated;
    }

    /**
     * 解析账户的角色 JSON（解析失败按无角色处理，避免因脏数据导致整批迁移失败）。
     */
    private List<String> parseRoles(final String rolesJson) {
        if (rolesJson == null || rolesJson.isBlank()) {
            return List.of();
        }
        try {
            final List<String> parsed = objectMapper.readValue(rolesJson, ROLE_LIST_TYPE);
            return parsed == null ? List.of() : parsed;
        } catch (JsonProcessingException e) {
            log.warn("解析账户角色 JSON 失败，按无角色处理: {}", e.getMessage());
            return List.of();
        }
    }
}
