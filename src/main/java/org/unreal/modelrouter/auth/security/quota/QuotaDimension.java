package org.unreal.modelrouter.auth.security.quota;

/**
 * 配额账本维度（租户 / API Key / 用户 / 服务类型 / 模型 / 分组）。
 *
 * <p>账本按上述六维聚合统计请求数与 token 数。任一维度未知时统一折叠为空串
 * {@link #SENTINEL} 而不是 {@code null}，原因有两点：</p>
 * <ul>
 *   <li>数据库维度列声明为 NOT NULL，空串哨兵值避免每次落库前做空值判断；</li>
 *   <li>{@code null} 与空串在账本中语义相同（均为“未指定”），归一化后
 *       {@link #equals(Object)} / {@link #hashCode()} 保持稳定，避免出现
 *       两份内容相同但 key 不同的内存槽位。</li>
 * </ul>
 *
 * <p>分组维度（{@code groupId}）用于「模型分组（池 / pool）」级独立配额：请求 {@code model}
 * 命中池时 {@code groupId = 池名}、{@code model} 维度留空（分组桶）；选实例后补记一行
 * {@code groupId = 池名, model = 实例名}（成员桶）。单线治理下分组与模型<b>并存</b>——
 * {@code groupId} 是治理主键、{@code model} 是组内观测明细；Key 维度恒生效。</p>
 *
 * @param tenantId    租户 ID，未指定时为空串
 * @param apiKeyId    API Key ID，未指定时为空串
 * @param userId      用户 ID，未指定时为空串
 * @param serviceType 服务类型（chat / embedding / rerank / tts / stt），未指定时为空串
 * @param model       模型名称（已接入模型 / 实例名），未指定时为空串
 * @param groupId     分组（模型分组 / 池）名称，未指定时为空串
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
public record QuotaDimension(String tenantId, String apiKeyId, String userId, String serviceType, String model, String groupId) {

    /** 维度缺省哨兵值（空串），与数据库维度列的 NOT NULL 约束配套使用 */
    public static final String SENTINEL = "";

    /**
     * 兼容构造器（不含分组维度）。
     *
     * <p>v3.4 之前维度只有五维，本构造器保留原签名并把 {@code groupId} 置为空串哨兵值，
     * 使既有调用点（与单测）语义不变——「未指定分组」与「显式空串」在账本中等价。</p>
     *
     * @param tenantId    租户 ID
     * @param apiKeyId    API Key ID
     * @param userId      用户 ID
     * @param serviceType 服务类型
     * @param model       模型名称
     */
    public QuotaDimension(final String tenantId,
                          final String apiKeyId,
                          final String userId,
                          final String serviceType,
                          final String model) {
        this(tenantId, apiKeyId, userId, serviceType, model, null);
    }

    /**
     * 规范化构造：把任意 {@code null} 维度折叠为空串哨兵值。
     */
    public QuotaDimension {
        tenantId = normalize(tenantId);
        apiKeyId = normalize(apiKeyId);
        userId = normalize(userId);
        serviceType = normalize(serviceType);
        model = normalize(model);
        groupId = normalize(groupId);
    }

    /**
     * 仅指定 API Key 的维度，其余维度取哨兵值。
     * 适用于尚不知道该请求租户 / 用户 / 模型 / 分组的查询。
     *
     * @param apiKeyId API Key ID，可为 {@code null}
     * @return 归一化后的维度
     */
    public static QuotaDimension ofApiKey(final String apiKeyId) {
        return new QuotaDimension(null, apiKeyId, null, null, null, null);
    }

    /**
     * 仅指定分组的维度（模型分组 / 池级配额）。
     *
     * @param groupId 分组（池）名称，可为 {@code null}
     * @return 归一化后的维度
     */
    public static QuotaDimension ofGroup(final String groupId) {
        return new QuotaDimension(null, null, null, null, null, groupId);
    }

    /**
     * 「分组 + 实例」并存维度（v3.5 治理收口）。
     *
     * <p>单线治理下，账本明细行同时记录：</p>
     * <ul>
     *   <li>{@code groupId} —— 请求命中的模型分组（虚拟模型名，<b>治理主键</b>），
     *       分组级配额判定与「GROUP BY group_id」聚合都基于它；</li>
     *   <li>{@code model} —— 实际落到的已接入模型（实例名，<b>观测明细</b>），
     *       用于组内模型维度的用量下钻。</li>
     * </ul>
     *
     * <p><b>两行而非一行</b>：本维度是「组内成员桶」行，与预留期写入的「分组桶」行
     * （{@link #ofGroup(String)}，{@code model} 为空）<b>并列存在于账本中</b>，
     * 八列唯一键不同（{@code model} 列取值不同）。因此：</p>
     * <ul>
     *   <li><b>分组聚合</b>只统计 {@code model} 为空的分组桶行；</li>
     *   <li><b>组内明细</b>只统计本维度产生的成员桶行。</li>
     * </ul>
     * <p>两者<b>互斥、不可相加</b>——分组桶已包含该分组内全部请求，相加会让分组用量翻倍。</p>
     *
     * @param groupId 分组（池）名称，可为 {@code null}
     * @param model   实例名称，可为 {@code null}
     * @return 归一化后的维度
     */
    public static QuotaDimension ofGroupAndModel(final String groupId, final String model) {
        return new QuotaDimension(null, null, null, null, model, groupId);
    }

    /**
     * 仅指定模型的维度（已接入模型 / 实例级配额）。
     *
     * @param model 模型（实例）名称，可为 {@code null}
     * @return 归一化后的维度
     */
    public static QuotaDimension ofModel(final String model) {
        return new QuotaDimension(null, null, null, null, model, null);
    }

    /**
     * 全部维度取哨兵值的空维度。
     *
     * @return 空维度
     */
    public static QuotaDimension empty() {
        return new QuotaDimension(null, null, null, null, null, null);
    }

    /**
     * 把 {@code null} 归一化为空串哨兵值。
     *
     * @param value 原始维度值
     * @return 非 {@code null} 的维度值
     */
    private static String normalize(final String value) {
        return value == null ? SENTINEL : value;
    }
}
