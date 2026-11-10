/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.config.service.ModelPriceQuotaLimitResolver;
import org.unreal.modelrouter.config.service.ModelPriceService;
import org.unreal.modelrouter.persistence.jpa.entity.ModelPriceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.InstanceCircuitBreakerRepository;
import org.unreal.modelrouter.persistence.jpa.repository.InstanceRateLimitRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelPriceRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelProviderRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 厂商模型维度额度与限速的执行落地测试（v3.5 Trunkline UI 改造）。
 *
 * <p>被测链路：{@code ModelPriceService}（价格与额度合一表）→
 * {@link ModelPriceQuotaLimitResolver}（{@code @Qualifier("model")} 解析器）→
 * {@link QuotaReservation#appendDimensionWithLimits(QuotaDimension, QuotaLimitSet)}
 * （判定 + 记账一次完成）。</p>
 *
 * <p>全部用真实对象 + 动态代理替身（不依赖 Mockito）：账本用
 * {@link InMemoryQuotaLedgerRepository}，价格表用手写的 {@link ModelPriceRepository} 代理。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("厂商模型维度额度与限速执行测试")
class ModelDimensionQuotaEnforcementTest {

    private static final String MODEL = "gpt-4";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 14, 13, 45, 30);

    private FakeModelPriceRepository prices;
    private ModelPriceService modelPriceService;
    private ModelPriceQuotaLimitResolver resolver;
    private QuotaLedgerService ledgerService;

    @BeforeEach
    void setUp() {
        prices = new FakeModelPriceRepository();
        modelPriceService = new ModelPriceService(
                prices.proxy(),
                emptyProxy(ModelProviderRepository.class),
                emptyProxy(ServiceInstanceRepository.class),
                emptyProxy(ServiceConfigRepository.class),
                emptyProxy(InstanceCircuitBreakerRepository.class),
                emptyProxy(InstanceRateLimitRepository.class));
        resolver = new ModelPriceQuotaLimitResolver(modelPriceService);

        final QuotaProperties properties = new QuotaProperties();
        properties.setEnabled(true);
        ledgerService = new QuotaLedgerService(
                InMemoryQuotaLedgerRepository.create().proxy(), properties,
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
    }

    // ==================== 限额解析 ====================

    @Test
    @DisplayName("解析器：返回厂商模型配置的限额；未配置的模型为不限额")
    void resolver_shouldReturnConfiguredLimits() {
        final ModelPriceUpsertRequestHolder holder = new ModelPriceUpsertRequestHolder();
        holder.dailyRequestLimit = 5L;
        holder.priceInput = new BigDecimal("1.5");
        modelPriceService.upsert(MODEL, holder.build());

        final QuotaLimitSet limits = resolver.resolve(MODEL).orElseThrow();
        assertEquals(5L, limits.dayRequests());
        assertEquals(0L, limits.dayTokens());
        assertEquals(QuotaLimitSet.UNLIMITED, resolver.resolve("not-configured").orElseThrow(),
                "未配置额度的模型应解析为不限额");
    }

    @Test
    @DisplayName("限额缓存：upsert / delete 后立即生效，无需等缓存失效")
    void limitCache_shouldRefreshOnUpsertAndDelete() {
        final ModelPriceUpsertRequestHolder holder = new ModelPriceUpsertRequestHolder();
        holder.dailyRequestLimit = 5L;
        modelPriceService.upsert(MODEL, holder.build());
        assertEquals(5L, resolver.resolve(MODEL).orElseThrow().dayRequests());

        holder.dailyRequestLimit = 9L;
        modelPriceService.upsert(MODEL, holder.build());
        assertEquals(9L, resolver.resolve(MODEL).orElseThrow().dayRequests(),
                "改额度后必须立即生效");

        modelPriceService.delete(MODEL);
        assertEquals(QuotaLimitSet.UNLIMITED, resolver.resolve(MODEL).orElseThrow(),
                "删除后应回到不限额");
    }

    // ==================== 带限额的维度追加 ====================

    @Test
    @DisplayName("厂商模型维度超限：返回超限结果，且该维度不登记（不冲正不残留）")
    void appendDimensionWithLimits_exceeded_shouldRejectWithoutResidue() {
        final QuotaLimitSet limits = new QuotaLimitSet(0L, 0L, 0L, 1L, 0L, 0L, 0L);
        // QuotaReservation 是单请求凭据（同一凭据对相同维度去重），
        // 每个模拟请求必须用独立凭据
        final QuotaReservation first =
                new QuotaReservation(ledgerService, List.of(QuotaDimension.ofApiKey("key-1")), 10L);

        assertTrue(first.appendDimensionWithLimits(QuotaDimension.ofModel(MODEL), limits).isEmpty(),
                "额度充足时应放行");
        assertEquals(1L, requestCount(QuotaDimension.ofModel(MODEL), QuotaWindow.DAY));

        final QuotaReservation second =
                new QuotaReservation(ledgerService, List.of(QuotaDimension.ofApiKey("key-1")), 10L);
        final Optional<QuotaLimitViolation> violation =
                second.appendDimensionWithLimits(QuotaDimension.ofModel(MODEL), limits);
        assertTrue(violation.isPresent(), "额度用满后应判定超限");
        assertEquals(QuotaLimitViolation.METRIC_DAILY_REQUESTS, violation.get().metric());
        assertEquals(1L, requestCount(QuotaDimension.ofModel(MODEL), QuotaWindow.DAY),
                "被拒的增量必须回滚，模型维度只保留放行的那一笔");
        assertFalse(second.dimensions().contains(QuotaDimension.ofModel(MODEL)),
                "超限维度不得登记进凭据（否则结算会对不存在的计数冲正）");
    }

    @Test
    @DisplayName("厂商模型维度不限额：只记账不判定，用量连续")
    void appendDimensionWithLimits_unlimited_shouldOnlyRecord() {
        final QuotaReservation first =
                new QuotaReservation(ledgerService, List.of(QuotaDimension.ofApiKey("key-1")), 10L);
        final QuotaReservation second =
                new QuotaReservation(ledgerService, List.of(QuotaDimension.ofApiKey("key-1")), 10L);

        assertTrue(first.appendDimensionWithLimits(
                QuotaDimension.ofModel(MODEL), QuotaLimitSet.UNLIMITED).isEmpty());
        assertTrue(second.appendDimensionWithLimits(
                QuotaDimension.ofModel(MODEL), QuotaLimitSet.UNLIMITED).isEmpty());

        assertEquals(2L, requestCount(QuotaDimension.ofModel(MODEL), QuotaWindow.DAY),
                "不限额维度仍应完整记账");
        assertTrue(first.dimensions().contains(QuotaDimension.ofModel(MODEL)));
        assertTrue(second.dimensions().contains(QuotaDimension.ofModel(MODEL)));
    }

    @Test
    @DisplayName("厂商模型维度登记后随凭据一起结算（冲正不留残量）")
    void appendedModelDimension_shouldSettleWithReservation() {
        final QuotaLimitSet limits = new QuotaLimitSet(0L, 0L, 0L, 100L, 0L, 0L, 0L);
        final QuotaReservation reservation =
                new QuotaReservation(ledgerService, List.of(QuotaDimension.ofApiKey("key-1")), 10L);

        assertTrue(reservation.appendDimensionWithLimits(QuotaDimension.ofModel(MODEL), limits).isEmpty());
        assertEquals(1L, requestCount(QuotaDimension.ofModel(MODEL), QuotaWindow.DAY));

        QuotaReservation.settleFailure(reservation);

        assertEquals(0L, requestCount(QuotaDimension.ofModel(MODEL), QuotaWindow.DAY));
        assertEquals(0L, requestCount(QuotaDimension.ofApiKey("key-1"), QuotaWindow.DAY));
    }

    // ==================== 辅助 ====================

    private long requestCount(final QuotaDimension dimension, final QuotaWindow window) {
        return ledgerService.usageStrict(dimension, window).map(QuotaUsage::requestCount).orElse(0L);
    }

    /** 空仓库代理（findAll 返回空列表，其余方法返回默认值） */
    @SuppressWarnings("unchecked")
    private static <T> T emptyProxy(final Class<T> repositoryInterface) {
        final InvocationHandler handler = (proxy, method, args) -> {
            if ("findAll".equals(method.getName()) || "findAllByEnabledTrue".equals(method.getName())) {
                return new ArrayList<>();
            }
            return defaultValue(method.getReturnType());
        };
        return (T) Proxy.newProxyInstance(repositoryInterface.getClassLoader(),
                new Class<?>[]{repositoryInterface}, handler);
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(type)) {
            return Boolean.FALSE;
        }
        if (long.class.equals(type)) {
            return 0L;
        }
        if (int.class.equals(type)) {
            return 0;
        }
        return 0;
    }

    /**
     * 手写的价格表仓库替身（动态代理，避免为 JpaRepository 的数十个方法写样板）。
     */
    private static final class FakeModelPriceRepository implements InvocationHandler {

        private final Map<String, ModelPriceEntity> rows = new LinkedHashMap<>();
        private long nextId = 1L;

        ModelPriceRepository proxy() {
            return (ModelPriceRepository) Proxy.newProxyInstance(
                    ModelPriceRepository.class.getClassLoader(),
                    new Class<?>[]{ModelPriceRepository.class}, this);
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            switch (method.getName()) {
                case "findAll":
                    return new ArrayList<>(rows.values());
                case "findAllByEnabledTrue":
                    return rows.values().stream()
                            .filter(e -> e.getEnabled() != null && e.getEnabled())
                            .toList();
                case "findByModelName":
                    return Optional.ofNullable(rows.get((String) args[0]));
                case "save": {
                    final ModelPriceEntity entity = (ModelPriceEntity) args[0];
                    if (entity.getId() == null) {
                        entity.setId(nextId++);
                    }
                    rows.put(entity.getModelName(), entity);
                    return entity;
                }
                case "delete":
                    rows.remove(((ModelPriceEntity) args[0]).getModelName());
                    return null;
                case "toString":
                    return "FakeModelPriceRepository";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }

    /**
     * 价格 + 额度写入请求的便捷构造（避免在测试里铺开全部字段）。
     */
    private static final class ModelPriceUpsertRequestHolder {
        private Long dailyRequestLimit;
        private BigDecimal priceInput;

        org.unreal.modelrouter.config.dto.ModelPriceUpsertRequest build() {
            return org.unreal.modelrouter.config.dto.ModelPriceUpsertRequest.builder()
                    .dailyRequestLimit(dailyRequestLimit)
                    .priceInput(priceInput)
                    .build();
        }
    }
}
