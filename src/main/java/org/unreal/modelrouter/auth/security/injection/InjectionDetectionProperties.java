package org.unreal.modelrouter.auth.security.injection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 注入检测配置（G10），前缀 {@code trunkline.security.injection}。
 *
 * <p>标准 Spring 配置绑定，可直接写在任意既有 yml / 环境变量，无需新增配置文件。示例：</p>
 * <pre>
 * trunkline:
 *   security:
 *     injection:
 *       enabled: true          # 总开关（默认 true）
 *       action: BLOCK          # BLOCK=命中即 403；LOG=仅审计放行（默认 BLOCK）
 *       max-scan-bytes: 1048576 # 单请求扫描上限（默认 1MB，超出部分不扫描，fail-open）
 *       disabled-rules: [INJ-006]
 *       additional-rules:
 *         - id: INJ-901
 *           name: custom-rule
 *           category: 自定义
 *           description: 客户自定义特征
 *           pattern: "(?i)bad\\s+word"
 * </pre>
 *
 * <p>本类为自研扩展（fork 维护：纯新增包，零上游接触；
 * 采用 @Component 注册以避开 ModelRouterApplication 的扫描包清单改动）。</p>
 *
 * @author wjy-gateway Extensions
 * @since 3.3.1
 * @modified Trunkline Team
 */
@Data
@Component
@ConfigurationProperties(prefix = "trunkline.security.injection")
public class InjectionDetectionProperties {

    /** 总开关，默认启用。 */
    private boolean enabled = true;

    /** 命中后动作：BLOCK=403 拦截；LOG=仅记录审计后放行（灰度观测用）。 */
    private DetectionAction action = DetectionAction.BLOCK;

    /** 单请求参与扫描的最大字节数（超出部分跳过扫描，fail-open 保路由 SLA）。 */
    private long maxScanBytes = 1024 * 1024;

    /** 按 id 禁用的内置/附加规则。 */
    private Set<String> disabledRules = new LinkedHashSet<>();

    /** 客户/租户自定义附加规则。 */
    private List<RuleSpec> additionalRules = new ArrayList<>();

    /** 动作枚举。 */
    public enum DetectionAction {
        /** 命中即拦截，返回 403（OpenAI 错误格式）。 */
        BLOCK,
        /** 仅记录审计事件，请求放行（灰度观测、误报调参期使用）。 */
        LOG
    }

    /** 附加规则定义（yml 映射载体）。 */
    @Data
    public static class RuleSpec {
        /** 规则 id，建议 INJ-9xx 段避免与内置冲突。 */
        private String id;
        /** 规则名。 */
        private String name;
        /** 类别（审计展示用）。 */
        private String category;
        /** 说明。 */
        private String description;
        /** 正则表达式（经 SafeRegexValidator 安全编译）。 */
        private String pattern;
    }
}
