/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.enterprise.spi.EnterpriseFeaturesProvider;

/**
 * 企业能力探测端点（O6）：{@code GET /api/v1/enterprise/features}。
 *
 * <p>前端特性开关的唯一数据源：社区版返回空能力集 {@code {}}；
 * 商业版 jar 在场时 {@link EnterpriseFeaturesProvider} Bean 被企业实现替换，
 * 同一路径返回对应能力集（{@code {sso:true, tenant:true, ...}}）。</p>
 *
 * <p>授权：任意已登录用户可访问（URL 矩阵登记 SELF，见
 * {@code FixedRoleAccessPolicy}）——前端所有角色都可能需要按能力显隐企业入口。
 * 响应体只有能力键值对，不含任何密钥、用户或租户数据，不构成信息泄露面。</p>
 *
 * <p>注意：同步返回 {@code ResponseEntity}，按项目纪律<b>不加</b>方法级
 * {@code @PreAuthorize}（Reactive 上下文下会 500），授权完全由 URL 矩阵承载。</p>
 *
 * @author Trunkline Team
 */
@RestController
@RequestMapping("/api/v1/enterprise")
@CrossOrigin(origins = "*")
@Tag(name = "企业能力探测", description = "前端特性开关数据源（只读；社区版返回空能力集）")
public class EnterpriseFeaturesController {

    private final EnterpriseFeaturesProvider featuresProvider;

    public EnterpriseFeaturesController(final EnterpriseFeaturesProvider featuresProvider) {
        this.featuresProvider = featuresProvider;
    }

    /**
     * 探测当前部署的企业能力集。
     *
     * @return 能力键值对；社区版恒为 {@code {}}
     */
    @GetMapping("/features")
    @Operation(summary = "企业能力探测",
            description = "返回当前部署可用的企业能力集；开源社区版恒为空对象，"
                    + "企业 jar 在场时返回对应能力集。前端据此显隐企业专属 UI。")
    public ResponseEntity<RouterResponse<java.util.Map<String, Object>>> features() {
        return ResponseEntity.ok(RouterResponse.success(featuresProvider.getFeatures()));
    }
}
