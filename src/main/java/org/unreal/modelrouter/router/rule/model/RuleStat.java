package org.unreal.modelrouter.router.rule.model;

/**
 * 规则命中统计项
 */
public final class RuleStat {

    private String ruleId;
    private String ruleName;
    private String actionType;
    /** 进程生命周期内累计命中次数 */
    private long hits;
    /** 本自然月命中次数（跨月归零） */
    private long monthHits;

    public RuleStat() {
    }

    public RuleStat(final String ruleId, final String ruleName, final String actionType, final long hits) {
        this(ruleId, ruleName, actionType, hits, 0L);
    }

    public RuleStat(final String ruleId, final String ruleName, final String actionType,
                    final long hits, final long monthHits) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.actionType = actionType;
        this.hits = hits;
        this.monthHits = monthHits;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(final String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(final String ruleName) {
        this.ruleName = ruleName;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(final String actionType) {
        this.actionType = actionType;
    }

    public long getHits() {
        return hits;
    }

    public void setHits(final long hits) {
        this.hits = hits;
    }

    public long getMonthHits() {
        return monthHits;
    }

    public void setMonthHits(final long monthHits) {
        this.monthHits = monthHits;
    }
}
