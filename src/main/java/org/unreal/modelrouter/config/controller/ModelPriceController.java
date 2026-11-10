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
import org.unreal.modelrouter.common.dto.ModelPriceDTO;
import org.unreal.modelrouter.config.dto.ModelPriceCatalogItemDTO;
import org.unreal.modelrouter.config.dto.ModelPriceUpsertRequest;
import org.unreal.modelrouter.config.service.ModelPriceService;

import java.util.List;

/**
 * 模型价格与额度控制器（v3.5 Trunkline UI 改造，接入管理「价格与额度」tab）。
 *
 * <p><b>一张表</b>：价格与额度合并为同一张表，一行 = 一个<b>厂商模型</b>
 * （已接入的模型实例名），每行既有单价（¥ / 千 token），也有该模型的额度与限速。
 * 价格来源一期手工配置（用户决策 #2）。</p>
 *
 * <p><b>计价口径</b>：账单按厂商模型名计价——用量落在 {@code token_usage.instance_name}，
 * 与本表 {@code model_name} 键一致。</p>
 *
 * <p>虚拟模型（模型分组）的额度与限速独立配置，落在分组定义（池）上，不在本表。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("/api/config/prices")
@RequiredArgsConstructor
@Tag(name = "模型价格与额度", description = "接入管理「价格与额度」tab 的厂商模型单价与额度限速配置")
public class ModelPriceController {

    private final ModelPriceService modelPriceService;

    /**
     * 价格与额度目录：一行 = 一个已接入的厂商模型（含已配置的单价与额度限速）。
     */
    @GetMapping("/catalog")
    @Operation(summary = "查询价格与额度目录",
            description = "返回已接入厂商模型的价格与额度合并列表，"
                    + "含单价、是否已配置标记与额度/限速配置")
    public ResponseEntity<RouterResponse<List<ModelPriceCatalogItemDTO>>> catalog() {
        return ResponseEntity.ok(RouterResponse.success(modelPriceService.catalog()));
    }

    /**
     * 全部价格与额度行（含停用）。
     */
    @GetMapping
    @Operation(summary = "查询价格与额度列表", description = "返回全部厂商模型价格与额度行（含停用）")
    public ResponseEntity<RouterResponse<List<ModelPriceDTO>>> list() {
        return ResponseEntity.ok(RouterResponse.success(modelPriceService.listAll()));
    }

    /**
     * 按厂商模型名 upsert 价格与额度（存在更新，不存在新建）。
     *
     * <p>请求体中的额度字段为 {@code null} 时表示「不修改」，{@code 0} 表示「不限制」。</p>
     */
    @PutMapping("/{modelName}")
    @Operation(summary = "配置厂商模型价格与额度",
            description = "按厂商模型名写入输入/输出单价（¥ / 千 token）与额度限速"
                    + "（每分钟请求数、小时/日/月 请求数与 token 上限、告警阈值），不存在则新建")
    public ResponseEntity<RouterResponse<ModelPriceDTO>> upsert(
            @PathVariable final String modelName,
            @RequestBody final ModelPriceUpsertRequest request) {
        final ModelPriceDTO saved = modelPriceService.upsert(modelName, request);
        return ResponseEntity.ok(RouterResponse.success(saved, "价格与额度已保存"));
    }

    /**
     * 删除价格与额度行（该模型回到 ¥0 计、无限额）。
     */
    @DeleteMapping("/{modelName}")
    @Operation(summary = "删除厂商模型价格与额度",
            description = "删除指定厂商模型的价格与额度配置")
    public ResponseEntity<RouterResponse<Void>> delete(@PathVariable final String modelName) {
        modelPriceService.delete(modelName);
        return ResponseEntity.ok(RouterResponse.success(null, "价格与额度已删除"));
    }
}
