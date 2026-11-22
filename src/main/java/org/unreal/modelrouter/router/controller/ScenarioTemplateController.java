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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.router.scenario.ScenarioApplyResult;
import org.unreal.modelrouter.router.scenario.ScenarioTemplate;
import org.unreal.modelrouter.router.scenario.ScenarioTemplateService;

import java.util.List;
import java.util.Map;

/**
 * 一键虚拟模型场景模板端点（v0.2.0）。
 *
 * <p>沿用同步 {@code ResponseEntity<RouterResponse<...>>} 风格，未加方法级
 * {@code @PreAuthorize}，授权交给 URL 固定角色矩阵（OPS）——与画像 Controller 同构。</p>
 *
 * @author Trunkline Team
 */
@RestController
@RequestMapping("/api/config/scenarios")
@CrossOrigin(origins = "*")
@Tag(name = "一键虚拟模型", description = "按用途选模并一键建池 + 路由规则（v0.2.0）")
public class ScenarioTemplateController {

    private final ScenarioTemplateService service;

    public ScenarioTemplateController(final ScenarioTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "场景模板列表", description = "返回内置用途模板及其选模/建池/规则策略")
    public ResponseEntity<RouterResponse<List<ScenarioTemplate>>> list() {
        return ResponseEntity.ok(RouterResponse.success(service.list()));
    }

    @PostMapping("/preview")
    @Operation(summary = "预览场景", description = "只生成将创建的池与规则草稿，不落库（body: {scenarioId}）")
    public ResponseEntity<RouterResponse<ScenarioApplyResult>> preview(
            @RequestBody(required = false) final Map<String, Object> body) {
        final Object id = body == null ? null : body.get("scenarioId");
        return ResponseEntity.ok(RouterResponse.success(service.preview(id == null ? null : id.toString())));
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "一键应用场景", description = "按画像选模，创建池与规则并热生效，返回已创建项摘要")
    public ResponseEntity<RouterResponse<ScenarioApplyResult>> apply(@PathVariable("id") final String id) {
        return ResponseEntity.ok(RouterResponse.success(service.apply(id)));
    }
}
