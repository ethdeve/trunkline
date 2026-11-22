/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.router.capability.ModelCapabilityProfile;
import org.unreal.modelrouter.router.capability.ModelCapabilityProfileService;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.pool.PoolConfigService;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;
import org.unreal.modelrouter.router.rule.RuleConfigService;
import org.unreal.modelrouter.router.rule.model.RuleDefinition;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一键虚拟模型：按用途从画像库选模，生成池 + 路由规则并落库（v0.2.0）。
 *
 * <p>数据来源为 classpath 下的 scenario-templates.json；与画像文件一样 fail-soft：
 * 文件缺失或损坏只记录告警并返回空模板列表，绝不阻断启动。</p>
 *
 * <p>落库复用 {@link PoolConfigService#createValidated(PoolDefinition)} 与
 * {@link RuleConfigService#createValidated(RuleDefinition)}，不走 HTTP 自调用；
 * 单项失败只记 warning 并继续，避免用户因部分成功而重复应用。</p>
 *
 * @author Trunkline Team
 */
@Service
public class ScenarioTemplateService {

    private static final Logger logger = LoggerFactory.getLogger(ScenarioTemplateService.class);
    private static final String RESOURCE = "data/scenario-templates.json";

    private final ModelCapabilityProfileService profileService;
    private final ModelServiceRegistry modelServiceRegistry;
    private final PoolConfigService poolConfigService;
    private final RuleConfigService ruleConfigService;
    private final List<ScenarioTemplate> templates;

    public ScenarioTemplateService(final ObjectMapper objectMapper,
                                   final ModelCapabilityProfileService profileService,
                                   final ModelServiceRegistry modelServiceRegistry,
                                   final PoolConfigService poolConfigService,
                                   final RuleConfigService ruleConfigService) {
        this.profileService = profileService;
        this.modelServiceRegistry = modelServiceRegistry;
        this.poolConfigService = poolConfigService;
        this.ruleConfigService = ruleConfigService;
        this.templates = loadTemplates(objectMapper);
    }

    /** 全部场景模板（稳定顺序）。 */
    public List<ScenarioTemplate> list() {
        return templates;
    }

    public Optional<ScenarioTemplate> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return templates.stream().filter(t -> id.equals(t.getId())).findFirst();
    }

    /** 只生成草稿，不写任何配置。 */
    public ScenarioApplyResult preview(final String id) {
        return build(requireTemplate(id), false);
    }

    /** 一键应用：建池 + 建规则并热生效。 */
    public ScenarioApplyResult apply(final String id) {
        return build(requireTemplate(id), true);
    }

    private ScenarioTemplate requireTemplate(final String id) {
        return findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Scenario template not found: " + id));
    }

    private ScenarioApplyResult build(final ScenarioTemplate template, final boolean applied) {
        final ScenarioApplyResult result = new ScenarioApplyResult();
        result.setScenarioId(template.getId());
        result.setScenarioName(template.getName());
        result.setApplied(applied);

        // 用 LinkedHashMap 保持模板里的 serviceType 顺序，占位符 ${poolName} 取第一个池。
        final Map<String, Selected> selected = new LinkedHashMap<>();
        if (template.getPool() != null) {
            for (String serviceType : template.getSelection().getServiceTypes()) {
                final List<ModelRouterProperties.ModelInstance> instances = instancesOf(serviceType);
                if (instances.isEmpty()) {
                    result.getWarnings().add("服务类型 " + serviceType + " 暂无已接入实例，跳过建池");
                    continue;
                }
                final Selected one = selectOne(serviceType, instances, template.getSelection(), result);
                final String poolName = resolve(template.getPool().getNamePattern(), selected, template)
                        .replace("${serviceType}", serviceType);
                if (poolName.contains("${")) {
                    result.getWarnings().add("池名模板仍含未识别占位符，跳过：" + template.getPool().getNamePattern());
                    continue;
                }
                one.poolName = poolName;
                selected.put(serviceType, one);

                final PoolDefinition pool = new PoolDefinition();
                pool.setPoolName(poolName);
                pool.setName(template.getName() + " · " + serviceType);
                pool.setServiceType(serviceType);
                pool.setStrategy(template.getPool().getStrategy());
                pool.setDescription("由场景模板 " + template.getId() + " 一键创建"
                        + (one.profileMatched ? "（画像命中 " + one.profile.getCanonicalId() + "）" : "（未命中画像，使用默认实例）"));
                final PoolMember member = new PoolMember();
                member.setInstanceId(one.instance.getInstanceId() != null
                        ? one.instance.getInstanceId() : one.instance.getName());
                member.setModelName(one.instance.getName());
                pool.setMembers(new ArrayList<>(List.of(member)));

                final ScenarioApplyResult.PoolSummary summary = new ScenarioApplyResult.PoolSummary();
                summary.setPoolName(poolName);
                summary.setServiceType(serviceType);
                summary.setStrategy(pool.getStrategy());
                summary.setInstanceName(one.instance.getName());
                summary.setCanonicalId(one.profile != null ? one.profile.getCanonicalId() : null);
                summary.setProfileMatched(one.profileMatched);
                if (applied) {
                    try {
                        poolConfigService.createValidated(pool);
                        summary.setCreated(true);
                    } catch (RuntimeException e) {
                        summary.setCreated(false);
                        result.getWarnings().add("创建池 " + poolName + " 失败：" + e.getMessage());
                    }
                }
                result.getPools().add(summary);
            }
        }

        for (ScenarioTemplate.RuleSpec spec : template.getRules()) {
            final RuleDefinition rule = buildRule(spec, selected, template, result);
            if (rule == null) {
                continue;
            }
            final ScenarioApplyResult.RuleSummary summary = new ScenarioApplyResult.RuleSummary();
            summary.setName(rule.getName());
            summary.setTargetModel(rule.getAction().getModelName());
            if (applied) {
                try {
                    ruleConfigService.createValidated(rule);
                    summary.setCreated(true);
                } catch (RuntimeException e) {
                    summary.setCreated(false);
                    result.getWarnings().add("创建规则 " + rule.getName() + " 失败：" + e.getMessage());
                }
            }
            result.getRules().add(summary);
        }
        return result;
    }

    private RuleDefinition buildRule(final ScenarioTemplate.RuleSpec spec,
                                     final Map<String, Selected> selected,
                                     final ScenarioTemplate template,
                                     final ScenarioApplyResult result) {
        try {
            final RuleDefinition rule = new RuleDefinition();
            rule.setName(resolve(spec.getName(), selected, template));
            rule.setDescription(resolve(spec.getDescription(), selected, template));
            rule.setPriority(spec.getPriority());
            rule.setEnabled(true);
            final List<RuleDefinition.Condition> conditions = new ArrayList<>();
            for (ScenarioTemplate.ConditionSpec c : spec.getConditions()) {
                conditions.add(new RuleDefinition.Condition(
                        RuleDefinition.ConditionType.valueOf(c.getType()),
                        RuleDefinition.Operator.valueOf(c.getOperator()),
                        resolve(c.getValue(), selected, template)));
            }
            rule.setConditions(conditions);
            if (spec.getActions().size() != 1) {
                result.getWarnings().add("规则 " + rule.getName() + " 动作数量不是 1，跳过："
                        + spec.getActions().size());
                return null;
            }
            final ScenarioTemplate.ActionSpec a = spec.getActions().get(0);
            final RuleDefinition.ActionType type = RuleDefinition.ActionType.valueOf(a.getType());
            final RuleDefinition.Action action = new RuleDefinition.Action(type, null);
            switch (type) {
                case TARGET_MODEL -> action.setModelName(resolve(a.getModelName(), selected, template));
                case TARGET_INSTANCE -> action.setInstanceId(resolve(a.getInstanceId(), selected, template));
                case TARGET_ADAPTER -> action.setAdapterName(resolve(a.getAdapterName(), selected, template));
                case LB_STRATEGY -> action.setLbStrategy(resolve(a.getLbStrategy(), selected, template));
                case RATE_LIMIT -> {
                    action.setCapacity(a.getCapacity());
                    action.setRate(a.getRate());
                    action.setAlgorithm(a.getAlgorithm());
                    action.setScope(a.getScope());
                    action.setWarmUpPeriod(a.getWarmUpPeriod());
                }
                case TARGET_TAGS -> {
                    result.getWarnings().add("规则 " + rule.getName() + " 使用了暂不支持的场景动作 TARGET_TAGS，跳过");
                    return null;
                }
            }
            // 占位符未解析（例如对应 serviceType 无实例被跳过）时绝不落库，否则会创建无效路由。
            final String target = action.getModelName() != null ? action.getModelName()
                    : action.getInstanceId() != null ? action.getInstanceId()
                    : action.getAdapterName() != null ? action.getAdapterName()
                    : action.getLbStrategy();
            if (target != null && target.contains("${")) {
                result.getWarnings().add("规则 " + rule.getName() + " 的目标仍含未解析占位符 " + target + "，跳过");
                return null;
            }
            rule.setAction(action);
            return rule;
        } catch (IllegalArgumentException e) {
            result.getWarnings().add("规则 " + spec.getName() + " 模板非法，跳过：" + e.getMessage());
            return null;
        }
    }

    private Selected selectOne(final String serviceType,
                               final List<ModelRouterProperties.ModelInstance> instances,
                               final ScenarioTemplate.Selection selection,
                               final ScenarioApplyResult result) {
        final String useCase = firstNonBlank(selection.getUseCases());
        final String prefer = firstNonBlank(selection.getPrefer());
        if (useCase != null) {
            final List<ModelCapabilityProfile> profiles =
                    profileService.filter(useCase, serviceType, null, prefer, selection.getMaxLatency());
            // SPI 覆盖实现可能返回 null，按空画像降级到 first-available，绝不 NPE。
            for (ModelCapabilityProfile profile : profiles != null ? profiles : List.<ModelCapabilityProfile>of()) {
                for (ModelRouterProperties.ModelInstance instance : instances) {
                    if (profile.matchesName(instance.getName())) {
                        return new Selected(instance, profile, true);
                    }
                }
            }
        }
        // 画像未命中时降级到第一个可用实例，并明确标注（不伪造画像）。
        result.getWarnings().add("服务类型 " + serviceType + " 未命中画像，使用默认实例 "
                + instances.get(0).getName());
        return new Selected(instances.get(0), null, false);
    }

    private List<ModelRouterProperties.ModelInstance> instancesOf(final String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return List.of();
        }
        try {
            final ModelServiceRegistry.ServiceType type =
                    ModelServiceRegistry.ServiceType.valueOf(serviceType.trim());
            final Map<ModelServiceRegistry.ServiceType,
                    List<ModelRouterProperties.ModelInstance>> all = modelServiceRegistry.getAllInstances();
            final List<ModelRouterProperties.ModelInstance> found = all == null ? null : all.get(type);
            return found != null ? found : List.of();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private static String resolve(final String text, final Map<String, Selected> selected,
                                  final ScenarioTemplate template) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Map.Entry<String, Selected> entry : selected.entrySet()) {
            out = out.replace("${poolName." + entry.getKey() + "}", entry.getValue().poolName);
            out = out.replace("${modelName." + entry.getKey() + "}", entry.getValue().instance.getName());
        }
        final Selected first = selected.values().stream().findFirst().orElse(null);
        if (first != null) {
            out = out.replace("${poolName}", first.poolName);
            out = out.replace("${modelName}", first.instance.getName());
        }
        return out.replace("${id}", template.getId());
    }

    private static String firstNonBlank(final List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private List<ScenarioTemplate> loadTemplates(final ObjectMapper objectMapper) {
        final ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            logger.warn("场景模板文件缺失({})，一键虚拟模型返回空列表（不影响路由/计费）", RESOURCE);
            return Collections.emptyList();
        }
        try (InputStream in = resource.getInputStream()) {
            final JsonNode root = objectMapper.readTree(in);
            final JsonNode nodes = root.get("templates");
            if (nodes == null || !nodes.isArray()) {
                logger.warn("场景模板文件缺少 templates 数组，返回空列表");
                return Collections.emptyList();
            }
            final List<ScenarioTemplate> result = new ArrayList<>();
            for (JsonNode node : nodes) {
                try {
                    final ScenarioTemplate template = objectMapper.treeToValue(node, ScenarioTemplate.class);
                    if (template != null && template.getId() != null && template.getName() != null) {
                        result.add(template);
                    }
                } catch (Exception e) {
                    logger.warn("跳过非法场景模板条目：{}", node.path("id").asText("?"));
                }
            }
            logger.info("场景模板已加载：{} 条（来源 {}）", result.size(), RESOURCE);
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            logger.warn("场景模板解析失败，降级为空列表：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static final class Selected {
        private final ModelRouterProperties.ModelInstance instance;
        private final ModelCapabilityProfile profile;
        private final boolean profileMatched;
        private String poolName;

        private Selected(final ModelRouterProperties.ModelInstance instance,
                         final ModelCapabilityProfile profile,
                         final boolean profileMatched) {
            this.instance = instance;
            this.profile = profile;
            this.profileMatched = profileMatched;
        }
    }
}
