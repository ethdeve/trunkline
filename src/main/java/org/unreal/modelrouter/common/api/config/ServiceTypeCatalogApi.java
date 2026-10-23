package org.unreal.modelrouter.common.api.config;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 模型分类目录接口 —— 查询「模型名 / 分组名」归属于哪些模型分类。
 *
 * <p><b>为什么需要它：</b>密钥的模型白名单（allowedModels）与分组白名单（allowedGroups）
 * 存的是<b>名字</b>，而密钥权限（permissions）存的是<b>模型分类</b>。要在配置期判断
 * 「白名单里的名字是否落在该密钥被授权的分类内」，必须先把名字解析成分类。
 * 而名字的权威归属分散在两处：
 * <ul>
 *   <li>实例名 → {@code service_instance.service_config_id} → {@code service_config.service_type}</li>
 *   <li>分组名 → 资源池定义（YAML + 持久化）里的 {@code serviceType}</li>
 * </ul>
 * 这两处的数据都归 config / router 模块所有，而 {@code auth} 模块不能反向依赖它们
 * （它们已经依赖 auth，双向会形成包级环）。故此处只声明接口，实现放在 config 侧，
 * auth 侧仅依赖本接口。</p>
 *
 * @author JAiRouter Team
 * @since 3.3.2
 */
public interface ServiceTypeCatalogApi {

    /**
     * 解析给定名字可能所属的模型分类集合。
     *
     * <p><b>语义与运行时对齐：</b>运行时 {@code ServiceRequestHandler#hasModelPermission}
     * 判定的是 {@code allowedModels.contains(modelName) || allowedGroups.contains(modelName)}
     * —— 两个白名单是<b>并集</b>关系，且池路由优先于实例过滤。因此同一个名字既可能是
     * 实例名也可能是分组名，本方法返回两者的<b>分类并集</b>。</p>
     *
     * <p>同名实例在库内<b>不唯一</b>（可分属不同 service_config），故返回集合而非单值。</p>
     *
     * @param names 待解析的名字集合（可为 null / 空）
     * @return 名字 → 模型分类集合的映射；未登记的名字<b>不出现在</b>结果中
     *         （调用方据此区分「查不到」与「分类不匹配」）
     */
    Map<String, Set<String>> resolveServiceTypes(Collection<String> names);
}
