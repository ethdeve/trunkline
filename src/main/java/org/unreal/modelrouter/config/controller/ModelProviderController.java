/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

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
import org.unreal.modelrouter.common.dto.ProviderCatalogDTO;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.config.dto.BatchAddModelsResult;
import org.unreal.modelrouter.config.dto.CreateModelProviderRequest;
import org.unreal.modelrouter.config.service.ModelProviderService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 模型供应商控制器（v2.11.0）
 *
 * <p>供应商层 REST 面：CRUD、启停、换 Key 级联、发现模型、批量添加模型。
 * 权限与实例配置同级（{@code /api/config/**} 段规则，见固定角色矩阵 FixedRoleAccessPolicy）。
 * @author Trunkline Team
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
     * 供应商目录：供应商 + 其下已接入模型（合并视图，供「接入厂商」表格直接渲染）。
     *
     * <p>与 {@link #list()} 的区别：一次返回模型名列表，前端无需逐个供应商再查模型。</p>
     */
    @GetMapping("/catalog")
    public ResponseEntity<RouterResponse<List<ProviderCatalogDTO>>> catalog() {
        return ResponseEntity.ok(RouterResponse.success(providerService.listCatalog()));
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
     * 删除供应商（其下仍有已接入模型时返回 409，须先删除这些模型）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<RouterResponse<Void>> delete(@PathVariable final Long id) {
        providerService.delete(id);
        return ResponseEntity.ok(RouterResponse.success(null, "供应商已删除"));
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
     * 批量添加模型：按勾选清单创建实例（继承供应商地址与 Key）。
     * 响应携带 skipped 跳过明细（同名实例已存在，含归属供应商），前端据此逐条提示。
     */
    @PostMapping("/{id}/models")
    public ResponseEntity<RouterResponse<Map<String, Object>>> batchAddModels(
            @PathVariable final Long id,
            @RequestBody final CreateModelProviderRequest.BatchAddModelsRequest request) {
        final BatchAddModelsResult result = providerService.batchAddModels(id, request);
        final Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("created", result.getCreated());
        data.put("skipped", result.getSkipped());
        final String message;
        if (result.getCreated() > 0 && result.getSkipped().isEmpty()) {
            message = "已添加 " + result.getCreated() + " 个模型";
        } else if (result.getCreated() > 0) {
            message = "已添加 " + result.getCreated() + " 个模型，跳过 " + result.getSkipped().size() + " 个（已存在）";
        } else {
            message = "没有新模型需要添加（可能已存在）";
        }
        return ResponseEntity.ok(RouterResponse.success(data, message));
    }
}
