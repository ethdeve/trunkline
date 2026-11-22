/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.router.capability.ModelCapabilityProfile;
import org.unreal.modelrouter.router.capability.ModelCapabilityProfileService;

import java.util.List;
import java.util.Map;

/**
 * 模型能力档案查询控制器（v0.2.0）。
 *
 * <p>只读端点，授权交给 URL 固定角色矩阵（FixedRoleAccessPolicy 登记为 OPS）。
 * 沿用同步 {@code ResponseEntity<RouterResponse<...>>} 风格且<strong>不加方法级
 * @PreAuthorize</strong>——避免上一轮 4 个同步 Controller 在 WebFlux 下的 500 问题。</p>
 *
 * @author Trunkline Team
 */
@RestController
@RequestMapping("/api/config/model-capabilities")
@CrossOrigin(origins = "*")
@Tag(name = "模型能力档案", description = "模型擅长什么的静态画像查询（v0.2.0）")
public class ModelCapabilityController {

    private final ModelCapabilityProfileService service;

    public ModelCapabilityController(final ModelCapabilityProfileService service) {
        this.service = service;
    }

    @GetMapping("/list")
    @Operation(summary = "画像列表/筛选", description = "按用途、服务类型、厂商、能力门槛筛选模型画像")
    public ResponseEntity<RouterResponse<List<ModelCapabilityProfile>>> list(
            @RequestParam(required = false) final String useCase,
            @RequestParam(required = false) final String serviceType,
            @RequestParam(required = false) final String vendor,
            @RequestParam(required = false) final String preferStrength,
            @RequestParam(required = false) final String minLatency) {
        return ResponseEntity.ok(RouterResponse.success(
                service.filter(useCase, serviceType, vendor, preferStrength, minLatency)));
    }

    @GetMapping("/facets")
    @Operation(summary = "筛选项", description = "返回可选厂商与适用场景标签")
    public ResponseEntity<RouterResponse<Map<String, Object>>> facets() {
        return ResponseEntity.ok(RouterResponse.success(Map.of(
                "vendors", service.vendors(),
                "useCases", service.useCases(),
                "provider", service.providerName())));
    }

    @GetMapping("/{modelId}")
    @Operation(summary = "单模型画像", description = "按 modelId / 别名 / canonicalId 查询画像")
    public ResponseEntity<RouterResponse<ModelCapabilityProfile>> get(@PathVariable final String modelId) {
        return service.findByModelName(modelId)
                .map(p -> ResponseEntity.ok(RouterResponse.success(p)))
                .orElseGet(() -> ResponseEntity.ok(RouterResponse.success(null, "未找到该模型的画像")));
    }
}
