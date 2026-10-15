package org.unreal.modelrouter.auth.security.injection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.common.util.SafeRegexValidator;

/**
 * 注入检测规则引擎（G10）：组装默认规则 + 外部附加规则，统一安全编译。
 *
 * <p>规则来源：</p>
 * <ol>
 *   <li>内置默认规则（{@link #defaultRules()}）：指令覆盖 / 越狱令牌 / 系统提示词探测 /
 *       特殊标记注入 / 编码混淆五类，零模型成本；</li>
 *   <li>配置附加规则：{@code jairouter.security.injection.additional-rules}
 *       （标准 Spring 配置绑定，yml / 环境变量均可，无需新增配置文件）；</li>
 *   <li>配置禁用：{@code jairouter.security.injection.disabled-rules} 按 id 关闭。</li>
 * </ol>
 *
 * <p>所有正则经 {@link SafeRegexValidator} 编译（长度/量词上限，防 ReDoS），
 * 非法正则跳过并告警，不阻断启动。规则变更需重启生效（热更列为后续增强）。</p>
 *
 * <p>本类为自研扩展（fork 维护：纯新增包，零上游接触）。</p>
 *
 * @author wjy-gateway Extensions
 * @since 3.3.1
 */
@Component
public class InjectionRuleEngine {

    private static final Logger LOG = LoggerFactory.getLogger(InjectionRuleEngine.class);

    private final List<InjectionRule> rules;

    public InjectionRuleEngine(final InjectionDetectionProperties properties) {
        final List<InjectionRule> assembled = new ArrayList<>();
        final Set<String> disabled = properties.getDisabledRules() == null
                ? Collections.emptySet() : properties.getDisabledRules();

        for (final InjectionRule rule : defaultRules()) {
            if (!disabled.contains(rule.getId())) {
                assembled.add(rule);
            }
        }
        if (properties.getAdditionalRules() != null) {
            for (final InjectionDetectionProperties.RuleSpec spec : properties.getAdditionalRules()) {
                if (spec == null || spec.getPattern() == null || spec.getPattern().isBlank()) {
                    continue;
                }
                final Pattern compiled = SafeRegexValidator.compileOrNull(spec.getPattern());
                if (compiled == null) {
                    LOG.warn("[G10] 附加注入规则正则非法，已跳过: id={}", spec.getId());
                    continue;
                }
                assembled.add(new InjectionRule(spec.getId(), spec.getName(), spec.getCategory(),
                        spec.getDescription(), spec.getPattern(), compiled));
            }
        }
        this.rules = Collections.unmodifiableList(assembled);
        LOG.info("[G10] 注入检测规则装载完成: 有效 {} 条 (禁用 {} 条)",
                rules.size(), disabled.size());
    }

    /**
     * 扫描文本，返回命中的规则列表（可能为空，绝不返回 null）。
     */
    public List<InjectionRule> match(final String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        final List<InjectionRule> hits = new ArrayList<>();
        for (final InjectionRule rule : rules) {
            if (rule.getCompiled() != null && rule.getCompiled().matcher(text).find()) {
                hits.add(rule);
            }
        }
        return hits;
    }

    public List<InjectionRule> getRules() {
        return rules;
    }

    /**
     * 内置默认规则集。命名风格保持「口语上先想清楚这条规则抓什么」再落正则，
     * 全部按「宁漏勿误」收敛，避免误伤正常业务提示词。
     */
    static List<InjectionRule> defaultRules() {
        final List<InjectionRule> list = new ArrayList<>();
        // INJ-001 指令覆盖：要求忽略此前全部指令
        list.add(rule("INJ-001", "instruction-override", "指令覆盖",
                "要求模型忽略/无视此前指令",
                "(?i)\\b(?:ignore|disregard|forget)\\s+(?:all\\s+|any\\s+|your\\s+)?"
                        + "(?:previous|prior|above|earlier)\\s+"
                        + "(?:instructions?|prompts?|rules?|messages?)"));
        // INJ-002 越狱令牌：知名越狱话术
        list.add(rule("INJ-002", "jailbreak-token", "越狱令牌",
                "DAN / developer mode / do anything now 等越狱话术",
                "(?i)\\b(?:DAN\\s+mode|developer\\s+mode|do\\s+anything\\s+now"
                        + "|AIM\\s*:\\s*always\\s+intelligent)"));
        // INJ-003 系统提示词探测：套取系统提示词
        list.add(rule("INJ-003", "system-prompt-probe", "系统提示词探测",
                "要求输出/复述系统提示词",
                "(?i)\\b(?:repeat|print|reveal|show|output|display)\\s+(?:me\\s+)?"
                        + "(?:your|the)\\s+(?:system\\s+prompt|initial\\s+instructions?"
                        + "|hidden\\s+prompt|original\\s+instructions?|system\\s+message)"));
        // INJ-004 特殊标记注入：伪造对话控制标记
        list.add(rule("INJ-004", "special-token-injection", "特殊标记注入",
                "在用户输入中伪造 im_start/system 等对话控制标记",
                "(?i)<\\|(?:im_start|im_end|system|endoftext)\\|>"));
        // INJ-005 分隔符伪造：用 markdown 分隔符伪装系统段
        list.add(rule("INJ-005", "delimiter-forge", "分隔符伪造",
                "用 ###/=== 分隔符伪造 system 段落",
                "(?im)^#{1,4}\\s*system\\s*:?\\s*$|^={3,}\\s*system\\s*:?\\s*$"));
        // INJ-006 编码混淆：要求解码并执行 base64 载荷
        list.add(rule("INJ-006", "encoding-obfuscation", "编码混淆",
                "base64 载荷 + 解码执行指令组合",
                "(?i)base64[^\\n\"]{0,64}\\b(?:decode|execute|follow|run)\\b"));
        return list;
    }

    private static InjectionRule rule(final String id, final String name, final String category,
                                      final String description, final String regex) {
        final Pattern compiled = SafeRegexValidator.compileOrNull(regex);
        if (compiled == null) {
            LOG.error("[G10] 内置规则正则非法，该规则将不生效: id={}", id);
        }
        return new InjectionRule(id, name, category, description, regex, compiled);
    }

    /** 防止误用：构造不可变集合视图。 */
    static Set<String> immutableCopy(final Set<String> source) {
        return source == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
