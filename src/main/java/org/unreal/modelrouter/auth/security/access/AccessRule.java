package org.unreal.modelrouter.auth.security.access;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Set;

/**
 * 固定权限矩阵的单条访问规则：HTTP 方法集合 + 路径模式 → 最低访问级别。
 *
 * <p>规则按登记顺序匹配（首条命中优先），更具体的路径必须登记在更宽泛的模式之前。</p>
 *
 * @author JAiRouter Team
 * @since 3.5.1
 * @modified Trunkline Team
 */
public record AccessRule(Set<HttpMethod> methods, String pathPattern, AccessLevel level, PathPattern compiled) {

    private static final PathPatternParser PARSER = new PathPatternParser();

    /** 匹配写方法集合（POST / PUT / DELETE / PATCH） */
    private static final Set<HttpMethod> WRITE_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH);

    public AccessRule {
        methods = methods == null || methods.isEmpty() ? Set.of() : Set.copyOf(methods);
        compiled = PARSER.parse(pathPattern);
    }

    /** 仅 GET */
    public static AccessRule get(final String pathPattern, final AccessLevel level) {
        return new AccessRule(Set.of(HttpMethod.GET), pathPattern, level, null);
    }

    /** 写操作（POST/PUT/DELETE/PATCH） */
    public static AccessRule write(final String pathPattern, final AccessLevel level) {
        return new AccessRule(WRITE_METHODS, pathPattern, level, null);
    }

    /** 任意方法 */
    public static AccessRule any(final String pathPattern, final AccessLevel level) {
        return new AccessRule(Set.of(), pathPattern, level, null);
    }

    /**
     * 判断指定方法与具体路径是否命中本规则。
     *
     * @param method 请求方法（可为 null，null 仅匹配「任意方法」规则）
     * @param path   具体请求路径
     * @return true 表示命中
     */
    public boolean matches(final HttpMethod method, final String path) {
        if (!compiled.matches(org.springframework.http.server.PathContainer.parsePath(path))) {
            return false;
        }
        return methods.isEmpty() || (method != null && methods.contains(method));
    }
}
