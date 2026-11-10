/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.billing.dto.MyBillsVO;
import org.unreal.modelrouter.billing.dto.MyRecentUsageVO;
import org.unreal.modelrouter.billing.dto.TeamUsageVO;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ModelPriceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelPriceRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账单聚合服务（v3.5 Trunkline UI 改造，「我的账单」，§4.3）。
 *
 * <p>聚合口径：以 {@code token_usage} 的成功调用为事实来源，按「用户 + 月份 + 厂商模型」
 * 汇总 token 用量；再与 {@code model_price} 表的模型单价合并，按
 * {@code promptTokens × priceInput / 1000 + completionTokens × priceOutput / 1000}
 * 计算花费（单价为 ¥ / 千 token）。</p>
 *
 * <p><b>计价口径</b>：账单按<b>厂商模型名</b>计价——分组维度为 {@code token_usage.instance_name}
 * （请求最终落到的已接入模型实例名），与 {@code model_price.model_name} 键一致；
 * 不按虚拟模型（对外路由名）计价。</p>
 *
 * <p>价格缺失容错：未配置价格（或停用）的模型按 ¥0 计入，前端账单页给空态提示；
 * 金额计算在内存完成，避免跨实体 JOIN 的方言兼容性风险。</p>
 *
 * @author Trunkline Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    /** 单价为「每千 token」，token 数除以此常量换算为金额 */
    private static final BigDecimal TOKENS_PER_UNIT = new BigDecimal("1000");

    private final TokenUsageRepository tokenUsageRepository;
    private final ModelPriceRepository modelPriceRepository;
    private final JwtAccountRepository jwtAccountRepository;

    /**
     * 聚合当前用户指定月份的账单。
     *
     * @param userId 用户名（JWT subject，服务端 principal 透传，严禁信任前端传参）
     * @param year   年份
     * @param month  月份（1-12）
     * @return 月度汇总 + 按厂商模型明细
     */
    @Transactional(readOnly = true)
    public MyBillsVO getMyBills(final String userId, final int year, final int month) {
        // 1. 加载已启用的模型单价（内存建索引，避免逐行查库的 N+1）
        //    键 = 厂商模型名，与 token_usage.instance_name 对齐
        final Map<String, ModelPriceEntity> priceIndex = new HashMap<>();
        for (final ModelPriceEntity price : modelPriceRepository.findAllByEnabledTrue()) {
            if (price.getModelName() != null) {
                priceIndex.put(price.getModelName(), price);
            }
        }

        // 2. 按「用户 + 月份 + 模型」聚合 token 用量（成功调用）
        final List<Object[]> modelRows =
                tokenUsageRepository.aggregateByUserMonthModel(userId, year, month);

        // 3. 逐模型合并单价、算花费
        final List<MyBillsVO.ModelBill> modelBills = new ArrayList<>(modelRows.size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        long totalTokens = 0L, promptTokens = 0L, completionTokens = 0L, requestCount = 0L;

        for (final Object[] row : modelRows) {
            final String modelName = (String) row[0];
            final long prompt = ((Number) row[1]).longValue();
            final long completion = ((Number) row[2]).longValue();
            final long total = ((Number) row[3]).longValue();
            final long requests = ((Number) row[4]).longValue();

            final ModelPriceEntity price = priceIndex.get(modelName);
            final BigDecimal priceIn = price != null ? price.getPriceInput() : BigDecimal.ZERO;
            final BigDecimal priceOut = price != null ? price.getPriceOutput() : BigDecimal.ZERO;

            final BigDecimal amount = computeAmount(prompt, completion, priceIn, priceOut);

            modelBills.add(MyBillsVO.ModelBill.builder()
                    .modelName(modelName)
                    .promptTokens(prompt)
                    .completionTokens(completion)
                    .totalTokens(total)
                    .requestCount(requests)
                    .priceInput(priceIn)
                    .priceOutput(priceOut)
                    .amount(amount)
                    .build());

            totalAmount = totalAmount.add(amount);
            totalTokens += total;
            promptTokens += prompt;
            completionTokens += completion;
            requestCount += requests;
        }

        // 按金额降序（未计价的 ¥0 排后）
        modelBills.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 5. 按天 × 虚拟模型的交叉表（v3.5：账单页按原型改为「按天汇总」）
        final List<MyBillsVO.DailyBill> dailyBills = buildDailyBills(userId, year, month, priceIndex);

        // 6. 上月同期（KPI 卡的「比上月省了 ¥x」参照）
        final int prevYear = month == 1 ? year - 1 : year;
        final int prevMonth = month == 1 ? 12 : month - 1;
        final BigDecimal prevAmount = computeMonthAmount(userId, prevYear, prevMonth, priceIndex);
        final long prevRequests = countMonthRequests(userId, prevYear, prevMonth);

        return MyBillsVO.builder()
                .month(String.format("%04d-%02d", year, month))
                .currency("CNY")
                .totalAmount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .totalTokens(totalTokens)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .requestCount(requestCount)
                .previousMonthAmount(prevAmount.setScale(4, RoundingMode.HALF_UP))
                .previousMonthRequestCount(prevRequests)
                .byModel(modelBills)
                .byDay(dailyBills)
                .build();
    }

    /**
     * 计算某月的总花费（¥）——用于「上月同期」对比。
     *
     * <p>与 {@link #getMyBills} 同一计价口径：按厂商模型单价算后汇总。</p>
     */
    private BigDecimal computeMonthAmount(final String userId, final int year, final int month,
                                          final Map<String, ModelPriceEntity> priceIndex) {
        BigDecimal amount = BigDecimal.ZERO;
        for (final Object[] row : tokenUsageRepository.aggregateByUserMonthModel(userId, year, month)) {
            final String modelName = (String) row[0];
            final long prompt = ((Number) row[1]).longValue();
            final long completion = ((Number) row[2]).longValue();
            final ModelPriceEntity price = priceIndex.get(modelName);
            final BigDecimal priceIn = price != null ? price.getPriceInput() : BigDecimal.ZERO;
            final BigDecimal priceOut = price != null ? price.getPriceOutput() : BigDecimal.ZERO;
            amount = amount.add(computeAmount(prompt, completion, priceIn, priceOut));
        }
        return amount;
    }

    /**
     * 统计某月的成功调用次数——用于「上月同期」对比。
     */
    private long countMonthRequests(final String userId, final int year, final int month) {
        final List<Object[]> rows = tokenUsageRepository.aggregateByUserMonth(userId, year, month);
        if (rows.isEmpty()) {
            return 0L;
        }
        final Object[] row = rows.get(0);
        // aggregateByUserMonth 返回 [totalTokens, promptTokens, completionTokens, requestCount]
        return ((Number) row[3]).longValue();
    }

    /**
     * 「我的额度」页的近期用量（v3.5 Trunkline UI 改造）。
     *
     * <p>聚合最近 {@code days} 天（默认 7 天）内该用户按<b>虚拟模型</b>分组的用量，
     * 金额按厂商模型单价算好后汇总到虚拟模型。</p>
     *
     * <p>与 {@link #buildDailyBills} 的月度按天聚合分开实现：本查询<b>跨自然月</b>——
     * 月初时「本周最常用」仍能算准，而按月聚合取不到上月末的数据。</p>
     *
     * @param userId 用户名
     * @param days   回溯天数（缺省 7）
     * @return 近期用量视图
     */
    public MyRecentUsageVO getMyRecentUsage(final String userId, final int days) {
        final int window = days > 0 ? Math.min(days, 90) : 7;
        final LocalDate end = LocalDate.now();
        final LocalDate start = end.minusDays(window - 1L);

        // 单价索引
        final Map<String, ModelPriceEntity> priceIndex = new HashMap<>();
        for (final ModelPriceEntity price : modelPriceRepository.findAllByEnabledTrue()) {
            if (price.getModelName() != null) {
                priceIndex.put(price.getModelName(), price);
            }
        }

        final List<Object[]> rows = tokenUsageRepository.aggregateByUserDateRangeVirtualModel(
                userId, start.toString(), end.toString());

        // 虚拟模型 → 累加器
        final Map<String, long[]> counters = new LinkedHashMap<>();
        final Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        long totalTokens = 0L;
        long totalRequests = 0L;

        for (final Object[] row : rows) {
            final String rawVirtualModel = (String) row[0];
            final String instanceName = (String) row[1];
            final long prompt = ((Number) row[2]).longValue();
            final long completion = ((Number) row[3]).longValue();
            final long total = ((Number) row[4]).longValue();
            final long requests = ((Number) row[5]).longValue();

            final ModelPriceEntity price = priceIndex.get(instanceName);
            final BigDecimal priceIn = price != null ? price.getPriceInput() : BigDecimal.ZERO;
            final BigDecimal priceOut = price != null ? price.getPriceOutput() : BigDecimal.ZERO;
            final BigDecimal amount = computeAmount(prompt, completion, priceIn, priceOut);

            final String virtualModel = rawVirtualModel != null && !rawVirtualModel.isBlank()
                    ? rawVirtualModel : TeamUsageVO.UNASSIGNED_VIRTUAL_MODEL;

            final long[] c = counters.computeIfAbsent(virtualModel, k -> new long[3]);
            c[0] += requests;
            c[1] += total;
            c[2] += 1;
            amounts.merge(virtualModel, amount, BigDecimal::add);

            totalAmount = totalAmount.add(amount);
            totalTokens += total;
            totalRequests += requests;
        }

        final List<MyRecentUsageVO.VirtualModelUsage> usages = new ArrayList<>(counters.size());
        for (final Map.Entry<String, long[]> entry : counters.entrySet()) {
            final BigDecimal amount = amounts.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            usages.add(MyRecentUsageVO.VirtualModelUsage.builder()
                    .virtualModel(entry.getKey())
                    .requestCount(entry.getValue()[0])
                    .totalTokens(entry.getValue()[1])
                    .amount(amount.setScale(4, RoundingMode.HALF_UP))
                    .amountRatio(totalAmount.signum() == 0 ? 0d
                            : amount.divide(totalAmount, 4, RoundingMode.HALF_UP).doubleValue())
                    .build());
        }
        // 按花费降序（「最常用」取第一个）
        usages.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        return MyRecentUsageVO.builder()
                .startDate(start.toString())
                .endDate(end.toString())
                .totalAmount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .totalTokens(totalTokens)
                .requestCount(totalRequests)
                .byVirtualModel(usages)
                .build();
    }

    /**
     * 构建「按天 × 虚拟模型」交叉表（v3.5 Trunkline UI 改造）。
     *
     * <p><b>分组维度与计价维度分离</b>：行按日期、列按<b>虚拟模型</b>（员工视角，
     * 不该关心背后是哪个厂商），但金额必须按<b>厂商模型</b>单价算——
     * 同一个虚拟模型背后可能挂着多个厂商模型，各自单价不同。</p>
     *
     * <p>因此聚合查询同时返回 {@code virtualModel}（分组）与 {@code instanceName}（计价）：
     * 先按厂商模型匹配单价算出这一格的金额，再汇总到虚拟模型列。</p>
     *
     * <p>{@code virtualModel} 为空（未命中任何虚拟模型的历史数据）归入「未归类」，不丢数据。</p>
     *
     * @param userId     用户名
     * @param year       年份
     * @param month      月份
     * @param priceIndex 厂商模型 → 单价的索引
     * @return 按日期倒序的每日明细
     */
    private List<MyBillsVO.DailyBill> buildDailyBills(final String userId, final int year, final int month,
                                                      final Map<String, ModelPriceEntity> priceIndex) {
        final List<Object[]> dayRows =
                tokenUsageRepository.aggregateByUserMonthDayVirtualModel(userId, year, month);

        // 日期 → 该日的虚拟模型金额累加器（保持插入顺序，便于前端稳定渲染列）
        final Map<String, Map<String, BigDecimal>> dayVirtualAmounts = new LinkedHashMap<>();
        final Map<String, Long> dayRequests = new LinkedHashMap<>();
        final Map<String, Long> dayTokens = new LinkedHashMap<>();

        for (final Object[] row : dayRows) {
            final String usageDate = (String) row[0];
            final String rawVirtualModel = (String) row[1];
            final String instanceName = (String) row[2];
            final long prompt = ((Number) row[3]).longValue();
            final long completion = ((Number) row[4]).longValue();
            final long total = ((Number) row[5]).longValue();
            final long requests = ((Number) row[6]).longValue();

            final ModelPriceEntity price = priceIndex.get(instanceName);
            final BigDecimal priceIn = price != null ? price.getPriceInput() : BigDecimal.ZERO;
            final BigDecimal priceOut = price != null ? price.getPriceOutput() : BigDecimal.ZERO;
            final BigDecimal amount = computeAmount(prompt, completion, priceIn, priceOut);

            final String virtualModel = rawVirtualModel != null && !rawVirtualModel.isBlank()
                    ? rawVirtualModel : TeamUsageVO.UNASSIGNED_VIRTUAL_MODEL;

            dayVirtualAmounts.computeIfAbsent(usageDate, k -> new LinkedHashMap<>())
                    .merge(virtualModel, amount, BigDecimal::add);
            dayRequests.merge(usageDate, requests, Long::sum);
            dayTokens.merge(usageDate, total, Long::sum);
        }

        final List<MyBillsVO.DailyBill> result = new ArrayList<>(dayVirtualAmounts.size());
        for (final Map.Entry<String, Map<String, BigDecimal>> entry : dayVirtualAmounts.entrySet()) {
            final Map<String, BigDecimal> byVirtual = entry.getValue();
            BigDecimal dayAmount = BigDecimal.ZERO;
            for (final BigDecimal v : byVirtual.values()) {
                dayAmount = dayAmount.add(v);
            }
            result.add(MyBillsVO.DailyBill.builder()
                    .usageDate(entry.getKey())
                    .amount(dayAmount.setScale(4, RoundingMode.HALF_UP))
                    .requestCount(dayRequests.get(entry.getKey()))
                    .totalTokens(dayTokens.get(entry.getKey()))
                    .byVirtualModel(byVirtual)
                    .build());
        }
        return result;
    }

    /**
     * 团队用量聚合（全公司 / 按部门）。
     *
     * <p>事实来源与「我的账单」同源：{@code token_usage} 的成功调用。
     * 部门归属来自 {@code jwt_accounts.department}（用户名关联），账号不存在或未填部门的
     * 归入「未分配部门」——不丢数据。</p>
     *
     * @param dimension  维度：COMPANY（全公司，按人）/ DEPARTMENT（按部门）
     * @param year       年份
     * @param month      月份（1-12）
     * @param department 部门筛选（可选；缺省=全部部门）
     * @return 分组明细 + 按厂商模型明细 + 总计
     */
    @Transactional(readOnly = true)
    public TeamUsageVO getTeamUsage(final String dimension, final int year, final int month,
                                    final String department) {
        final boolean byDepartment = TeamUsageVO.DIMENSION_DEPARTMENT.equalsIgnoreCase(dimension);
        final String effDimension = byDepartment
                ? TeamUsageVO.DIMENSION_DEPARTMENT : TeamUsageVO.DIMENSION_COMPANY;

        // 1. 价格索引
        final Map<String, ModelPriceEntity> priceIndex = new HashMap<>();
        for (final ModelPriceEntity price : modelPriceRepository.findAllByEnabledTrue()) {
            if (price.getModelName() != null) {
                priceIndex.put(price.getModelName(), price);
            }
        }

        // 2. 账号索引（用户名 → 部门/显示名）
        final Map<String, JwtAccountEntity> accounts = new HashMap<>();
        for (final JwtAccountEntity account : jwtAccountRepository.findAll()) {
            if (account.getUsername() != null) {
                accounts.put(account.getUsername(), account);
            }
        }

        // 3. 逐人逐虚拟模型逐厂商模型聚合行（含失败调用，用于算成功率）
        final List<Object[]> rows =
                tokenUsageRepository.aggregateByMonthUserVirtualModel(year, month);

        // 4. 分组累加器
        final Map<String, Accumulator> groups = new LinkedHashMap<>();
        final Map<String, Accumulator> models = new LinkedHashMap<>();
        final Map<String, VirtualModelAccumulator> virtualModels = new LinkedHashMap<>();
        final Map<String, ServiceTypeAccumulator> serviceTypes = new LinkedHashMap<>();
        final Map<String, DepartmentAccumulator> departments = new LinkedHashMap<>();
        final Set<String> users = new LinkedHashSet<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        long totalTokens = 0L, promptTokens = 0L, completionTokens = 0L, requestCount = 0L;
        long successCount = 0L, failedCount = 0L;

        for (final Object[] row : rows) {
            final String userId = (String) row[0];
            final String rawVirtualModel = (String) row[1];
            final String modelName = (String) row[2];
            final String serviceType = (String) row[3];
            final long prompt = ((Number) row[4]).longValue();
            final long completion = ((Number) row[5]).longValue();
            final long total = ((Number) row[6]).longValue();
            final long requests = ((Number) row[7]).longValue();
            final long success = ((Number) row[8]).longValue();

            // 虚拟模型名：未命中任何分组（历史数据 / 内部路径）归入「未归类」，不丢数据
            final String virtualModel = rawVirtualModel != null && !rawVirtualModel.isBlank()
                    ? rawVirtualModel : TeamUsageVO.UNASSIGNED_VIRTUAL_MODEL;

            final JwtAccountEntity account = accounts.get(userId);
            final String dept = account != null && account.getDepartment() != null
                    && !account.getDepartment().isBlank()
                    ? account.getDepartment() : TeamUsageVO.UNASSIGNED_DEPARTMENT;

            // 部门筛选
            if (department != null && !department.isBlank() && !department.equals(dept)) {
                continue;
            }

            final ModelPriceEntity price = priceIndex.get(modelName);
            final BigDecimal priceIn = price != null && price.getPriceInput() != null
                    ? price.getPriceInput() : BigDecimal.ZERO;
            final BigDecimal priceOut = price != null && price.getPriceOutput() != null
                    ? price.getPriceOutput() : BigDecimal.ZERO;
            final BigDecimal amount = computeAmount(prompt, completion, priceIn, priceOut);

            // 分组键：按部门 = 部门名；全公司 = 用户名
            final String groupKey = byDepartment ? dept : userId;
            final String groupLabel = byDepartment
                    ? dept
                    : (account != null ? account.getUsername() : userId);
            final Accumulator groupAcc = groups.computeIfAbsent(groupKey, k -> {
                final Accumulator acc = new Accumulator();
                acc.label = groupLabel;
                return acc;
            });
            groupAcc.add(prompt, completion, total, requests, amount);
            groupAcc.success += success;
            groupAcc.failed += (requests - success);
            if (byDepartment) {
                groupAcc.users.add(userId);
            }

            final Accumulator modelAcc = models.computeIfAbsent(modelName, k -> new Accumulator());
            modelAcc.add(prompt, completion, total, requests, amount);

            // 虚拟模型维度（主展示维度）—— 同时记录其下钻的厂商模型
            final VirtualModelAccumulator vmAcc =
                    virtualModels.computeIfAbsent(virtualModel, VirtualModelAccumulator::new);
            vmAcc.add(prompt, completion, total, requests, amount);
            final Accumulator vmModelAcc =
                    vmAcc.models.computeIfAbsent(modelName, k -> new Accumulator());
            vmModelAcc.add(prompt, completion, total, requests, amount);

            // 用途类型维度（聊天 / 嵌入 / 排序 / 语音 / 画图）
            final ServiceTypeAccumulator stAcc =
                    serviceTypes.computeIfAbsent(serviceType == null ? "chat" : serviceType,
                            key -> new ServiceTypeAccumulator());
            stAcc.add(prompt, completion, total, requests, amount);

            // 部门汇总卡（不受「维度 / 部门筛选」影响，始终全量）
            final DepartmentAccumulator deptAcc =
                    departments.computeIfAbsent(dept, key -> new DepartmentAccumulator());
            deptAcc.add(prompt, completion, total, requests, amount);
            deptAcc.users.add(userId);

            users.add(userId);
            totalAmount = totalAmount.add(amount);
            totalTokens += total;
            promptTokens += prompt;
            completionTokens += completion;
            requestCount += requests;
            successCount += success;
            failedCount += (requests - success);
        }

        // 5. 组装分组明细（按花费降序）
        final List<TeamUsageVO.GroupUsage> groupList = new ArrayList<>(groups.size());
        for (final Map.Entry<String, Accumulator> entry : groups.entrySet()) {
            final Accumulator acc = entry.getValue();
            groupList.add(TeamUsageVO.GroupUsage.builder()
                    .key(entry.getKey())
                    .label(acc.label)
                    .userCount(byDepartment ? acc.users.size() : 1)
                    .requestCount(acc.requests)
                    .promptTokens(acc.prompt)
                    .completionTokens(acc.completion)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .build());
        }
        groupList.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 6. 按厂商模型明细（按花费降序）
        final List<TeamUsageVO.ModelUsage> modelList = new ArrayList<>(models.size());
        for (final Map.Entry<String, Accumulator> entry : models.entrySet()) {
            final Accumulator acc = entry.getValue();
            modelList.add(TeamUsageVO.ModelUsage.builder()
                    .modelName(entry.getKey())
                    .requestCount(acc.requests)
                    .promptTokens(acc.prompt)
                    .completionTokens(acc.completion)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .build());
        }
        modelList.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 7. 按虚拟模型明细（主展示维度，按花费降序）
        final List<TeamUsageVO.VirtualModelUsage> virtualModelList =
                new ArrayList<>(virtualModels.size());
        for (final Map.Entry<String, VirtualModelAccumulator> entry : virtualModels.entrySet()) {
            final VirtualModelAccumulator acc = entry.getValue();
            final List<TeamUsageVO.ModelUsage> subModels = new ArrayList<>(acc.models.size());
            for (final Map.Entry<String, Accumulator> modelEntry : acc.models.entrySet()) {
                final Accumulator modelAcc = modelEntry.getValue();
                subModels.add(TeamUsageVO.ModelUsage.builder()
                        .modelName(modelEntry.getKey())
                        .requestCount(modelAcc.requests)
                        .promptTokens(modelAcc.prompt)
                        .completionTokens(modelAcc.completion)
                        .totalTokens(modelAcc.total)
                        .amount(modelAcc.amount.setScale(4, RoundingMode.HALF_UP))
                        .build());
            }
            subModels.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));
            virtualModelList.add(TeamUsageVO.VirtualModelUsage.builder()
                    .virtualModel(entry.getKey())
                    .label(entry.getKey())
                    .modelCount(acc.models.size())
                    .requestCount(acc.requests)
                    .promptTokens(acc.prompt)
                    .completionTokens(acc.completion)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .amountRatio(ratio(acc.amount, totalAmount))
                    .models(subModels)
                    .build());
        }
        virtualModelList.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 8. 按用途类型分布（按花费降序）
        final List<TeamUsageVO.ServiceTypeUsage> serviceTypeList =
                new ArrayList<>(serviceTypes.size());
        for (final Map.Entry<String, ServiceTypeAccumulator> entry : serviceTypes.entrySet()) {
            final ServiceTypeAccumulator acc = entry.getValue();
            serviceTypeList.add(TeamUsageVO.ServiceTypeUsage.builder()
                    .serviceType(entry.getKey())
                    .label(humanizeServiceType(entry.getKey()))
                    .requestCount(acc.requests)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .amountRatio(ratio(acc.amount, totalAmount))
                    .build());
        }
        serviceTypeList.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        // 9. 部门汇总卡（第一张是「全公司」，其余按花费降序）
        final List<TeamUsageVO.DepartmentSummary> departmentList =
                new ArrayList<>(departments.size() + 1);
        departmentList.add(TeamUsageVO.DepartmentSummary.builder()
                .department(TeamUsageVO.COMPANY_LABEL)
                .userCount(users.size())
                .requestCount(requestCount)
                .totalTokens(totalTokens)
                .amount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .amountRatio(totalAmount.signum() > 0 ? 1.0d : 0.0d)
                .company(true)
                .build());
        for (final Map.Entry<String, DepartmentAccumulator> entry : departments.entrySet()) {
            final DepartmentAccumulator acc = entry.getValue();
            departmentList.add(TeamUsageVO.DepartmentSummary.builder()
                    .department(entry.getKey())
                    .userCount(acc.users.size())
                    .requestCount(acc.requests)
                    .totalTokens(acc.total)
                    .amount(acc.amount.setScale(4, RoundingMode.HALF_UP))
                    .amountRatio(ratio(acc.amount, totalAmount))
                    .company(false)
                    .build());
        }
        departmentList.sort((a, b) -> {
            // 「全公司」永远排第一，其余按花费降序
            if (a.isCompany() != b.isCompany()) {
                return a.isCompany() ? -1 : 1;
            }
            return b.getAmount().compareTo(a.getAmount());
        });

        return TeamUsageVO.builder()
                .month(String.format("%04d-%02d", year, month))
                .currency("CNY")
                .dimension(effDimension)
                .departmentFilter(department)
                .totalAmount(totalAmount.setScale(4, RoundingMode.HALF_UP))
                .totalTokens(totalTokens)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .requestCount(requestCount)
                .userCount(users.size())
                .successCount(successCount)
                .failedCount(failedCount)
                .successRate(requestCount > 0 ? (double) successCount / requestCount : 0.0d)
                .groups(groupList)
                .byModel(modelList)
                .byVirtualModel(virtualModelList)
                .byServiceType(serviceTypeList)
                .departments(departmentList)
                .build();
    }

    /**
     * 占比（0~1）。
     */
    private static double ratio(final BigDecimal part, final BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            return 0.0d;
        }
        return part.divide(total, 6, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 用途类型人话化（设计规格 §6.4：员工侧不出现技术词）。
     */
    private static String humanizeServiceType(final String serviceType) {
        if (serviceType == null) {
            return "聊天对话";
        }
        return switch (serviceType.toLowerCase()) {
            case "chat" -> "聊天对话";
            case "embedding" -> "知识库嵌入";
            case "rerank" -> "文档排序";
            case "tts" -> "语音合成";
            case "stt" -> "语音识别";
            case "imgGen", "imggen", "image" -> "图片生成";
            case "imgEdit", "imgedit" -> "图片编辑";
            default -> serviceType;
        };
    }

    /**
     * 全部部门列表（去重排序）。
     *
     * <p>供「团队用量」部门维度筛选下拉使用，避免前端硬编码部门清单。</p>
     */
    @Transactional(readOnly = true)
    public List<String> getDepartments() {
        final List<String> departments = new ArrayList<>();
        for (final JwtAccountEntity account : jwtAccountRepository.findAll()) {
            final String dept = account.getDepartment();
            if (dept != null && !dept.isBlank() && !departments.contains(dept.trim())) {
                departments.add(dept.trim());
            }
        }
        departments.sort(String::compareTo);
        return departments;
    }

    /**
     * 分组累加器（内部用）。
     */
    private static final class Accumulator {
        private String label;
        private long prompt;
        private long completion;
        private long total;
        private long requests;
        private long success;
        private long failed;
        private BigDecimal amount = BigDecimal.ZERO;
        private final Set<String> users = new LinkedHashSet<>();

        private void add(final long promptTokens, final long completionTokens, final long totalTokens,
                         final long requestCount, final BigDecimal lineAmount) {
            this.prompt += promptTokens;
            this.completion += completionTokens;
            this.total += totalTokens;
            this.requests += requestCount;
            this.amount = this.amount.add(lineAmount);
        }
    }

    /**
     * 虚拟模型（模型分组）累加器，附带其下钻的厂商模型明细。
     */
    private static final class VirtualModelAccumulator {
        private final String name;
        private final Map<String, Accumulator> models = new LinkedHashMap<>();
        private long prompt;
        private long completion;
        private long total;
        private long requests;
        private BigDecimal amount = BigDecimal.ZERO;

        private VirtualModelAccumulator(final String name) {
            this.name = name;
        }

        private void add(final long promptTokens, final long completionTokens, final long totalTokens,
                         final long requestCount, final BigDecimal lineAmount) {
            this.prompt += promptTokens;
            this.completion += completionTokens;
            this.total += totalTokens;
            this.requests += requestCount;
            this.amount = this.amount.add(lineAmount);
        }
    }

    /**
     * 用途类型累加器。
     */
    private static final class ServiceTypeAccumulator {
        private long prompt;
        private long completion;
        private long total;
        private long requests;
        private BigDecimal amount = BigDecimal.ZERO;

        /** 类型名本身已作 Map 键，累加器无需再持有 */
        private ServiceTypeAccumulator() {
        }

        private void add(final long promptTokens, final long completionTokens, final long totalTokens,
                         final long requestCount, final BigDecimal lineAmount) {
            this.prompt += promptTokens;
            this.completion += completionTokens;
            this.total += totalTokens;
            this.requests += requestCount;
            this.amount = this.amount.add(lineAmount);
        }
    }

    /**
     * 部门汇总累加器。
     */
    private static final class DepartmentAccumulator {
        private long prompt;
        private long completion;
        private long total;
        private long requests;
        private BigDecimal amount = BigDecimal.ZERO;
        private final Set<String> users = new LinkedHashSet<>();

        /** 部门名本身已作 Map 键，累加器无需再持有 */
        private DepartmentAccumulator() {
        }

        private void add(final long promptTokens, final long completionTokens, final long totalTokens,
                         final long requestCount, final BigDecimal lineAmount) {
            this.prompt += promptTokens;
            this.completion += completionTokens;
            this.total += totalTokens;
            this.requests += requestCount;
            this.amount = this.amount.add(lineAmount);
        }
    }

    /**
     * 计算单个模型的花费：{@code prompt/1000 × priceInput + completion/1000 × priceOutput}。
     *
     * @param prompt     输入 token
     * @param completion 输出 token
     * @param priceIn    输入单价（¥ / 千 token）
     * @param priceOut   输出单价（¥ / 千 token）
     * @return 花费（¥，4 位小数）
     */
    private static BigDecimal computeAmount(final long prompt, final long completion,
                                            final BigDecimal priceIn, final BigDecimal priceOut) {
        BigDecimal amount = priceIn.multiply(BigDecimal.valueOf(prompt))
                .divide(TOKENS_PER_UNIT, 8, RoundingMode.HALF_UP)
                .add(priceOut.multiply(BigDecimal.valueOf(completion))
                        .divide(TOKENS_PER_UNIT, 8, RoundingMode.HALF_UP));
        return amount.setScale(4, RoundingMode.HALF_UP);
    }
}
