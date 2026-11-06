package org.unreal.modelrouter.common.filter;

import org.springframework.core.Ordered;

/**
 * WebFilter 链顺序契约（X2 整改：过滤器顺序唯一化）。
 *
 * <p>整改前两处同值冲突均靠「bean 名字母序」侥幸稳定：</p>
 * <ul>
 *   <li>{@code CachedBodyWebFilter}(MIN) 与 {@code TracingWebFilter}(MIN) 同值，
 *       靠 c &lt; t 字母序保证 CachedBody 先执行；</li>
 *   <li>{@code writeableHeaders}(MIN+1) 与 {@code webFluxMetricsFilter}(MIN+1) 同值，
 *       靠 w 字母序保证 Metrics 先执行。</li>
 * </ul>
 *
 * <p>本契约给每个过滤器分配<b>唯一</b> order 值，并把全部魔法数收敛到这一处。
 * 新增过滤器请在此登记，禁止直接书写 {@code HIGHEST_PRECEDENCE + n} 字面量。</p>
 *
 * <p>链序（由先到后）：</p>
 * <pre>
 * CachedBody(MIN) → writeableHeaders(MIN+1) → WebMetrics(MIN+10)
 *   → Tracing(MIN+100) → [Security 链(-100)] → InjectionDetection(MIN+2000)
 *   → ResponseSanitization(20) → … → TracingSecurity(LOWEST-100)
 * </pre>
 *
 * <p><b>Security 链位置说明</b>：Spring Security 的 {@code WebFilterChainProxy} 由
 * Spring Boot 自动装配注册，注解值为 {@code @Order(-100)}（framework bean，
 * 本库不可修改）。因此 {@code HIGHEST_PRECEDENCE + 2000} 实际执行在 Security 链
 * <b>之前</b>——{@code InjectionDetectionFilter} 的 javadoc 曾误写「安全认证之后」，
 * 已修正。若未来需要真正「认证后」的请求体扫描，须把 order 调到 {@code > -100}
 * 或挂入 SecurityWebFilterChain 内部。</p>
 *
 * @author JAiRouter Team
 * @since 3.3.1
 */
public final class FilterOrderContract {

    /** 请求体缓存：链首，必须先于一切读 body 的过滤器。 */
    public static final int CACHED_BODY = Ordered.HIGHEST_PRECEDENCE;

    /** 请求头可写化包装：紧跟 CachedBody。 */
    public static final int WRITEABLE_HEADERS = CACHED_BODY + 1;

    /** WebFlux 指标采集：读 headers + 装饰响应，无需可写头。 */
    public static final int WEB_METRICS = CACHED_BODY + 10;

    /** 追踪根 Span 创建：在 Security 链之前，使认证过程全程可追踪。 */
    public static final int TRACING = CACHED_BODY + 100;

    /**
     * Spring Boot 注册的安全链 {@code WebFilterChainProxy} 实际 order（{@code @Order(-100)}）。
     * 仅作链序参照点，本库不可修改。
     */
    public static final int SECURITY_CHAIN = -100;

    /** Prompt 注入扫描：自研扩展（G10），order 大于 TRACING 但仍小于 SECURITY_CHAIN（认证前执行）。 */
    public static final int INJECTION_DETECTION = CACHED_BODY + 2000;

    /** 安全追踪装饰：链尾（认证完成后记录安全事件，必须在 Security 链之后）。 */
    public static final int TRACING_SECURITY = Ordered.LOWEST_PRECEDENCE - 100;

    private FilterOrderContract() {
    }
}
