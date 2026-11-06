package org.unreal.modelrouter.auth.security.service;

import reactor.core.publisher.Mono;

/**
 * 数据同步服务接口
 *
 * <p>提供 Redis 缓存和 StoreManager 之间的数据恢复能力。<b>S2 代码清债（任务 21）</b>：
 * 原接口还声明了 {@code syncFromRedisToStoreManager} / {@code performBidirectionalSync} /
 * {@code checkDataConsistency} / {@code repairDataInconsistency} / {@code getSyncStats} /
 * {@code cleanupSyncData} 六个方法——全仓零调用方，且实现是返回 {@code success=true} 的
 * 假成功 stub（"not implemented yet"），已整体移除。要恢复这些能力，应先实现真实
 * 同步逻辑并接上消费方，再按需加回接口方法。</p>
 */
public interface DataSyncService {

    /**
     * 从StoreManager恢复数据到Redis
     * @return 恢复操作结果
     */
    Mono<SyncResult> recoverFromStoreManagerToRedis();

    /**
     * 启动时自动恢复数据（先做存储健康检查，不健康则跳过）
     * @return 恢复操作结果
     */
    Mono<SyncResult> performStartupRecovery();

    /**
     * 同步结果类
     */
    class SyncResult {
        private final boolean success;
        private final long processedCount;
        private final long successCount;
        private final long failureCount;
        private final String message;
        private final long durationMs;

        public SyncResult(final boolean success, final long processedCount, final long successCount,
                         final long failureCount, final String message, final long durationMs) {
            this.success = success;
            this.processedCount = processedCount;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.message = message;
            this.durationMs = durationMs;
        }

        // Getters
        public boolean isSuccess() {
            return success;
        }

        public long getProcessedCount() {
            return processedCount;
        }

        public long getSuccessCount() {
            return successCount;
        }

        public long getFailureCount() {
            return failureCount;
        }

        public String getMessage() {
            return message;
        }

        public long getDurationMs() {
            return durationMs;
        }

        @Override
        public String toString() {
            return String.format("SyncResult{success=%s, processed=%d, "
                            + "success=%d, failure=%d, duration=%dms, message='%s'}",
                success, processedCount, successCount, failureCount,
                durationMs, message);
        }
    }
}