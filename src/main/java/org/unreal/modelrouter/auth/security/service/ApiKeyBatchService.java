package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.audit.ExtendedSecurityAuditService;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchExportVO;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchImportRequest;
import org.unreal.modelrouter.auth.security.dto.ApiKeyBatchImportResult;
import org.unreal.modelrouter.auth.security.dto.ApiKeyCreationVO;
import org.unreal.modelrouter.auth.security.model.UsageStatistics;
import org.unreal.modelrouter.auth.security.util.ApiKeyHashUtil;
import org.unreal.modelrouter.common.constants.ServiceTypeConstants;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API Key 批量操作服务
 * 负责 API Key 的批量导入、导出、轮换和清理
 *
 * @since v2.14.1
 */
@Slf4j
@Service
public class ApiKeyBatchService {

    private static final DateTimeFormatter EXPIRES_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 审计服务（用于记录批量操作）
    @Autowired(required = false)
    private ExtendedSecurityAuditService extendedAuditService;

    // 持久化服务（用于保存变更）
    @Autowired(required = false)
    private ApiKeyPersistenceService persistenceService;

    // 白名单与权限分类一致性校验器（2026-10-24 新增）
    @Autowired
    private ApiKeyWhitelistValidator whitelistValidator;

    // O(1) 查找索引（C6 整改）
    @Autowired(required = false)
    private ApiKeyLookupIndex lookupIndex;

    // 密钥明文加密器（「我的密钥」复制能力的底层，2026-11 用户决策）
    @Autowired
    private ApiKeyPlaintextCipher plaintextCipher;

    // 审计辅助类
    private final ApiKeyAuditHelper auditHelper = new ApiKeyAuditHelper();

    /**
     * 批量导出 API Key 配置
     * 导出的数据不包含 keyValue 和 keyHash，仅包含可恢复的配置信息
     *
     * @param apiKeyCache API Key 缓存（keyHash -> ApiKey）
     * @return 导出响应 VO
     */
    public Mono<ApiKeyBatchExportVO> exportApiKeys(final Map<String, ApiKey> apiKeyCache) {
        return Mono.fromCallable(() -> {
            List<ApiKeyBatchExportVO.ExportedKey> exportedKeys = apiKeyCache.values().stream()
                    .map(this::convertToExportedKey)
                    .sorted(Comparator.comparing(ApiKeyBatchExportVO.ExportedKey::getCreatedAt).reversed())
                    .toList();

            return ApiKeyBatchExportVO.builder()
                    .exportTime(LocalDateTime.now())
                    .total(exportedKeys.size())
                    .keys(exportedKeys)
                    .build();
        });
    }

    /**
     * 批量导入 API Key
     *
     * @param request       导入请求
     * @param apiKeyCache   API Key 缓存（keyHash -> ApiKey）
     * @param keyIdIndex    API Key ID 索引（keyId -> keyHash）
     * @param importedBy    导入操作者
     * @param ipAddress     操作者 IP
     * @return 导入结果 VO
     */
    public Mono<ApiKeyBatchImportResult> importApiKeys(final ApiKeyBatchImportRequest request,
                                                        final Map<String, ApiKey> apiKeyCache,
                                                        final Map<String, String> keyIdIndex,
                                                        final String importedBy,
                                                        final String ipAddress) {
        return Mono.fromCallable(() -> {
            List<ApiKeyCreationVO> importedKeys = new ArrayList<>();
            List<ApiKeyBatchImportResult.ImportError> errors = new ArrayList<>();
            int successCount = 0;
            int failureCount = 0;

            // 如果是替换模式，先清除所有现有密钥
            if (request.getMode() == ApiKeyBatchImportRequest.ImportMode.REPLACE) {
                apiKeyCache.clear();
                keyIdIndex.clear();
                log.info("批量导入：REPLACE 模式，已清除所有现有密钥");
            }

            // 导入每个密钥
            for (ApiKeyBatchImportRequest.ApiKeyImportItem item : request.getKeys()) {
                try {
                    ApiKeyImportResult result = importSingleKey(item, apiKeyCache, keyIdIndex,
                            importedBy, ipAddress,
                            request.getMode() == ApiKeyBatchImportRequest.ImportMode.MERGE);

                    if (result.isSuccess()) {
                        importedKeys.add(result.getCreationVO());
                        successCount++;
                        log.info("批量导入成功: {}", result.getKeyId());
                    } else {
                        errors.add(ApiKeyBatchImportResult.ImportError.builder()
                                .keyId(item.getKeyId())
                                .reason(result.getErrorMessage())
                                .build());
                        failureCount++;
                        log.error("批量导入失败: {}", item.getKeyId());
                    }
                } catch (Exception e) {
                    errors.add(ApiKeyBatchImportResult.ImportError.builder()
                            .keyId(item.getKeyId())
                            .reason("导入失败: " + e.getMessage())
                            .build());
                    failureCount++;
                    log.error("批量导入失败: {}", item.getKeyId(), e);
                }
            }

            // 持久化变更
            if (persistenceService != null) {
                persistenceService.saveApiKeysToStore(apiKeyCache);
            }

            // 重建 O(1) 查找索引（导入密钥若携带明文将在此被索引；存量无明文密钥回落 O(n)）
            if (lookupIndex != null) {
                lookupIndex.rebuild(apiKeyCache);
            }

            // 记录审计
            auditHelper.auditBatchImport(extendedAuditService, importedBy, ipAddress, successCount, failureCount);

            return ApiKeyBatchImportResult.builder()
                    .totalAttempted(request.getKeys().size())
                    .successCount(successCount)
                    .failureCount(failureCount)
                    .importedKeys(importedKeys)
                    .errors(errors)
                    .build();
        });
    }

    /**
     * 轮换所有需要轮换的密钥
     *
     * @param apiKeyCache   API Key 缓存（keyHash -> ApiKey）
     * @param keyIdIndex    API Key ID 索引（keyId -> keyHash）
     * @return 轮换的密钥数量
     */
    public Mono<Integer> rotateExpiredKeys(final Map<String, ApiKey> apiKeyCache,
                                            final Map<String, String> keyIdIndex) {
        return Mono.fromCallable(() -> {
            int rotatedCount = 0;
            LocalDateTime now = LocalDateTime.now();

            for (ApiKey apiKey : apiKeyCache.values()) {
                if (apiKey.needsRotation() && apiKey.isEnabled()) {
                    try {
                        rotateKeyInternal(apiKey, apiKeyCache, keyIdIndex, now);
                        rotatedCount++;
                        log.info("自动轮换密钥: {}", apiKey.getKeyId());
                    } catch (Exception e) {
                        log.error("轮换密钥失败: {}", apiKey.getKeyId(), e);
                    }
                }
            }

            if (rotatedCount > 0 && persistenceService != null) {
                persistenceService.saveApiKeysToStore(apiKeyCache);
            }

            return rotatedCount;
        });
    }

    /**
     * 强制轮换指定密钥
     *
     * @param keyId         API Key ID
     * @param apiKeyCache   API Key 缓存
     * @param keyIdIndex    ID 索引
     * @param rotatedBy     轮换操作者
     * @return 创建响应 VO（包含新的 keyValue）
     */
    public Mono<ApiKeyCreationVO> forceRotateKey(final String keyId,
                                                  final Map<String, ApiKey> apiKeyCache,
                                                  final Map<String, String> keyIdIndex,
                                                  final String rotatedBy) {
        return Mono.fromCallable(() -> {
            String keyHash = keyIdIndex.get(keyId);
            if (keyHash == null) {
                throw new IllegalArgumentException("API Key不存在: " + keyId);
            }

            ApiKey apiKey = apiKeyCache.get(keyHash);
            if (apiKey == null) {
                throw new IllegalArgumentException("API Key缓存不一致: " + keyId);
            }

            // 生成新的 keyValue
            String originalKeyValue = ApiKey.generateApiKey("sk-", 32);
            String newKeyHash = ApiKeyHashUtil.hashApiKey(originalKeyValue);

            // 更新缓存：移除旧的 keyHash，添加新的
            apiKeyCache.remove(keyHash);
            apiKey.setKeyHash(newKeyHash);
            // 明文此刻可得：更新脱敏展示串与加密明文（2026-11 复制能力）
            apiKey.setKeyMasked(ApiKey.maskKey(originalKeyValue));
            apiKey.setKeyEncrypted(plaintextCipher.encrypt(originalKeyValue));
            apiKey.setLastRotatedAt(LocalDateTime.now());
            // 同步更新 O(1) 查找索引：先摘除旧 lookupHash，再按新 keyValue 重建
            if (lookupIndex != null) {
                lookupIndex.remove(apiKey);
                lookupIndex.attachLookupHash(apiKey, originalKeyValue);
                lookupIndex.index(apiKey);
            }
            apiKeyCache.put(newKeyHash, apiKey);
            keyIdIndex.put(keyId, newKeyHash);

            // 持久化
            if (persistenceService != null) {
                persistenceService.saveApiKeysToStore(apiKeyCache);
            }

            // 记录审计
            auditHelper.auditApiKeyRotated(extendedAuditService, keyId, rotatedBy);

            log.info("强制轮换密钥成功: {}, 操作者: {}", keyId, rotatedBy);

            // 2026-11 安全加固：操作者 ≠ 持有人时，轮换响应不回传明文（C6）；
            // 持有人登录后在「我的密钥」里查看并复制新密钥值。
            final boolean selfHeld = rotatedBy != null && rotatedBy.equals(apiKey.getHolder());
            return ApiKeyCreationVO.builder()
                    .keyId(keyId)
                    .keyValue(selfHeld ? originalKeyValue : null)  // 仅持有人本人操作时回传明文
                    .description(apiKey.getDescription())
                    .holder(apiKey.getHolder())
                    .permissions(apiKey.getPermissions())
                    .enabled(apiKey.isEnabled())
                    .createdAt(apiKey.getCreatedAt())
                    .expiresAt(apiKey.getExpiresAt())
                    .lastRotatedAt(apiKey.getLastRotatedAt())
                    .warning(selfHeld
                        ? "密钥已轮换，新的密钥值仅显示一次，请妥善保存！"
                        : "密钥已轮换并加密保存，持有人可在「我的密钥」中查看并复制新的密钥值。")
                    .build();
        });
    }

    /**
     * 清理过期密钥
     * 自动禁用已过期但尚未禁用的密钥
     *
     * @param apiKeyCache   API Key 缓存
     * @return 处理的密钥数量
     */
    public Mono<Integer> cleanupExpiredKeys(final Map<String, ApiKey> apiKeyCache) {
        return Mono.fromCallable(() -> {
            int cleanedCount = 0;

            for (ApiKey apiKey : apiKeyCache.values()) {
                // 检查是否过期且仍启用
                if (apiKey.isExpired() && apiKey.isEnabled()) {
                    try {
                        apiKey.setEnabled(false);
                        cleanedCount++;
                        log.info("自动禁用过期密钥: {}", apiKey.getKeyId());
                        auditHelper.auditApiKeyExpired(extendedAuditService, apiKey.getKeyId());
                    } catch (Exception e) {
                        log.error("禁用过期密钥失败: {}", apiKey.getKeyId(), e);
                    }
                }
            }

            if (cleanedCount > 0 && persistenceService != null) {
                persistenceService.saveApiKeysToStore(apiKeyCache);
            }

            return cleanedCount;
        });
    }

    /**
     * 获取密钥轮换统计信息
     *
     * @param apiKeyCache   API Key 缓存
     * @return 轮换统计
     */
    public Mono<ApiKeyRotationStats> getRotationStats(final Map<String, ApiKey> apiKeyCache) {
        return Mono.fromCallable(() -> {
            int totalKeys = apiKeyCache.size();
            int keysWithRotation = 0;
            int keysNeedingRotation = 0;
            int rotatedToday = 0;
            LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();

            for (ApiKey apiKey : apiKeyCache.values()) {
                if (apiKey.getRotationPeriodDays() > 0) {
                    keysWithRotation++;
                }
                if (apiKey.needsRotation()) {
                    keysNeedingRotation++;
                }
                if (apiKey.getLastRotatedAt() != null
                        && !apiKey.getLastRotatedAt().isBefore(todayStart)) {
                    rotatedToday++;
                }
            }

            return new ApiKeyRotationStats(totalKeys, keysWithRotation, keysNeedingRotation, rotatedToday);
        });
    }

    /**
     * 获取过期密钥统计信息
     *
     * @param apiKeyCache   API Key 缓存
     * @return 过期统计
     */
    public Mono<ApiKeyExpirationStats> getExpirationStats(final Map<String, ApiKey> apiKeyCache) {
        return Mono.fromCallable(() -> {
            int totalKeys = apiKeyCache.size();
            int expiredKeys = 0;
            int expiringToday = 0;
            int disabledKeys = 0;
            LocalDateTime todayEnd = LocalDateTime.now().toLocalDate().atTime(23, 59, 59);

            for (ApiKey apiKey : apiKeyCache.values()) {
                if (apiKey.isExpired()) {
                    expiredKeys++;
                } else if (apiKey.getExpiresAt() != null
                        && !apiKey.getExpiresAt().isAfter(todayEnd)) {
                    expiringToday++;
                }
                if (!apiKey.isEnabled()) {
                    disabledKeys++;
                }
            }

            return new ApiKeyExpirationStats(totalKeys, expiredKeys, expiringToday, disabledKeys);
        });
    }

    // ============ 内部方法 ============

    /**
     * 导入单个密钥
     */
    private ApiKeyImportResult importSingleKey(final ApiKeyBatchImportRequest.ApiKeyImportItem item,
                                          final Map<String, ApiKey> apiKeyCache,
                                          final Map<String, String> keyIdIndex,
                                          final String importedBy,
                                          final String ipAddress,
                                          final boolean mergeMode) {
        // 生成 keyId
        String keyId = item.getKeyId() != null && !item.getKeyId().trim().isEmpty()
                ? item.getKeyId()
                : "key-" + UUID.randomUUID().toString().substring(0, 8);

        // 检查 keyId 是否已存在（MERGE 模式）
        if (mergeMode && keyIdIndex.containsKey(keyId)) {
            return ApiKeyImportResult.failure(keyId, "API Key ID已存在");
        }

        // 生成原始 keyValue
        String originalKeyValue = ApiKey.generateApiKey("sk-", 32);
        String keyHash = ApiKeyHashUtil.hashApiKey(originalKeyValue);

        // 解析过期时间
        LocalDateTime expiresAt = parseExpiresAt(item.getExpiresAt());

        // 归一化权限
        List<String> normalizedPermissions = normalizePermissions(item.getPermissions());
        // 2026-11：导入未提供分类权限时按白名单自动推导（与单条创建路径口径一致）
        if (normalizedPermissions == null || normalizedPermissions.isEmpty()) {
            normalizedPermissions = whitelistValidator.derivePermissions(item.getAllowedGroups());
        }

        // 2026-10-24：白名单与权限分类一致性校验（导入路径同样要拦）。
        // 校验失败 → 该条导入失败（不阻断整批），错误原因回传给前端展示。
        try {
            whitelistValidator.validate(normalizedPermissions, item.getAllowedGroups());
        } catch (IllegalArgumentException e) {
            return ApiKeyImportResult.failure(keyId, e.getMessage());
        }

        // 构建 ApiKey 实体
        ApiKey apiKey = ApiKey.builder()
                .keyId(keyId)
                .keyHash(keyHash)
                .keyValue(null)
                .keyPrefix("sk-")
                // 明文此刻可得：落盘脱敏展示串与加密明文（2026-11 复制能力）
                .keyMasked(ApiKey.maskKey(originalKeyValue))
                .keyEncrypted(plaintextCipher.encrypt(originalKeyValue))
                .description(item.getDescription())
                .holder(item.getHolder())
                .permissions(normalizedPermissions)
                // 2026-10-24 修复：此前导入路径丢弃了白名单字段（DTO 有、实体没设），
                // 导致「导出→导入」后分组白名单静默丢失
                .allowedGroups(item.getAllowedGroups())
                .enabled(item.getEnabled() != null ? item.getEnabled() : true)
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now())
                .createdBy(importedBy)
                .creatorIpAddress(ipAddress)
                .rotationPeriodDays(item.getRotationPeriodDays() != null ? item.getRotationPeriodDays() : 0)
                .allowedIpAddresses(item.getAllowedIpAddresses())
                .dailyRequestLimit(item.getDailyRequestLimit() != null ? item.getDailyRequestLimit() : 0L)
                .usage(UsageStatistics.builder()
                        .totalRequests(0L)
                        .successfulRequests(0L)
                        .failedRequests(0L)
                        .dailyUsage(new HashMap<>())
                        .build())
                .build();

        // 更新缓存
        if (lookupIndex != null) {
            lookupIndex.attachLookupHash(apiKey, originalKeyValue);
        }
        apiKeyCache.put(keyHash, apiKey);
        keyIdIndex.put(keyId, keyHash);

        // 构建响应（2026-11 安全加固：操作者 ≠ 持有人时不回传明文，C6）
        final boolean selfHeld = importedBy != null && importedBy.equals(apiKey.getHolder());
        ApiKeyCreationVO creationVO = ApiKeyCreationVO.builder()
                .keyId(keyId)
                .keyValue(selfHeld ? originalKeyValue : null)  // 仅持有人本人导入时回传明文
                .description(apiKey.getDescription())
                .holder(apiKey.getHolder())
                .permissions(apiKey.getPermissions())
                .enabled(apiKey.isEnabled())
                .createdAt(apiKey.getCreatedAt())
                .expiresAt(apiKey.getExpiresAt())
                .warning(selfHeld
                    ? "密钥值只会显示一次，请妥善保存！"
                    : "密钥已导入并加密保存，持有人可在「我的密钥」中查看并复制密钥值。")
                .build();

        return ApiKeyImportResult.success(keyId, creationVO);
    }

    /**
     * 解析过期时间
     */
    private LocalDateTime parseExpiresAt(final String expiresAtStr) {
        if (expiresAtStr == null || expiresAtStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(expiresAtStr, EXPIRES_AT_FORMATTER);
        } catch (Exception e) {
            log.warn("解析过期时间失败: {}", expiresAtStr);
            return null;
        }
    }

    /**
     * 内部轮换密钥方法
     */
    private void rotateKeyInternal(final ApiKey apiKey,
                                   final Map<String, ApiKey> apiKeyCache,
                                   final Map<String, String> keyIdIndex,
                                   final LocalDateTime now) {
        String keyId = apiKey.getKeyId();
        String oldKeyHash = apiKey.getKeyHash();

        // 生成新的 keyValue
        String originalKeyValue = ApiKey.generateApiKey("sk-", 32);
        String newKeyHash = ApiKeyHashUtil.hashApiKey(originalKeyValue);

        // 更新缓存
        apiKeyCache.remove(oldKeyHash);
        apiKey.setKeyHash(newKeyHash);
        // 明文此刻可得：更新脱敏展示串与加密明文（2026-11 复制能力）
        apiKey.setKeyMasked(ApiKey.maskKey(originalKeyValue));
        apiKey.setKeyEncrypted(plaintextCipher.encrypt(originalKeyValue));
        apiKey.setLastRotatedAt(now);
        // 同步更新 O(1) 查找索引（摘除旧 lookupHash，按新 keyValue 重建）
        if (lookupIndex != null) {
            lookupIndex.remove(apiKey);
            lookupIndex.attachLookupHash(apiKey, originalKeyValue);
            lookupIndex.index(apiKey);
        }
        apiKeyCache.put(newKeyHash, apiKey);
        keyIdIndex.put(keyId, newKeyHash);
    }

    /**
     * 转换为导出格式
     */
    private ApiKeyBatchExportVO.ExportedKey convertToExportedKey(final ApiKey apiKey) {
        return ApiKeyBatchExportVO.ExportedKey.builder()
                .keyId(apiKey.getKeyId())
                .description(apiKey.getDescription())
                .permissions(apiKey.getPermissions())
                // 2026-10-24 修复：此前导出漏掉白名单字段（DTO 有、builder 没设），
                // 导致「导出→导入」往返后分组白名单丢失
                .allowedGroups(apiKey.getAllowedGroups())
                .enabled(apiKey.isEnabled())
                .createdAt(apiKey.getCreatedAt())
                .createdBy(apiKey.getCreatedBy())
                .holder(apiKey.getHolder())
                .expiresAt(apiKey.getExpiresAt())
                .allowedIpAddresses(apiKey.getAllowedIpAddresses())
                .dailyRequestLimit(apiKey.getDailyRequestLimit())
                .rotationPeriodDays(apiKey.getRotationPeriodDays())
                .lastRotatedAt(apiKey.getLastRotatedAt())
                .build();
    }

    /**
     * 归一化导入的权限列表到枚举规范值（chat/embedding/.../imgGen/imgEdit）。
     *
     * <p>导入路径不经过 {@code ApiKeyService.validatePermissions}，需在此单独归一化，
     * 否则 CSV 里写 {@code imggen} 会被原样落库，前端 i18n 键匹配不到。</p>
     *
     * @param permissions 导入项中的权限列表
     * @return 规范值权限列表（无法识别的值原样保留）
     */
    private List<String> normalizePermissions(final List<String> permissions) {
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

}