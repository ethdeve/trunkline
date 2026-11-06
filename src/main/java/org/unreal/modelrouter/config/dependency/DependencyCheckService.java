package org.unreal.modelrouter.config.dependency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.pool.PoolPersistenceService;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;
import org.unreal.modelrouter.router.pool.model.PoolMember;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 删除前的依赖检测服务。
 *
 * <p><b>背景</b>：本项目数据库层<b>零外键约束</b>（{@code service_instance.provider_id} 等
 * 只是裸列 + 普通索引），因此「模板 / 供应商 / 已接入模型 / 模型分组 / 密钥」之间的引用关系
 * 全部依赖应用层维护。此前各删除端点不做任何依赖检查，删掉上层对象后下层就成了悬空引用，
 * 表现为「模型在库里但路由不到」「密钥白名单指向不存在的分组」等难以定位的问题。</p>
 *
 * <p><b>引用链</b>（自上而下）：</p>
 * <pre>
 *   供应商模板 (providerUid = "tpl-" + 模板id)
 *     └─ 模型供应商 (provider_id)
 *          └─ 已接入模型 (pool.members[].instanceId)
 *               └─ 模型分组
 *                    └─ 密钥 (apiKey.allowedGroups)
 * </pre>
 *
 * <p><b>v3.5 治理收口：</b>密钥已移除 {@code allowedModels}（实例名白名单），
 * 密钥对实例的引用<b>只能经由所属分组</b>，故链路上不再有「密钥直连实例」的旁路。</p>
 *
 * <p><b>放置位置</b>：独立包 {@code config.dependency}，只向下依赖
 * {@code persistence} / {@code router.pool} / {@code auth.security}，不反向依赖
 * {@code config.core} 与 {@code config.service}，避免与既有注入关系形成包级循环。</p>
 *
 * @author JAiRouter Team
 * @since 3.3.2
 * @modified Trunkline Team
 */
@Slf4j
@Service
public class DependencyCheckService {

    private final ModelProviderRepository providerRepository;
    private final ServiceInstanceRepository instanceRepository;
    private final PoolPersistenceService poolPersistenceService;
    private final ApiKeyService apiKeyService;

    public DependencyCheckService(final ModelProviderRepository providerRepository,
                                  final ServiceInstanceRepository instanceRepository,
                                  final PoolPersistenceService poolPersistenceService,
                                  final ApiKeyService apiKeyService) {
        this.providerRepository = providerRepository;
        this.instanceRepository = instanceRepository;
        this.poolPersistenceService = poolPersistenceService;
        this.apiKeyService = apiKeyService;
    }

    // ------------------------------------------------------------------
    // 模型供应商
    // ------------------------------------------------------------------

    /**
     * 检查模型供应商：其下若还有已接入模型，则不允许删除（须先删除这些模型）。
     */
    public DependencyReport checkProvider(final Long providerId) {
        final ModelProviderEntity provider = providerRepository.findById(providerId).orElse(null);
        final DependencyReport report = new DependencyReport(
                "provider", providerId == null ? null : String.valueOf(providerId),
                provider == null ? null : provider.getName());
        if (provider == null) {
            return report;
        }
        for (final ServiceInstanceEntity instance : instanceRepository.findByProviderId(providerId)) {
            report.getBlockers().add(new DependencyBlocker(
                    "instance", String.valueOf(instance.getId()), instance.getInstanceName(), "已接入模型"));
        }
        return report;
    }

    // ------------------------------------------------------------------
    // 已接入模型
    // ------------------------------------------------------------------

    /**
     * 检查已接入模型：被模型分组引用、或被密钥白名单关联（模型名 / 分组名）时不允许删除。
     */
    public DependencyReport checkInstance(final Long instanceId) {
        final ServiceInstanceEntity entity = instanceRepository.findById(instanceId).orElse(null);
        final DependencyReport report = new DependencyReport(
                "instance", instanceId == null ? null : String.valueOf(instanceId),
                entity == null ? null : entity.getInstanceName());
        if (entity == null) {
            return report;
        }

        final List<String> poolNames = poolsReferencing(entity);
        for (final String poolName : poolNames) {
            report.getBlockers().add(new DependencyBlocker(
                    "pool", poolName, poolName,
                    "模型分组成员（可先删除该分组，或把本模型加入其它分组后再删）"));
        }

        for (final ApiKey key : apiKeys()) {
            final String keyId = key.getKeyId();
            // v3.5 治理收口：密钥只通过「分组白名单」间接引用实例——
            // 密钥已无 allowedModels，实例的唯一引用来源是所属分组。
            final boolean hitByGroup = containsIgnoreCase(key.getAllowedGroups(), poolNames);
            if (!hitByGroup) {
                continue;
            }
            if (isEnabled(key)) {
                report.getBlockers().add(new DependencyBlocker(
                        "apiKey", keyId, describeKey(key), "密钥白名单关联了该模型所属分组"));
            } else {
                report.getWarnings().add("密钥「" + describeKey(key) + "」已停用，但仍关联该模型所属分组（启用后调用将失败）");
            }
        }
        return report;
    }

    // ------------------------------------------------------------------
    // 模型分组
    // ------------------------------------------------------------------

    /**
     * 检查模型分组：被密钥白名单（allowedGroups）引用时不允许删除。
     *
     * <p><b>v3.5 治理收口 —— 强制实例先入组：</b>对外调用的唯一入口是模型分组，
     * 实例只有作为分组成员才可被调用。因此删除分组时需要确认其成员实例
     * 在<b>其它分组</b>中是否仍有归属。</p>
     *
     * <p><b>游离实例降级为提示而非阻断</b>：删除整个分组后，其独占成员实例会变成
     * 「游离实例」（配置存在、但无任何对外入口）。此时<b>阻断删除会造成死锁</b>——
     * 删实例被「仍是分组成员」阻断、删分组被「会造成游离」阻断，两条路都走不通，
     * 管理员无法清理配置。因此整组删除放行，只在 {@code warnings} 里提示
     * 「这些实例已游离，请删除或加入其它分组」（控制台「未入组」标记会同步标红）。</p>
     *
     * <p>相比之下，<b>修改</b>分组成员（{@code PUT}）造成游离实例仍然硬阻断
     * （{@code PoolConfigController.assertNoOrphanedInstances}）——那是「手滑移出成员」
     * 的防护，与「整组下线」不是一回事。</p>
     */
    public DependencyReport checkPool(final String poolName) {
        final DependencyReport report = new DependencyReport("pool", poolName, poolName);
        for (final ApiKey key : apiKeys()) {
            if (!containsIgnoreCase(key.getAllowedGroups(), poolName)) {
                continue;
            }
            if (isEnabled(key)) {
                report.getBlockers().add(new DependencyBlocker(
                        "apiKey", key.getKeyId(), describeKey(key), "密钥白名单引用了该分组"));
            } else {
                report.getWarnings().add("密钥「" + describeKey(key) + "」已停用，但仍引用该分组");
            }
        }
        // 强制入组：删除后成员实例将失去全部分组归属 → 仅提示（整组删除放行，避免删不掉的死锁）
        final List<String> orphans = instancesOrphanedByPoolRemoval(poolName, null);
        if (!orphans.isEmpty()) {
            report.getWarnings().add("删除该分组后，以下实例将不再属于任何模型分组（无法对外提供调用）："
                    + String.join("、", orphans) + "。请在「接入的模型」页将其删除或加入其它模型分组。");
        }
        return report;
    }

    /**
     * 检查模型分组的成员变更：移除成员后是否会产生「游离实例」。
     *
     * <p>v3.5 治理收口 —— 强制实例先入组：实例只有作为分组成员才可被调用。
     * 从分组移除成员时，若该实例在其它分组中已无归属，就会变成游离实例
     * （配置存在、但无任何对外入口），此时阻断变更。</p>
     *
     * @param poolName    分组名
     * @param removedKeys 本次移除的成员标识（UUID / 实例名 / 数字主键）；为 {@code null} 表示整组删除
     * @return 会变成游离实例的实例名列表（空表示安全）
     */
    public List<String> findOrphanedInstances(final String poolName, final Collection<String> removedKeys) {
        return instancesOrphanedByPoolRemoval(poolName, removedKeys);
    }

    // ------------------------------------------------------------------
    // 供应商模板
    // ------------------------------------------------------------------

    /**
     * 检查供应商模板：由该模板启用的模型供应商存在时不允许删除。
     *
     * <p>模板与供应商之间没有数据库外键，靠 {@code providerUid = "tpl-" + 模板id} 弱关联
     * （自定义模板创建供应商时写入的幂等标识）。</p>
     */
    public DependencyReport checkTemplate(final String templateId) {
        final DependencyReport report = new DependencyReport("template", templateId, templateId);
        if (templateId == null || templateId.isBlank()) {
            return report;
        }
        final String providerUid = "tpl-" + templateId;
        for (final ModelProviderEntity provider : providerRepository.findAll()) {
            if (providerUid.equals(provider.getProviderUid())) {
                report.getBlockers().add(new DependencyBlocker(
                        "provider", String.valueOf(provider.getId()), provider.getName(), "由该模板启用"));
            }
        }
        return report;
    }

    // ------------------------------------------------------------------
    // 密钥
    // ------------------------------------------------------------------

    /**
     * 检查密钥：密钥是引用链末端，仅做提示不阻断（历史用量记录需保留用于审计）。
     */
    public DependencyReport checkApiKey(final String keyId) {
        final DependencyReport report = new DependencyReport("apiKey", keyId, keyId);
        for (final ApiKey key : apiKeys()) {
            if (keyId != null && keyId.equals(key.getKeyId())) {
                report.setTargetName(describeKey(key));
                final List<String> groups = key.getAllowedGroups();
                if (groups != null && !groups.isEmpty()) {
                    report.getWarnings().add("该密钥关联了 " + groups.size() + " 个模型分组：" + String.join("、", groups));
                }
                break;
            }
        }
        return report;
    }

    // ------------------------------------------------------------------
    // 统一断言
    // ------------------------------------------------------------------

    /**
     * 断言目标可删除；存在阻断项时抛出 409 业务异常。
     *
     * @param report 依赖检查报告
     * @throws ApiException 存在阻断项时抛出（HTTP 409）
     */
    public void assertDeletable(final DependencyReport report) {
        if (report != null && !report.isDeletable()) {
            throw new ApiException("CONFLICT_" + report.getTargetType().toUpperCase() + "_IN_USE", report.summary());
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 计算「删除指定分组后（或移除指定成员后）会变成游离实例」的实例名列表。
     *
     * <p><b>游离实例</b>：不属于任何启用分组的实例。v3.5 治理收口后实例只能经分组被调用，
     * 游离实例等价于死配置——所以删除分组 / 移除成员时必须阻断。</p>
     *
     * <p>判定口径：某实例属于待删分组，但不属于任何其它启用分组。</p>
     *
     * @param poolName    待处理的分组名
     * @param removedKeys 本次移除的成员标识；为 {@code null} 表示整组删除（取该组全部成员）
     * @return 游离实例名列表（空表示无副作用，可安全变更）
     */
    private List<String> instancesOrphanedByPoolRemoval(final String poolName,
                                                        final Collection<String> removedKeys) {
        final List<String> orphans = new ArrayList<>();
        if (poolPersistenceService == null || poolName == null) {
            return orphans;
        }
        final List<PoolDefinition> pools;
        try {
            pools = poolPersistenceService.loadAll();
        } catch (RuntimeException e) {
            log.warn("读取模型分组失败，跳过游离实例检查: {}", e.getMessage());
            return orphans;
        }
        if (pools == null || pools.isEmpty()) {
            return orphans;
        }
        // 1) 找出待删分组的成员（按实例名归一，池成员标识可能是 UUID / 实例名 / 数字主键）
        final Set<String> targetMemberKeys = new LinkedHashSet<>();
        for (final PoolDefinition pool : pools) {
            if (pool == null || !poolName.equals(pool.getPoolName()) || pool.getMembers() == null) {
                continue;
            }
            for (final PoolMember member : pool.getMembers()) {
                if (member != null && member.getInstanceId() != null) {
                    targetMemberKeys.add(member.getInstanceId());
                }
            }
        }
        if (targetMemberKeys.isEmpty()) {
            return orphans;
        }
        // 变更场景：只关心本次被移除的成员
        if (removedKeys != null) {
            targetMemberKeys.retainAll(new LinkedHashSet<>(removedKeys));
            if (targetMemberKeys.isEmpty()) {
                return orphans;
            }
        }
        // 2) 其它启用分组已覆盖的成员键
        final Set<String> coveredByOthers = new LinkedHashSet<>();
        for (final PoolDefinition pool : pools) {
            if (pool == null || pool.getMembers() == null) {
                continue;
            }
            if (poolName.equals(pool.getPoolName())) {
                continue;
            }
            if (!pool.isEnabled()) {
                continue;
            }
            for (final PoolMember member : pool.getMembers()) {
                if (member != null && member.getInstanceId() != null) {
                    coveredByOthers.add(member.getInstanceId());
                }
            }
        }
        // 3) 待删分组成员中，未被其它启用分组覆盖的 → 游离
        for (final String key : targetMemberKeys) {
            if (coveredByOthers.contains(key)) {
                continue;
            }
            // 键可能是 UUID / 实例名 / 数字主键，统一解析为实例名展示
            orphans.add(resolveInstanceName(key));
        }
        return orphans;
    }

    /**
     * 把池成员的标识解析为实例名（供提示展示）。
     *
     * @param memberKey 成员标识（UUID / 实例名 / 数字主键）
     * @return 实例名；查不到时回退为原标识
     */
    private String resolveInstanceName(final String memberKey) {
        for (final ServiceInstanceEntity instance : instanceRepository.findAll()) {
            if (memberKey.equals(instance.getInstanceName())
                    || memberKey.equals(instance.getInstanceId())
                    || memberKey.equals(String.valueOf(instance.getId()))) {
                return instance.getInstanceName();
            }
        }
        return memberKey;
    }

    /** 引用该实例的模型分组名（成员标识按 UUID / 实例名 / 数字主键三口径匹配） */
    private List<String> poolsReferencing(final ServiceInstanceEntity entity) {
        final List<String> poolNames = new ArrayList<>();
        if (poolPersistenceService == null) {
            return poolNames;
        }
        final Set<String> matchKeys = instanceMatchKeys(entity);
        List<PoolDefinition> pools;
        try {
            pools = poolPersistenceService.loadAll();
        } catch (RuntimeException e) {
            log.warn("读取模型分组失败，跳过分组引用检查: {}", e.getMessage());
            return poolNames;
        }
        if (pools == null) {
            return poolNames;
        }
        for (final PoolDefinition pool : pools) {
            if (pool == null || pool.getMembers() == null) {
                continue;
            }
            final boolean hit = pool.getMembers().stream()
                    .anyMatch(member -> member != null
                            && member.getInstanceId() != null
                            && matchKeys.contains(member.getInstanceId()));
            if (hit) {
                poolNames.add(pool.getPoolName());
            }
        }
        return poolNames;
    }

    /** 实例的三种可被池成员引用的标识 */
    private Set<String> instanceMatchKeys(final ServiceInstanceEntity entity) {
        final Set<String> keys = new LinkedHashSet<>();
        if (entity.getInstanceId() != null) {
            keys.add(entity.getInstanceId());
        }
        if (entity.getInstanceName() != null) {
            keys.add(entity.getInstanceName());
        }
        if (entity.getId() != null) {
            keys.add(String.valueOf(entity.getId()));
        }
        return keys;
    }

    private List<ApiKey> apiKeys() {
        final Map<String, ApiKey> cache = apiKeyService == null ? null : apiKeyService.getApiKeyCache();
        return cache == null ? List.of() : new ArrayList<>(cache.values());
    }

    /** 密钥是否处于启用状态（停用密钥只做提示，不阻断删除） */
    private boolean isEnabled(final ApiKey key) {
        return key.isEnabled();
    }

    private String describeKey(final ApiKey key) {
        final String description = key.getDescription();
        return description == null || description.isBlank() ? key.getKeyId() : description;
    }

    /** 大小写不敏感地判断目标列表是否包含指定值 */
    private boolean containsIgnoreCase(final List<String> values, final String target) {
        if (values == null || values.isEmpty() || target == null) {
            return false;
        }
        return values.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(target));
    }

    /** 大小写不敏感地判断目标列表是否包含候选集合中的任意一项 */
    private boolean containsIgnoreCase(final List<String> values, final List<String> targets) {
        if (values == null || values.isEmpty() || targets == null || targets.isEmpty()) {
            return false;
        }
        return targets.stream().anyMatch(t -> containsIgnoreCase(values, t));
    }
}
