package org.unreal.modelrouter.config.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.common.api.config.ServiceTypeCatalogApi;
import org.unreal.modelrouter.common.constants.ServiceTypeConstants;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.router.pool.PoolSelector;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型分类目录实现（{@link ServiceTypeCatalogApi} 的 config 侧实现）。
 *
 * <p>数据源与运行时保持同源，避免「配置期判定」与「运行期行为」出现分歧：</p>
 * <ul>
 *   <li><b>实例名 → 分类</b>：{@code service_instance.service_config_id} →
 *       {@code service_config.service_type}。这正是运行时路由分桶所用的分类
 *       （运行时按端点分类取该 service_config 下的实例集合）。</li>
 *   <li><b>分组名 → 分类</b>：{@link PoolSelector#getAllPools()} 是生效池快照
 *       （YAML + 持久化合并结果），与运行时 {@code findPool} 的匹配依据一致。</li>
 * </ul>
 *
 * <p><b>为什么放在 config 侧：</b>auth 模块不能反向依赖 config/router
 * （它们已依赖 auth，双向会形成包级环）。auth 侧只依赖
 * {@code common.api.config} 里的接口。</p>
 *
 * <p><b>实现说明：</b>直接查 JPA 仓储而非走 {@code ServiceInstanceManager#getAllInstances()}，
 * 因为后者会对每个实例做 provider/config/限流/熔断的 N+1 查询，而这里只需要
 * 「名字 → 分类」这一列数据。</p>
 *
 * @author JAiRouter Team
 * @since 3.3.2
 */
@Slf4j
@Service
public class ServiceTypeCatalogAdapter implements ServiceTypeCatalogApi {

    private final ServiceInstanceRepository serviceInstanceRepository;
    private final ServiceConfigRepository serviceConfigRepository;
    private final PoolSelector poolSelector;

    public ServiceTypeCatalogAdapter(final ServiceInstanceRepository serviceInstanceRepository,
                                     final ServiceConfigRepository serviceConfigRepository,
                                     final PoolSelector poolSelector) {
        this.serviceInstanceRepository = serviceInstanceRepository;
        this.serviceConfigRepository = serviceConfigRepository;
        this.poolSelector = poolSelector;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实例名与分组名都会登记到同一张映射里：v3.5 治理收口后
     * {@code hasModelPermission} 只判定 {@code allowedGroups}（唯一模型级授权维度），
     * 且池路由优先于实例过滤，所以同一个名字既要考虑
     * 「作为实例名」的归属，也要考虑「作为分组名」的归属。</p>
     *
     * <p>分类值统一归一化到 {@link ServiceTypeConstants.ServiceType} 的规范值
     * （chat/embedding/rerank/tts/stt/imgGen/imgEdit），大小写无关。</p>
     */
    @Override
    public Map<String, Set<String>> resolveServiceTypes(final Collection<String> names) {
        final Map<String, Set<String>> result = new LinkedHashMap<>();
        if (names == null || names.isEmpty()) {
            return result;
        }
        final Set<String> wanted = new LinkedHashSet<>();
        for (final String name : names) {
            if (name != null && !name.trim().isEmpty()) {
                wanted.add(name.trim());
            }
        }
        if (wanted.isEmpty()) {
            return result;
        }

        // 1) 实例名 → 分类
        try {
            final Map<Long, String> serviceTypeByConfigId = new HashMap<>();
            final List<ServiceConfigEntity> configs = serviceConfigRepository.findAll();
            for (final ServiceConfigEntity config : configs) {
                final String canonical = ServiceTypeConstants.ServiceType.normalizeValue(config.getServiceType());
                if (canonical != null) {
                    serviceTypeByConfigId.put(config.getId(), canonical);
                }
            }
            final List<ServiceInstanceEntity> instances = serviceInstanceRepository.findAll();
            for (final ServiceInstanceEntity instance : instances) {
                final String name = instance.getInstanceName();
                if (name == null || !wanted.contains(name)) {
                    continue;
                }
                final String canonical = serviceTypeByConfigId.get(instance.getServiceConfigId());
                if (canonical != null) {
                    result.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(canonical);
                }
            }
        } catch (final Exception e) {
            // 实例目录读取失败不应阻断密钥保存（保守放行：查不到就不判不匹配）
            log.warn("读取实例目录失败，跳过模型分类校验的实例部分: {}", e.getMessage());
        }

        // 2) 分组名 → 分类（生效池快照，与运行时 findPool 同源）
        try {
            for (final PoolDefinition pool : poolSelector.getAllPools()) {
                final String name = pool.getPoolName();
                if (name == null || !wanted.contains(name)) {
                    continue;
                }
                final String canonical = ServiceTypeConstants.ServiceType.normalizeValue(pool.getServiceType());
                if (canonical != null) {
                    result.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(canonical);
                }
            }
        } catch (final Exception e) {
            log.warn("读取资源池目录失败，跳过模型分类校验的分组部分: {}", e.getMessage());
        }

        return result;
    }
}
