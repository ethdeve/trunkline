/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景预览 / 一键应用结果（v0.2.0）。
 *
 * <p>只返回「创建了什么 + 跳过了什么 + 为什么」，不抛部分失败的 5xx：
 * 池或规则单个失败时记入 {@link #warnings} 并继续其余项，避免用户重复应用。</p>
 *
 * @author Trunkline Team
 */
public final class ScenarioApplyResult {

    private String scenarioId;
    private String scenarioName;
    /** true=已落库并热生效；false=仅预览，不写任何配置。 */
    private boolean applied;
    private List<PoolSummary> pools = new ArrayList<>();
    private List<RuleSummary> rules = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public static final class PoolSummary {
        private String poolName;
        private String serviceType;
        private String strategy;
        private String instanceName;
        private String canonicalId;
        private boolean profileMatched;
        private boolean created;

        public String getPoolName() {
            return poolName;
        }

        public void setPoolName(final String poolName) {
            this.poolName = poolName;
        }

        public String getServiceType() {
            return serviceType;
        }

        public void setServiceType(final String serviceType) {
            this.serviceType = serviceType;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(final String strategy) {
            this.strategy = strategy;
        }

        public String getInstanceName() {
            return instanceName;
        }

        public void setInstanceName(final String instanceName) {
            this.instanceName = instanceName;
        }

        public String getCanonicalId() {
            return canonicalId;
        }

        public void setCanonicalId(final String canonicalId) {
            this.canonicalId = canonicalId;
        }

        public boolean isProfileMatched() {
            return profileMatched;
        }

        public void setProfileMatched(final boolean profileMatched) {
            this.profileMatched = profileMatched;
        }

        public boolean isCreated() {
            return created;
        }

        public void setCreated(final boolean created) {
            this.created = created;
        }
    }

    public static final class RuleSummary {
        private String name;
        private String targetModel;
        private boolean created;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getTargetModel() {
            return targetModel;
        }

        public void setTargetModel(final String targetModel) {
            this.targetModel = targetModel;
        }

        public boolean isCreated() {
            return created;
        }

        public void setCreated(final boolean created) {
            this.created = created;
        }
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(final String scenarioId) {
        this.scenarioId = scenarioId;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(final String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public boolean isApplied() {
        return applied;
    }

    public void setApplied(final boolean applied) {
        this.applied = applied;
    }

    public List<PoolSummary> getPools() {
        return pools;
    }

    public void setPools(final List<PoolSummary> pools) {
        this.pools = pools;
    }

    public List<RuleSummary> getRules() {
        return rules;
    }

    public void setRules(final List<RuleSummary> rules) {
        this.rules = rules;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(final List<String> warnings) {
        this.warnings = warnings;
    }
}
