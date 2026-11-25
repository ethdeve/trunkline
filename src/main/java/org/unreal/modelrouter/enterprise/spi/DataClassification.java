/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

/**
 * 数据密级（#9 数据分级出境）。
 *
 * <p>首版三档（9-A 已确认口径）：{@link #PUBLIC} / {@link #INTERNAL} /
 * {@link #CONFIDENTIAL}，{@code code} 稳定不随展示名变化，便于策略持久化与扩展。
 * 三档已覆盖"公开 / 境内 / 机密"三类去向；将来新增 RESTRICTED 只需追加枚举值，
 * 不改动 SPI 签名（与 {@link EnterpriseSpiVersion} 的「加枚举不算破坏性」口径一致）。</p>
 *
 * <p>密级由服务端可信源注入（API Key 的 {@code ApiKey.metadata.classification}，
 * 其次分组/部门映射），<b>不信任请求头自报</b>——请求头仅作辅助提示且必须被服务端
 * 策略覆盖。</p>
 *
 * @author Trunkline Team
 */
public enum DataClassification {

    /** 公开：无出境限制 */
    PUBLIC("PUBLIC", 0),

    /** 内部：仅允许境内 / 私有部署模型 */
    INTERNAL("INTERNAL", 1),

    /** 机密：仅允许境内 / 私有部署，且强制留痕 */
    CONFIDENTIAL("CONFIDENTIAL", 2);

    private final String code;
    private final int priority;

    DataClassification(final String code, final int priority) {
        this.code = code;
        this.priority = priority;
    }

    public String getCode() {
        return code;
    }

    public int getPriority() {
        return priority;
    }

    /**
     * 从服务端配置值解析密级（大小写不敏感）.
     *
     * <p>无法识别 / 空值时回落 {@link #PUBLIC}——与"未知按最宽松解密级、由出境策略
     * 决定是否拒绝"的分层设计一致：密级只是输入，真正的拒绝由
     * {@link ClassificationRoutingPolicy} 决定；策略返回 null / 抛异常时主干 fail-closed。</p>
     */
    public static DataClassification fromCode(final String value) {
        if (value == null || value.isBlank()) {
            return PUBLIC;
        }
        for (final DataClassification c : values()) {
            if (c.code.equalsIgnoreCase(value.trim())) {
                return c;
            }
        }
        return PUBLIC;
    }
}
