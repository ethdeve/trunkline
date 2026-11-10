/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 配额 Redis key 旧前缀迁移器（品牌清理：{@code jairouter:quota} → {@code trunkline:quota}）。
 *
 * <p><b>背景</b>：JAiRouter 3.x 的分布式配额计数以 {@code jairouter:quota} 为 key 前缀；品牌清理后
 * 新前缀为 {@code trunkline:quota}。为避免升级后配额"清零重计"（旧桶里的已用量丢失，用户在窗口内
 * 获得超额余量），启动期需要把旧前缀下的存量计数<b>并入</b>新前缀。</p>
 *
 * <p><b>迁移语义</b>：对每个旧前缀 key（SCAN 分批，不阻塞 keyspace）：</p>
 * <ol>
 *   <li>用 {@link RedisCounterBackend#READ_SCRIPT} 读出 {@code req:tok} 计数；</li>
 *   <li>非空时用 {@link RedisCounterBackend#INCREMENT_SCRIPT} 把计数<b>累加</b>到同后缀的新前缀 key
 *       （求和而非覆盖——若新前缀 key 已被新版实例写入过，累加才是正确合并；TTL 语义沿用累加脚本：
 *       桶不存在时按窗口保留期设置）；</li>
 *   <li>删除旧 key。读取失败（如 WRONGTYPE，非计数数据）时跳过且不删除，宁可残留也不误删。</li>
 * </ol>
 *
 * <p><b>并发与集群</b>：每条命令都是单 key 操作，Redis Cluster 下安全；多实例同时迁移同一批 key 时
 * 累加语义保证最终计数正确（最坏情况是重复累加一次正在进行的增量，幅度不超过单窗口用量）。
 * 滚动升级期间旧版本实例仍会写旧前缀 key，这部分残余增量不会被新版本读到（窗口级别的影响，
 * 旧 key 按自身 TTL 过期）；下一次重启迁移会再次清扫。热路径不做双写——默认
 * {@code trunkline.quota.distributed.enabled=false}，且升级窗口通常以分钟计，影响可接受。</p>
 *
 * <p><b>触发时机</b>：由 {@code QuotaRedisConfiguration} 装配为 ApplicationRunner，在应用就绪前
 * 执行一次；Redis 不可用时记录告警并跳过（不影响启动，下次启动重试）。通过
 * {@code trunkline.quota.distributed.legacy-key-prefix}（置空）可关闭迁移。</p>
 *
 * @author Trunkline Team
 * @modified Trunkline Team 品牌清理新增：旧前缀配额计数一次性迁移
 */
@Slf4j
public final class QuotaRedisKeyMigrator {

    /** 读数脚本（与 {@link RedisCounterBackend#READ} 相同语义） */
    private static final RedisScript<String> READ =
        RedisScript.of(RedisCounterBackend.READ_SCRIPT, String.class);

    /** 累加脚本（与 {@link RedisCounterBackend#INCREMENT} 相同语义） */
    private static final RedisScript<String> INCREMENT =
        RedisScript.of(RedisCounterBackend.INCREMENT_SCRIPT, String.class);

    /** SCAN 单批数量（与 RedisCounterBackend 一致） */
    private static final int SCAN_BATCH = 256;

    /** 单条命令超时（迁移在启动期执行，可比热路径宽松） */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10L);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final QuotaProperties properties;

    /**
     * 构造迁移器。
     *
     * @param redisTemplate String 序列化的 Reactive Redis 模板，不能为 {@code null}
     * @param properties    账本配置（新旧 key 前缀与保留期）
     */
    public QuotaRedisKeyMigrator(final ReactiveRedisTemplate<String, String> redisTemplate,
                                 final QuotaProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate 不能为 null");
        this.properties = Objects.requireNonNull(properties, "properties 不能为 null");
    }

    /**
     * 执行一次迁移：把旧前缀下的存量计数并入新前缀，返回迁移的 key 数量。
     *
     * <p>无旧前缀配置（未配置 / 显式置空 / 与新前缀相同）时直接返回 0。</p>
     *
     * @return 处理的旧 key 数量（含读取失败跳过的）
     */
    public Mono<Long> migrate() {
        final String legacyPrefix = properties.distributedLegacyKeyPrefix();
        if (legacyPrefix == null) {
            return Mono.just(0L);
        }
        final String newPrefix = properties.distributedKeyPrefix();
        final RedisCounterBackend backend = new RedisCounterBackend(redisTemplate, properties);
        final ScanOptions options = ScanOptions.scanOptions()
            .match(legacyPrefix + ":*").count(SCAN_BATCH).build();
        return redisTemplate.scan(options)
            .concatMap(legacyKey -> migrateOne(legacyKey, legacyPrefix, newPrefix, backend)
                .onErrorResume(e -> {
                    log.warn("配额旧前缀 key 迁移失败，跳过: key={}, 原因={}", legacyKey, e.getMessage());
                    return Mono.just(0L);
                }))
            .reduce(0L, Long::sum)
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("配额 Redis key 旧前缀迁移完成: {} → {}, 共 {} 个 key",
                        legacyPrefix, newPrefix, count);
                }
            });
    }

    /**
     * 迁移单个旧 key：读旧计数 → 累加进新 key → 删除旧 key。
     *
     * @param legacyKey    旧前缀 key
     * @param legacyPrefix 旧前缀
     * @param newPrefix    新前缀
     * @param backend      计数后端（用于按窗口计算 TTL）
     * @return 处理数量（0 或 1）
     */
    private Mono<Long> migrateOne(final String legacyKey,
                                  final String legacyPrefix,
                                  final String newPrefix,
                                  final RedisCounterBackend backend) {
        final String newKey = newPrefix + legacyKey.substring(legacyPrefix.length());
        return redisTemplate.execute(READ, List.of(legacyKey), List.of())
            .next()
            .defaultIfEmpty("")
            .flatMap(payload -> {
                final Mono<Long> delete = redisTemplate.delete(Flux.just(legacyKey));
                final long[] values = parseValues(payload);
                if (values == null) {
                    // 桶为空或读取不到字段：仅删除旧 key（避免空壳残留）
                    return delete.map(ok -> 1L);
                }
                final long ttl = ttlFor(legacyKey, backend);
                return redisTemplate.execute(INCREMENT, List.of(newKey),
                        List.of(String.valueOf(values[0]), String.valueOf(values[1]), String.valueOf(ttl)))
                    .next()
                    .then(delete)
                    .map(ok -> 1L);
            });
    }

    /**
     * 解析 READ 脚本返回值（{@code "req:tok"}）；空串或非法格式返回 {@code null}。
     */
    private static long[] parseValues(final String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        final int separator = payload.indexOf(':');
        if (separator < 0) {
            return null;
        }
        try {
            return new long[]{Long.parseLong(payload.substring(0, separator).trim()),
                Long.parseLong(payload.substring(separator + 1).trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 key 中解析窗口名并计算保留 TTL；解析失败兜底 1 天。
     */
    private long ttlFor(final String legacyKey, final RedisCounterBackend backend) {
        final String[] segments = legacyKey.split(":");
        if (segments.length >= 2) {
            try {
                return backend.ttlSeconds(QuotaWindow.valueOf(segments[segments.length - 2]));
            } catch (RuntimeException e) {
                log.debug("旧 key 窗口段无法解析，按 1 天 TTL 迁移: {}", legacyKey);
            }
        }
        return 86400L;
    }
}
