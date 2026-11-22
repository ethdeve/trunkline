/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键虚拟模型场景模板（v0.2.0）。
 *
 * <p>每个模板 = 用途（useCases）+ 选模策略（selection）+ 池策略（pool）+
 * 规则条件/动作（rules）。条件与动作里的占位符（如 {@code ${poolName.chat}}、
 * {@code ${modelName.chat}}）由 {@link ScenarioTemplateService} 在 apply 时注入
 * 真实池名/实例名——规则模板里的硬编码示例值绝不能原样落库。</p>
 *
 * @author Trunkline Team
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ScenarioTemplate {

    private String id;
    private String name;
    private String description;
    private List<String> useCases = new ArrayList<>();
    private Selection selection = new Selection();
    private PoolSpec pool;
    private List<RuleSpec> rules = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Selection {
        private List<String> useCases = new ArrayList<>();
        private List<String> serviceTypes = new ArrayList<>();
        private List<String> prefer = new ArrayList<>();
        private String maxLatency = "";
        private String fallback = "first-available";

        public List<String> getUseCases() {
            return useCases;
        }

        public void setUseCases(final List<String> useCases) {
            this.useCases = useCases != null ? useCases : new ArrayList<>();
        }

        public List<String> getServiceTypes() {
            return serviceTypes;
        }

        public void setServiceTypes(final List<String> serviceTypes) {
            this.serviceTypes = serviceTypes != null ? serviceTypes : new ArrayList<>();
        }

        public List<String> getPrefer() {
            return prefer;
        }

        public void setPrefer(final List<String> prefer) {
            this.prefer = prefer != null ? prefer : new ArrayList<>();
        }

        public String getMaxLatency() {
            return maxLatency;
        }

        public void setMaxLatency(final String maxLatency) {
            this.maxLatency = maxLatency != null ? maxLatency : "";
        }

        public String getFallback() {
            return fallback;
        }

        public void setFallback(final String fallback) {
            this.fallback = fallback != null && !fallback.isBlank() ? fallback : "first-available";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PoolSpec {
        private String strategy = "weighted-random";
        private String namePattern = "scenario-${id}-${serviceType}";

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(final String strategy) {
            this.strategy = strategy != null && !strategy.isBlank() ? strategy : "weighted-random";
        }

        public String getNamePattern() {
            return namePattern;
        }

        public void setNamePattern(final String namePattern) {
            this.namePattern = namePattern != null && !namePattern.isBlank()
                    ? namePattern : "scenario-${id}-${serviceType}";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class RuleSpec {
        private String name;
        private String description;
        private int priority;
        private List<ConditionSpec> conditions = new ArrayList<>();
        private List<ActionSpec> actions = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(final int priority) {
            this.priority = priority;
        }

        public List<ConditionSpec> getConditions() {
            return conditions;
        }

        public void setConditions(final List<ConditionSpec> conditions) {
            this.conditions = conditions != null ? conditions : new ArrayList<>();
        }

        public List<ActionSpec> getActions() {
            return actions;
        }

        public void setActions(final List<ActionSpec> actions) {
            this.actions = actions != null ? actions : new ArrayList<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ConditionSpec {
        private String type;
        private String operator;
        private String value;

        public String getType() {
            return type;
        }

        public void setType(final String type) {
            this.type = type;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(final String operator) {
            this.operator = operator;
        }

        public String getValue() {
            return value;
        }

        public void setValue(final String value) {
            this.value = value;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ActionSpec {
        private String type;
        private String modelName;
        private String instanceId;
        private String adapterName;
        private String lbStrategy;
        private Long capacity;
        private Long rate;
        private String algorithm;
        private String scope;
        private Long warmUpPeriod;

        public String getType() {
            return type;
        }

        public void setType(final String type) {
            this.type = type;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(final String modelName) {
            this.modelName = modelName;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(final String instanceId) {
            this.instanceId = instanceId;
        }

        public String getAdapterName() {
            return adapterName;
        }

        public void setAdapterName(final String adapterName) {
            this.adapterName = adapterName;
        }

        public String getLbStrategy() {
            return lbStrategy;
        }

        public void setLbStrategy(final String lbStrategy) {
            this.lbStrategy = lbStrategy;
        }

        public Long getCapacity() {
            return capacity;
        }

        public void setCapacity(final Long capacity) {
            this.capacity = capacity;
        }

        public Long getRate() {
            return rate;
        }

        public void setRate(final Long rate) {
            this.rate = rate;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(final String algorithm) {
            this.algorithm = algorithm;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(final String scope) {
            this.scope = scope;
        }

        public Long getWarmUpPeriod() {
            return warmUpPeriod;
        }

        public void setWarmUpPeriod(final Long warmUpPeriod) {
            this.warmUpPeriod = warmUpPeriod;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public List<String> getUseCases() {
        return useCases;
    }

    public void setUseCases(final List<String> useCases) {
        this.useCases = useCases != null ? useCases : new ArrayList<>();
    }

    public Selection getSelection() {
        return selection;
    }

    public void setSelection(final Selection selection) {
        this.selection = selection != null ? selection : new Selection();
    }

    public PoolSpec getPool() {
        return pool;
    }

    public void setPool(final PoolSpec pool) {
        this.pool = pool;
    }

    public List<RuleSpec> getRules() {
        return rules;
    }

    public void setRules(final List<RuleSpec> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
    }
}
