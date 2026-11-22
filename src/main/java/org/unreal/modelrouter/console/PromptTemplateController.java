package org.unreal.modelrouter.console;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提示词模板管理（列表 / 版本 / 简单编排）。
 *
 * <p>数据存放于 {@code config_data} 的 {@code console.prompt-templates} configKey，
 * 不建新表、不改 V1 基线（遵守零存量单一基线护栏）。每次保存同一 templateId 时，
 * 旧内容自动进入 versions 数组形成轻量版本历史。"编排"为有序的模板片段序列，
 * 以 {@code steps} 顺序拼接，不涉及运行时执行。</p>
 */
@RestController
@RequestMapping("/api/config/prompt-templates")
@Tag(name = "提示词模板管理", description = "提示词模板的列表、版本与简单编排")
public class PromptTemplateController {

    static final String KEY = "console.prompt-templates";

    private final ConsoleExtensionStore store;

    public PromptTemplateController(final ConsoleExtensionStore store) {
        this.store = store;
    }

    @GetMapping
    @Operation(summary = "列出全部提示词模板")
    public ResponseEntity<RouterResponse<List<Map<String, Object>>>> list() {
        return ResponseEntity.ok(RouterResponse.success(store.list(KEY)));
    }

    @PostMapping
    @Operation(summary = "新增或更新模板（同 id 覆盖并保留版本历史）")
    public ResponseEntity<RouterResponse<Map<String, Object>>> save(
            @RequestBody final Map<String, Object> body) {
        final String name = str(body.get("name"));
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        // 版本历史：同 id 覆盖前，把旧内容快照进 versions
        final String id = str(body.get("templateId"));
        final String effectiveId = id.isBlank() ? UUID.randomUUID().toString() : id;
        final List<Map<String, Object>> existing = store.list(KEY);
        final Map<String, Object> record = new LinkedHashMap<>(body);
        record.put("templateId", effectiveId);
        record.put("name", name);
        record.put("updatedAt", java.time.Instant.now().toString());
        if (!id.isBlank()) {
            for (final Map<String, Object> old : existing) {
                if (effectiveId.equals(str(old.get("templateId")))) {
                    final List<Object> versions = new ArrayList<>(asList(old.get("versions")));
                    final Map<String, Object> snapshot = new LinkedHashMap<>(old);
                    snapshot.remove("versions");
                    versions.add(0, snapshot);
                    record.put("versions", versions);
                    break;
                }
            }
        }
        record.putIfAbsent("versions", new ArrayList<>());
        store.upsert(KEY, "templateId", record);
        return ResponseEntity.ok(RouterResponse.success(store.plain(record)));
    }

    @GetMapping("/{templateId}/versions")
    @Operation(summary = "查看模板版本历史")
    public ResponseEntity<RouterResponse<List<Object>>> versions(@PathVariable final String templateId) {
        for (final Map<String, Object> t : store.list(KEY)) {
            if (templateId.equals(str(t.get("templateId")))) {
                return ResponseEntity.ok(RouterResponse.success(asList(t.get("versions"))));
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "template not found");
    }

    @PostMapping("/compose")
    @Operation(summary = "按模板 id 顺序编排（返回拼接后的文本，不落库）")
    public ResponseEntity<RouterResponse<Map<String, Object>>> compose(
            @RequestBody final Map<String, Object> body) {
        final List<?> ids = asList(body.get("templateIds"));
        final List<Map<String, Object>> all = store.list(KEY);
        final StringBuilder sb = new StringBuilder();
        int index = 0;
        for (final Object rawId : ids) {
            final String id = str(rawId);
            for (final Map<String, Object> t : all) {
                if (id.equals(str(t.get("templateId")))) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(str(t.get("content")));
                    index++;
                    break;
                }
            }
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", sb.toString());
        result.put("steps", index);
        return ResponseEntity.ok(RouterResponse.success(result));
    }

    @DeleteMapping("/{templateId}")
    @Operation(summary = "删除模板")
    public ResponseEntity<RouterResponse<Void>> delete(@PathVariable final String templateId) {
        store.delete(KEY, "templateId", templateId);
        return ResponseEntity.ok(RouterResponse.success(null));
    }

    private static String str(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(final Object value) {
        return value instanceof List<?> raw ? new ArrayList<>((List<Object>) raw) : new ArrayList<>();
    }
}
