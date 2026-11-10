/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧配置前缀 {@code jairouter.*} 的兼容映射器（S1 品牌清理任务 3）。
 *
 * <p>品牌从 JAiRouter 更名为 Trunkline 时，所有配置前缀从 {@code jairouter.} 迁移为
 * {@code trunkline.}。为不让存量部署在升级后集体失聪，本类把环境里已有的旧前缀键
 * **映射**为对应的新前缀键（而非改写原键）：
 *
 * <ul>
 *   <li>普通属性源（yml/properties/配置导入等）里的 {@code jairouter.security.xxx}
 *       → 暴露为 {@code trunkline.security.xxx}；</li>
 *   <li>系统环境变量 {@code JAIROUTER_SECURITY_XXX} → 暴露为
 *       {@code TRUNKLINE_SECURITY_XXX}（经 {@link SystemEnvironmentPropertySource}
 *       的宽松解析，{@code trunkline.security.xxx} 也能取到）。</li>
 * </ul>
 *
 * <h2>优先级：新前缀显式配置永远赢</h2>
 * <p>映射结果以 {@code addLast} 注入（最低优先级）：同一键如果用户已经写了
 * {@code trunkline.*} 新写法，旧值不会盖掉新值；只有「只配了旧前缀」的键才由映射兜底。
 * 这与 {@code FlywayDialectEnvironmentPostProcessor} 的 addLast 兜底语义一致。
 *
 * <h2>弃用提示</h2>
 * <p>EnvironmentPostProcessor 运行时日志系统尚未初始化，因此映射清单先暂存，
 * 待 {@link ApplicationEnvironmentPreparedEvent}（日志系统就绪后）统一打 WARN，
 * 提示运维把旧前缀迁移为新前缀。
 * @author Trunkline Team
 */
public class JairouterLegacyPropertyMapper
        implements EnvironmentPostProcessor, ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JairouterLegacyPropertyMapper.class);

    /** 旧配置前缀 */
    static final String LEGACY_PREFIX = "jairouter.";
    /** 新配置前缀 */
    static final String NEW_PREFIX = "trunkline.";
    /** 旧环境变量前缀 */
    static final String LEGACY_ENV_PREFIX = "JAIROUTER_";
    /** 新环境变量前缀 */
    static final String NEW_ENV_PREFIX = "TRUNKLINE_";

    /** 注入的映射属性源名称 */
    static final String SOURCE_NAME = "jairouterLegacyPropertyMapper";

    /**
     * 检测到的旧键清单（跨 EnvironmentPostProcessor 与 ApplicationListener 两个实例生命周期传递）。
     * spring.factories 会分别实例化两个对象，静态载体是可接受的最简方案；仅用于弃用告警。
     */
    static final List<String> DETECTED_LEGACY_KEYS = new ArrayList<>();

    /** 须在配置导入之后运行，才能看到导入文件与外部覆盖里的旧前缀键 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
                                       final SpringApplication application) {
        final MutablePropertySources sources = environment.getPropertySources();
        final Map<String, Object> mapped = new LinkedHashMap<>();
        final List<String> detected = new ArrayList<>();

        for (final PropertySource<?> source : sources) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            final boolean systemEnv = source.getName()
                    .equals(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            for (final String name : enumerable.getPropertyNames()) {
                if (systemEnv) {
                    // 环境变量形态：JAIROUTER_SECURITY_X → TRUNKLINE_SECURITY_X
                    if (name.startsWith(LEGACY_ENV_PREFIX)) {
                        final String target = NEW_ENV_PREFIX + name.substring(LEGACY_ENV_PREFIX.length());
                        // 首个（更高优先级来源）命中生效；但新前缀的显式环境变量永远赢——
                        // 若目标键已在任何来源里显式存在，则不映射。
                        if (explicitNewPrefixExists(sources, target) || mapped.containsKey(target)) {
                            continue;
                        }
                        mapped.put(target, source.getProperty(name));
                        detected.add(name);
                    }
                    continue;
                }
                // 点分形态：jairouter.xxx → trunkline.xxx
                if (name.startsWith(LEGACY_PREFIX)) {
                    final String target = NEW_PREFIX + name.substring(LEGACY_PREFIX.length());
                    if (mapped.containsKey(target)) {
                        continue;
                    }
                    mapped.put(target, source.getProperty(name));
                    detected.add(name);
                }
            }
        }

        if (!mapped.isEmpty()) {
            // addLast：新前缀的任何显式配置（含环境变量）优先于旧前缀映射
            sources.addLast(new SystemEnvironmentPropertySource(SOURCE_NAME, mapped));
            synchronized (DETECTED_LEGACY_KEYS) {
                DETECTED_LEGACY_KEYS.clear();
                DETECTED_LEGACY_KEYS.addAll(detected);
            }
        }
    }

    /** 日志系统就绪（environment prepared 之后）再打弃用警告 */
    @Override
    public void onApplicationEvent(final ApplicationEnvironmentPreparedEvent event) {
        final List<String> detected;
        synchronized (DETECTED_LEGACY_KEYS) {
            if (DETECTED_LEGACY_KEYS.isEmpty()) {
                return;
            }
            detected = new ArrayList<>(DETECTED_LEGACY_KEYS);
        }
        log.warn("检测到 {} 个旧配置前缀(jairouter.*)键，已映射为 trunkline.* 并继续生效；"
                        + "这些键将在后续大版本移除，请尽快迁移。涉及键（前 20 个）：{}",
                detected.size(), detected.subList(0, Math.min(20, detected.size())));
    }

    /**
     * 目标（新前缀）键是否已在任何既有属性源中显式存在。
     *
     * <p>对环境变量源会用宽松解析（{@code trunkline.security.x} ↔ {@code TRUNKLINE_SECURITY_X}），
     * 因此点分与下划线两种新写法都算「显式存在」。
     */
    private boolean explicitNewPrefixExists(final MutablePropertySources sources, final String target) {
        for (final PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && enumerable.containsProperty(target)) {
                return true;
            }
        }
        return false;
    }
}
