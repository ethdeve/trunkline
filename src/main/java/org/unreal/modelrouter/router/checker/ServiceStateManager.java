package org.unreal.modelrouter.router.checker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.monitor.controller.HealthStatusSseController;
import org.unreal.modelrouter.router.model.InstanceKeys;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务状态管理器
 * 管理服务和实例的健康状态，避免循环依赖
 *
 * v2.7.11 优化：使用 HealthStateBitSet 替代 Map<String, Boolean>
 * 性能提升：查询延迟 <0.01ms，内存占用降低 ~90%
 */
@Component
public class ServiceStateManager {

    private static final Logger log = LoggerFactory.getLogger(ServiceStateManager.class);

    // 注入SSE控制器
    @Lazy
    @Autowired(required = false)
    private HealthStatusSseController healthStatusSseController;

    // 存储每个服务类型的健康状态
    private final Map<String, Boolean> serviceHealthStatus = new ConcurrentHashMap<>();

    // v2.7.11: 使用 BitSet 存储实例健康状态，提升性能
    // R8（2026-10-28）：已删除与之并行的遗留 Map<String, Boolean> 双存储——
    // 此前写入侧要同时维护两份，读视图 getAllInstanceHealthStatus() 走 Map，
    // 判定走 BitSet，两者可能漂移。现在读视图由 BitSet 快照派生（snapshotKnownStates）。
    private final HealthStateBitSet instanceHealthBits = new HealthStateBitSet();

    /**
     * 获取特定服务类型的健康状态
     */
    public boolean isServiceHealthy(final String serviceType) {
        return serviceHealthStatus.getOrDefault(serviceType, true); // 默认认为是健康的
    }

    /**
     * 获取特定实例的健康状态
     *
     * @param serviceType 服务类型
     * @param instance    模型实例
     * @return 实例是否健康
     */
    public boolean isInstanceHealthy(final String serviceType, final ModelRouterProperties.ModelInstance instance) {
        // v2.7.11: 使用 BitSet 查询；#636: 键解析 instance-id → baseUrl → name，防 null 塌缩
        String instanceKey = InstanceKeys.healthKey(serviceType, instance);
        return instanceHealthBits.isHealthy(instanceKey, true); // 默认健康
    }

    /**
     * 获取特定实例的健康状态
     *
     * @param serviceType 服务类型
     * @param instance    模型实例
     * @return 实例是否健康
     */
    public boolean isInstanceHealthyByKey(final String instanceKey) {
        // v2.7.11: 使用 BitSet 查询
        // R7（2026-10-28）：默认值统一为 fail-open（未知=健康）。健康语义三处读路径
        // （isServiceHealthy / isInstanceHealthy / 本方法）必须同口径——未知实例先放行，
        // 由探活（ServerChecker 每 30s 预热）给出结论后再收紧；此前本方法默认 false，
        // 与另两处相悖，未探活过的实例会被误判为不健康。
        Boolean status = instanceHealthBits.getHealth(instanceKey);
        return status != null ? status : true;
    }

    /**
     * 获取特定实例的健康状态（三态返回）
     *
     * @param instanceKey 实例键值 (格式：serviceType:instanceId)
     * @return "HEALTHY" - 健康，"UNHEALTHY" - 不健康，"UNKNOWN" - 未知（未检查）
     */
    public String getInstanceHealthStatus(final String instanceKey) {
        // v2.7.11: 使用 BitSet 查询
        Boolean status = instanceHealthBits.getHealth(instanceKey);
        if (status == null) {
            return "UNKNOWN";
        }
        return status ? "HEALTHY" : "UNHEALTHY";
    }

    /**
     * 更新服务健康状态
     *
     * @param serviceType 服务类型
     * @param isHealthy   是否健康
     */
    public void updateServiceHealthStatus(final String serviceType, final boolean isHealthy) {
        serviceHealthStatus.put(serviceType, isHealthy);
    }

    /**
     * 失效指定服务类型的健康状态缓存，使其回落到默认值（健康）。
     *
     * <p><b>为什么需要它：</b>服务级健康状态是一个<b>粗粒度快照</b>——它只由
     * {@code ServerChecker.checkServiceInstances} 写入，而该方法<b>仅为「有实例」的分类
     * 建立检查任务</b>。因此当实例被新增/删除/改址后：</p>
     * <ul>
     *   <li>分类下实例被删光 → 该分类不再进入检查循环 → 缓存里的 {@code false} <b>永久残留</b>；</li>
     *   <li>分类下新增实例 → 缓存仍停留在上一轮探活结论 → 长达一个探活周期（30s）内
     *       该分类被判为不可用。</li>
     * </ul>
     * <p>配置变更是<b>管理员的显式意图</b>（换地址、上新实例），不应被上一轮的
     * 探活结论否决。调用方在实例增删改后调用本方法清掉陈旧快照，
     * 让下一次探活重新给出结论。</p>
     *
     * <p>注意：清掉服务级快照<b>不会</b>削弱可用性保护——真正的可用性判定在
     * <b>实例级</b>（{@code instanceHealthBits}）与路由层（{@code filterHealthyInstances}）：
     * 真正不健康的实例仍会被过滤掉，候选为空时路由层会抛出带逐成员明细的 503。</p>
     *
     * @param serviceType 服务类型（分类名）
     */
    public void resetServiceHealthStatus(final String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return;
        }
        if (serviceHealthStatus.remove(serviceType) != null) {
            log.info("已失效服务健康状态快照（配置变更后需重新探活）: {}", serviceType);
        }
    }

    /**
     * 更新实例健康状态
     *
     * @param serviceType 服务类型
     * @param instance    实例
     * @param isHealthy   是否健康
     */
    public void updateInstanceHealthStatus(
            final String serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final boolean isHealthy) {
        // #636: 键解析 instance-id → baseUrl → name，未配置 instance-id 的实例不再共享 ":null" 键
        String instanceKey = InstanceKeys.healthKey(serviceType, instance);
        Boolean previousStatus = instanceHealthBits.getHealth(instanceKey);

        // 只有状态发生变化时才更新
        if (previousStatus == null || previousStatus != isHealthy) {
            // R8: 只写 BitSet（遗留 Map 双存储已删除，读视图由快照派生）
            instanceHealthBits.setHealth(instanceKey, isHealthy);
            log.debug("实例健康状态更新: {} -> {}", instanceKey, isHealthy);
            
            // 通知SSE控制器推送更新
            if (healthStatusSseController != null) {
                try {
                    healthStatusSseController.notifyHealthStatusChange();
                    log.debug("已通知SSE控制器推送健康状态更新");
                } catch (Exception e) {
                    log.warn("通知SSE控制器推送健康状态更新时发生错误: {}", e.getMessage());
                }
            } else {
                log.debug("SSE控制器未注入，跳过推送更新");
            }
        } else {
            log.debug("实例健康状态未发生变化: {}", instanceKey);
        }
    }

    /**
     * 重置指定实例的健康判定，使其回落到「未检查」（默认健康）。
     *
     * <p><b>为什么需要它：</b>实例健康位（{@link HealthStateBitSet}）的键是
     * {@code serviceType:instanceId}——instanceId 是 UUID，<b>改上游地址不会改变键</b>。
     * 于是「把实例从故障地址改到可用地址」之后，旧地址探活得到的 {@code false} 会
     * 一直挂在该实例上，直到下一轮探活（最长 30s）才被覆盖。期间该实例被路由层
     * {@code filterHealthyInstances} 直接过滤掉，表现为「改完地址还是 503」。</p>
     *
     * <p>调用方在实例地址变更后调用本方法，让旧地址的探活结论立即失效、
     * 下一轮探活重新给出结论。</p>
     *
     * @param serviceType 服务类型（分类名）
     * @param instance    实例
     */
    public void resetInstanceHealthStatus(final String serviceType,
                                          final ModelRouterProperties.ModelInstance instance) {
        if (serviceType == null || serviceType.isBlank() || instance == null) {
            return;
        }
        // 键口径必须与探活写入侧一致（InstanceKeys.healthKey），
        // 否则重置的是另一个键，旧结论清不掉。
        clearHealthByKey(InstanceKeys.healthKey(serviceType, instance));
    }

    /**
     * 按「分类 + 实例ID」重置实例的健康判定，使其回落到「未检查」（默认健康）。
     *
     * <p><b>与 {@link #resetInstanceHealthStatus(String, ModelRouterProperties.ModelInstance)} 的区别：</b>
     * 本方法直接用 {@code serviceType + ":" + instanceId} 构造键，<b>不依赖运行时实例对象</b>。
     * 配置层（{@code ServiceInstanceManager}）手里只有 JPA 实体，没有运行时
     * {@code ModelInstance}，因此需要这条按 ID 的入口。</p>
     *
     * <p><b>键口径说明：</b>健康位的键是 {@code serviceType:instanceId}，
     * 其中 {@code serviceType} 取<b>分类枚举名</b>（{@code chat / imgGen / imgEdit} …）。
     * {@code service_config.service_type} 落库值与该枚举名一致，因此直接传入即可；
     * 若后续出现别名形态，需先归一化再调用，否则清的是另一个键。</p>
     *
     * @param serviceType 服务类型（分类枚举名）
     * @param instanceId  实例唯一 ID（UUID）
     */
    public void resetInstanceHealthStatusById(final String serviceType, final String instanceId) {
        if (serviceType == null || serviceType.isBlank() || instanceId == null || instanceId.isBlank()) {
            return;
        }
        clearHealthByKey(serviceType + ":" + instanceId);
    }

    /**
     * 按健康键清理「健康 / 已知」标志位（保留位索引，避免位槽泄漏）。
     */
    private void clearHealthByKey(final String instanceKey) {
        instanceHealthBits.clearHealth(instanceKey);
        log.info("已重置实例健康状态（配置变更后需重新探活）: {}", instanceKey);
    }

    /**
     * 清理指定实例在指定服务类型下的健康状态记录。
     * <p>实例跨服务类型迁移时调用：健康位图的键是 {@code serviceType + ":" + instanceId}，
     * 迁移后旧前缀下的记录是孤儿（永远不会再被读到，且会持续占用 BitSet 位），
     * 必须显式清除，避免内存泄漏与「同一实例两个健康态」的歧义。</p>
     *
     * @param serviceType  原服务类型
     * @param instanceId   实例唯一 ID
     */
    public void removeInstanceHealthStatus(final String serviceType, final String instanceId) {
        if (serviceType == null || instanceId == null || instanceId.isBlank()) {
            return;
        }
        final String instanceKey = serviceType + ":" + instanceId;
        // R8: 位图里该键位被清除后，快照视图（snapshotKnownStates）不再包含它，
        // 与原「Map 移除键」语义一致
        instanceHealthBits.removeInstance(instanceKey);
        log.info("已清理实例健康状态记录: {}", instanceKey);
    }

    /**
     * 获取所有服务的健康状态
     */
    public Map<String, Boolean> getAllServiceHealthStatus() {
        return new ConcurrentHashMap<>(serviceHealthStatus);
    }

    /**
     * 获取所有实例的健康状态（R8：由 BitSet 快照派生，不再维护独立 Map）
     */
    public Map<String, Boolean> getAllInstanceHealthStatus() {
        return new ConcurrentHashMap<>(instanceHealthBits.snapshotKnownStates());
    }

    /**
     * 清理过期的实例健康状态
     */
    public void clearExpiredInstanceHealthStatus() {
        HealthStateBitSet.Stats stats = instanceHealthBits.getStats();
        log.info("清理过期的实例健康状态，清理前已知实例数: {}", stats.getKnownCount());
        // R8: 遗留 Map 已删除，只需清位图（连带索引表，全部归零）
        instanceHealthBits.clear();
        log.info("实例健康状态缓存清理完成");
    }

    /**
     * v2.7.11: 获取 BitSet 统计信息
     */
    public HealthStateBitSet.Stats getBitSetStats() {
        return instanceHealthBits.getStats();
    }
}