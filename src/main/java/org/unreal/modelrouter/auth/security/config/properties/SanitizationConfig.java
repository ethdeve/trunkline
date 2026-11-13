package org.unreal.modelrouter.auth.security.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据脱敏配置类
 */
@Data
public class SanitizationConfig {
    /**
     * 请求脱敏配置
     */
    @Valid
    @NotNull
    private RequestSanitization request = new RequestSanitization();

    /**
     * 响应脱敏配置
     */
    @Valid
    @NotNull
    private ResponseSanitization response = new ResponseSanitization();

    /**
     * 请求脱敏配置
     */
    @Data
    public static class RequestSanitization {
        /**
         * 是否启用请求脱敏
         */
        private boolean enabled = true;

        /**
         * 敏感词列表
         */
        private List<String> sensitiveWords = new ArrayList<>();

        /**
         * PII数据模式列表
         */
        private List<String> piiPatterns = new ArrayList<>();

        /**
         * 掩码字符
         */
        @NotBlank
        @Size(min = 1, max = 5)
        private String maskingChar = "*";

        /**
         * 白名单用户列表
         */
        private List<String> whitelistUsers = new ArrayList<>();

        /**
         * 是否记录脱敏操作
         */
        private boolean logSanitization = true;

        /**
         * 脱敏失败时是否中断请求处理
         */
        private boolean failOnError = false;
    }

    /**
     * 响应脱敏配置
     */
    @Data
    public static class ResponseSanitization {
        /**
         * 是否启用响应脱敏
         */
        private boolean enabled = true;

        /**
         * 敏感词列表
         */
        private List<String> sensitiveWords = new ArrayList<>();

        /**
         * PII数据模式列表
         */
        private List<String> piiPatterns = new ArrayList<>();

        /**
         * 掩码字符
         */
        @NotBlank
        @Size(min = 1, max = 5)
        private String maskingChar = "*";

        /**
         * 是否记录脱敏操作
         */
        private boolean logSanitization = true;

        /**
         * 脱敏失败时是否中断响应处理
         */
        private boolean failOnError = false;

        /**
         * 是否保留 JSON 结构（仅对 application/json 有效）
         * 为 true 时，正则匹配仅作用于 JSON 字符串字面量内部，不破坏数值和结构
         */
        private boolean preserveJsonStructure = true;

        /**
         * O5 出站 DLP 开源开关：是否对 AI 实时推理路径（{@code /v1/**}、{@code /api/v1/**}）
         * 强制启用响应脱敏。
         *
         * <p>默认 {@code false}：保持现状，这些路径不进入脱敏过滤器，不改写客户端模型输出、
         * 不破坏 SSE 流式响应。设为 {@code true} 后，它们不再被脱敏排除集豁免——配合本节点
         * {@code enabled=true}，出站 prompt 中的 PII 即可被屏蔽。是否改写客户端模型输出由运维
         * 显式决策，开源侧只提供开关，规则库归企业版（B2）。</p>
         */
        private boolean maskAiPaths = false;

        /**
         * O5 追加到默认脱敏排除集的「精确」路径（命中即额外免于脱敏）。
         * 与 {@link #getExtraMaskExclusionPatterns()} 构成可配置的排除扩展，
         * 使硬编码默认集不再把所有路径一刀切。
         */
        private List<String> extraMaskExclusions = new ArrayList<>();

        /**
         * O5 追加到默认脱敏排除集的 Ant 风格模式（命中即额外免于脱敏）。
         */
        private List<String> extraMaskExclusionPatterns = new ArrayList<>();
    }
}
