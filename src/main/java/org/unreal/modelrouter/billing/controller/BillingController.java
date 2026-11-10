/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.billing.dto.MyBillsVO;
import org.unreal.modelrouter.billing.dto.MyRecentUsageVO;
import org.unreal.modelrouter.billing.dto.TeamUsageVO;
import org.unreal.modelrouter.billing.service.BillingService;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.exception.ApiException;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * 账单控制器（v3.5 Trunkline UI 改造，「我的账单」，§4.3）。
 *
 * <p>当前仅暴露「我的账单」聚合端点：按登录用户 + 月份，聚合 token × 价格。
 * 归属用户由服务端从 {@link Authentication#getName()} 解析，不信任前端传参，避免越权查看他人账单。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@RestController
@RequestMapping("api/billing")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "账单接口")
public class BillingController {

    private final BillingService billingService;

    /**
     * 「我的账单」月度聚合。
     *
     * <p>可见性：普通用户与运维（{@code hasAnyRole('USER','OPERATOR','ADMIN')}）。
     * 月份缺省取当前月。</p>
     *
     * @param authentication 当前登录用户（principal.name 为归属）
     * @param year           年份（缺省当前年）
     * @param month          月份（1-12，缺省当前月）
     * @return 月度汇总 + 按虚拟模型明细
     */
    @GetMapping("/my")
    @Operation(summary = "获取我的账单", description = "按当前登录用户与月份聚合 token × 价格的花费")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Mono<RouterResponse<MyBillsVO>> getMyBills(
            final Authentication authentication,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month) {
        final String owner = authentication != null ? authentication.getName() : null;
        final LocalDate today = LocalDate.now();
        final int effYear = year != null ? year : today.getYear();
        final int effMonth = month != null ? month : today.getMonthValue();
        try {
            return Mono.fromCallable(() -> billingService.getMyBills(owner, effYear, effMonth))
                    .map(vo -> RouterResponse.success(vo, "获取我的账单成功"))
                    .onErrorResume(e -> {
                        log.error("获取我的账单失败", e);
                        return Mono.error(ApiException.of("INTERNAL_ERROR", "获取我的账单失败"));
                    });
        } catch (Exception e) {
            log.error("获取我的账单异常", e);
            return Mono.error(ApiException.of("INTERNAL_ERROR", "获取我的账单失败"));
        }
    }

    /**
     * 「我的额度」页的近期用量（v3.5 Trunkline UI 改造）。
     *
     * <p>聚合最近若干天（默认 7 天）内该用户按<b>虚拟模型</b>分组的用量与花费，
     * 供额度首页的「本周最常用」卡片使用。</p>
     *
     * <p>可见性：普通用户与运维（{@code hasAnyRole('USER','OPERATOR','ADMIN')}）——
     * 只返回自己的数据，归属由服务端从 principal 解析。</p>
     *
     * @param authentication 当前登录用户（principal.name 为归属）
     * @param days           回溯天数（缺省 7，上限 90）
     * @return 近期用量视图
     */
    @GetMapping("/my/recent")
    @Operation(summary = "获取我的近期用量",
            description = "聚合当前登录用户最近 N 天按虚拟模型分组的 token 用量与花费")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Mono<RouterResponse<MyRecentUsageVO>> getMyRecentUsage(
            final Authentication authentication,
            @RequestParam(value = "days", required = false) Integer days) {
        final String owner = authentication != null ? authentication.getName() : null;
        final int effDays = days != null ? days : 7;
        try {
            return Mono.fromCallable(() -> billingService.getMyRecentUsage(owner, effDays))
                    .map(vo -> RouterResponse.success(vo, "获取我的近期用量成功"))
                    .onErrorResume(e -> {
                        log.error("获取我的近期用量失败", e);
                        return Mono.error(ApiException.of("INTERNAL_ERROR", "获取我的近期用量失败"));
                    });
        } catch (Exception e) {
            log.error("获取我的近期用量异常", e);
            return Mono.error(ApiException.of("INTERNAL_ERROR", "获取我的近期用量失败"));
        }
    }

    /**
     * 「团队用量」聚合（全公司 / 按部门）。
     *
     * <p>可见性：运维与管理员（{@code hasAnyRole('OPERATOR','ADMIN')}）——
     * 这是跨员工的用量视图，普通员工不应看到同事数据。</p>
     *
     * @param dimension  维度：COMPANY（全公司，按人）/ DEPARTMENT（按部门）
     * @param year       年份（缺省当前年）
     * @param month      月份（1-12，缺省当前月）
     * @param department 部门筛选（可选）
     * @return 分组明细 + 按模型明细 + 总计
     */
    @GetMapping("/team")
    @Operation(summary = "获取团队用量",
            description = "按全公司或部门维度聚合 token 用量与花费（跨员工视图）")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<TeamUsageVO>> getTeamUsage(
            @RequestParam(value = "dimension", required = false) String dimension,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month,
            @RequestParam(value = "department", required = false) String department) {
        final LocalDate today = LocalDate.now();
        final int effYear = year != null ? year : today.getYear();
        final int effMonth = month != null ? month : today.getMonthValue();
        try {
            return Mono.fromCallable(() -> billingService.getTeamUsage(dimension, effYear, effMonth, department))
                    .map(vo -> RouterResponse.success(vo, "获取团队用量成功"))
                    .onErrorResume(e -> {
                        log.error("获取团队用量失败", e);
                        return Mono.error(ApiException.of("INTERNAL_ERROR", "获取团队用量失败"));
                    });
        } catch (Exception e) {
            log.error("获取团队用量异常", e);
            return Mono.error(ApiException.of("INTERNAL_ERROR", "获取团队用量失败"));
        }
    }

    /**
     * 部门列表（去重排序），供团队用量的部门筛选下拉使用。
     */
    @GetMapping("/departments")
    @Operation(summary = "获取部门列表", description = "返回账号表中已配置的全部部门（去重排序）")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Mono<RouterResponse<List<String>>> getDepartments() {
        try {
            return Mono.fromCallable(billingService::getDepartments)
                    .map(list -> RouterResponse.success(list, "获取部门列表成功"));
        } catch (Exception e) {
            log.error("获取部门列表异常", e);
            return Mono.error(ApiException.of("INTERNAL_ERROR", "获取部门列表失败"));
        }
    }
}
