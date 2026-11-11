/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.config.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.dto.ModelPriceDTO;
import org.unreal.modelrouter.config.dto.ModelPriceCatalogItemDTO;
import org.unreal.modelrouter.config.dto.ModelPriceUpsertRequest;
import org.unreal.modelrouter.persistence.jpa.entity.ModelPriceEntity;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceInstanceEntity;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型「缓存单价」字段的落库 / 回显测试（接入管理「模型管理」单价列新增缓存档）。
 *
 * <p>被测链路：{@code ModelPriceService.upsert}（含负值归零）→ {@code toDTO}（listAll 回显）
 * → {@code buildItem}（catalog 回显，未配置时默认 0）。全部用真实 Service + 手写动态代理仓库替身，
 * 不依赖 Spring 上下文与数据库。</p>
 *
 * <p>口径说明：缓存单价本次仅「可配置 / 可回显」，{@code token_usage} 尚无缓存 token 计量维度，
 * 账单计算不读取本列——本测试确保这一新字段在配置链路里完好往返，不涉及账单。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("模型缓存单价配置测试")
class ModelPriceCacheTest {

    private static final String MODEL = "claude-3-5-sonnet";

    private FakeModelPriceRepository prices;
    private FakeInstanceRepository instances;
    private FakeLookupRepository providers;
    private FakeLookupRepository configs;
    private ModelPriceService service;

    @BeforeEach
    void setUp() {
        prices = new FakeModelPriceRepository();
        instances = new FakeInstanceRepository();
        providers = FakeLookupRepository.provider();
        configs = FakeLookupRepository.config();
        service = new ModelPriceService(
                prices.proxy(),
                providers.proxy(ModelProviderRepository.class),
                instances.proxy(),
                configs.proxy(ServiceConfigRepository.class),
                emptyProxy(InstanceCircuitBreakerRepository.class),
                emptyProxy(InstanceRateLimitRepository.class));
    }

    @Test
    @DisplayName("upsert 写入三档单价，listAll 原值回显缓存价")
    void upsert_shouldPersistAndExposeCachePrice() {
        service.upsert(MODEL, ModelPriceUpsertRequest.builder()
                .priceInput(new BigDecimal("3.000000"))
                .priceOutput(new BigDecimal("15.000000"))
                .priceCache(new BigDecimal("0.300000"))
                .build());

        final ModelPriceDTO dto = service.listAll().stream()
                .filter(d -> MODEL.equals(d.getModelName()))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3.000000").compareTo(dto.getPriceInput()));
        assertEquals(0, new BigDecimal("15.000000").compareTo(dto.getPriceOutput()));
        assertEquals(0, new BigDecimal("0.300000").compareTo(dto.getPriceCache()),
                "缓存单价应原样落库并回显");
    }

    @Test
    @DisplayName("不传缓存价新增行时默认 0，且不影响输入/输出价")
    void upsert_withoutCachePrice_shouldDefaultToZero() {
        service.upsert(MODEL, ModelPriceUpsertRequest.builder()
                .priceInput(new BigDecimal("1.5"))
                .priceOutput(new BigDecimal("2.5"))
                .build());

        final ModelPriceDTO dto = service.listAll().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(dto.getPriceCache()), "未配置缓存价应为 0");
        assertEquals(0, new BigDecimal("1.5").compareTo(dto.getPriceInput()));
        assertEquals(0, new BigDecimal("2.5").compareTo(dto.getPriceOutput()));
    }

    @Test
    @DisplayName("负缓存价归零，防止写入负数")
    void upsert_negativeCachePrice_shouldClampToZero() {
        service.upsert(MODEL, ModelPriceUpsertRequest.builder()
                .priceCache(new BigDecimal("-0.000001"))
                .build());

        final ModelPriceDTO dto = service.listAll().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(dto.getPriceCache()), "负缓存价应被归零");
    }

    @Test
    @DisplayName("已有行只改缓存价时，输入/输出价保持不变（部分更新语义）")
    void upsert_cacheOnly_shouldKeepOtherPrices() {
        service.upsert(MODEL, ModelPriceUpsertRequest.builder()
                .priceInput(new BigDecimal("3.00"))
                .priceOutput(new BigDecimal("15.00"))
                .priceCache(new BigDecimal("0.30"))
                .build());
        service.upsert(MODEL, ModelPriceUpsertRequest.builder()
                .priceCache(new BigDecimal("0.35"))
                .build());

        final ModelPriceDTO dto = service.listAll().get(0);
        assertEquals(0, new BigDecimal("3.00").compareTo(dto.getPriceInput()), "输入价不应被改动");
        assertEquals(0, new BigDecimal("15.00").compareTo(dto.getPriceOutput()), "输出价不应被改动");
        assertEquals(0, new BigDecimal("0.35").compareTo(dto.getPriceCache()), "缓存价应更新为新值");
    }

    @Test
    @DisplayName("catalog 中缓存价随价格行回显；目录含已接入模型且无价格行时缓存价为 0")
    void catalog_shouldExposeCachePriceOrZero() {
        // 已接入一个模型实例（无对应价格行）
        final ServiceInstanceEntity instance = ServiceInstanceEntity.builder()
                .instanceName(MODEL)
                .providerId(1L)
                .serviceConfigId(100L)
                .build();
        instance.setId(10L);
        instances.add(instance);
        providers.add(1L, "Anthropic");
        configs.add(100L, "chat");

        final List<ModelPriceCatalogItemDTO> items = service.catalog();
        assertEquals(1, items.size());
        final ModelPriceCatalogItemDTO noPrice = items.get(0);
        assertTrue(Boolean.FALSE.equals(noPrice.getHasPrice()));
        assertNotNull(noPrice.getPriceCache());
        assertEquals(0, BigDecimal.ZERO.compareTo(noPrice.getPriceCache()), "未配价模型缓存价应为 0");

        // 配价后重查，缓存价随行回显
        service.upsert(MODEL, ModelPriceUpsertRequest.builder()
                .priceCache(new BigDecimal("0.30")).build());
        final ModelPriceCatalogItemDTO priced = service.catalog().get(0);
        assertTrue(Boolean.TRUE.equals(priced.getHasPrice()));
        assertEquals(0, new BigDecimal("0.30").compareTo(priced.getPriceCache()));
    }

    // ==================== 动态代理替身 ====================

    /** 熔断/限流仓库的空代理：findByInstanceId 返回 Optional.empty()，避免 catalog 里 .map 空指针 */
    private static <T> T emptyProxy(final Class<T> repositoryInterface) {
        return repositoryInterface.cast(Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(),
                new Class<?>[]{repositoryInterface},
                (proxy, method, args) -> {
                    if ("findAll".equals(method.getName())) {
                        return List.of();
                    }
                    if ("findByInstanceId".equals(method.getName())
                            && java.util.Optional.class.isAssignableFrom(method.getReturnType())) {
                        return Optional.empty();
                    }
                    return defaultValue(method.getReturnType());
                }));
    }

    private static Object defaultValue(final Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class == returnType) {
            return false;
        }
        if (long.class == returnType) {
            return 0L;
        }
        if (double.class == returnType) {
            return 0.0d;
        }
        if (float.class == returnType) {
            return 0.0f;
        }
        return 0;
    }

    /** 手写的 model_price 仓库替身（动态代理，避免为 JpaRepository 的数十个方法写样板） */
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

    /** service_instance 仓库替身：catalog 按已接入实例组装目录 */
    private static final class FakeInstanceRepository implements InvocationHandler {

        private final List<ServiceInstanceEntity> rows = new ArrayList<>();

        ServiceInstanceRepository proxy() {
            return (ServiceInstanceRepository) Proxy.newProxyInstance(
                    ServiceInstanceRepository.class.getClassLoader(),
                    new Class<?>[]{ServiceInstanceRepository.class}, this);
        }

        void add(final ServiceInstanceEntity entity) {
            rows.add(entity);
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            switch (method.getName()) {
                case "findAll":
                    return new ArrayList<>(rows);
                case "toString":
                    return "FakeInstanceRepository";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }

    /** model_provider / service_config 查名替身：findAll 返回带 id/name 或 id/serviceType 的实体 */
    private static final class FakeLookupRepository implements InvocationHandler {

        private final Map<Long, String> values = new LinkedHashMap<>();
        private final boolean providerMode;

        private FakeLookupRepository(final boolean providerMode) {
            this.providerMode = providerMode;
        }

        static FakeLookupRepository provider() {
            return new FakeLookupRepository(true);
        }

        static FakeLookupRepository config() {
            return new FakeLookupRepository(false);
        }

        <T> T proxy(final Class<T> repositoryInterface) {
            return repositoryInterface.cast(Proxy.newProxyInstance(
                    repositoryInterface.getClassLoader(),
                    new Class<?>[]{repositoryInterface}, this));
        }

        void add(final Long id, final String label) {
            values.put(id, label);
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            if ("findAll".equals(method.getName())) {
                final List<Object> out = new ArrayList<>();
                values.forEach((id, label) -> {
                    if (providerMode) {
                        out.add(org.unreal.modelrouter.persistence.jpa.entity.ModelProviderEntity.builder()
                                .id(id).name(label).build());
                    } else {
                        out.add(org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity.builder()
                                .id(id).serviceType(label).build());
                    }
                });
                return out;
            }
            switch (method.getName()) {
                case "toString":
                    return "FakeLookupRepository";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }
}
