/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.injection;

/**
 * 启发式 Prompt 注入检测规则（G10）。
 *
 * <p>不可变对象：原始正则在 {@link InjectionRuleEngine} 构造时经
 * {@code SafeRegexValidator} 安全编译（ReDoS 防护），非法正则跳过并告警。</p>
 *
 * <p>本类为自研扩展（fork 维护：纯新增包，零上游接触）。</p>
 *
 * @author Trunkline Team
 */
public final class InjectionRule {

    private final String id;
    private final String name;
    private final String category;
    private final String description;
    private final String pattern;
    private final java.util.regex.Pattern compiled;

    public InjectionRule(final String id, final String name, final String category,
                         final String description, final String pattern,
                         final java.util.regex.Pattern compiled) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
        this.pattern = pattern;
        this.compiled = compiled;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getPattern() {
        return pattern;
    }

    public java.util.regex.Pattern getCompiled() {
        return compiled;
    }
}
