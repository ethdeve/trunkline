package org.unreal.modelrouter.auth.security.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额账本「分组聚合 vs 组内明细」口径测试（v3.5 治理收口）。
 *
 * <p><b>背景</b>：v3.5 单线治理下，一次请求会写入<b>两行</b>账本——预留期写分组桶
 * （{@code group_id = 分组名, model = ""}），选实例后补记成员桶
 * （{@code group_id = 分组名, model = 实例名}）。两者八列唯一键不同，是并列的两行。</p>
 *
 * <p>因此必须严格区分两个口径：</p>
 * <ul>
 *   <li><b>分组聚合</b>：只统计分组桶行（{@code usageByGroupAggregate}）；</li>
 *   <li><b>组内明细</b>：只统计成员桶行（{@code usageByGroupMembers}）。</li>
 * </ul>
 * <p>两者互斥、不可相加——混在一起求和会让分组用量翻倍（真实缺陷：修复前
 * {@code usageByDimension(ofGroup(pool))} 同时命中两族行，聚合值翻倍）。</p>
 *
 * <p>使用真实服务实现 + 手写内存仓库，不使用 Mockito mock。</p>
 *
 * @author JAiRouter Team
 * @since 3.5.0
 */
@DisplayName("QuotaLedgerService 分组聚合/组内明细口径测试")
class QuotaLedgerServiceGroupScopeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 14, 13, 45, 30);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String POOL = "pool-a";

    private InMemoryQuotaLedgerRepository repository;
    private QuotaProperties properties;

    @BeforeEach
    void setUp() {
        repository = InMemoryQuotaLedgerRepository.create();
        properties = new QuotaProperties();
        properties.setEnabled(true);
    }

    @Test
    @DisplayName("分组聚合只统计分组桶行，不与成员桶重复计数")
    void groupAggregateExcludesMemberRows() {
        final QuotaLedgerService service = service();
        final LocalDateTime dayStart = QuotaWindow.DAY.windowStart(NOW);

        // 分组桶：3 次请求 / 300 token
        repository.seedWithGroup(POOL, "", "", "", "", QuotaWindow.DAY.name(), dayStart, 3L, 300L);
        // 成员桶（实例 i-1）：2 次 / 200；成员桶（实例 i-2）：1 次 / 100
        repository.seedWithGroup(POOL, "", "", "", "i-1", QuotaWindow.DAY.name(), dayStart, 2L, 200L);
        repository.seedWithGroup(POOL, "", "", "", "i-2", QuotaWindow.DAY.name(), dayStart, 1L, 100L);

        final List<QuotaUsage> aggregate = service.usageByGroupAggregate(POOL);

        assertEquals(1, aggregate.size(), "分组聚合只应返回分组桶行（1 行）");
        final QuotaUsage usage = aggregate.get(0);
        assertEquals(3L, usage.requestCount(), "分组聚合请求数必须是分组桶的值（3），不能是 3+2+1=6");
        assertEquals(300L, usage.tokenCount(), "分组聚合 token 数必须是分组桶的值（300），不能是 600");
        assertTrue(usage.dimension().model().isEmpty(), "分组聚合行的 model 必须为空");
        assertEquals(POOL, usage.dimension().groupId());
    }

    @Test
    @DisplayName("组内明细只统计成员桶行，按实例拆分")
    void groupMembersReturnsMemberRowsOnly() {
        final QuotaLedgerService service = service();
        final LocalDateTime dayStart = QuotaWindow.DAY.windowStart(NOW);

        repository.seedWithGroup(POOL, "", "", "", "", QuotaWindow.DAY.name(), dayStart, 3L, 300L);
        repository.seedWithGroup(POOL, "", "", "", "i-1", QuotaWindow.DAY.name(), dayStart, 2L, 200L);
        repository.seedWithGroup(POOL, "", "", "", "i-2", QuotaWindow.DAY.name(), dayStart, 1L, 100L);

        final List<QuotaUsage> members = service.usageByGroupMembers(POOL);

        assertEquals(2, members.size(), "组内明细应返回 2 个成员桶行");
        long requests = 0L;
        long tokens = 0L;
        for (final QuotaUsage usage : members) {
            assertFalse(usage.dimension().model().isEmpty(), "组内明细行的 model 必须非空（实例名）");
            requests += usage.requestCount();
            tokens += usage.tokenCount();
        }
        assertEquals(3L, requests, "组内明细请求数之和应等于分组桶的 3");
        assertEquals(300L, tokens, "组内明细 token 数之和应等于分组桶的 300");
    }

    @Test
    @DisplayName("两口径互斥：聚合值与明细之和各自独立，不得相加")
    void aggregateAndMembersAreMutuallyExclusive() {
        final QuotaLedgerService service = service();
        final LocalDateTime dayStart = QuotaWindow.DAY.windowStart(NOW);

        repository.seedWithGroup(POOL, "", "", "", "", QuotaWindow.DAY.name(), dayStart, 3L, 300L);
        repository.seedWithGroup(POOL, "", "", "", "i-1", QuotaWindow.DAY.name(), dayStart, 3L, 300L);

        final long aggregate = service.usageByGroupAggregate(POOL).get(0).requestCount();
        final long memberSum = service.usageByGroupMembers(POOL).stream()
                .mapToLong(QuotaUsage::requestCount).sum();

        assertEquals(3L, aggregate);
        assertEquals(3L, memberSum);
        // 关键断言：两者各自都是 3，若按旧口径混算会得到 6（翻倍）
        assertEquals(3L, aggregate, "分组聚合必须是 3");
        assertEquals(3L, memberSum, "组内明细之和必须是 3");
        assertEquals(6L, aggregate + memberSum, "混加后为 6，这正是不可相加的原因");
    }

    @Test
    @DisplayName("usageByDimension(分组) 只命中分组桶行（修复前会翻倍）")
    void usageByDimensionWithGroupScopeExcludesMembers() {
        final QuotaLedgerService service = service();
        final LocalDateTime dayStart = QuotaWindow.DAY.windowStart(NOW);

        repository.seedWithGroup(POOL, "", "", "", "", QuotaWindow.DAY.name(), dayStart, 3L, 300L);
        repository.seedWithGroup(POOL, "", "", "", "i-1", QuotaWindow.DAY.name(), dayStart, 3L, 300L);

        final List<QuotaUsage> usages = service.usageByDimension(QuotaDimension.ofGroup(POOL));

        assertEquals(1, usages.size(), "按分组查询只应命中分组桶行");
        assertEquals(3L, usages.get(0).requestCount(), "按分组查询的请求数不得因成员桶而翻倍");
        assertEquals(300L, usages.get(0).tokenCount());
    }

    @Test
    @DisplayName("usageByDimension(分组+模型) 命中成员桶行")
    void usageByDimensionWithGroupAndModelHitsMemberRow() {
        final QuotaLedgerService service = service();
        final LocalDateTime dayStart = QuotaWindow.DAY.windowStart(NOW);

        repository.seedWithGroup(POOL, "", "", "", "", QuotaWindow.DAY.name(), dayStart, 3L, 300L);
        repository.seedWithGroup(POOL, "", "", "", "i-1", QuotaWindow.DAY.name(), dayStart, 2L, 200L);

        final List<QuotaUsage> usages = service.usageByDimension(QuotaDimension.ofGroupAndModel(POOL, "i-1"));

        assertEquals(1, usages.size());
        assertEquals(2L, usages.get(0).requestCount());
        assertEquals("i-1", usages.get(0).dimension().model());
    }

    @Test
    @DisplayName("按 API Key 维度查询不受分组口径影响（Key 总闸仍精确命中）")
    void apiKeyScopeUnaffected() {
        final QuotaLedgerService service = service();
        final LocalDateTime dayStart = QuotaWindow.DAY.windowStart(NOW);

        repository.seedWithGroup("", "key-1", "", "", "", QuotaWindow.DAY.name(), dayStart, 4L, 400L);
        repository.seedWithGroup(POOL, "", "", "", "", QuotaWindow.DAY.name(), dayStart, 3L, 300L);
        repository.seedWithGroup(POOL, "", "", "", "i-1", QuotaWindow.DAY.name(), dayStart, 3L, 300L);

        final List<QuotaUsage> usages = service.usageByDimension(QuotaDimension.ofApiKey("key-1"));

        assertEquals(1, usages.size(), "按 Key 查询只应命中 Key 总闸行");
        assertEquals(4L, usages.get(0).requestCount());
    }

    @Test
    @DisplayName("空分组名：两口径均返回空列表")
    void blankGroupIdReturnsEmpty() {
        final QuotaLedgerService service = service();

        assertTrue(service.usageByGroupAggregate("").isEmpty());
        assertTrue(service.usageByGroupMembers("").isEmpty());
        assertTrue(service.usageByGroupAggregate(null).isEmpty());
        assertTrue(service.usageByGroupMembers(null).isEmpty());
    }

    @Test
    @DisplayName("内存态与数据库快照口径一致（同一行不会被两侧重复计入）")
    void memoryAndDatabaseUseSameFilter() {
        final QuotaLedgerService service = service();
        final LocalDateTime dayStart = QuotaWindow.DAY.windowStart(NOW);

        // 先在内存态预留一行分组桶（走 reserve，不落库）
        service.reserve(QuotaRequest.of(QuotaDimension.ofGroup(POOL), 100));
        // 数据库侧预置成员桶行
        repository.seedWithGroup(POOL, "", "", "", "i-1", QuotaWindow.DAY.name(), dayStart, 5L, 500L);

        final List<QuotaUsage> aggregate = service.usageByGroupAggregate(POOL);
        final List<QuotaUsage> members = service.usageByGroupMembers(POOL);

        for (final QuotaUsage usage : aggregate) {
            assertTrue(usage.dimension().model().isEmpty(), "聚合口径不得混入成员桶行");
        }
        for (final QuotaUsage usage : members) {
            assertFalse(usage.dimension().model().isEmpty(), "明细口径不得混入分组桶行");
        }
        assertFalse(aggregate.isEmpty(), "内存态中的分组桶行应可见");
        assertEquals(1, members.size(), "数据库侧的成员桶行应可见");
    }

    /**
     * 构造账本服务（本地后端 + 内存仓库 + 固定时钟）.
     *
     * @return 账本服务
     */
    private QuotaLedgerService service() {
        return new QuotaLedgerService(repository.proxy(), properties,
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
    }
}
