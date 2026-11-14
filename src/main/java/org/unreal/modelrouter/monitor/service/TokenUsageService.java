package org.unreal.modelrouter.monitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.unreal.modelrouter.billing.price.PriceQuote;
import org.unreal.modelrouter.billing.price.PriceResolver;
import org.unreal.modelrouter.enterprise.config.TenantContextHolder;
import org.unreal.modelrouter.enterprise.spi.BillingProvider;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.monitor.dto.TokenUsageStatisticsDTO;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 使用量统计服务
 * 提供 Token 使用量的记录、查询和统计功能
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageRepository tokenUsageRepository;

    /**
     * 价格解析 SPI（O1 成交价快照 + O2 未定价 ≠ ¥0）。
     *
     * <p>落库时点取价固化进快照列；社区版默认 {@code CommunityPriceResolver}（恒空价，
     * 用量按「待定价」记录），商业版可覆盖为阶梯 / 时段 / 议价策略。取不到价 →
     * {@code priceMissing=true} 单列「待定价」，<b>绝不静默按 ¥0</b>。</p>
     */
    private final PriceResolver priceResolver;

    /**
     * 商业计费扩展点（O7-b 计费接线）。OSS 侧 {@code NoopBillingProvider} 恒注册（只记账不扣费），
     * 企业 jar 注入真实实现后自动接管。判空仅为无 Spring 装配的单元测试兜底。
     */
    private final BillingProvider billingProvider;

    /**
     * 记录 Token 使用量
     *
     * @param record Token 使用量记录
     */
    @Transactional
    public void recordTokenUsage(TokenUsageRecordDTO record) {
        try {
            // O1 成交价快照：DTO 显式携带的快照优先（企业链路透传），否则落库时点取价固化。
            // 计价键与账单口径一致：instance_name 优先，回落 model_name。
            final PriceSnapshot snapshot = resolvePriceSnapshot(record);
            TokenUsageEntity entity = TokenUsageEntity.builder()
                    .traceId(record.getTraceId())
                    .serviceType(record.getServiceType())
                    .modelName(record.getModelName())
                    .virtualModel(record.getVirtualModel())
                    .provider(record.getProvider())
                    .instanceName(record.getInstanceName())
                    .instanceUrl(record.getInstanceUrl())
                    .promptTokens(record.getPromptTokens() != null ? record.getPromptTokens() : 0L)
                    .completionTokens(record.getCompletionTokens() != null ? record.getCompletionTokens() : 0L)
                    .totalTokens(record.getTotalTokens() != null ? record.getTotalTokens() : 0L)
                    .apiKeyId(record.getApiKeyId())
                    .userId(record.getUserId())
                    .clientIp(record.getClientIp())
                    .isSuccess(record.getIsSuccess())
                    .errorCode(record.getErrorCode())
                    .errorMessage(record.getErrorMessage())
                    .responseTimeMs(record.getResponseTimeMs())
                    .occurredAt(record.getOccurredAt() != null ? record.getOccurredAt() : LocalDateTime.now())
                    .metadata(record.getMetadata())
                    .priceInputSnapshot(snapshot.priceInput())
                    .priceOutputSnapshot(snapshot.priceOutput())
                    .priceVersion(snapshot.version())
                    .priceMissing(snapshot.missing())
                    .build();

            tokenUsageRepository.save(entity);

            // O7-b 计费接线：事务提交后扣费（见 registerBillingAfterCommit 注释）
            registerBillingAfterCommit(record, snapshot);

            log.debug("Token usage recorded: model={}, totalTokens={}, serviceType={}, priceMissing={}",
                    record.getModelName(), record.getTotalTokens(), record.getServiceType(), snapshot.missing());
        } catch (Exception e) {
            log.error("Failed to record token usage", e);
        }
    }

    /**
     * O7-b 计费接线：在<b>主事务提交后</b>向 {@link BillingProvider} 上报扣费。
     *
     * <p>为什么不在事务体内扣费：① 事务内若 charge 内部触发 JPA 持久化异常，事务被标记
     * rollback-only，提交时抛 {@code UnexpectedRollbackException}，请求照样失败；② 事务内
     * 扣费与落库不同步（用量已提交、扣费丢失），对账必差。afterCommit 时主事务已提交，
     * 「降级不阻断」才是真话。</p>
     *
     * <p>未定价 ≠ ¥0（O7-b 验收）：{@code priceMissing} 或快照不完整（半价快照）的用量
     * <b>不上报</b>，按待定价留在账本（账单侧已单列），绝不按 0 元扣费。</p>
     */
    private void registerBillingAfterCommit(final TokenUsageRecordDTO record, final PriceSnapshot snapshot) {
        if (billingProvider == null) {
            return;
        }
        if (snapshot.missing() || snapshot.priceInput() == null || snapshot.priceOutput() == null) {
            log.info("本笔用量待定价（价格缺失或快照不完整），跳过计费上报: model={}, instanceName={}",
                    record.getModelName(), record.getInstanceName());
            return;
        }
        final BillingProvider.BillingEntry entry = toBillingEntry(record, snapshot);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    chargeSafely(entry);
                }
            });
        } else {
            // 无事务上下文（单测 / 非事务调用点）：直接扣费
            chargeSafely(entry);
        }
    }

    /** charge 失败只允许降级记日志，绝不向上抛（扣费失败不阻断已成功的调用）。 */
    private void chargeSafely(final BillingProvider.BillingEntry entry) {
        try {
            billingProvider.charge(entry);
        } catch (Exception e) {
            log.warn("计费扣减失败，已降级放行: username={}, model={}, cost={}, cause={}",
                    entry.username(), entry.model(), entry.cost(), e.getMessage());
        }
    }

    /**
     * 构造计费条目：cost 直接取 O1 成交价快照计算（企业链路显式快照优先的议价结果同样生效），
     * tenantId 从 {@link TenantContextHolder} 透传，model 与账单聚合口径一致（instance_name 回落 model_name）。
     */
    private BillingProvider.BillingEntry toBillingEntry(final TokenUsageRecordDTO record, final PriceSnapshot snapshot) {
        final long prompt = record.getPromptTokens() != null ? record.getPromptTokens() : 0L;
        final long completion = record.getCompletionTokens() != null ? record.getCompletionTokens() : 0L;
        final long total = record.getTotalTokens() != null ? record.getTotalTokens() : prompt + completion;
        // cost = (prompt×输入价 + completion×输出价) / 1000（单价按千 token 计价，与账单 computeAmount 口径一致）
        final BigDecimal cost = snapshot.priceInput().multiply(BigDecimal.valueOf(prompt))
                .add(snapshot.priceOutput().multiply(BigDecimal.valueOf(completion)))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        final String pricingKey = record.getInstanceName() != null && !record.getInstanceName().isBlank()
                ? record.getInstanceName() : record.getModelName();
        // B2：traceId 是真实账本的幂等键，必须与落库的 token_usage.trace_id 同源，
        // 否则调用方重试 / 事务补偿会导致同一笔用量被重复扣费（3.2 #8a）。
        return new BillingProvider.BillingEntry(
                record.getTraceId(), TenantContextHolder.currentTenantId(),
                record.getUserId(), pricingKey, cost, total);
    }

    /**
     * 成交价快照解析结果（内部载体）。
     */
    private record PriceSnapshot(java.math.BigDecimal priceInput,
                                 java.math.BigDecimal priceOutput,
                                 String version,
                                 boolean missing) {
    }

    /**
     * 解析成交价快照：显式快照优先 → PriceResolver 取价 → 取不到则标缺失（未定价 ≠ ¥0）。
     */
    private PriceSnapshot resolvePriceSnapshot(final TokenUsageRecordDTO record) {
        // 1) 调用方显式携带的快照优先。P0-5：必须两侧同时齐全，半价快照按「未定价」处理，
        //    绝不按 0 补齐（completionTokens * NULL 在 SQL 里会变 NULL，金额失真）。
        if (record.getPriceInputSnapshot() != null && record.getPriceOutputSnapshot() != null) {
            return new PriceSnapshot(record.getPriceInputSnapshot(), record.getPriceOutputSnapshot(),
                    record.getPriceVersion() != null ? record.getPriceVersion() : "explicit", false);
        }
        // 2) 落库时点取价：计价键 = instance_name（厂商模型）优先，回落 model_name（与账单聚合口径一致）
        final String pricingKey = record.getInstanceName() != null && !record.getInstanceName().isBlank()
                ? record.getInstanceName() : record.getModelName();
        try {
            // B1 / P0-6：租户 ID 随取价上下文进入 PriceResolver，企业实现可按 (tenant_id, model_name)
            // 命中租户专属价并回落 default 公共价；社区实现走 default 方法委托旧签名，行为不变。
            final PriceQuote quote = priceResolver != null
                    ? priceResolver.resolve(PriceResolver.PricingContext.of(
                            TenantContextHolder.currentTenantId(), pricingKey)).orElse(null)
                    : null;
            if (quote != null) {
                return new PriceSnapshot(quote.priceInput(), quote.priceOutput(), quote.version(), false);
            }
        } catch (Exception e) {
            // 取价异常不阻断计量（O2：计量层永不阻断），按缺失处理并告警
            log.warn("价格解析失败，本笔用量按待定价处理: model={}, instanceName={}, cause={}",
                    record.getModelName(), record.getInstanceName(), e.getMessage());
        }
        // 3) 未定价：标记缺失，账单侧单列「待定价」——绝不静默 ¥0
        return new PriceSnapshot(null, null, null, true);
    }

    /**
     * 批量记录 Token 使用量
     *
     * @param records Token 使用量记录列表
     */
    @Transactional
    public void recordTokenUsageBatch(List<TokenUsageRecordDTO> records) {
        for (TokenUsageRecordDTO record : records) {
            recordTokenUsage(record);
        }
    }

    /**
     * 获取 Token 使用量统计信息
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 统计信息 DTO
     */
    @Transactional(readOnly = true)
    public TokenUsageStatisticsDTO getTokenUsageStatistics(LocalDateTime startTime, LocalDateTime endTime) {
        // 设置默认时间范围
        LocalDateTime effectiveStartTime = startTime != null ? startTime : LocalDateTime.now().minusDays(7);
        LocalDateTime effectiveEndTime = endTime != null ? endTime : LocalDateTime.now();

        // 构建统计 DTO
        TokenUsageStatisticsDTO dto = TokenUsageStatisticsDTO.builder()
                .startTime(effectiveStartTime)
                .endTime(effectiveEndTime)
                .build();

        // 基本统计
        // issue #714：totalRequests（成功率分母）改用同窗口计数。修复前用 countAllUsage()
        // 统计全表，窗口远小于全表时成功率被严重稀释（全表 1000 / 窗口 10 / 成功 7 → 旧 0.7%、新 70%）。
        dto.setTotalRequests(tokenUsageRepository.countByTimeRange(effectiveStartTime, effectiveEndTime));
        dto.setTotalTokens(tokenUsageRepository.countTotalTokensByTimeRange(effectiveStartTime, effectiveEndTime));
        dto.setSuccessfulRequests(tokenUsageRepository.countSuccessByTimeRange(effectiveStartTime, effectiveEndTime));
        dto.setFailedRequests(tokenUsageRepository.countFailedByTimeRange(effectiveStartTime, effectiveEndTime));
        dto.setAvgResponseTimeMs(tokenUsageRepository.avgResponseTimeByTimeRange(effectiveStartTime, effectiveEndTime));

        // 计算输入输出 token（需要单独查询）
        List<Object[]> tokenDetails =
                tokenUsageRepository.countTokensByServiceType(effectiveStartTime, effectiveEndTime);
        long totalPrompt = 0L;
        long totalCompletion = 0L;
        for (Object[] row : tokenDetails) {
            totalPrompt += (Long) row[2];
            totalCompletion += (Long) row[3];
        }
        dto.setTotalPromptTokens(totalPrompt);
        dto.setTotalCompletionTokens(totalCompletion);

        // 计算成功率
        if (dto.getTotalRequests() > 0) {
            dto.setSuccessRate((double) dto.getSuccessfulRequests() / dto.getTotalRequests() * 100);
        } else {
            dto.setSuccessRate(0.0);
        }

        // 按模型统计
        List<Object[]> byModel = tokenUsageRepository.countTokensByModel(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.ModelTokenStats> modelStats = new ArrayList<>();
        for (Object[] row : byModel) {
            String modelName = (String) row[0];
            Long totalTokens = (Long) row[1];
            Long promptTokens = (Long) row[2];
            Long completionTokens = (Long) row[3];
            Long requestCount = (Long) row[4];
            Double avgTokensPerRequest = requestCount > 0 ? (double) totalTokens / requestCount : 0.0;

            modelStats.add(new TokenUsageStatisticsDTO.ModelTokenStats(
                    modelName, totalTokens, promptTokens, completionTokens, requestCount, avgTokensPerRequest));
        }
        dto.setByModel(modelStats);

        // 按服务类型统计
        List<Object[]> byServiceType =
                tokenUsageRepository.countTokensByServiceType(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.ServiceTypeStats> serviceTypeStats = new ArrayList<>();
        for (Object[] row : byServiceType) {
            String serviceType = (String) row[0];
            Long totalTokens = (Long) row[1];
            Long promptTokens = (Long) row[2];
            Long completionTokens = (Long) row[3];
            Long requestCount = (Long) row[4];

            serviceTypeStats.add(new TokenUsageStatisticsDTO.ServiceTypeStats(
                    serviceType, totalTokens, promptTokens, completionTokens, requestCount));
        }
        dto.setByServiceType(serviceTypeStats);

        // 按提供商统计
        List<Object[]> byProvider = tokenUsageRepository.countTokensByProvider(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.ProviderStats> providerStats = new ArrayList<>();
        for (Object[] row : byProvider) {
            String provider = (String) row[0];
            Long totalTokens = (Long) row[1];
            Long requestCount = (Long) row[2];

            providerStats.add(new TokenUsageStatisticsDTO.ProviderStats(provider, totalTokens, requestCount));
        }
        dto.setByProvider(providerStats);

        // 按日期统计
        List<Object[]> byDay = tokenUsageRepository.countTokensByDay(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.DailyStats> dailyStats = new ArrayList<>();
        for (Object[] row : byDay) {
            String date = (String) row[0];
            Long totalTokens = (Long) row[1];
            Long promptTokens = (Long) row[2];
            Long completionTokens = (Long) row[3];
            Long requestCount = (Long) row[4];

            dailyStats.add(new TokenUsageStatisticsDTO.DailyStats(
                    date, totalTokens, promptTokens, completionTokens, requestCount));
        }
        dto.setByDay(dailyStats);

        // 按周统计
        List<Object[]> byWeek = tokenUsageRepository.countTokensByWeek(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.WeeklyStats> weeklyStats = new ArrayList<>();
        for (Object[] row : byWeek) {
            Integer year = (Integer) row[0];
            Integer week = (Integer) row[1];
            Long totalTokens = (Long) row[2];
            Long promptTokens = (Long) row[3];
            Long completionTokens = (Long) row[4];
            Long requestCount = (Long) row[5];
            String weekLabel = year + "-W" + String.format("%02d", week);

            weeklyStats.add(new TokenUsageStatisticsDTO.WeeklyStats(
                    year, week, totalTokens, promptTokens, completionTokens, requestCount, weekLabel));
        }
        dto.setByWeek(weeklyStats);

        // 按月统计
        List<Object[]> byMonth = tokenUsageRepository.countTokensByMonth(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.MonthlyStats> monthlyStats = new ArrayList<>();
        for (Object[] row : byMonth) {
            Integer year = (Integer) row[0];
            Integer month = (Integer) row[1];
            Long totalTokens = (Long) row[2];
            Long promptTokens = (Long) row[3];
            Long completionTokens = (Long) row[4];
            Long requestCount = (Long) row[5];
            String monthLabel = year + "-" + String.format("%02d", month);

            monthlyStats.add(new TokenUsageStatisticsDTO.MonthlyStats(
                    year, month, totalTokens, promptTokens, completionTokens, requestCount, monthLabel));
        }
        dto.setByMonth(monthlyStats);

        // 按小时统计
        List<Object[]> byHour = tokenUsageRepository.countTokensByHour(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.HourlyStats> hourlyStats = new ArrayList<>();
        for (Object[] row : byHour) {
            Integer hour = (Integer) row[0];
            Long totalTokens = (Long) row[1];
            Long requestCount = (Long) row[2];
            String hourLabel = String.format("%02d:00", hour);

            hourlyStats.add(new TokenUsageStatisticsDTO.HourlyStats(hour, totalTokens, requestCount, hourLabel));
        }
        dto.setByHour(hourlyStats);

        // 按 API Key 统计（可选）
        List<Object[]> byApiKey = tokenUsageRepository.countTokensByApiKey(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.ApiKeyStats> apiKeyStats = new ArrayList<>();
        for (Object[] row : byApiKey) {
            String apiKeyId = (String) row[0];
            Long totalTokens = (Long) row[1];
            Long requestCount = (Long) row[2];

            apiKeyStats.add(new TokenUsageStatisticsDTO.ApiKeyStats(apiKeyId, totalTokens, requestCount));
        }
        dto.setByApiKey(apiKeyStats);

        // 按用户统计（可选）
        List<Object[]> byUser = tokenUsageRepository.countTokensByUser(effectiveStartTime, effectiveEndTime);
        List<TokenUsageStatisticsDTO.UserStats> userStats = new ArrayList<>();
        for (Object[] row : byUser) {
            String userId = (String) row[0];
            Long totalTokens = (Long) row[1];
            Long requestCount = (Long) row[2];

            userStats.add(new TokenUsageStatisticsDTO.UserStats(userId, totalTokens, requestCount));
        }
        dto.setByUser(userStats);

        return dto;
    }

    /**
     * 获取最近的使用记录
     *
     * @param limit 最大数量
     * @return 使用记录列表
     */
    @Transactional(readOnly = true)
    public List<TokenUsageEntity> getRecentUsage(int limit) {
        return tokenUsageRepository.findRecentUsage(Math.min(limit, 100));
    }

    /**
     * 获取指定模型的最近使用记录
     *
     * @param modelName 模型名称
     * @param limit     最大数量
     * @return 使用记录列表
     */
    @Transactional(readOnly = true)
    public List<TokenUsageEntity> getRecentUsageByModel(String modelName, int limit) {
        return tokenUsageRepository.findRecentUsageByModel(modelName, Math.min(limit, 100));
    }

    /**
     * 删除过期使用记录
     *
     * @param cutoffTime 截止时间
     * @return 删除的数量
     */
    @Transactional
    public int deleteOldUsageRecords(LocalDateTime cutoffTime) {
        log.info("删除截止时间 {} 之前的 Token 使用记录", cutoffTime);
        return tokenUsageRepository.deleteByOccurredAtBefore(cutoffTime);
    }

    /**
     * 获取模型使用量排名
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     最大数量
     * @return 模型使用量排名
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTopModels(LocalDateTime startTime, LocalDateTime endTime, int limit) {
        List<Object[]> results = tokenUsageRepository.countTokensByModel(startTime, endTime);
        List<Map<String, Object>> topModels = new ArrayList<>();

        for (int i = 0; i < Math.min(limit, results.size()); i++) {
            Object[] row = results.get(i);
            Map<String, Object> modelInfo = new HashMap<>();
            modelInfo.put("rank", i + 1);
            modelInfo.put("modelName", row[0]);
            modelInfo.put("totalTokens", row[1]);
            modelInfo.put("promptTokens", row[2]);
            modelInfo.put("completionTokens", row[3]);
            modelInfo.put("requestCount", row[4]);
            topModels.add(modelInfo);
        }

        return topModels;
    }

    /**
     * 获取服务类型使用量排名
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     最大数量
     * @return 服务类型使用量排名
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTopServiceTypes(LocalDateTime startTime, LocalDateTime endTime, int limit) {
        List<Object[]> results = tokenUsageRepository.countTokensByServiceType(startTime, endTime);
        List<Map<String, Object>> topServiceTypes = new ArrayList<>();

        for (int i = 0; i < Math.min(limit, results.size()); i++) {
            Object[] row = results.get(i);
            Map<String, Object> serviceInfo = new HashMap<>();
            serviceInfo.put("rank", i + 1);
            serviceInfo.put("serviceType", row[0]);
            serviceInfo.put("totalTokens", row[1]);
            serviceInfo.put("promptTokens", row[2]);
            serviceInfo.put("completionTokens", row[3]);
            serviceInfo.put("requestCount", row[4]);
            topServiceTypes.add(serviceInfo);
        }

        return topServiceTypes;
    }
}
