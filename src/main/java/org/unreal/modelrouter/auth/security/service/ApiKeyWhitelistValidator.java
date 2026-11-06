package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi;
import org.unreal.modelrouter.common.constants.ServiceTypeConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 密钥白名单与权限分类的一致性校验器。
 *
 * <p><b>要解决的问题：</b>密钥的「可调用模型分类」（permissions）与
 * 「可访问模型分组」（allowedGroups）是两个独立维度，
 * 历史上两者之间没有任何校验，导致三种「看起来配好了、实际调不通」的组合：</p>
 * <table border="1">
 *   <caption>不一致组合的实际后果</caption>
 *   <tr><th>permissions</th><th>白名单</th><th>运行时结果</th></tr>
 *   <tr><td>chat</td><td>chat 分组</td><td>正常</td></tr>
 *   <tr><td>tts</td><td>chat 分组</td><td>403 —— 权限层直接拒绝</td></tr>
 *   <tr><td>chat</td><td>tts 分组</td><td>权限层放行，但池按分类匹配不上 → 404/503</td></tr>
 * </table>
 *
 * <p><b>校验规则：</b>白名单里的每个名字，其所属模型分类必须落在该密钥被授权的
 * 分类集合内。名字的分类归属由 {@link ServiceTypeCatalogApi} 提供
 * （实现位于 config 侧，auth 不反向依赖它）。</p>
 *
 * <p><b>v3.5 治理收口：</b>对外调用的唯一入口是模型分组（虚拟模型名），
 * 原先并存的 {@code allowedModels}（已接入模型 / 实例名白名单）已移除，
 * 因此本类只校验分组名到分类的映射。</p>
 *
 * <p><b>可达性判定：</b>运行时请求先按 URL 端点确定分类 C（C 必须 ∈ permissions），
 * 再在该分类的实例/池目录里按名字解析。所以一个名字「可达」的充要条件是
 * <b>存在某个被授权的分类，其目录下能解析出该名字</b>。同名实例可分属多个分类
 * （实例名在库内不唯一），因此按「分类集合 ∩ 授权集合 ≠ ∅」判定。</p>
 *
 * <p><b>保守放行的两种情况</b>（不阻断保存，只告警）：</p>
 * <ol>
 *   <li><b>名字查不到</b>：白名单里的名字在实例/池目录中都不存在（可能已被删除，
 *       或用户填了外部模型名）。无法判定其分类，放行。</li>
 *   <li><b>目录读取失败</b>：catalog 实现内部已做兜底（返回空结果 = 全部查不到）。</li>
 * </ol>
 *
 * <p><b>ADMIN 语义：</b>permissions 含 {@code admin}（旧权限格式）时，
 * 运行时 {@code hasServicePermission} 直接放行所有分类，不做白名单分类校验。</p>
 *
 * @author JAiRouter Team
 * @since 3.3.2
 * @modified Trunkline Team
 */
@Slf4j
@Component
public class ApiKeyWhitelistValidator {

    /** 旧权限格式：ADMIN 在运行时放行所有服务类型，不参与白名单分类校验 */
    private static final String LEGACY_ADMIN = "admin";

    private final ObjectProvider<ServiceTypeCatalogApi> catalogProvider;

    public ApiKeyWhitelistValidator(final ObjectProvider<ServiceTypeCatalogApi> catalogProvider) {
        this.catalogProvider = catalogProvider;
    }

    /**
     * 找出「分类不被权限覆盖」的白名单项，按模型分类归组。
     *
     * @param permissions   权限（模型分类）列表
     * @param allowedGroups 可访问模型分组（池名）列表
     * @return 分类 → 该分类下不一致的名字集合；无不一致时返回空 Map
     */
    public Map<String, Set<String>> findInvalidByCategory(final List<String> permissions,
                                                          final List<String> allowedGroups) {
        final Map<String, Set<String>> empty = new LinkedHashMap<>();
        if (permissions == null || permissions.isEmpty()) {
            // 没有任何分类权限 → 白名单分类无从谈起（且运行时本就 403）
            return empty;
        }
        if (containsAdmin(permissions)) {
            // ADMIN 放行所有分类
            return empty;
        }

        final Set<String> granted = new LinkedHashSet<>();
        for (final String permission : permissions) {
            final String canonical = ServiceTypeConstants.ServiceType.normalizeValue(permission);
            if (canonical != null) {
                granted.add(canonical);
            }
        }
        if (granted.isEmpty()) {
            return empty;
        }

        final Set<String> names = new LinkedHashSet<>();
        addAll(names, allowedGroups);
        if (names.isEmpty()) {
            return empty;
        }

        final ServiceTypeCatalogApi catalog = catalogProvider.getIfAvailable();
        if (catalog == null) {
            // config 模块未装配（单测/裁剪部署）→ 无法判定，放行
            log.debug("模型分类目录不可用，跳过白名单分类一致性校验");
            return empty;
        }

        final Map<String, Set<String>> resolved = catalog.resolveServiceTypes(names);
        if (resolved == null || resolved.isEmpty()) {
            return empty;
        }

        final Map<String, Set<String>> invalidByCategory = new LinkedHashMap<>();
        final Set<String> unknownNames = new TreeSet<>();
        for (final String name : names) {
            final Set<String> categories = resolved.get(name);
            if (categories == null || categories.isEmpty()) {
                // 查不到 → 无法判定，放行（可能是已删除的实例 / 外部模型名）
                unknownNames.add(name);
                continue;
            }
            boolean reachable = false;
            for (final String category : categories) {
                if (granted.contains(category)) {
                    reachable = true;
                    break;
                }
            }
            if (!reachable) {
                for (final String category : categories) {
                    invalidByCategory.computeIfAbsent(category, k -> new TreeSet<>()).add(name);
                }
            }
        }

        if (!unknownNames.isEmpty()) {
            log.debug("以下白名单项在实例/池目录中未找到，跳过分类校验: {}", unknownNames);
        }
        return invalidByCategory;
    }

    /**
     * 校验白名单与权限分类的一致性，不一致则抛出异常。
     *
     * @param permissions   已归一化的权限（模型分类）列表
     * @param allowedGroups 可访问模型分组（池名）列表
     * @throws ApiKeyWhitelistMismatchException 当存在分类不被权限覆盖的白名单项时抛出
     *         （由控制器映射为 400 INVALID_REQUEST）
     */
    public void validate(final List<String> permissions,
                          final List<String> allowedGroups) {
        final Map<String, Set<String>> invalid = findInvalidByCategory(permissions, allowedGroups);
        if (invalid.isEmpty()) {
            return;
        }
        throw new ApiKeyWhitelistMismatchException(buildMessage(invalid, permissions));
    }

    /**
     * 按白名单自动推导「可调用的模型分类」（2026-11：前端已移除分类手选字段）。
     *
     * <p><b>推导规则：</b></p>
     * <ul>
     *   <li>白名单非空 → 分类 = 白名单内各分组所属分类的并集（密钥恰好能调通所配分组）；</li>
     *   <li>白名单为空（= 不限制模型）→ 全部分类；</li>
     *   <li>分组在目录中解析不出分类（已删除 / 裁剪部署）→ 该分组跳过；
     *       全部解析不出时兜底为全部分类，避免产出「永远 403」的死密钥。</li>
     * </ul>
     *
     * <p>推导结果必然通过 {@link #validate(List, List)} 的一致性校验，
     * 因为分类恰好覆盖白名单（未解析名本来就被校验器放行）。</p>
     *
     * @param allowedGroups 白名单分组列表（可为 null / 空）
     * @return 推导出的分类权限列表（非空）
     */
    public List<String> derivePermissions(final List<String> allowedGroups) {
        final Set<String> categories = new LinkedHashSet<>();
        final ServiceTypeCatalogApi catalog = catalogProvider.getIfAvailable();
        if (catalog != null && allowedGroups != null && !allowedGroups.isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final String group : allowedGroups) {
                if (group != null && !group.trim().isEmpty()) {
                    names.add(group.trim());
                }
            }
            if (!names.isEmpty()) {
                final Map<String, Set<String>> resolved = catalog.resolveServiceTypes(names);
                if (resolved != null) {
                    for (final Set<String> types : resolved.values()) {
                        if (types != null) {
                            categories.addAll(types);
                        }
                    }
                }
            }
        }
        if (categories.isEmpty()) {
            // 白名单为空（不限制模型）或目录不可用 → 全部分类，避免永远 403 的死密钥
            return new ArrayList<>(ServiceTypeConstants.getAllServiceTypes());
        }
        return new ArrayList<>(categories);
    }

    /**
     * 构建可操作的报错文案。
     *
     * @param invalidByCategory 分类 → 不一致名字集合
     * @param permissions       已授权分类（用于提示「请补勾哪个分类」）
     * @return 报错文案
     */
    public String buildMessage(final Map<String, Set<String>> invalidByCategory,
                                final List<String> permissions) {
        final StringBuilder detail = new StringBuilder();
        invalidByCategory.forEach((category, items) -> {
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append('[').append(category).append("] ")
                  .append(String.join("、", items));
        });
        final Set<String> granted = new LinkedHashSet<>();
        for (final String permission : permissions) {
            final String canonical = ServiceTypeConstants.ServiceType.normalizeValue(permission);
            if (canonical != null) {
                granted.add(canonical);
            }
        }
        return String.format(
            "可访问模型/分组与「可调用的模型分类」不一致：%s。当前已授权分类：%s。"
            + "请为这些模型/分组所属的分类补勾权限，或从白名单中移除它们。",
            detail, granted.isEmpty() ? "（无）" : String.join("、", granted));
    }

    private void addAll(final Set<String> target, final Collection<String> source) {
        if (source == null) {
            return;
        }
        for (final String item : source) {
            if (item != null && !item.trim().isEmpty()) {
                target.add(item.trim());
            }
        }
    }

    private boolean containsAdmin(final List<String> permissions) {
        for (final String permission : permissions) {
            if (permission != null && LEGACY_ADMIN.equalsIgnoreCase(permission.trim())) {
                return true;
            }
        }
        return false;
    }
}
