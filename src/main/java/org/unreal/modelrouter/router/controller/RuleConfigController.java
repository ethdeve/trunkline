package org.unreal.modelrouter.router.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.rule.RuleDecision;
import org.unreal.modelrouter.router.rule.RuleDefinitionPersistenceService;
import org.unreal.modelrouter.router.rule.RuleEngineService;
import org.unreal.modelrouter.router.rule.RuleStatsService;
import org.unreal.modelrouter.router.rule.model.RuleDefinition;
import org.unreal.modelrouter.router.rule.model.RuleStat;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 路由规则配置管理控制器
 * 提供规则的增删改查、启停、优先级调整接口
 */
@RestController
@RequestMapping("/api/config/rules")
@CrossOrigin(origins = "*")
@Tag(name = "路由规则管理", description = "提供路由规则的增删改查、启停、优先级调整接口")
public class RuleConfigController {

    private static final Logger logger = LoggerFactory.getLogger(RuleConfigController.class);

    private final RuleEngineService ruleEngineService;
    private final RuleDefinitionPersistenceService persistenceService;
    private final RuleStatsService ruleStatsService;
    private final RateLimitManager rateLimitManager;

    // 当前生效规则(含 YAML 默认 + 持久化用户规则,按 priority 降序)
    private final CopyOnWriteArrayList<RuleDefinition> activeRules = new CopyOnWriteArrayList<>();

    public RuleConfigController(final RuleEngineService ruleEngineService,
                                final RuleDefinitionPersistenceService persistenceService,
                                final RuleStatsService ruleStatsService,
                                final RateLimitManager rateLimitManager) {
        this.ruleEngineService = ruleEngineService;
        this.persistenceService = persistenceService;
        this.ruleStatsService = ruleStatsService;
        this.rateLimitManager = rateLimitManager;
        reloadActiveRules();
    }

    /**
     * 重新加载生效规则(合并 YAML 默认 + 持久化,持久化同 id 覆盖 YAML)
     */
    private synchronized void reloadActiveRules() {
        List<RuleDefinition> yamlRules = ruleEngineService.getYamlRules();
        List<RuleDefinition> persistedRules = persistenceService.loadAll();

        List<RuleDefinition> merged = new ArrayList<>();
        for (RuleDefinition rule : yamlRules) {
            rule.setSource(RuleDefinition.Source.YAML);
            merged.add(rule);
        }
        for (RuleDefinition rule : persistedRules) {
            rule.setSource(RuleDefinition.Source.PERSISTED);
            // 持久化规则同 id 覆盖 YAML
            merged.removeIf(r -> r.getId().equals(rule.getId()));
            merged.add(rule);
        }

        activeRules.clear();
        activeRules.addAll(merged);
        ruleEngineService.reloadRules(merged);
        logger.info("规则已加载: YAML {} 条 + 持久化 {} 条 = {} 条",
                yamlRules.size(), persistedRules.size(), merged.size());
    }

    /**
     * 获取所有规则列表(按 priority 降序)
     */
    @GetMapping("/list")
    @Operation(summary = "获取所有规则列表", description = "获取全部路由规则(含 YAML 默认与持久化)")
    public ResponseEntity<RouterResponse<List<RuleDefinition>>> getAllRules() {
        List<RuleDefinition> sorted = new ArrayList<>(activeRules);
        sorted.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        return ResponseEntity.ok(RouterResponse.success(sorted));
    }

    /**
     * 获取单条规则
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取单条规则", description = "按 ID 获取规则详情")
    public ResponseEntity<RouterResponse<RuleDefinition>> getRule(@PathVariable final String id) {
        RuleDefinition rule = findRule(id);
        return ResponseEntity.ok(RouterResponse.success(rule));
    }

    /**
     * 创建规则
     */
    @PostMapping
    @Operation(summary = "创建规则", description = "创建新规则并热生效")
    public ResponseEntity<RouterResponse<RuleDefinition>> createRule(@RequestBody final RuleDefinition rule) {
        validateRule(rule);
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setId(UUID.randomUUID().toString());
        }
        if (activeRules.stream().anyMatch(r -> r.getId().equals(rule.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Rule with id '" + rule.getId() + "' already exists");
        }
        persistenceService.save(rule);
        reloadActiveRules();
        return ResponseEntity.status(HttpStatus.CREATED).body(RouterResponse.success(rule));
    }

    /**
     * 更新规则
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新规则", description = "按 ID 更新规则并热生效")
    public ResponseEntity<RouterResponse<RuleDefinition>> updateRule(@PathVariable final String id,
                                                                     @RequestBody final RuleDefinition rule) {
        RuleDefinition existing = findRule(id);
        // issue #708: YAML 来源规则的"改了被 reload 覆盖"与删除同源，一并拒绝（409）
        if (isYamlManaged(existing)) {
            return yamlManagedConflict();
        }
        validateRule(rule);
        rule.setId(existing.getId());
        persistenceService.save(rule);
        // v2.8.8: 清理旧规则级限流器(仍为 RATE_LIMIT 时下次命中惰性重建)
        rateLimitManager.removeRuleRateLimiter(id);
        reloadActiveRules();
        return ResponseEntity.ok(RouterResponse.success(rule));
    }

    /**
     * 删除规则
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除规则", description = "按 ID 删除规则并热生效")
    public ResponseEntity<RouterResponse<Void>> deleteRule(@PathVariable final String id) {
        RuleDefinition existing = findRule(id);
        // issue #708: YAML 规则删除后 reload 会把它重新合并进来（谎报成功）——校验期拒绝
        if (isYamlManaged(existing)) {
            return yamlManagedConflict();
        }
        persistenceService.remove(id);
        // v2.8.8: 清理规则级限流器,防泄漏
        rateLimitManager.removeRuleRateLimiter(id);
        reloadActiveRules();
        return ResponseEntity.ok(RouterResponse.success(null));
    }

    /**
     * 启用规则
     */
    @PutMapping("/{id}/enable")
    @Operation(summary = "启用规则", description = "启用指定规则")
    public ResponseEntity<RouterResponse<RuleDefinition>> enableRule(@PathVariable final String id) {
        RuleDefinition rule = findRule(id);
        // issue #708: YAML 规则的启停同样会被 reload 覆盖，拒绝
        if (isYamlManaged(rule)) {
            return yamlManagedConflict();
        }
        rule.setEnabled(true);
        persistenceService.save(rule);
        reloadActiveRules();
        return ResponseEntity.ok(RouterResponse.success(rule));
    }

    /**
     * 停用规则
     */
    @PutMapping("/{id}/disable")
    @Operation(summary = "停用规则", description = "停用指定规则")
    public ResponseEntity<RouterResponse<RuleDefinition>> disableRule(@PathVariable final String id) {
        RuleDefinition rule = findRule(id);
        // issue #708: YAML 规则的启停同样会被 reload 覆盖，拒绝
        if (isYamlManaged(rule)) {
            return yamlManagedConflict();
        }
        rule.setEnabled(false);
        persistenceService.save(rule);
        reloadActiveRules();
        return ResponseEntity.ok(RouterResponse.success(rule));
    }

    /**
     * 批量调整优先级
     * 仅更新持久化规则;未知 id(如 YAML 规则)跳过并计数,不再 404
     */
    @PutMapping("/priority")
    @Operation(summary = "批量调整优先级", description = "批量重排规则优先级 [{id, priority}],未知 id 跳过")
    public ResponseEntity<RouterResponse<Map<String, Object>>> updatePriorities(
            @RequestBody final List<Map<String, Object>> priorities) {
        if (priorities == null || priorities.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Priorities must not be empty");
        }
        List<RuleDefinition> persisted = persistenceService.loadAll();
        int updated = 0;
        int skipped = 0;
        for (Map<String, Object> item : priorities) {
            Object idObj = item.get("id");
            Object priorityObj = item.get("priority");
            if (idObj == null || priorityObj == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each item must have id and priority");
            }
            String id = String.valueOf(idObj);
            int priority = Integer.parseInt(String.valueOf(priorityObj));
            RuleDefinition rule = persisted.stream()
                    .filter(r -> r.getId().equals(id))
                    .findFirst()
                    .orElse(null);
            if (rule == null) {
                skipped++;
                logger.warn("优先级调整跳过未知规则 id: {} (可能为 YAML 规则)", id);
                continue;
            }
            rule.setPriority(priority);
            updated++;
        }
        persistenceService.saveAll(persisted);
        reloadActiveRules();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("skipped", skipped);
        return ResponseEntity.ok(RouterResponse.success(result));
    }

    /**
     * 规则模拟测试(dry-run)
     * 输入示例请求,返回命中的规则与动作,不修改任何状态
     */
    @PostMapping("/validate")
    @Operation(summary = "规则模拟测试", description = "输入示例请求(modelName/IP/headers),返回命中规则与动作,只读不改状态")
    public ResponseEntity<RouterResponse<Map<String, Object>>> validateRule(
            @RequestBody final Map<String, Object> request) {
        if (request == null || request.get("modelName") == null
                || String.valueOf(request.get("modelName")).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelName is required");
        }

        String modelName = String.valueOf(request.get("modelName"));
        String serviceTypeName = request.get("serviceType") != null
                ? String.valueOf(request.get("serviceType")) : "chat";
        String clientIp = request.get("clientIp") != null
                ? String.valueOf(request.get("clientIp")) : "127.0.0.1";
        @SuppressWarnings("unchecked")
        Map<String, String> headers = request.get("headers") instanceof Map
                ? (Map<String, String>) request.get("headers") : null;

        ModelServiceRegistry.ServiceType serviceType = Arrays.stream(ModelServiceRegistry.ServiceType.values())
                .filter(st -> st.name().equalsIgnoreCase(serviceTypeName))
                .findFirst()
                .orElse(null);
        if (serviceType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid serviceType: " + serviceTypeName
                    + " (valid: chat/embedding/rerank/tts/stt/imgGen/imgEdit)");
        }

        RuleDecision decision = ruleEngineService.evaluate(serviceType, modelName, clientIp, headers);

        Map<String, Object> result = new LinkedHashMap<>();
        if (decision == null) {
            result.put("matched", false);
            result.put("message", "未命中任何规则,将走默认路由逻辑");
        } else {
            RuleDefinition rule = decision.getRule();
            result.put("matched", true);
            result.put("ruleId", rule.getId());
            result.put("ruleName", rule.getName());
            result.put("priority", rule.getPriority());
            Map<String, Object> actionMap = new LinkedHashMap<>();
            actionMap.put("type", rule.getAction().getType().name());
            if (decision.getTargetModelName() != null) {
                actionMap.put("target", decision.getTargetModelName());
            } else if (decision.getTargetInstanceId() != null) {
                actionMap.put("target", decision.getTargetInstanceId());
            } else if (decision.getTargetAdapterName() != null) {
                actionMap.put("target", decision.getTargetAdapterName());
            } else if (decision.getLbStrategy() != null) {
                actionMap.put("target", decision.getLbStrategy());
            }
            // v2.8.8: RATE_LIMIT 动作输出限流参数
            if (rule.getAction().getType() == RuleDefinition.ActionType.RATE_LIMIT) {
                actionMap.put("capacity", rule.getAction().getCapacity());
                actionMap.put("rate", rule.getAction().getRate());
                actionMap.put("algorithm", rule.getAction().getAlgorithm());
                actionMap.put("scope", rule.getAction().getScope());
                actionMap.put("warmUpPeriod", rule.getAction().getWarmUpPeriod());
            }
            result.put("action", actionMap);
            result.put("message", "命中规则: " + rule.getName());
        }
        return ResponseEntity.ok(RouterResponse.success(result));
    }

    /**
     * 规则命中统计
     * 返回各规则命中次数(ruleId+actionType),仅统计在生效规则列表中的规则
     *
     * <p>每项含累计命中 {@code hits} 与本自然月命中 {@code monthHits}（跨月归零）。</p>
     */
    @GetMapping("/stats")
    @Operation(summary = "规则命中统计", description = "返回各规则的累计命中与本月命中次数(基于内存计数)")
    public ResponseEntity<RouterResponse<List<RuleStat>>> getRuleStats() {
        Map<String, RuleDefinition> activeById = activeRules.stream()
                .collect(Collectors.toMap(RuleDefinition::getId, r -> r, (a, b) -> a));
        List<RuleStat> stats = ruleStatsService.getStats().stream()
                .filter(s -> activeById.containsKey(s.getRuleId()))
                .map(s -> new RuleStat(s.getRuleId(), activeById.get(s.getRuleId()).getName(),
                        s.getActionType(), s.getHits(), s.getMonthHits()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(RouterResponse.success(stats));
    }

    private RuleDefinition findRule(final String id) {
        return activeRules.stream()
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found: " + id));
    }

    /**
     * 判定规则是否由 YAML 配置文件管理（issue #708）。
     *
     * <p>来源由 {@code reload} 时赋值；未经 reload 的旧对象可能为 null——
     * 此时回退「不在持久化集合即视为 YAML 管理」（宁可误拒不可谎报成功）。</p>
     */
    private boolean isYamlManaged(final RuleDefinition rule) {
        if (rule == null) {
            return false;
        }
        if (rule.getSource() == RuleDefinition.Source.YAML) {
            return true;
        }
        if (rule.getSource() == RuleDefinition.Source.PERSISTED) {
            return false;
        }
        final String id = rule.getId();
        return id != null && persistenceService.loadAll().stream()
                .noneMatch(r -> id.equals(r.getId()));
    }

    /**
     * YAML 管理项的统一 409 响应（issue #708：错误码 {@code YAML_RULE_ACTIVE}）。
     */
    private <T> ResponseEntity<RouterResponse<T>> yamlManagedConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(RouterResponse.error(
                        "该规则由 YAML 配置文件定义，无法通过 API 删除/修改/启停；请修改配置文件后重启生效",
                        "YAML_RULE_ACTIVE"));
    }

    private void validateRule(final RuleDefinition rule) {
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
        // v2.8.8: RATE_LIMIT 动作需 capacity>0 且 rate>0
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
