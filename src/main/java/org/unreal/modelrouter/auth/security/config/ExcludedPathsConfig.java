package org.unreal.modelrouter.auth.security.config;

import java.util.List;
import java.util.Set;

import org.springframework.util.AntPathMatcher;

/**
 * 统一管理所有需要排除认证和数据脱敏的路径配置
 *
 * <p>【issue #834】精确集合与 Ant 模式分工：精确集合只做 {@code equals}，需要放行子路径的
 * 一律显式登记为 {@code /xxx/**} 模式。此前 {@code path.startsWith(x)} 让 {@code "/health"}
 * 把 {@code /healthz}、{@code /healthcheck} 一并放行，{@code "/admin"} 把
 * {@code /administrator} 一并放行，形成匿名越权面。收紧后放行面只减不增。</p>
 */
public class ExcludedPathsConfig {
    /** Private constructor to prevent instantiation. */
    private ExcludedPathsConfig() {}
    
    /**
     * 需要排除认证的「精确」路径集合（只做 equals）。
     */
    public static final Set<String> AUTH_EXCLUDED_PATHS;
    
    /**
     * 需要排除数据脱敏的「精确」路径集合（只做 equals）。
     */
    public static final Set<String> DATA_MASKING_EXCLUDED_PATHS;
    
    /**
     * 需要排除认证的Ant风格路径模式列表（子路径放行必须显式登记）。
     */
    public static final List<String> AUTH_EXCLUDED_PATTERNS;

    /**
     * 需要排除数据脱敏的Ant风格路径模式列表。
     */
    public static final List<String> DATA_MASKING_EXCLUDED_PATTERNS;

    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * R3-P1：API 文档路径是否排除认证（由 SecurityConfiguration 按 docs-public 设置）。
     * 默认 false —— 文档端点需要认证。
     */
    private static volatile boolean apiDocsAuthExcluded = false;

    /**
     * 设置 API 文档是否免认证（Swagger / OpenAPI）。
     */
    public static void setApiDocsAuthExcluded(final boolean excluded) {
        apiDocsAuthExcluded = excluded;
    }

    static {
        // 认证排除路径（精确匹配）
        // R3-P1：Swagger / api-docs 不再默认排除，由 docs-public 开关控制。
        // 注意：/actuator/**、/admin/**、/api/setup/** 的子路径由下方 Ant 模式放行，
        // 不需要也不允许在这里用前缀集合表达。
        AUTH_EXCLUDED_PATHS = Set.of(
            "/actuator/",
            "/health",
            "/metrics",
            "/api/auth/jwt/login",
            "/api/auth/jwt/validate",
            "/favicon.ico",
            "/.well-known"
        );

        // 认证排除路径模式（Ant 风格）
        // 【2026-11-10 收紧】原 /docs-res/** 匿名放行已移除：该静态映射直接暴露
        // classpath:/docs/ 下的全部 zh/en .md 正文，普通用户无需任何权限即可绕过
        // /api/docs/content 的 OPS 鉴权读取帮助文档。帮助文档正文统一走鉴权接口。
        AUTH_EXCLUDED_PATTERNS = List.of(
            "/actuator/**",
            "/admin/**",
            // 首次部署尚未创建管理员，初始化接口必须匿名可达；服务层在完成初始化后
            // 会对 /api/setup/datasource 与 /api/setup/admin 返回 409 拒绝再次调用。
            "/api/setup/**",
            // ACME / OAuth 等 well-known 子路径仍需可达，但不再靠 startsWith 误放行
            "/.well-known/**"
        );
        
        // 数据脱敏排除路径（精确匹配）
        //
        // 设计裁决（v3.1.x）：PII 主战场是「聊天调用历史 / 日志 / 追踪记录」中的用户数据，
        // 由 SanitizationService.sanitizeForStorage 在 SUMMARY 记录链路处理，不经本过滤器。
        // 本过滤器只作用于「未被排除的 HTTP JSON 响应」，因此：
        //  1) AI 实时推理路径（/api/v1/**）排除 —— 不改写客户端模型输出；
        //  2) 管理台 /api/** 全部排除 —— 管理接口返回配置/密钥元数据时不得被掩码破坏；
        //  3) 静态资源与运维端点排除。
        DATA_MASKING_EXCLUDED_PATHS = Set.of(
            "/actuator/",
            "/health",
            "/metrics",
            "/swagger-ui/",
            "/v3/api-docs",
            "/favicon.ico",
            "/.well-known",
            "/static/",
            "/css/",
            "/js/",
            "/images/",
            "/admin",
            // 管理台与配置 API：管理控制台需要看到真实配置值
            "/api/",
            // AI 实时推理（覆盖 /api/v1/chat 等；若未来有非 /api 的 AI 入口再单独登记）
            "/v1/"
        );

        // 数据脱敏排除路径模式（Ant 风格）
        // 【issue #834】把原来靠 startsWith 前缀放行的静态资源/文档子路径显式登记，
        // 保证收紧精确集合后 UI 静态资源仍不被脱敏改写，同时不再误放行 /apix 之类路径。
        DATA_MASKING_EXCLUDED_PATTERNS = List.of(
            "/actuator/**",
            "/api/**",
            "/v1/**",
            "/admin/**",
            "/static/**",
            "/css/**",
            "/js/**",
            "/images/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/.well-known/**"
        );
    }
    
    /**
     * 检查路径是否在认证排除列表中。
     *
     * <p>精确集合只做 equals，子路径必须命中显式 Ant 模式（issue #834）。</p>
     */
    public static boolean isAuthExcluded(final String path) {
        if (path == null) {
            return false;
        }
        // R3-P1：文档路径仅在 docs-public=true 时免认证
        if (apiDocsAuthExcluded && isApiDocsPath(path)) {
            return true;
        }
        // 根路径仅精确匹配豁免（SpaWebFluxConfig 把 / 302 引导到 /admin/）。
        // 严禁把 "/" 放进精确集合做前缀匹配，否则会放行所有请求。
        if (path.equals("/")) {
            return true;
        }
        if (AUTH_EXCLUDED_PATHS.contains(path)) {
            return true;
        }
        return AUTH_EXCLUDED_PATTERNS.stream().anyMatch(pattern ->
            pathMatcher.match(pattern, path));
    }

    /**
     * 文档路径同样不允许裸前缀匹配：/webjarsx 不应被当成 /webjars 的子路径。
     */
    private static boolean isApiDocsPath(final String path) {
        if (path == null) {
            return false;
        }
        return matchesPathOrSubpath(path, "/swagger-ui")
                || matchesPathOrSubpath(path, "/v3/api-docs")
                || matchesPathOrSubpath(path, "/webjars");
    }

    private static boolean matchesPathOrSubpath(final String path, final String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }
    
    /**
     * 检查路径是否在数据脱敏排除列表中（精确集合 + 显式 Ant 模式）。
     */
    public static boolean isDataMaskExcluded(final String path) {
        if (path == null) {
            return false;
        }
        if (DATA_MASKING_EXCLUDED_PATHS.contains(path)) {
            return true;
        }
        return DATA_MASKING_EXCLUDED_PATTERNS.stream().anyMatch(pattern ->
            pathMatcher.match(pattern, path));
    }
}
