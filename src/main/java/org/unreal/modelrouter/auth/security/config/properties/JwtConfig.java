package org.unreal.modelrouter.auth.security.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * JWT配置类
 */
@Data
public class JwtConfig {
    /**
     * 是否启用JWT认证
     */
    private boolean enabled = false;

    /**
     * JWT 令牌请求头名称。
     *
     * <p>仅接受此配置头（大小写不敏感）；旧版本认证头已不再兼容。
     */
    @NotBlank
    @Size(min = 1, max = 100)
    private String jwtHeader = "Trunkline_Token";

    /**
     * JWT签名密钥
     */
    @Size(min = 32, message = "JWT密钥长度至少32个字符；请设置环境变量 JWT_SECRET（可用 openssl rand -base64 32 或镜像内置 --generate-key 生成）")
    private String secret;

    /**
     * 签名算法
     */
    @NotBlank
    @Pattern(regexp = "^(HS256|HS384|HS512|RS256|RS384|RS512)$", message = "不支持的JWT算法")
    private String algorithm = "HS256";

    /**
     * 令牌过期时间（分钟）
     */
    @Min(1)
    @Max(1440)
    private long expirationMinutes = 60;

    /**
     * 刷新令牌过期时间（天）
     */
    @Min(1)
    @Max(30)
    private long refreshExpirationDays = 7;

    /**
     * 令牌发行者
     */
    @NotBlank
    @Size(min = 1, max = 100)
    private String issuer = "trunkline";

    /**
     * 是否启用令牌黑名单
     */
    private boolean blacklistEnabled = true;

    /**
     * 黑名单存储不可用时是否 fail-closed（基础默认 false，可用性优先；prod profile 显式 true 收紧安全态）。
     *
     * <p>false：存储不可用/降级短路期间放行（返回"不在黑名单"），已撤销/登出的令牌
     * 在存储恢复前不会被拦截——这是既有取舍（issue #76）。true：同一场景下按
     * "在黑名单"处理并拒绝令牌（安全优先，issue #118）。</p>
     *
     * <p><b>生产务必为 true</b>：{@code application-prod.yml} 已显式设为 true；基础模块
     * {@code config/auth/jwt.yml} 与 dev profile 刻意保持 false（本地/开发可用性优先）。
     * 若生产以非 prod profile 启动，须自行确保此值为 true，否则降级期仍为 fail-open。</p>
     */
    private boolean blacklistFailClosedWhenUnavailable = false;

    /**
     * 预配置的JWT账户列表
     */
    @Valid
    private List<JwtAccountProperties> accounts;
}
