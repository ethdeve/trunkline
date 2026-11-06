package org.unreal.modelrouter.router.model;

import org.springframework.stereotype.Service;
import org.unreal.modelrouter.router.adapter.AdapterRegistry;
import org.unreal.modelrouter.router.adapter.ServiceCapability;
import org.unreal.modelrouter.router.pool.PoolSelector;
import org.unreal.modelrouter.router.pool.model.PoolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型目录服务（v3.1）.
 *
 * <p>汇总所有服务类型的可用模型，供两个入口面使用：
 * <ul>
 *   <li>控制台面 {@code GET /api/models}：返回 {@code RouterResponse} 包裹体，
 *       内容为<b>全量清单</b>（实例 + 分组），供管理端查看接入情况；</li>
 *   <li>OpenAI 原生面 {@code GET /v1/models}：返回原生 {@code {object:"list", data:[...]}}，
 *       内容为<b>可调用清单</b>（只有模型分组），与 v3.5 单线治理口径一致。</li>
 * </ul>
 *
 * <p>两个入口面的清单口径按 v3.5 治理收口做了区分：实例名已不可对外调用
 * （路由层关闭实例直调、白名单层只认分组），因此对外的模型目录不应再列实例名，
 * 否则 OpenAI 兼容客户端按目录发起调用必然 403/404。</p>
 *
 * <p>抽取自原 {@code ModelInfoController} 的内联逻辑，字段集合保持完全一致，避免两处漂移。</p>
 *
 * @author JAiRouter Team
 * @since v3.1
 * @modified Trunkline Team
 */
@Service
public class ModelCatalogService {

    private final ModelServiceRegistry registry;
    private final AdapterRegistry adapterRegistry;
    private final PoolSelector poolSelector;

    /**
     * 构造函数.
     *
     * @param registry        模型服务注册表（提供各服务类型的可用模型）
     * @param adapterRegistry 适配器注册表（提供各服务类型当前适配器名）
     * @param poolSelector    资源池选择器（提供模型分组/虚拟模型名）
     */
    public ModelCatalogService(final ModelServiceRegistry registry,
                               final AdapterRegistry adapterRegistry,
                               final PoolSelector poolSelector) {
        this.registry = registry;
        this.adapterRegistry = adapterRegistry;
        this.poolSelector = poolSelector;
    }

    /**
     * 列出全部可用模型.
     *
     * @return 模型信息列表（id / object / created / owned_by / service_type / adapter）
     */
    public List<Map<String, Object>> listAllModels() {
        final List<Map<String, Object>> allModels = new ArrayList<>();

        for (ModelServiceRegistry.ServiceType serviceType : ModelServiceRegistry.ServiceType.values()) {
            final Set<String> availableModels = registry.getAvailableModels(serviceType);
            if (availableModels == null || availableModels.isEmpty()) {
                continue;
            }
            for (String modelName : availableModels) {
                final Map<String, Object> modelInfo = new LinkedHashMap<>();
                modelInfo.put("id", modelName);
                modelInfo.put("object", "model");
                modelInfo.put("created", System.currentTimeMillis() / 1000);
                modelInfo.put("owned_by", "model-router");
                modelInfo.put("service_type", serviceType.name());
                modelInfo.put("adapter", resolveAdapterName(serviceType));
                allModels.add(modelInfo);
            }
        }

        // 模型分组(虚拟模型)与实例名同为对外可调用的模型名，必须出现在目录中：
        // OpenAI 兼容客户端常按"配置的模型必须在 /v1/models 列表中"校验连通性，
        // 缺少分组名会导致自定义 provider 接入(如 model1)测试失败。
        for (PoolDefinition pool : poolSelector.getAllPools()) {
            if (!pool.isEnabled() || pool.getPoolName() == null || pool.getPoolName().isBlank()) {
                continue;
            }
            final Map<String, Object> poolInfo = new LinkedHashMap<>();
            poolInfo.put("id", pool.getPoolName());
            poolInfo.put("object", "model");
            poolInfo.put("created", System.currentTimeMillis() / 1000);
            poolInfo.put("owned_by", "model-router");
            poolInfo.put("service_type", pool.getServiceType());
            poolInfo.put("adapter", resolveAdapterNameByString(pool.getServiceType()));
            allModels.add(poolInfo);
        }

        return allModels;
    }

    /**
     * 列出全部可调用模型（v3.5 单线治理：<b>只有模型分组（虚拟模型名）可被调用</b>）。
     *
     * <p>实例名在 v3.5 之后<b>不再是对外可调用的模型名</b>——路由层已关闭实例直调
     * （未命中任何分组即 404），密钥白名单也只配分组（直调实例会被白名单层 403）。
     * 因此 OpenAI 兼容的模型目录（{@code GET /v1/models}）只列分组，
     * 否则客户端按目录里的实例名发起调用必然失败，属于对外契约与治理口径不一致。</p>
     *
     * <p>包含：</p>
     * <ul>
     *   <li>已启用的模型分组（{@code poolName}）——必须<b>显式创建</b>，
     *       没建分组就没有可调用的虚拟模型。</li>
     * </ul>
     *
     * <p><b>v3.6 治理收口：</b>不再输出 {@code auto-model} 这类隐式系统分组。</p>
     *
     * @return 模型信息列表（id / object / created / owned_by / service_type / adapter）
     */
    public List<Map<String, Object>> listCallableModels() {
        final List<Map<String, Object>> models = new ArrayList<>();
        final Set<String> listed = new LinkedHashSet<>();

        for (final PoolDefinition pool : poolSelector.getAllPools()) {
            if (!pool.isEnabled() || pool.getPoolName() == null || pool.getPoolName().isBlank()) {
                continue;
            }
            listed.add(pool.getPoolName());
            models.add(buildModelEntry(pool.getPoolName(), pool.getServiceType()));
        }

        return models;
    }

    /**
     * 以 OpenAI 列表结构返回全部<b>可调用</b>模型（模型分组）。
     *
     * @return {@code {object:"list", data:[{...}]}}，可直接作为 {@code GET /v1/models} 响应体
     */
    public Map<String, Object> listCallableModelsAsOpenAiList() {
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", listCallableModels());
        return response;
    }

    /** 构造一条 OpenAI 风格的模型条目 */
    private Map<String, Object> buildModelEntry(final String modelName, final String serviceType) {
        final Map<String, Object> modelInfo = new LinkedHashMap<>();
        modelInfo.put("id", modelName);
        modelInfo.put("object", "model");
        modelInfo.put("created", System.currentTimeMillis() / 1000);
        modelInfo.put("owned_by", "model-router");
        modelInfo.put("service_type", serviceType);
        modelInfo.put("adapter", resolveAdapterNameByString(serviceType));
        return modelInfo;
    }

    /**
     * 以 OpenAI 列表结构返回全部可用模型.
     *
     * @return {@code {object:"list", data:[{...}]}}，可直接作为 {@code GET /v1/models} 响应体
     */
    public Map<String, Object> listAllModelsAsOpenAiList() {
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", listAllModels());
        return response;
    }

    /**
     * 按服务类型字符串解析适配器名（分组配置里 serviceType 为字符串，需容错转换）.
     */
    private String resolveAdapterNameByString(final String serviceType) {
        try {
            return resolveAdapterName(ModelServiceRegistry.ServiceType.valueOf(serviceType));
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 解析服务类型当前适配器的简单类名.
     *
     * @param serviceType 服务类型
     * @return 适配器简单类名；不可用时返回 {@code unknown}
     */
    private String resolveAdapterName(final ModelServiceRegistry.ServiceType serviceType) {
        try {
            final ServiceCapability adapter = adapterRegistry.getAdapter(serviceType);
            return adapter != null ? adapter.getClass().getSimpleName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
