package org.unreal.modelrouter.auth.security.quota;

/**
 * 配额账本维度（租户 / API Key / 用户 / 服务类型 / 模型 / 分组）。
 *
 * <p>账本按上述六维聚合统计请求数与 token 数。任一维度未知时统一折叠为哨兵值
 * 而不是 {@code null}，原因有两点：</p>
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
 * @param tenantId    租户 ID，未指定时缺省租户 {@link #TENANT_SENTINEL}
 * @param apiKeyId    API Key ID，未指定时为空串
 * @param userId      用户 ID，未指定时为空串
 * @param serviceType 服务类型（chat / embedding / rerank / tts / stt），未指定时为空串
 * @param model       模型名称（已接入模型 / 实例名），未指定时为空串
 * @param groupId     分组（模型分组 / 池）名称，未指定时为空串
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
public record QuotaDimension(String tenantId, String apiKeyId, String userId, String serviceType, String model, String groupId) {

    /** 非租户维度缺省哨兵值（空串），与数据库维度列的 NOT NULL 约束配套使用 */
    public static final String SENTINEL = "";

    /**
     * 租户维度缺省哨兵值（{@code default}）。
     *
     * <p>历史账本曾用空串表示「未指定租户」，与其余 5 张业务表、JWT claim、
     * Redis 命名空间以及 Hibernate 行级过滤器的 {@code default} 口径冲突：
     * 打开多租户后过滤器按 {@code tenant_id='default'} 查询，账本却写在
     * {@code tenant_id=''}，导致账本整体「读不到」。故租户维度单独归一到
     * {@code default}，与 {@code TenantContextHolder} 回落值保持一致。
     * 非租户维度仍用 {@link #SENTINEL}，二者不可混用。</p>
     */
    public static final String TENANT_SENTINEL = "default";

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
     * 规范化构造：非租户维度 {@code null} 折叠为 {@link #SENTINEL}，
     * 租户维度 {@code null}/{@code 空串} 折叠为 {@link #TENANT_SENTINEL}。
     */
    public QuotaDimension {
        tenantId = normalizeTenant(tenantId);
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
     * 指定 Key 与归属租户的维度（多租户账本打标）。
     *
     * <p>与 {@link #ofApiKey(String)} 的区别仅在于租户维度：本方法显式写入调用归属租户，
     * 使账本行带正确 {@code tenant_id}、能被行级过滤器按租户读到。传 {@code null}/空串
     * 仍归一为 {@link #TENANT_SENTINEL}（单租户 default）。</p>
     *
     * @param apiKeyId API Key ID
     * @param tenantId 归属租户（来自 TenantContextHolder 或 Key 绑定）
     * @return 归一化后的维度
     */
    public static QuotaDimension ofApiKey(final String apiKeyId, final String tenantId) {
        return new QuotaDimension(tenantId, apiKeyId, null, null, null, null);
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

    /**
     * 租户维度归一：{@code null} 与历史空串都折叠为 {@link #TENANT_SENTINEL}。
     *
     * <p>空串一并归一，兼容此前落库的历史账本行（{@code tenant_id=''}）。
     * 项目尚未正式投产、无存量数据迁移脚本——重新初始化即按新口径落库。</p>
     */
    private static String normalizeTenant(final String value) {
        return value == null || value.isEmpty() ? TENANT_SENTINEL : value;
    }
}
