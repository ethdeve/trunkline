/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.config.dependency.DependencyCheckService;
import org.unreal.modelrouter.config.dependency.DependencyReport;

/**
 * 删除前的依赖预检端点。
 *
 * <p>让控制台在弹出「确认删除」之前就能拿到「谁还在用我」的清单，
 * 避免用户点完删除才收到一句笼统的失败提示。</p>
 *
 * <p>路径：{@code GET /api/config/dependencies/{targetType}/{target}}，
 * 其中 {@code targetType} 取值 provider / instance / pool / template / apiKey。</p>
 *
 * @author Trunkline Team
 * @modified Trunkline Team
 */
@RestController
@RequestMapping("/api/config/dependencies")
@CrossOrigin(origins = "*")
@Tag(name = "依赖检测", description = "删除前的引用关系预检（模板/供应商/已接入模型/模型分组/密钥）")
public class DependencyController {

    private final DependencyCheckService dependencyCheckService;

    public DependencyController(final DependencyCheckService dependencyCheckService) {
        this.dependencyCheckService = dependencyCheckService;
    }

    /**
     * 查询目标对象的引用关系。
     *
     * @param targetType provider / instance / pool / template / apiKey
     * @param target    目标标识（供应商与模型为数字主键；分组为池名；模板为模板 id；密钥为 keyId）
     * @return 依赖报告（含阻断项与提示项）
     */
    @GetMapping("/{targetType}/{target}")
    @Operation(summary = "查询删除依赖", description = "查询目标对象当前被哪些对象引用")
    public ResponseEntity<RouterResponse<DependencyReport>> check(
            @PathVariable final String targetType,
            @PathVariable final String target) {
        return ResponseEntity.ok(RouterResponse.success(resolve(targetType, target)));
    }

    private DependencyReport resolve(final String targetType, final String target) {
        final String type = targetType == null ? "" : targetType.trim().toLowerCase();
        switch (type) {
            case "provider":
                return dependencyCheckService.checkProvider(parseLong(target));
            case "instance":
                return dependencyCheckService.checkInstance(parseLong(target));
            case "pool":
                return dependencyCheckService.checkPool(target);
            case "template":
                return dependencyCheckService.checkTemplate(target);
            case "apikey":
            case "api-key":
                return dependencyCheckService.checkApiKey(target);
            default:
                throw new ApiException("INVALID_REQUEST", "不支持的依赖检测目标类型: " + targetType);
        }
    }

    private Long parseLong(final String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new ApiException("INVALID_REQUEST", "目标标识必须是数字: " + value);
        }
    }
}
