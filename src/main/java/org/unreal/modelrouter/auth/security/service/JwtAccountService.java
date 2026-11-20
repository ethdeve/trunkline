package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.common.dto.CreateJwtAccountRequest;
import org.unreal.modelrouter.common.dto.JwtAccountDTO;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 账户服务
 * v1.5.2: 使用 JPA 实现，使用 DTO 替代 Map
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtAccountService {

    private final JwtAccountRepository jwtAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    /**
     * 【issue #704】账户状态守卫（可选依赖）：删除/禁用账户后立即失效 JWT 验证期的
     * 30 秒状态缓存，让旧令牌当场失效，而不是等缓存自然过期。同包，无需 import。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AccountStatusGuard accountStatusGuard;

    /**
     * 获取所有账户
     */
    public List<JwtAccountDTO> getAllAccounts() {
        return jwtAccountRepository.findAll()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 获取单个账户
     */
    public JwtAccountDTO getAccount(final String username) {
        return jwtAccountRepository.findByUsername(username)
                .map(this::convertToDTO)
                .orElseThrow(() -> new RuntimeException("Account not found: " + username));
    }

    /**
     * 判断账户是否存在（用于「分配给谁」这类引用完整性校验）。
     *
     * <p>YAML 中配置的初始账户会被 {@code JpaDatabaseInitializer} 播种进库，
     * 因此查库即可覆盖「配置账户 + 控制台新建账户」全部来源。</p>
     *
     * @param username 用户名
     * @return 存在返回 true
     */
    public boolean accountExists(final String username) {
        return username != null && !username.isBlank()
                && jwtAccountRepository.existsByUsername(username.trim());
    }

    /**
     * 创建账户
     */
    @Transactional
    public JwtAccountDTO createAccount(final CreateJwtAccountRequest request) {
        if (jwtAccountRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists: " + request.getUsername());
        }

        try {
            JwtAccountEntity entity = JwtAccountEntity.builder()
                    .username(request.getUsername())
                    .password(passwordEncoder.encode(request.getPassword()))
                    .roles(objectMapper.writeValueAsString(request.getRoles()))
                    .department(normalizeDepartment(request.getDepartment()))
                    .name(normalizeName(request.getName()))
                    .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                    .build();

            JwtAccountEntity saved = jwtAccountRepository.save(entity);
            log.info("Created JWT account: {} with enabled={}", request.getUsername(), entity.getEnabled());
            return convertToDTO(saved);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize roles", e);
        }
    }

    /**
     * 更新账户
     */
    @Transactional
    public JwtAccountDTO updateAccount(final String username, final CreateJwtAccountRequest request) {
        JwtAccountEntity entity = jwtAccountRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Account not found: " + username));

        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            entity.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        if (request.getRoles() != null) {
            try {
                entity.setRoles(objectMapper.writeValueAsString(request.getRoles()));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize roles", e);
            }
        }
        // 部门：null = 不修改（保持原值）；空串 = 清空
        if (request.getDepartment() != null) {
            entity.setDepartment(normalizeDepartment(request.getDepartment()));
        }
        // 姓名：null = 不修改；空串 = 清空
        if (request.getName() != null) {
            entity.setName(normalizeName(request.getName()));
        }

        JwtAccountEntity saved = jwtAccountRepository.save(entity);
        log.info("Updated JWT account: {}", username);
        return convertToDTO(saved);
    }

    /**
     * 删除账户
     */
    @Transactional
    public void deleteAccount(final String username) {
        jwtAccountRepository.findByUsername(username).ifPresent(entity -> {
            jwtAccountRepository.delete(entity);
            log.info("Deleted JWT account: {}", username);
        });
        invalidateCachedStatus(username);
    }

    /** 账户状态变更后清缓存；未装配守卫（如极简单测上下文）时静默跳过。 */
    private void invalidateCachedStatus(final String username) {
        if (accountStatusGuard != null) {
            accountStatusGuard.invalidate(username);
        }
    }

    /**
     * 验证密码
     */
    public boolean verifyPassword(final String username, final String password) {
        return jwtAccountRepository.findByUsername(username)
                .map(entity -> passwordEncoder.matches(password, entity.getPassword()))
                .orElse(false);
    }

    /**
     * 切换账户状态
     */
    @Transactional
    public JwtAccountDTO toggleAccountStatus(final String username, final boolean enabled) {
        JwtAccountEntity entity = jwtAccountRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Account not found: " + username));
        entity.setEnabled(enabled);
        JwtAccountEntity saved = jwtAccountRepository.save(entity);
        invalidateCachedStatus(username);
        log.info("Toggled JWT account status: {} -> {}", username, enabled);
        return convertToDTO(saved);
    }

    /**
     * 获取全部部门列表（去重、按名称排序）。
     *
     * <p>供账号页部门下拉与「团队用量」部门维度筛选使用，避免前端硬编码部门清单。</p>
     */
    public List<String> getDepartments() {
        return jwtAccountRepository.findAll().stream()
                .map(JwtAccountEntity::getDepartment)
                .filter(d -> d != null && !d.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 归一化部门：去空白，空串归 null（表示「未分配部门」）。
     */
    private static String normalizeDepartment(final String department) {
        if (department == null) {
            return null;
        }
        final String trimmed = department.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 归一化姓名：去空白，空串归 null（表示「未填写」）。
     */
    private static String normalizeName(final String name) {
        if (name == null) {
            return null;
        }
        final String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private JwtAccountDTO convertToDTO(final JwtAccountEntity entity) {
        List<Object> roles = List.of();
        try {
            if (entity.getRoles() != null) {
                roles = objectMapper.readValue(entity.getRoles(), List.class);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse roles for user: {}", entity.getUsername());
        }

        return JwtAccountDTO.builder()
                .id(entity.getId())
                .username(entity.getUsername())
                .roles(roles)
                .department(entity.getDepartment())
                .name(entity.getName())
                .enabled(entity.getEnabled() != null ? entity.getEnabled() : true)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
