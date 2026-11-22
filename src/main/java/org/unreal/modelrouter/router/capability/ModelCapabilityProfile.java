/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.capability;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型能力画像（v0.2.0 capability profiles）。
 *
 * <p>静态描述「模型擅长什么」，与运行时实例（ServiceInstance）解耦：通过
 * {@code canonicalId} 把多个别名（中转站/算力中心的自由模型名）归一到同一画像。
 * 维度粒度见方案 §8.1：客观维度用精确值，主观维度用 high/mid/low 三档，
 * 未知统一用字符串 {@code "unknown"}（而非 false，避免假阴性）。</p>
 *
 * <p>本 DTO 同时承载内置基线（builtin）、现场持久化（persisted）、名称识别
 * （resolver）、能力探测（probe）四种来源，通过 {@link #source} 区分，前端展示时
 * 标注来源，避免把实测/推测值误读为厂商承诺。</p>
 *
 * @author Trunkline Team
 */
public final class ModelCapabilityProfile {

    /** 未知维度取值——与布尔 false 严格区分（false=明确不支持，unknown=未标注）。 */
    public static final String UNKNOWN = "unknown";
    public static final String HIGH = "high";
    public static final String MID = "mid";
    public static final String LOW = "low";
    public static final String YES = "yes";
    public static final String NO = "no";

    /** 内置基线来源。 */
    public static final String SOURCE_BUILTIN = "builtin";
    /** 现场/企业持久化注入。 */
    public static final String SOURCE_PERSISTED = "persisted";
    /** 名称识别（别名归一）命中。 */
    public static final String SOURCE_RESOLVER = "resolver";
    /** 主动能力探测实测。 */
    public static final String SOURCE_PROBE = "probe";
    /** SPI 注入。 */
    public static final String SOURCE_SPI = "spi";

    /** 标准型号 ID（别名归一的目标；画像查询主键）。 */
    private String canonicalId;
    /** 接入时实际使用的模型名（与 instanceName 对齐，作为旁路绑定键）。 */
    private String modelId;
    /** 厂商。 */
    private String vendor;
    /** 别名列表（自由模型名 → canonicalId 的映射来源）。 */
    private List<String> aliases = new ArrayList<>();
    /** 服务类型（7 类，对齐 ServiceTypeConstants）。 */
    private String serviceType;
    /** 是否支持 function/tool call：yes/no/unknown。 */
    private String functionCall = UNKNOWN;
    /** 上下文窗口 tokens（字符串以支持区间如 "128000" 或 "32k-128k"）。 */
    private String contextWindow = UNKNOWN;
    /** 是否多模态输入：yes/no/unknown。 */
    private String multimodal = UNKNOWN;
    /** 是否支持流式。 */
    private boolean streaming = true;
    /** 主观强度维度。 */
    private Strengths strengths = new Strengths();
    /** 成本档位：low/mid/high/unknown。 */
    private String cost = UNKNOWN;
    /** 延迟档位：low/mid/high/unknown。 */
    private String latency = UNKNOWN;
    /** 适用场景标签（与场景模板 useCases 对应）。 */
    private List<String> useCases = new ArrayList<>();
    /** 画像来源（builtin/persisted/resolver/probe/spi）。 */
    private String source = SOURCE_BUILTIN;
    /** 说明。 */
    private String description;

    /** 主观强度四维度（high/mid/low/unknown）。 */
    public static final class Strengths {
        private String code = UNKNOWN;
        private String chinese = UNKNOWN;
        private String math = UNKNOWN;
        private String reasoning = UNKNOWN;

        public String getCode() {
            return code;
        }

        public void setCode(final String code) {
            this.code = normalize(code);
        }

        public String getChinese() {
            return chinese;
        }

        public void setChinese(final String chinese) {
            this.chinese = normalize(chinese);
        }

        public String getMath() {
            return math;
        }

        public void setMath(final String math) {
            this.math = normalize(math);
        }

        public String getReasoning() {
            return reasoning;
        }

        public void setReasoning(final String reasoning) {
            this.reasoning = normalize(reasoning);
        }

        private static String normalize(final String value) {
            return value == null || value.isBlank() ? UNKNOWN : value.trim().toLowerCase();
        }
    }

    public String getCanonicalId() {
        return canonicalId;
    }

    public void setCanonicalId(final String canonicalId) {
        this.canonicalId = canonicalId;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(final String modelId) {
        this.modelId = modelId;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(final String vendor) {
        this.vendor = vendor;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(final List<String> aliases) {
        this.aliases = aliases != null ? new ArrayList<>(aliases) : new ArrayList<>();
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(final String serviceType) {
        this.serviceType = serviceType;
    }

    public String getFunctionCall() {
        return functionCall;
    }

    public void setFunctionCall(final String functionCall) {
        this.functionCall = normalizeLevel(functionCall);
    }

    public String getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(final String contextWindow) {
        this.contextWindow = contextWindow == null || contextWindow.isBlank() ? UNKNOWN : contextWindow.trim();
    }

    public String getMultimodal() {
        return multimodal;
    }

    public void setMultimodal(final String multimodal) {
        this.multimodal = normalizeLevel(multimodal);
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(final boolean streaming) {
        this.streaming = streaming;
    }

    public Strengths getStrengths() {
        return strengths;
    }

    public void setStrengths(final Strengths strengths) {
        this.strengths = strengths != null ? strengths : new Strengths();
    }

    public String getCost() {
        return cost;
    }

    public void setCost(final String cost) {
        this.cost = normalizeLevel(cost);
    }

    public String getLatency() {
        return latency;
    }

    public void setLatency(final String latency) {
        this.latency = normalizeLevel(latency);
    }

    public List<String> getUseCases() {
        return useCases;
    }

    public void setUseCases(final List<String> useCases) {
        this.useCases = useCases != null ? new ArrayList<>(useCases) : new ArrayList<>();
    }

    public String getSource() {
        return source;
    }

    public void setSource(final String source) {
        this.source = source == null || source.isBlank() ? SOURCE_BUILTIN : source.trim().toLowerCase();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    /** 取值归一：yes/no/unknown 或 high/mid/low/unknown 通用小写化。 */
    private static String normalizeLevel(final String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.trim().toLowerCase();
    }

    /**
     * 该画像是否覆盖给定模型名（modelId 或任一 alias，大小写不敏感）。
     */
    public boolean matchesName(final String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        final String target = name.trim().toLowerCase();
        if (target.equals(modelId) || target.equals(canonicalId)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias != null && target.equals(alias.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
