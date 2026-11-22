package org.unreal.modelrouter.router.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.router.rule.model.RuleDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 路由规则「创建」及生效规则状态的可复用服务（v0.2.0 场景模板服务下沉）。
 *
 * <p>把 {@code RuleConfigController} 的<strong>生效规则状态（activeRules）+ reload +
 * createRule 的校验/重名/id/save/热刷新</strong>下沉于此，供 Controller 与场景模板 apply 共用——
 * 场景 apply 建的规则必须同时进入规则引擎（路由生效）与管理端列表（可见），两者共享同一份
 * activeRules 状态。这是纯重构：<strong>逻辑与 Controller 原有实现逐字一致</strong>，对外行为不变。</p>
 *
 * @author Trunkline Team
 */
@Service
public class RuleConfigService {

    private static final Logger logger = LoggerFactory.getLogger(RuleConfigService.class);

    private final RuleEngineService ruleEngineService;
    private final RuleDefinitionPersistenceService persistenceService;

    /** 当前生效规则（含 YAML 默认 + 持久化用户规则，按 priority 降序）——下沉自 Controller。 */
    private final CopyOnWriteArrayList<RuleDefinition> activeRules = new CopyOnWriteArrayList<>();

    public RuleConfigService(final RuleEngineService ruleEngineService,
                             final RuleDefinitionPersistenceService persistenceService) {
        this.ruleEngineService = ruleEngineService;
        this.persistenceService = persistenceService;
        reload();
    }

    /** 重新加载生效规则（合并 YAML 默认 + 持久化，持久化同 id 覆盖 YAML）——从 Controller 原样搬入。 */
    public synchronized void reload() {
        List<RuleDefinition> yamlRules = ruleEngineService.getYamlRules();
        List<RuleDefinition> persistedRules = persistenceService.loadAll();

        List<RuleDefinition> merged = new ArrayList<>();
        for (RuleDefinition rule : yamlRules) {
            rule.setSource(RuleDefinition.Source.YAML);
            merged.add(rule);
        }
        for (RuleDefinition rule : persistedRules) {
            rule.setSource(RuleDefinition.Source.PERSISTED);
            merged.removeIf(r -> r.getId().equals(rule.getId()));
            merged.add(rule);
        }

        activeRules.clear();
        activeRules.addAll(merged);
        ruleEngineService.reloadRules(merged);
        logger.info("规则已加载: YAML {} 条 + 持久化 {} 条 = {} 条",
                yamlRules.size(), persistedRules.size(), merged.size());
    }

    /** 返回生效规则快照（Controller 读端点用）。 */
    public List<RuleDefinition> getActiveRules() {
        return activeRules;
    }

    /** 按 id 查生效规则——从 Controller 原样搬入。 */
    public RuleDefinition findRule(final String id) {
        return activeRules.stream()
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found: " + id));
    }

    /**
     * 创建规则（校验 + id + 重名 + 持久化 + 热刷新），与 Controller.createRule 行为一致。
     *
     * @return 已落库的规则定义
     * @throws ResponseStatusException 校验失败（400）或重名（409）
     */
    public RuleDefinition createValidated(final RuleDefinition rule) {
        validateRule(rule);
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setId(UUID.randomUUID().toString());
        }
        if (activeRules.stream().anyMatch(r -> r.getId().equals(rule.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Rule with id '" + rule.getId() + "' already exists");
        }
        persistenceService.save(rule);
        reload();
        return rule;
    }

    /** 校验规则——从 Controller 原样搬入，逐条一致（update 端点也复用）。 */
    public void validateRule(final RuleDefinition rule) {
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rule body must not be null");
        }
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rule name must not be blank");
        }
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rule must have at least one condition");
        }
        if (rule.getAction() == null || rule.getAction().getType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rule must have an action");
        }
        if (rule.getAction().getType() == RuleDefinition.ActionType.RATE_LIMIT) {
            long capacity = rule.getAction().getCapacity() != null ? rule.getAction().getCapacity() : 0L;
            long rate = rule.getAction().getRate() != null ? rule.getAction().getRate() : 0L;
            if (capacity <= 0 || rate <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "RATE_LIMIT action requires capacity > 0 and rate > 0");
            }
        }
        for (RuleDefinition.Condition condition : rule.getConditions()) {
            if (condition.getType() == null || condition.getOperator() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Condition type and operator are required");
            }
            if (condition.getType() == RuleDefinition.ConditionType.HEADER
                    && (condition.getField() == null || condition.getField().isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HEADER condition requires field");
            }
            if (condition.getOperator() == RuleDefinition.Operator.CIDR_MATCH
                    && condition.getType() != RuleDefinition.ConditionType.CLIENT_IP) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CIDR_MATCH operator is only valid for CLIENT_IP conditions");
            }
        }
    }
}
