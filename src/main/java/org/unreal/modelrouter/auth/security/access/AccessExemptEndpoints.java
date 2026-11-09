package org.unreal.modelrouter.auth.security.access;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Set;

/**
 * 固定权限矩阵的显式豁免清单——「有意不登记 URL 级别规则」的端点。
 *
 * <p>豁免的端点按「已认证」放行：它们要么是自助端点（方法级
 * {@code @PreAuthorize} 已约束属主），要么是服务代理面（授权由 API-Key 服务类型
 * 权限 + 配额控制，而非 URL 矩阵）。未登记且未豁免的端点 fail-closed（仅 ADMIN）。</p>
 *
 * <p><b>判定顺序</b>：先 {@link FixedRoleAccessPolicy}（规则命中即 covered），
 * 再本清单（命中即豁免→已认证放行），都未命中才是 fail-closed。</p>
 *
 * <p>每条豁免必须写明理由，审查时逐条可追溯。</p>
 *
 * @author JAiRouter Team
 * @since 3.5.1
 * @modified Trunkline Team
 */
public final class AccessExemptEndpoints {

    private static final List<CompiledExemption> COMPILED = compile();

    private AccessExemptEndpoints() {
    }

    /**
     * 一条豁免：HTTP 方法集合（空 = 任意）+ 路径模式 + 豁免理由。
     */
    public record Exemption(Set<HttpMethod> methods, String pathPattern, String reason) {

        public Exemption {
            methods = methods == null ? Set.of() : Set.copyOf(methods);
        }

        /** 仅 GET */
        public static Exemption get(final String pathPattern, final String reason) {
            return new Exemption(Set.of(HttpMethod.GET), pathPattern, reason);
        }

        /** 仅 POST */
        public static Exemption post(final String pathPattern, final String reason) {
            return new Exemption(Set.of(HttpMethod.POST), pathPattern, reason);
        }

        /** 写操作（POST/PUT/DELETE/PATCH） */
        public static Exemption write(final String pathPattern, final String reason) {
            return new Exemption(
                    Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH),
                    pathPattern, reason);
        }
    }

    /** 全部豁免条目（只读，供审计与测试逐条断言） */
    public static List<Exemption> exemptions() {
        return COMPILED.stream().map(CompiledExemption::exemption).toList();
    }

    /**
     * 判断指定方法与具体路径是否命中豁免清单。
     *
     * @param method 请求方法（可为 null）
     * @param path   具体请求路径
     * @return true 表示豁免（已认证放行）
     */
    public static boolean isExempt(final HttpMethod method, final String path) {
        for (final CompiledExemption entry : COMPILED) {
            if (entry.matches(method, path)) {
                return true;
            }
        }
        return false;
    }

    private static List<CompiledExemption> compile() {
        final PathPatternParser parser = new PathPatternParser();
        return defaultExemptions().stream()
                .map(e -> new CompiledExemption(e, parser.parse(e.pathPattern())))
                .toList();
    }

    private static List<Exemption> defaultExemptions() {
        return List.of(
                // ===== AI 服务代理面（UniversalController，7 个 POST） =====
                // 授权语义是 API-Key 服务类型权限 + 配额，不走 URL 矩阵。
                // API-Key 主体仅携带 ROLE_<SERVICE>（ApiKeyAuthentication），
                // 登记级别规则会 403 全部 API-Key 服务调用（产品破坏性变更）。
                Exemption.write("/api/v1/**",
                        "AI 服务代理面（chat/embeddings/rerank/audio/images）：授权由 API-Key 服务类型权限 + 配额控制；"
                                + "API-Key 主体携带 ROLE_<SERVICE> 而非角色，URL 级别规则会阻断全部服务调用"),

                // ===== JWT 自助端点（方法级 @PreAuthorize 约束属主） =====
                Exemption.post("/api/auth/jwt/refresh",
                        "自助刷新本人令牌（前端 stores/user.ts），任意登录用户可用；无属主越权面（令牌即身份）"),
                Exemption.post("/api/auth/jwt/revoke",
                        "自助撤销本人令牌；方法级 @PreAuthorize(hasRole('ADMIN') or authentication.name == #request.userId) 已约束属主"),
                Exemption.get("/api/auth/jwt/tokens",
                        "自助令牌列表；方法级 @PreAuthorize(hasRole('ADMIN') or (hasRole('USER') and #userId == authentication.name)) 已约束属主，"
                                + "登记级别规则会破坏普通用户管理本人令牌"),

                // ===== 控制台/客户端公共读取 =====
                Exemption.get("/api/models",
                        "模型目录（ModelInfoController），UI 与客户端公共读取，无敏感操作面"),
                Exemption.get("/api/docs/content",
                        "内置帮助文档正文（DocsController）：按控制台菜单组织，面向所有已登录角色"
                                + "（员工也要读「我的」分组的帮助）；内容随 jar 打包、纯只读且不含敏感数据。"
                                + "未登记端点 fail-closed 仅 ADMIN，会让非管理员 403、帮助中心显示「没找到这页帮助」"),

                // ===== Token 用量上报（外部摄取面） =====
                // 内部代理的用量落库走进程内 TokenUsageRecorder → TokenUsageService，
                // 不经过此 HTTP 端点；这两个 POST 是对外文档公开的摄取 API，
                // 调用方认证形态未知（可能使用 API-Key，同样只携带 ROLE_<SERVICE>），
                // 登记级别规则会破坏上报链路。
                Exemption.post("/api/token-usage/record",
                        "Token 用量上报摄取面：内部代理经进程内 TokenUsageRecorder 落库，不经此端点；"
                                + "对外公开摄取 API，调用方可能为 API-Key（仅 ROLE_<SERVICE>），登记级别规则会阻断上报"),
                Exemption.post("/api/token-usage/record/batch",
                        "批量 Token 用量上报摄取面：同 /api/token-usage/record，外部摄取 API 不宜绑定级别规则")
        );
    }

    /** 预编译条目 */
    private record CompiledExemption(Exemption exemption, PathPattern pattern) {

        boolean matches(final HttpMethod method, final String path) {
            if (!pattern.matches(PathContainer.parsePath(path))) {
                return false;
            }
            return exemption.methods().isEmpty()
                    || (method != null && exemption.methods().contains(method));
        }
    }
}
