package org.unreal.modelrouter.console;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.router.adapter.persistence.AdapterDefinitionPersistenceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器生命周期（上下线 / 灰度 / 版本）。
 *
 * <p>不改动 adapter 定义本身（那是 {@code adapter_definitions} 的职责），而是在独立
 * configKey {@code console.adapter-lifecycle} 里按 adapter 名维护一份运行状态：
 * {@code online}（是否上线）、{@code gray}（是否灰度）、{@code grayPercent}（灰度比例）、
 * {@code rolloutVersion}（灰度目标版本）。"上线"是逻辑开关，实际路由是否命中灰度版本
 * 由规则编排层消费这些标记；本控制器只负责生命周期元数据的登记与展示，不直接改写
 * 路由规则，避免与 H3 策略编排的职责重叠。</p>
 */
@RestController
@RequestMapping("/api/config/adapter/lifecycle")
@Tag(name = "适配器生命周期", description = "适配器上下线、灰度与版本状态")
public class AdapterLifecycleController {

    static final String KEY = "console.adapter-lifecycle";

    private final ConsoleExtensionStore store;
    private final AdapterDefinitionPersistenceService adapterDefinitions;

    public AdapterLifecycleController(final ConsoleExtensionStore store,
                                      final AdapterDefinitionPersistenceService adapterDefinitions) {
        this.store = store;
        this.adapterDefinitions = adapterDefinitions;
    }

    @GetMapping
    @Operation(summary = "列出所有适配器的生命周期状态（含未登记的，默认上线）")
    public ResponseEntity<RouterResponse<List<Map<String, Object>>>> list() {
        final Map<String, Object> configured = store.getMap(KEY);
        final Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : configured.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> raw) {
                byName.put(entry.getKey(), castMap(raw));
            }
        }
        final List<Map<String, Object>> result = new ArrayList<>();
        for (final String name : adapterNames()) {
            final Map<String, Object> state = byName.getOrDefault(name, defaultState(name));
            result.add(state);
        }
        // 已登记但定义已删除的残留也展示，便于清理
        for (final Map.Entry<String, Map<String, Object>> entry : byName.entrySet()) {
            if (!adapterNames().contains(entry.getKey())) result.add(entry.getValue());
        }
        return ResponseEntity.ok(RouterResponse.success(result));
    }

    @PostMapping("/{name}/state")
    @Operation(summary = "设置某适配器的生命周期状态")
    public ResponseEntity<RouterResponse<Map<String, Object>>> set(
            @PathVariable final String name, @RequestBody final Map<String, Object> body) {
        if (!adapterNames().contains(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "adapter not found: " + name);
        }
        final Map<String, Object> state = defaultState(name);
        if (body.get("online") instanceof Boolean b) state.put("online", b);
        if (body.get("gray") instanceof Boolean b) state.put("gray", b);
        if (body.get("grayPercent") instanceof Number n) {
            final int pct = Math.max(0, Math.min(100, n.intValue()));
            state.put("grayPercent", pct);
        }
        if (body.get("rolloutVersion") != null) state.put("rolloutVersion", String.valueOf(body.get("rolloutVersion")));
        state.put("updatedAt", java.time.Instant.now().toString());
        final Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(name, state);
        store.putMap(KEY, patch);
        return ResponseEntity.ok(RouterResponse.success(state));
    }

    private List<String> adapterNames() {
        final List<String> names = new ArrayList<>();
        try {
            for (final String key : adapterDefinitions.loadAllDefinitions().keySet()) names.add(key);
        } catch (final RuntimeException ignored) {
            // 定义存储尚未初始化时，仍返回已登记状态，不让页面整体 500
        }
        return names;
    }

    private static Map<String, Object> defaultState(final String name) {
        final Map<String, Object> state = new LinkedHashMap<>();
        state.put("adapterName", name);
        state.put("online", true);
        state.put("gray", false);
        state.put("grayPercent", 0);
        state.put("rolloutVersion", "");
        return state;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(final Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
