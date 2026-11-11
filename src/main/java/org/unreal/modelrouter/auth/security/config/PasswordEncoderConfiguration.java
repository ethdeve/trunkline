package org.unreal.modelrouter.auth.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PasswordEncoder配置类
 * 专门用于配置密码编码器Bean，避免循环依赖问题
 */
@Configuration
public class PasswordEncoderConfiguration {

    /**
     * 配置密码编码器。
     *
     * <p>只注册 bcrypt 一种算法：库内账户口令均为 {@code $2a$} bcrypt 哈希，
     * 保留 {@code noop} 映射会让手工写入 {@code {noop}xxx} 的明文口令被直接比对通过，
     * 属于不必要的残留能力，故移除。DelegatingPasswordEncoder 仍会按前缀分派，
     * 历史无前缀哈希按默认 bcrypt 处理，不影响已有账户登录。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.password.DelegatingPasswordEncoder(
                "bcrypt",
                java.util.Map.of(
                        "bcrypt", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                )
        );
    }
}