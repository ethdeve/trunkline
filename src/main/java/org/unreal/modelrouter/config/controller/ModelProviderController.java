package org.unreal.modelrouter.config.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.dto.ModelProviderDTO;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.config.dto.CreateModelProviderRequest;
import org.unreal.modelrouter.config.service.ModelProviderService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 模型供应商控制器（v2.11.0）
 *
 * <p>供应商层 REST 面：CRUD、启停、换 Key 级联、发现模型、批量添加模型。
 * 权限与实例配置同码（{@code /api/config/**} 段规则，见 PermissionRuleRegistry）。
 */
@Slf4j
@RestController
@RequestMapping("/api/config/providers")
@RequiredArgsConstructor
public class ModelProviderController {

    private final ModelProviderService providerService;

    /**
     * 供应商列表（卡片；apiKey 不回传，仅 hasApiKey）
     */
    @GetMapping
    public ResponseEntity<RouterResponse<List<ModelProviderDTO>>> list() {
        return ResponseEntity.ok(RouterResponse.success(providerService.listAll()));
    }

    /**
     * 供应商详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<RouterResponse<ModelProviderDTO>> get(@PathVariable final Long id) {
        return ResponseEntity.ok(RouterResponse.success(providerService.get(id)));
    }

    /**
     * 创建供应商（预设目录一键启用 / 自定义添加都走这里）
     */
    @PostMapping
    public ResponseEntity<RouterResponse<ModelProviderDTO>> create(
            @RequestBody final CreateModelProviderRequest request) {
        final ModelProviderDTO created = providerService.create(request);
        return ResponseEntity.ok(RouterResponse.success(created, "供应商已添加"));
    }

    /**
     * 更新供应商（apiKey 留空=保持原值；换 Key 用专用接口）
     */
    @PutMapping("/{id}")
    public ResponseEntity<RouterResponse<ModelProviderDTO>> update(
            @PathVariable final Long id,
            @RequestBody final CreateModelProviderRequest request) {
        return ResponseEntity.ok(RouterResponse.success(providerService.update(id, request), "供应商已更新"));
    }

    /**
     * 删除供应商（解绑其下实例，实例保留）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<RouterResponse<Void>> delete(@PathVariable final Long id) {
        providerService.delete(id);
        return ResponseEntity.ok(RouterResponse.success(null, "供应商已删除（其下模型已保留并解除关联）"));
    }

    /**
     * 更换上游 API Key（级联刷新该供应商下所有实例的 Authorization 头）
     */
    @PutMapping("/{id}/api-key")
    public ResponseEntity<RouterResponse<ModelProviderDTO>> rotateKey(
            @PathVariable final Long id,
            @RequestBody final Map<String, String> body) {
        final String apiKey = body.get("apiKey");
        return ResponseEntity.ok(RouterResponse.success(providerService.rotateKey(id, apiKey), "Key 已更换并同步到所有模型"));
    }

    /**
     * 启用 / 禁用供应商
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<RouterResponse<ModelProviderDTO>> updateStatus(
            @PathVariable final Long id,
            @RequestParam final String status) {
        return ResponseEntity.ok(RouterResponse.success(providerService.updateStatus(id, status)));
    }

    /**
     * 发现模型：代理上游拉取模型 ID 列表（OpenAI 兼容 /models）。
     * 响应式返回，避免在 parallel 线程上 block。
     */
    @PostMapping("/{id}/discover-models")
    public Mono<RouterResponse<List<String>>> discoverModels(@PathVariable final Long id) {
        return providerService.discoverModels(id)
                .map(RouterResponse::success);
    }

    /**
     * 供应商下已接入的模型实例列表（管理模型抽屉「已接入」区）
     */
    @GetMapping("/{id}/models")
    public ResponseEntity<RouterResponse<List<ServiceInstanceDTO>>> listModels(@PathVariable final Long id) {
        return ResponseEntity.ok(RouterResponse.success(providerService.listModels(id)));
    }

    /**
     * 批量添加模型：按勾选清单创建实例（继承供应商地址与 Key）
     */
    @PostMapping("/{id}/models")
    public ResponseEntity<RouterResponse<Map<String, Object>>> batchAddModels(
            @PathVariable final Long id,
            @RequestBody final CreateModelProviderRequest.BatchAddModelsRequest request) {
        final int created = providerService.batchAddModels(id, request);
        return ResponseEntity.ok(RouterResponse.success(
                Map.of("created", created),
                created > 0 ? "已添加 " + created + " 个模型" : "没有新模型需要添加（可能已存在）"));
    }
}
