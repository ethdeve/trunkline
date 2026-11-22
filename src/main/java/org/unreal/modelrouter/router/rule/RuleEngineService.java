package org.unreal.modelrouter.router.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.common.util.IpUtils;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.rule.model.RuleDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 规则引擎
 * 按优先级降序匹配规则,首条命中即返回决策;无命中返回 null(走原始路由逻辑)
 * 条件组合:规则内 AND;规则间 OR 用优先级 + 首条命中表达
 */
@Service
public class RuleEngineService {

    private static final Logger logger = LoggerFactory.getLogger(RuleEngineService.class);

    private final RuleDefinitionProperties yamlProperties;

    private volatile List<RuleDefinition> rules = new ArrayList<>();

    /**
     * 单元测试构造(无 YAML 配置)
     */
    public RuleEngineService() {
        this.yamlProperties = null;
    }

    @Autowired
    public RuleEngineService(final RuleDefinitionProperties yamlProperties) {
        this.yamlProperties = yamlProperties;
    }

    /**
     * 获取 YAML 默认规则(配置属性来源)
     */
    public List<RuleDefinition> getYamlRules() {
        return yamlProperties != null && yamlProperties.getRules() != null
                ? yamlProperties.getRules() : Collections.emptyList();
    }

    /**
     * 热更新规则列表(CRUD 后由 Controller 调用)
     */
    public void reloadRules(final List<RuleDefinition> newRules) {
        List<RuleDefinition> sorted = new ArrayList<>(newRules);
        // 优先级降序;同优先级保持添加顺序(稳定排序)
        sorted.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        this.rules = Collections.unmodifiableList(sorted);
        logger.info("规则引擎已加载 {} 条规则", sorted.size());
    }

    /**
     * 求值:按优先级降序匹配,返回首条命中规则;无命中返回 null
     */
    public RuleDecision evaluate(final ModelServiceRegistry.ServiceType serviceType,
                                 final String modelName,
                                 final String clientIp,
                                 final Map<String, String> headers) {
        for (RuleDefinition rule : rules) {
            if (!rule.isEnabled() || rule.getConditions() == null || rule.getConditions().isEmpty()
                    || rule.getAction() == null) {
                continue;
            }
            if (matches(rule, serviceType, modelName, clientIp, headers)) {
                logger.debug("规则命中: id={}, name={}", rule.getId(), rule.getName());
                return new RuleDecision(rule);
            }
        }
        return null;
    }

    /**
     * 条件组合求值：按 rule.matchMode 分流 ANY(OR) / ALL(AND)。
     *
     * <p>2026-11-22 整改：字段 {@code matchMode}（默认 {@code ALL}）此前只是预留、引擎从不读取，
     * 导致 UI 配了 ANY 也恒按 AND 求值。现 ANY 只要任一条件命中即返回 true，ALL 保持原语义。
     * evaluate 与 /rules/validate 共用本方法，两者语义天然一致。空条件列表在 evaluate 已被跳过，
     * 此处 ALL 分支对空列表返回 true、ANY 分支返回 false，符合各自代数恒等值。</p>
     */
    private boolean matches(final RuleDefinition rule,
                            final ModelServiceRegistry.ServiceType serviceType,
                            final String modelName,
                            final String clientIp,
                            final Map<String, String> headers) {
        final boolean anyMode = "ANY".equalsIgnoreCase(rule.getMatchMode());
        if (anyMode) {
            for (RuleDefinition.Condition condition : rule.getConditions()) {
                if (matchesCondition(condition, serviceType, modelName, clientIp, headers)) {
                    return true;
                }
            }
            return false;
        }
        // ALL（默认）：全部条件命中才通过
        for (RuleDefinition.Condition condition : rule.getConditions()) {
            if (!matchesCondition(condition, serviceType, modelName, clientIp, headers)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCondition(final RuleDefinition.Condition condition,
                                     final ModelServiceRegistry.ServiceType serviceType,
                                     final String modelName,
                                     final String clientIp,
                                     final Map<String, String> headers) {
        if (condition == null || condition.getType() == null || condition.getOperator() == null) {
            return false;
        }
        return switch (condition.getType()) {
            case SERVICE_TYPE -> matchesValue(String.valueOf(serviceType), condition);
            case MODEL_NAME -> matchesValue(modelName, condition);
            case HEADER -> matchesHeader(headers, condition);
            case CLIENT_IP -> matchesClientIp(clientIp, condition);
            case WEIGHT -> matchesWeight(clientIp, modelName, condition);
        };
    }

    private boolean matchesValue(final String actual, final RuleDefinition.Condition condition) {
        if (actual == null) {
            return false;
        }
        String expected = condition.getValue() == null ? "" : condition.getValue();
        return switch (condition.getOperator()) {
            case EQUALS -> actual.equalsIgnoreCase(expected);
            case CONTAINS -> actual.toLowerCase().contains(expected.toLowerCase());
            case STARTS_WITH -> actual.toLowerCase().startsWith(expected.toLowerCase());
            case REGEX -> matchesRegex(actual, expected);
            case CIDR_MATCH -> false; // 仅适用于 IP
        };
    }

    private boolean matchesHeader(final Map<String, String> headers, final RuleDefinition.Condition condition) {
        if (headers == null || condition.getField() == null || condition.getField().isBlank()) {
            return false;
        }
        String headerValue = headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(condition.getField()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (headerValue == null) {
            return false;
        }
        String expected = condition.getValue() == null ? "" : condition.getValue();
        return switch (condition.getOperator()) {
            case EQUALS -> headerValue.equalsIgnoreCase(expected);
            case CONTAINS -> headerValue.toLowerCase().contains(expected.toLowerCase());
            case STARTS_WITH -> headerValue.toLowerCase().startsWith(expected.toLowerCase());
            case REGEX -> matchesRegex(headerValue, expected);
            case CIDR_MATCH -> false;
        };
    }

    private boolean matchesClientIp(final String clientIp, final RuleDefinition.Condition condition) {
        if (clientIp == null) {
            return false;
        }
        String expected = condition.getValue() == null ? "" : condition.getValue();
        return switch (condition.getOperator()) {
            case EQUALS -> clientIp.equalsIgnoreCase(expected);
            case CIDR_MATCH -> cidrMatches(clientIp, expected);
            case CONTAINS, STARTS_WITH, REGEX -> matchesValue(clientIp, condition);
        };
    }

    /**
     * WEIGHT 条件:基于 (clientIp + modelName) 稳定哈希,命中百分比区间 [0, weight)
     * 同一请求稳定命中同一规则;weight 默认 50 表示 50%
     */
    private boolean matchesWeight(final String clientIp, final String modelName,
                                   final RuleDefinition.Condition condition) {
        int weight = condition.getWeight() != null ? condition.getWeight()
                : parseWeight(condition.getValue());
        if (weight <= 0) {
            return false;
        }
        String seed = String.valueOf(clientIp) + "|" + modelName;
        int hash = Math.abs(seed.hashCode());
        return hash % 100 < Math.min(weight, 100);
    }

    private int parseWeight(final String value) {
        if (value == null) {
            return 50;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    private boolean matchesRegex(final String actual, final String regex) {
        try {
            return Pattern.compile(regex).matcher(actual).find();
        } catch (PatternSyntaxException e) {
            logger.warn("规则正则表达式非法: {}", regex);
            return false;
        }
    }

    /**
     * CIDR 匹配,支持 192.168.1.0/24 与精确 IP(掩码 /32)
     *
     * <p>#151 §3.3：实现已提升到 {@link IpUtils#matchesCidr} 供全局复用，此处仅委托，行为不变。</p>
     */
    private boolean cidrMatches(final String ip, final String cidr) {
        return IpUtils.matchesCidr(ip, cidr);
    }
}
