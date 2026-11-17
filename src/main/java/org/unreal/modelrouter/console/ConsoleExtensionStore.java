package org.unreal.modelrouter.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台扩展功能的通用 JSON 存储底座。
 *
 * <p>背景：extract-fe-roadmap 中的净新增管理功能（提示词模板 / 适配器生命周期 /
 * API Key 审批单 / 组-角色映射）都是轻量配置数据。为了遵守「零存量：只有 V1 单一
 * 基线 + SHA256 护栏、不写升级 SQL」的红线，这些功能一律不建新表、不改 V1，
 * 统一复用 OSS 已有的通用 KV 表 {@code config_data}（{@link StoreManager}），
 * 以独立 configKey 隔离，互不影响。</p>
 *
 * <p>并发：所有读-改-写都走 {@link StoreManager#mutateConfig}，JPA 实现是事务 +
 * 悲观行锁，覆盖多副本共享同一 PG 的场景，避免整表覆盖吞掉并发写。</p>
 *
 * <p>数据形态：每个 configKey 下固定一个 {@code Map}，列表型数据放在
 * {@code items} 键下（值为 {@code List<Map<String,Object>>}）；键值型数据直接
 * 用顶层键。本类只负责存取与结构规整，不承载业务语义。</p>
 */
@Service
public class ConsoleExtensionStore {

    /** 列表型配置中承载列表的键。 */
    public static final String ITEMS = "items";

    private final StoreManager storeManager;
    private final ObjectMapper objectMapper;

    public ConsoleExtensionStore(final StoreManager storeManager, final ObjectMapper objectMapper) {
        this.storeManager = storeManager;
        this.objectMapper = objectMapper;
    }

    /** 读取列表；配置不存在或结构异常时返回空列表（不返回 null）。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(final String configKey) {
        final Map<String, Object> config = storeManager.getConfig(configKey);
        if (config == null) return new ArrayList<>();
        final Object items = config.get(ITEMS);
        if (items instanceof List<?> raw) {
            final List<Map<String, Object>> result = new ArrayList<>();
            for (final Object element : raw) {
                if (element instanceof Map<?, ?> map) {
                    result.add(castMap(map));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    /**
     * 按 idField 幂等新增/覆盖一条记录（不存在则追加，存在则整条替换）。
     *
     * @return true 表示新增，false 表示覆盖
     */
    public boolean upsert(final String configKey, final String idField, final Map<String, Object> item) {
        final Object[] added = {false};
        storeManager.mutateConfig(configKey, current -> {
            final Map<String, Object> config = current == null ? new LinkedHashMap<>() : current;
            final List<Map<String, Object>> items = new ArrayList<>(listFrom(config));
            final Object id = item.get(idField);
            int found = -1;
            if (id != null) {
                for (int i = 0; i < items.size(); i++) {
                    if (id.equals(items.get(i).get(idField))) { found = i; break; }
                }
            }
            if (found >= 0) items.set(found, new LinkedHashMap<>(item));
            else { items.add(new LinkedHashMap<>(item)); added[0] = true; }
            config.put(ITEMS, items);
            return config;
        });
        return added[0];
    }

    /** 按 idField 删除一条记录；不存在也幂等成功。 */
    public void delete(final String configKey, final String idField, final Object id) {
        storeManager.mutateConfig(configKey, current -> {
            if (current == null) return null;
            final List<Map<String, Object>> items = new ArrayList<>(listFrom(current));
            items.removeIf(entry -> id != null && id.equals(entry.get(idField)));
            current.put(ITEMS, items);
            return current;
        });
    }

    /** 读取一个键值型配置（顶层键）。 */
    public Map<String, Object> getMap(final String configKey) {
        final Map<String, Object> config = storeManager.getConfig(configKey);
        return config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
    }

    /** 原子地把一组键写入键值型配置。 */
    public void putMap(final String configKey, final Map<String, Object> values) {
        storeManager.mutateConfig(configKey, current -> {
            final Map<String, Object> config = current == null ? new LinkedHashMap<>() : current;
            config.putAll(values);
            return config;
        });
    }

    /** 原子删除一个顶层键。 */
    public void removeKey(final String configKey, final String key) {
        storeManager.mutateConfig(configKey, current -> {
            if (current == null) return null;
            current.remove(key);
            return current;
        });
    }

    /** 深拷贝一份为可直接序列化的普通 Map（防止 Jackson 序列化 JPA 代理）。 */
    public Map<String, Object> plain(final Map<String, Object> source) {
        return objectMapper.convertValue(source, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listFrom(final Map<String, Object> config) {
        final Object items = config.get(ITEMS);
        if (!(items instanceof List<?> raw)) return new ArrayList<>();
        final List<Map<String, Object>> result = new ArrayList<>();
        for (final Object element : raw) {
            if (element instanceof Map<?, ?> map) result.add(castMap(map));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
