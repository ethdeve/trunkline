/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.dto.ModelQuotaDTO;
import org.unreal.modelrouter.config.dto.ModelQuotaCatalogItemDTO;
import org.unreal.modelrouter.config.dto.ModelQuotaUpsertRequest;
import org.unreal.modelrouter.config.service.ModelQuotaService;

import java.util.List;

/**
 * 厂商模型额度与限速控制器（开源基础能力，v3.5 Trunkline UI 改造接入管理「额度与限速」tab）。
 *
 * <p><b>一行 = 一个厂商模型</b>（已接入的模型实例名），每行承载该模型的额度与限速。
 * 单价属商业能力，由企业版「价格配置」端点提供。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/config/quota")
@RequiredArgsConstructor
@Tag(name = "厂商模型额度与限速", description = "接入管理「额度与限速」tab 的厂商模型额度与限速配置")
public class ModelQuotaController {

    private final ModelQuotaService modelQuotaService;

    /**
     * 额度与限速目录：一行 = 一个已接入的厂商模型（含已配置的额度/限速）。
     */
    @GetMapping("/catalog")
    @Operation(summary = "查询额度与限速目录",
            description = "返回已接入厂商模型的额度与限速合并列表，含是否启用与额度/限速配置")
    public ResponseEntity<RouterResponse<List<ModelQuotaCatalogItemDTO>>> catalog() {
        return ResponseEntity.ok(RouterResponse.success(modelQuotaService.catalog()));
    }

    /**
     * 全部额度与限速行（含停用）。
     *
     * <p>路径必须是 {@code /list}：无路径的 {@code GET /api/config/quota} 已被
     * {@code QuotaConfigController#getConfig()}（运行时配额配置）占用，两者同路径会触发
     * WebFlux 的 {@code Ambiguous mapping} 并导致整个应用启动失败（隔离启动冒烟实证）。</p>
     */
    @GetMapping("/list")
    @Operation(summary = "查询额度与限速列表", description = "返回全部厂商模型额度与限速行（含停用）")
    public ResponseEntity<RouterResponse<List<ModelQuotaDTO>>> list() {
        return ResponseEntity.ok(RouterResponse.success(modelQuotaService.listAll()));
    }

    /**
     * 按厂商模型名 upsert 额度与限速（存在更新，不存在新建）。
     */
    @PutMapping("/{modelName}")
    @Operation(summary = "配置厂商模型额度与限速",
            description = "按厂商模型名写入额度与限速（每分钟请求数、小时/日/月 请求数与 token 上限、告警阈值），不存在则新建")
    public ResponseEntity<RouterResponse<ModelQuotaDTO>> upsert(
            @PathVariable final String modelName,
            @RequestBody final ModelQuotaUpsertRequest request) {
        final ModelQuotaDTO saved = modelQuotaService.upsert(modelName, request);
        return ResponseEntity.ok(RouterResponse.success(saved, "额度与限速已保存"));
    }

    /**
     * 删除额度与限速行（该模型回到不限额）。
     */
    @DeleteMapping("/{modelName}")
    @Operation(summary = "删除厂商模型额度与限速",
            description = "删除指定厂商模型的额度与限速配置")
    public ResponseEntity<RouterResponse<Void>> delete(@PathVariable final String modelName) {
        modelQuotaService.delete(modelName);
        return ResponseEntity.ok(RouterResponse.success(null, "额度与限速已删除"));
    }
}
