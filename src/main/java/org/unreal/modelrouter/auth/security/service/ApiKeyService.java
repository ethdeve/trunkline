package org.unreal.modelrouter.auth.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchExportVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchImportRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchImportResult;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreateRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreationVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyListVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyUpdateRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyVO;
import org.unreal.modelrouter.auth.security.event.ApiKeyAuditEvent;
import org.unreal.modelrouter.auth.security.model.UsageStatistics;
import org.unreal.modelrouter.auth.security.quota.QuotaLimits;
import org.unreal.modelrouter.auth.security.util.ApiKeyHashUtil;
import org.unreal.modelrouter.common.constants.ServiceTypeConstants;
import org.unreal.modelrouter.persistence.store.StoreManager;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Primary
@Service
public class ApiKeyService {

    private final Map<String, ApiKey> apiKeyCache = new ConcurrentHashMap<>();
    private final Map<String, String> keyIdIndex = new ConcurrentHashMap<>();
    private final SecurityProperties securityProperties;

    /** 是否定期从共享存储刷新本副本 API Key 缓存（#162） */
    @Value("${jairouter.security.api-key.cache-refresh-enabled:true}")
    private boolean cacheRefreshEnabled;

    @Autowired private ApiKeyValidator apiKeyValidator;
    @Autowired private ApiKeyBatchService apiKeyBatchService;
    @Autowired private ApiKeyPersistenceService apiKeyPersistenceService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    /** 白名单与权限分类一致性校验器（2026-10-24 新增） */
    @Autowired private ApiKeyWhitelistValidator apiKeyWhitelistValidator;

    @Autowired
    public ApiKeyService(@Qualifier("jpaStoreManager") StoreManager sm,
                         ObjectMapper om, SecurityProperties sp) {
        this.securityProperties = sp;
    }

    public Mono<ApiKey> validateApiKey(String kv) { return validateApiKey(kv, null, null); }

    public Mono<ApiKey> validateApiKey(String kv, String ep, String ip) {
        return Mono.defer(() -> {
            var r = apiKeyValidator.validateFully(kv, apiKeyCache, ip);
            if (r.isSuccess()) {
                updateUsageStatistics(r.getApiKey().getKeyId(), true);
                eventPublisher.publishEvent(ApiKeyAuditEvent.used(r.getApiKey().getKeyId(), ep, ip, true));
                return Mono.just(r.getApiKey());
            }
            if (r.getKeyId() != null) {
                updateUsageStatistics(r.getKeyId(), false);
                eventPublisher.publishEvent(ApiKeyAuditEvent.used(r.getKeyId(), ep, ip, false));
            }
            String et = r.getFailureType() == ApiKeyValidator.ValidationFailureType.FORMAT_ERROR
                    ? "API_KEY_MISSING"
                    : r.getFailureType() == ApiKeyValidator.ValidationFailureType.NOT_FOUND
                    ? "API_KEY_INVALID" : "API_KEY_VALIDATION_FAILED";
            eventPublisher.publishEvent(ApiKeyAuditEvent.securityEvent(et, r.getErrorMessage(), r.getKeyId(), ip));
            return Mono.error(r.toException());
        });
    }

    public Mono<ApiKeyCreationVO> createApiKey(ApiKeyCreateRequest req) { return createApiKey(req, "system", null); }

    public Mono<ApiKeyCreationVO> createApiKey(ApiKeyCreateRequest req, String by, String ip) {
        return Mono.fromCallable(() -> {
            // issue #96：创建路径此前对 quotaAlertThreshold 完全没有校验，现与更新路径统一区间
            QuotaLimits.validateAlertThreshold(req.getQuotaAlertThreshold());
            validateLimitNonNegative("hourlyRequestLimit", req.getHourlyRequestLimit());
            validateLimitNonNegative("hourlyTokenLimit", req.getHourlyTokenLimit());
            validateLimitNonNegative("monthlyRequestLimit", req.getMonthlyRequestLimit());
            validateLimitNonNegative("monthlyTokenLimit", req.getMonthlyTokenLimit());
            String kid = req.getKeyId() != null && !req.getKeyId().isEmpty()
                    ? req.getKeyId()
                    : "key-" + UUID.randomUUID().toString().substring(0, 8);
            if (keyIdIndex.containsKey(kid)) throw new IllegalArgumentException("API Key ID已存在: " + kid);
            String kv = ApiKey.generateApiKey("sk-", 32);
            String kh = ApiKeyHashUtil.hashApiKey(kv);
            List<String> validatedPermissions = validatePermissions(req.getPermissions());
            // 白名单与权限分类一致性校验（防止配出「永远调不通」的密钥）
            apiKeyWhitelistValidator.validate(validatedPermissions, req.getAllowedGroups());
            ApiKey ak = ApiKey.builder().keyId(kid).keyHash(kh).keyPrefix("sk-").description(req.getDescription())
                .permissions(validatedPermissions)
                .allowedGroups(req.getAllowedGroups())
                .enabled(req.getEnabled() != null ? req.getEnabled() : true)
                .expiresAt(req.getExpiresAt()).createdAt(LocalDateTime.now()).createdBy(by).creatorIpAddress(ip)
                .rotationPeriodDays(req.getRotationPeriodDays() != null ? req.getRotationPeriodDays() : 0)
                .allowedIpAddresses(req.getAllowedIpAddresses())
                .dailyRequestLimit(req.getDailyRequestLimit() != null ? req.getDailyRequestLimit() : 0L)
                .dailyTokenLimit(req.getDailyTokenLimit() != null ? req.getDailyTokenLimit() : 0L)
                .hourlyRequestLimit(req.getHourlyRequestLimit() != null ? req.getHourlyRequestLimit() : 0L)
                .hourlyTokenLimit(req.getHourlyTokenLimit() != null ? req.getHourlyTokenLimit() : 0L)
                .monthlyRequestLimit(req.getMonthlyRequestLimit() != null ? req.getMonthlyRequestLimit() : 0L)
                .monthlyTokenLimit(req.getMonthlyTokenLimit() != null ? req.getMonthlyTokenLimit() : 0L)
                .rateLimitPerMinute(req.getRateLimitPerMinute() != null ? req.getRateLimitPerMinute() : 0)
                .quotaAlertThreshold(req.getQuotaAlertThreshold() != null ? req.getQuotaAlertThreshold() : 0.8)
                .usage(UsageStatistics.builder().totalRequests(0L).successfulRequests(0L).failedRequests(0L)
                    .dailyUsage(new HashMap<>()).dailyTokenUsage(new HashMap<>()).build()).build();
            apiKeyCache.put(kh, ak); keyIdIndex.put(kid, kh);
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            eventPublisher.publishEvent(ApiKeyAuditEvent.created(kid, by, ip));
            log.info("创建API Key成功: {}", kid);
            return ApiKeyCreationVO.builder().keyId(kid).keyValue(kv).description(ak.getDescription())
                .createdBy(by)
                .permissions(ak.getPermissions())
                .allowedGroups(ak.getAllowedGroups())
                .enabled(ak.isEnabled()).createdAt(ak.getCreatedAt())
                .expiresAt(ak.getExpiresAt())
                .dailyRequestLimit(ak.getDailyRequestLimit())
                .dailyTokenLimit(ak.getDailyTokenLimit())
                .hourlyRequestLimit(ak.getHourlyRequestLimit())
                .hourlyTokenLimit(ak.getHourlyTokenLimit())
                .monthlyRequestLimit(ak.getMonthlyRequestLimit())
                .monthlyTokenLimit(ak.getMonthlyTokenLimit())
                .rateLimitPerMinute(ak.getRateLimitPerMinute())
                .quotaAlertThreshold(ak.getQuotaAlertThreshold())
                .warning("密钥值只会显示一次，请妥善保存！").build();
        });
    }

    /**
     * 校验多窗口配额限额字段非负（{@code 0} 表示不限制，负数视为非法配置）。
     *
     * @param name  字段名（用于错误信息）
     * @param value 字段值，可为 {@code null}（表示不修改）
     */
    private static void validateLimitNonNegative(final String name, final Long value) {
        if (value != null && value < 0L) {
            throw new IllegalArgumentException(name + " 不能为负（0 表示不限制）");
        }
    }

    public Mono<ApiKeyVO> updateApiKey(String kid, ApiKeyUpdateRequest req) {
        return Mono.fromCallable(() -> {
            String kh = keyIdIndex.get(kid);
            if (kh == null) throw new ApiKeyNotFoundException("API Key不存在: " + kid);
            ApiKey ak = apiKeyCache.get(kh);
            if (ak == null) throw new ApiKeyNotFoundException("API Key缓存不一致: " + kid);

            // 2026-10-24：白名单与权限分类一致性校验。
            // 只拦截「本次改动新引入的」不一致项 —— 存量数据里已有的不一致
            // （历史遗留）不阻塞其它字段的编辑，避免存量密钥变成不可编辑的死数据。
            final List<String> oldPermissions = ak.getPermissions();
            final List<String> oldGroups = ak.getAllowedGroups();
            final List<String> newPermissions = req.getPermissions() != null
                    ? validatePermissions(req.getPermissions()) : oldPermissions;
            final List<String> newGroups = req.getAllowedGroups() != null
                    ? req.getAllowedGroups() : oldGroups;
            validateWhitelistDelta(oldPermissions, oldGroups, newPermissions, newGroups);

            if (req.getDescription() != null) ak.setDescription(req.getDescription());
            if (req.getEnabled() != null) ak.setEnabled(req.getEnabled());
            if (req.getExpiresAt() != null) ak.setExpiresAt(req.getExpiresAt());
            if (req.getPermissions() != null) ak.setPermissions(newPermissions);
            if (req.getAllowedGroups() != null) ak.setAllowedGroups(newGroups);
            if (req.getAllowedIpAddresses() != null) ak.setAllowedIpAddresses(req.getAllowedIpAddresses());
            if (req.getDailyRequestLimit() != null) ak.setDailyRequestLimit(req.getDailyRequestLimit());
            if (req.getRotationPeriodDays() != null) ak.setRotationPeriodDays(req.getRotationPeriodDays());
            if (req.getDailyTokenLimit() != null) ak.setDailyTokenLimit(req.getDailyTokenLimit());
            validateLimitNonNegative("hourlyRequestLimit", req.getHourlyRequestLimit());
            if (req.getHourlyRequestLimit() != null) ak.setHourlyRequestLimit(req.getHourlyRequestLimit());
            validateLimitNonNegative("hourlyTokenLimit", req.getHourlyTokenLimit());
            if (req.getHourlyTokenLimit() != null) ak.setHourlyTokenLimit(req.getHourlyTokenLimit());
            validateLimitNonNegative("monthlyRequestLimit", req.getMonthlyRequestLimit());
            if (req.getMonthlyRequestLimit() != null) ak.setMonthlyRequestLimit(req.getMonthlyRequestLimit());
            validateLimitNonNegative("monthlyTokenLimit", req.getMonthlyTokenLimit());
            if (req.getMonthlyTokenLimit() != null) ak.setMonthlyTokenLimit(req.getMonthlyTokenLimit());
            if (req.getRateLimitPerMinute() != null) ak.setRateLimitPerMinute(req.getRateLimitPerMinute());
            if (req.getQuotaAlertThreshold() != null) {
                // issue #96：更新路径与创建路径统一区间
                QuotaLimits.validateAlertThreshold(req.getQuotaAlertThreshold());
                ak.setQuotaAlertThreshold(req.getQuotaAlertThreshold());
            }
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            log.info("更新API Key成功: {}", kid);
            return convertToVO(ak);
        });
    }

    public Mono<Void> deleteApiKey(String kid) { return deleteApiKey(kid, "system"); }

    public Mono<Void> deleteApiKey(String kid, String by) {
        return Mono.fromRunnable(() -> {
            String kh = keyIdIndex.get(kid);
            if (kh == null) throw new IllegalArgumentException("API Key不存在: " + kid);
            apiKeyCache.remove(kh); keyIdIndex.remove(kid);
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            eventPublisher.publishEvent(ApiKeyAuditEvent.revoked(kid, "手动删除", by));
            log.info("删除API Key成功: {}", kid);
        });
    }

    public Mono<ApiKeyListVO> getAllApiKeysVO() {
        return Mono.fromCallable(() -> {
            List<ApiKeyVO> items = apiKeyCache.values().stream().map(this::convertToVO)
                .sorted(Comparator.comparing(ApiKeyVO::getCreatedAt).reversed()).toList();
            int en = 0, dis = 0, exp = 0; long ttr = 0, tsr = 0, tfr = 0;
            String td = LocalDateTime.now().toLocalDate().toString();
            for (ApiKey ak : apiKeyCache.values()) {
                if (ak.isEnabled()) en++; else dis++;
                if (ak.isExpired()) exp++;
                UsageStatistics us = ak.getUsage();
                if (us != null) {
                    Map<String, Long> du = us.getDailyUsage();
                    if (du != null && du.get(td) != null) ttr += du.get(td);
                    tsr += us.getSuccessfulRequests(); tfr += us.getFailedRequests();
                }
            }
            return ApiKeyListVO.builder().items(items).total(items.size())
                .enabledCount(en).disabledCount(dis).expiredCount(exp)
                .summary(ApiKeyListVO.Summary.builder().todayTotalRequests(ttr)
                    .todaySuccessfulRequests(tsr).todayFailedRequests(tfr).build()).build();
        });
    }

    public Mono<ApiKeyVO> getApiKeyByIdVO(String kid) {
        return Mono.fromCallable(() -> {
            String kh = keyIdIndex.get(kid);
            if (kh == null) throw new IllegalArgumentException("API Key不存在: " + kid);
            ApiKey ak = apiKeyCache.get(kh);
            if (ak == null) throw new IllegalArgumentException("API Key缓存不一致: " + kid);
            return convertToVO(ak);
        });
    }

    public Mono<ApiKeyVO> enableApiKey(String kid) {
        return Mono.fromCallable(() -> {
            String kh = keyIdIndex.get(kid);
            if (kh == null) throw new IllegalArgumentException("API Key不存在: " + kid);
            ApiKey ak = apiKeyCache.get(kh);
            if (ak == null) throw new IllegalArgumentException("API Key缓存不一致: " + kid);
            ak.setEnabled(true);
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            log.info("启用API Key成功: {}", kid);
            return convertToVO(ak);
        });
    }

    public Mono<ApiKeyVO> disableApiKey(String kid) {
        return Mono.fromCallable(() -> {
            String kh = keyIdIndex.get(kid);
            if (kh == null) throw new IllegalArgumentException("API Key不存在: " + kid);
            ApiKey ak = apiKeyCache.get(kh);
            if (ak == null) throw new IllegalArgumentException("API Key缓存不一致: " + kid);
            ak.setEnabled(false);
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            log.info("禁用API Key成功: {}", kid);
            return convertToVO(ak);
        });
    }

    public Mono<ApiKeyBatchExportVO> exportApiKeys() { return apiKeyBatchService.exportApiKeys(apiKeyCache); }

    public Mono<ApiKeyBatchImportResult> importApiKeys(ApiKeyBatchImportRequest req, String by, String ip) {
        return apiKeyBatchService.importApiKeys(req, apiKeyCache, keyIdIndex, by, ip);
    }

    public Mono<Integer> rotateExpiredKeys() { return apiKeyBatchService.rotateExpiredKeys(apiKeyCache, keyIdIndex); }

    public Mono<ApiKeyCreationVO> forceRotateKey(String kid, String by) {
        return apiKeyBatchService.forceRotateKey(kid, apiKeyCache, keyIdIndex, by);
    }

    public Mono<Integer> cleanupExpiredKeys() { return apiKeyBatchService.cleanupExpiredKeys(apiKeyCache); }

    public Mono<ApiKeyRotationStats> getRotationStats() {
        return apiKeyBatchService.getRotationStats(apiKeyCache);
    }

    public Mono<ApiKeyExpirationStats> getExpirationStats() {
        return apiKeyBatchService.getExpirationStats(apiKeyCache);
    }

    public boolean hasPersistedAccountConfig() { return apiKeyPersistenceService.hasPersistedAccountConfig(); }

    public void initializeApiKeyFromYaml() {
        apiKeyPersistenceService.initializeApiKeyFromYaml(apiKeyCache, keyIdIndex, loadApiKeysFromConfig());
    }

    public void loadLatestApiKeyConfig() {
        apiKeyPersistenceService.loadLatestApiKeyConfig(apiKeyCache, keyIdIndex, loadApiKeysFromConfig());
    }
    
    /**
     * 加载持久化配置并与 YAML 配置合并（YAML 优先）
     * v2.x 修复：解决 YAML 中定义的 API Key 无法生效的问题
     */
    public void loadAndMergeWithYaml() {
        apiKeyPersistenceService.loadAndMergeWithYaml(apiKeyCache, keyIdIndex, loadApiKeysFromConfig());
    }

    /**
     * 定期从共享存储刷新本副本的 API Key 缓存（跨副本最终一致，#162）。
     *
     * <p>缓存是本副本的内存镜像，只在本进程内更新：多副本下在副本 A 吊销或新建的 Key，
     * 副本 B 原先要等到重启才能感知——其间已吊销的 Key 会继续被放行。本方法让各副本在
     * {@code jairouter.security.api-key.cache-refresh-interval-seconds} 内收敛到共享存储的
     * 最新内容（含移除已删除项）。</p>
     *
     * <p><b>每个副本都必须执行</b>，因此刻意不加跨副本排他锁：加锁会让只有一个副本刷新
     * 自己的缓存，其余副本依旧停留在过期视图。</p>
     */
    @Scheduled(fixedDelayString = "${jairouter.security.api-key.cache-refresh-interval-seconds:60}",
            timeUnit = TimeUnit.SECONDS)
    public void refreshCacheFromSharedStore() {
        if (!cacheRefreshEnabled) {
            return;
        }
        int refreshed = apiKeyPersistenceService.refreshApiKeyCache(apiKeyCache, keyIdIndex);
        if (refreshed >= 0) {
            log.debug("本副本 API Key 缓存已刷新，当前 {} 个密钥", refreshed);
        }
    }

    /**
     * 更新 API Key 请求使用统计
     *
     * <p><b>线程语义（issue #262）</b>：{@code saveApiKeysToStore} 是整仓序列化 + 存储写入的
     * 阻塞调用，而本方法经 {@link #validateApiKey(String, String, String)} 由 WebFilter 认证链
     * 调用、运行在 Reactor 非阻塞线程（Netty 事件循环）上时，把工作整体交给
     * {@link reactor.core.scheduler.Schedulers#boundedElastic()} 执行后立即返回；
     * 非 Reactor 线程仍内联执行，保持“方法返回即已写入”的既有语义。</p>
     *
     * @param kid  API Key ID
     * @param succ 本次请求是否成功
     */
    public void updateUsageStatistics(String kid, boolean succ) {
        // 阻塞存储写入不得阻塞 EventLoop（issue #262）
        if (reactor.core.scheduler.Schedulers.isInNonBlockingThread()) {
            reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> doUpdateUsageStatistics(kid, succ));
            return;
        }
        doUpdateUsageStatistics(kid, succ);
    }

    /**
     * 更新 API Key 请求使用统计的实际实现（阻塞：全量写存储）.
     *
     * <p>必须运行在可阻塞线程上；由 {@link #updateUsageStatistics(String, boolean)} 负责选择执行线程。</p>
     */
    private void doUpdateUsageStatistics(String kid, boolean succ) {
        String kh = keyIdIndex.get(kid);
        if (kh == null) { log.warn("API Key不存在: {}", kid); return; }
        ApiKey ak = apiKeyCache.get(kh);
        if (ak == null) { log.warn("API Key不存在: {}", kid); return; }
        if (ak.getUsage() == null) {
            ak.setUsage(UsageStatistics.builder()
                    .dailyUsage(new HashMap<>())
                    .dailyTokenUsage(new HashMap<>())
                    .totalRequests(0L).successfulRequests(0L).failedRequests(0L)
                    .build());
        }
        UsageStatistics st = ak.getUsage(); st.incrementRequest(succ);
        apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
        log.debug("更新API Key使用统计: {} (成功: {})", kid, succ);
    }

    /**
     * 更新 API Key Token 使用量
     *
     * <p><b>线程语义（issue #115）</b>：{@code saveApiKeysToStore} 是整仓序列化 + 文件/Redis 写入的
     * 阻塞调用，而调用方位于流式 {@code doOnComplete} 等 Reactor 非阻塞线程（Netty 事件循环）上时，
     * 把工作整体交给 {@link reactor.core.scheduler.Schedulers#boundedElastic()} 执行后立即返回；
     * 非 Reactor 线程仍内联执行，保持“方法返回即已写入”的既有语义。</p>
     *
     * @param kid   API Key ID
     * @param tokens 消耗的 Token 数
     */
    public void updateTokenUsage(String kid, long tokens) {
        if (tokens <= 0) {
            return;
        }
        // 阻塞存储写入不得阻塞 EventLoop（issue #115）
        if (reactor.core.scheduler.Schedulers.isInNonBlockingThread()) {
            reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> doUpdateTokenUsage(kid, tokens));
            return;
        }
        doUpdateTokenUsage(kid, tokens);
    }

    /**
     * 更新 API Key Token 使用量的实际实现（阻塞：全量写存储）.
     *
     * <p>必须运行在可阻塞线程上；由 {@link #updateTokenUsage(String, long)} 负责选择执行线程。</p>
     */
    private void doUpdateTokenUsage(String kid, long tokens) {
        String kh = keyIdIndex.get(kid);
        if (kh == null) {
            log.warn("API Key不存在: {}", kid);
            return;
        }
        ApiKey ak = apiKeyCache.get(kh);
        if (ak == null || ak.getUsage() == null) {
            log.warn("API Key或usage不存在: {}", kid);
            return;
        }
        ak.getUsage().incrementTokens(tokens);
        apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
        log.debug("更新API Key Token使用量: {} (tokens: {})", kid, tokens);
    }

    /**
     * 重置指定 API Key 的每日配额计数器
     *
     * @param kid API Key ID
     */
    public Mono<Void> resetDailyQuota(String kid) {
        return Mono.fromRunnable(() -> {
            String kh = keyIdIndex.get(kid);
            if (kh == null) throw new IllegalArgumentException("API Key不存在: " + kid);
            ApiKey ak = apiKeyCache.get(kh);
            if (ak == null || ak.getUsage() == null) {
                throw new IllegalArgumentException("API Key或usage不存在: " + kid);
            }
            ak.getUsage().resetDaily();
            apiKeyPersistenceService.saveApiKeysToStore(apiKeyCache);
            log.info("重置API Key每日配额: {}", kid);
        });
    }

    private ApiKeyVO convertToVO(ApiKey ak) {
        ApiKeyVO vo = ApiKeyVO.builder().keyId(ak.getKeyId()).description(ak.getDescription())
            .permissions(normalizeStoredPermissions(ak.getPermissions()))
            .allowedGroups(ak.getAllowedGroups())
            .allowedIpAddresses(ak.getAllowedIpAddresses())
            .enabled(ak.isEnabled()).expired(ak.isExpired())
            .createdAt(ak.getCreatedAt()).createdBy(ak.getCreatedBy()).creatorIpAddress(ak.getCreatorIpAddress())
            .rotationPeriodDays(ak.getRotationPeriodDays()).lastRotatedAt(ak.getLastRotatedAt())
            .needsRotation(ak.needsRotation()).expiresAt(ak.getExpiresAt())
            .dailyRequestLimit(ak.getDailyRequestLimit())
            .dailyTokenLimit(ak.getDailyTokenLimit())
            .hourlyRequestLimit(ak.getHourlyRequestLimit())
            .hourlyTokenLimit(ak.getHourlyTokenLimit())
            .monthlyRequestLimit(ak.getMonthlyRequestLimit())
            .monthlyTokenLimit(ak.getMonthlyTokenLimit())
            .rateLimitPerMinute(ak.getRateLimitPerMinute())
            .quotaAlertThreshold(ak.getQuotaAlertThreshold())
            .todayRequestCount(ak.getTodayRequestCount())
            .todayTokenUsage(ak.getTodayTokenUsage())
            .quotaAlertTriggered(ak.isQuotaAlertTriggered())
            .build();
        if (ak.getUsage() != null) {
            vo.setTotalRequests(ak.getUsage().getTotalRequests());
            vo.setSuccessfulRequests(ak.getUsage().getSuccessfulRequests());
            vo.setFailedRequests(ak.getUsage().getFailedRequests());
            vo.setLastUsedAt(ak.getUsage().getLastUsedAt());
        }
        vo.calculateRemainingDays();
        return vo;
    }

    /**
     * 更新路径的白名单一致性校验（差异比对版）。
     *
     * <p><b>为什么不用绝对校验：</b>存量密钥里可能已经存在「权限与白名单分类不一致」
     * 的历史数据（本次校验上线前允许这么配）。若用绝对校验，这些密钥将完全无法编辑
     * （改个描述都会被拒），变成死数据。因此这里只拦截<b>本次改动新引入的</b>
     * 不一致项：</p>
     * <ul>
     *   <li>新加的白名单项，分类不被新权限覆盖 → 拒绝</li>
     *   <li>收窄权限导致原本一致的白名单项变得不一致 → 拒绝</li>
     *   <li>本来就存在的不一致项（本次未触碰）→ 放行，允许用户逐步清理</li>
     * </ul>
     *
     * @param oldPermissions 变更前权限
     * @param oldModels      变更前模型白名单
     * @param oldGroups      变更前分组白名单
     * @param newPermissions 变更后权限
     * @param newModels      变更后模型白名单
     * @param newGroups      变更后分组白名单
     */
    private void validateWhitelistDelta(final List<String> oldPermissions,
                                        final List<String> oldGroups,
                                        final List<String> newPermissions,
                                        final List<String> newGroups) {
        final Map<String, Set<String>> oldInvalid =
                apiKeyWhitelistValidator.findInvalidByCategory(oldPermissions, oldGroups);
        final Map<String, Set<String>> newInvalid =
                apiKeyWhitelistValidator.findInvalidByCategory(newPermissions, newGroups);
        if (newInvalid.isEmpty()) {
            return;
        }
        // 剔除「变更前就已不一致」的名字 → 只保留本次新引入的
        final Map<String, Set<String>> introduced = new LinkedHashMap<>();
        newInvalid.forEach((category, items) -> {
            final Set<String> previouslyBad = oldInvalid.get(category);
            final Set<String> fresh = new TreeSet<>();
            for (final String item : items) {
                if (previouslyBad == null || !previouslyBad.contains(item)) {
                    fresh.add(item);
                }
            }
            if (!fresh.isEmpty()) {
                introduced.put(category, fresh);
            }
        });
        if (introduced.isEmpty()) {
            return;
        }
        throw new ApiKeyWhitelistMismatchException(
                apiKeyWhitelistValidator.buildMessage(introduced, newPermissions));
    }

    /**
     * 归一化已落库的权限列表（兼容历史小写值）。
     *
     * <p>历史版本 {@code validatePermissions} 用 {@code toLowerCase()} 归一化，
     * 会把 {@code imgGen}/{@code imgEdit} 存成 {@code imggen}/{@code imgedit}，
     * 导致前端 i18n 键匹配不到、复选框回显丢失。此处统一还原为枚举规范值。</p>
     *
     * @param permissions 已落库的权限列表
     * @return 规范值权限列表（无法识别的值原样保留）
     */
    private List<String> normalizeStoredPermissions(final List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return permissions;
        }
        final List<String> normalized = new ArrayList<>(permissions.size());
        for (final String permission : permissions) {
            final String canonical = ServiceTypeConstants.ServiceType.normalizeValue(permission);
            normalized.add(canonical != null ? canonical : permission);
        }
        return normalized;
    }

    private List<ApiKey> loadApiKeysFromConfig() {
        return securityProperties.getApiKey().getKeys().stream()
            .peek(item -> apiKeyPersistenceService.initializeApiKeyFields(item)).toList();
    }

    public Map<String, ApiKey> getApiKeyCache() { return apiKeyCache; }
    public Map<String, String> getKeyIdIndex() { return keyIdIndex; }

    /**
     * 验证权限列表是否有效.
     *
     * <p>权限值必须是 ServiceTypeConstants 中定义的服务类型（如 chat, embedding 等）。
     *
     * @param permissions 权限列表
     * @return 验证后的权限列表（已过滤无效值）
     */
    public List<String> validatePermissions(final List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return permissions;
        }

        List<String> validPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (permission == null || permission.trim().isEmpty()) {
                continue;
            }

            String trimmedPermission = permission.trim();

            // 检查是否是有效的服务类型
            // 归一化到枚举规范值（chat/embedding/.../imgGen/imgEdit），
            // 不能用 toLowerCase：imgGen/imgEdit 转小写后得到 imggen/imgedit，
            // 与 ServiceTypeConstants 枚举 value 及前端 i18n 键都不一致，
            // 会导致密钥列表把权限显示成原始值，且前端复选框回显匹配不到而被静默丢弃。
            final String normalized = ServiceTypeConstants.ServiceType.normalizeValue(trimmedPermission);
            if (normalized != null) {
                validPermissions.add(normalized);
            } else if (isLegacyPermission(trimmedPermission.toLowerCase())) {
                // 处理旧权限格式，转换为全部服务类型权限
                log.warn("检测到旧权限格式 '{}'，将自动转换为全部服务类型权限。"
                    + "请更新 API Key 权限配置为服务类型（chat, embedding, rerank, tts, stt, imgGen, imgEdit）",
                    permission);
                return convertLegacyPermissionsToAllServiceTypes();
            } else {
                log.warn("忽略无效的权限值: {}", permission);
            }
        }

        return validPermissions;
    }

    /**
     * 检查是否是旧权限格式（READ, WRITE, DELETE, ADMIN）.
     *
     * @param permission 权限值
     * @return 是否是旧权限格式
     */
    private boolean isLegacyPermission(final String permission) {
        return "read".equals(permission)
            || "write".equals(permission)
            || "delete".equals(permission)
            || "admin".equals(permission);
    }

    /**
     * 将旧权限格式转换为全部服务类型权限.
     *
     * @return 全部服务类型权限列表
     */
    private List<String> convertLegacyPermissionsToAllServiceTypes() {
        return new ArrayList<>(ServiceTypeConstants.getServiceTypeList());
    }
}
