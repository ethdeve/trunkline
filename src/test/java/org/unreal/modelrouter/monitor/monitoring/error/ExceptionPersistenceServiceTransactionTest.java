package org.unreal.modelrouter.monitor.monitoring.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.unreal.modelrouter.persistence.jpa.entity.ExceptionStatsHourlyEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ExceptionEventRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * issue #857：小时统计刷新的事务生效性。
 *
 * <p>用真实的 {@link AbstractPlatformTransactionManager} 子类而非 mock 驱动事务，
 * 这样 Spring 会完整激活 synchronization，并提交/回滚时触发 afterCommit 回调，
 * 能真实还原代理与自调用的差异。</p>
 */
@DisplayName("ExceptionPersistenceService 小时统计事务（#857）")
class ExceptionPersistenceServiceTransactionTest {

    private RecordingTransactionManager txManager;
    private EntityManager entityManager;
    private ExceptionPersistenceService service;
    private Map<String, ExceptionStatsHourlyEntity> cache;
    private final List<Boolean> activeOnMerge = new ArrayList<>();

    @BeforeEach
    void setUp() {
        txManager = new RecordingTransactionManager();
        entityManager = mock(EntityManager.class);
        service = newService(txManager, entityManager);
        cache = cacheOf(service);
        activeOnMerge.clear();
        doAnswer(invocation -> {
            activeOnMerge.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.getArgument(0);
        }).when(entityManager).merge(any());
    }

    private ExceptionPersistenceService newService(final PlatformTransactionManager tm,
                                                   final EntityManager em) {
        ExceptionPersistenceService svc = new ExceptionPersistenceService(
                mock(ExceptionEventRepository.class), new ObjectMapper(),
                mock(ErrorCodeResolver.class), tm);
        ReflectionTestUtils.setField(svc, "entityManager", em);
        return svc;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ExceptionStatsHourlyEntity> cacheOf(final ExceptionPersistenceService svc) {
        return (Map<String, ExceptionStatsHourlyEntity>) ReflectionTestUtils.getField(svc, "hourlyStatsCache");
    }

    private static ExceptionStatsHourlyEntity stats() {
        return new ExceptionStatsHourlyEntity();
    }

    @Test
    @DisplayName("定时入口开启真实事务；注解入口自调用时无事务（缺陷反证）")
    void scheduledFlushRunsInTransaction() {
        cache.put("k1", stats());
        service.flushHourlyStatsSafely();

        assertEquals(List.of(true), activeOnMerge, "定时任务必须在真实事务内执行 merge");
        assertTrue(txManager.committed, "事务应正常提交");
        assertTrue(cache.isEmpty(), "提交后应清理缓存");

        // 反证：直接调用注解方法 = 未经代理的自调用，@Transactional 不生效
        activeOnMerge.clear();
        cache.put("k1", stats());
        service.flushHourlyStatsToDatabase();

        assertEquals(List.of(false), activeOnMerge, "绕过代理的自调用仍无事务，这是 #857 的根因");
        assertFalse(cache.isEmpty(), "无事务同步时保守保留缓存，不丢数据");
    }

    @Test
    @DisplayName("提交后按快照精准清理，不清掉刷新期间并发新增的统计")
    void afterCommitRemovesOnlyFlushedEntries() {
        cache.put("k1", stats());
        ExceptionStatsHourlyEntity concurrent = stats();
        doAnswer(invocation -> {
            activeOnMerge.add(TransactionSynchronizationManager.isActualTransactionActive());
            cache.put("k2-concurrent", concurrent); // 模拟 persistException 并发写入
            return invocation.getArgument(0);
        }).when(entityManager).merge(any());

        service.flushHourlyStatsSafely();

        assertTrue(txManager.committed);
        assertFalse(cache.containsKey("k1"), "本次已落库的条目应被清理");
        assertTrue(cache.containsKey("k2-concurrent"), "刷新期间新增的条目不得被 clear() 误删");
    }

    @Test
    @DisplayName("merge 抛异常时整体回滚，缓存保留且下一轮可重试落库")
    void mergeFailureRollsBackAndKeepsCache() {
        cache.put("k1", stats());
        doThrow(new RuntimeException("boom")).when(entityManager).merge(any());

        assertDoesNotThrow(() -> service.flushHourlyStatsSafely(), "异常不得终止定时任务的后续调度");

        assertTrue(txManager.rolledBack, "异常必须触发事务回滚");
        assertFalse(txManager.committed);
        assertTrue(cache.containsKey("k1"), "回滚后缓存必须保留，否则统计数据永久丢失");

        doAnswer(invocation -> invocation.getArgument(0)).when(entityManager).merge(any());
        service.flushHourlyStatsSafely();

        assertTrue(txManager.committed, "下一轮重试应正常提交");
        assertTrue(cache.isEmpty());
    }

    @Test
    @DisplayName("@PreDestroy shutdown 也通过显式事务落库")
    void shutdownFlushesInTransaction() {
        cache.put("k1", stats());

        service.shutdown();

        assertEquals(List.of(true), activeOnMerge, "关闭钩子同样不能绕过事务");
        assertTrue(txManager.committed);
        assertTrue(cache.isEmpty());
    }

    @Test
    @DisplayName("事务管理器已关闭时 shutdown 不抛出（@PreDestroy 兜底）")
    void shutdownToleratesClosedTransactionManager() {
        PlatformTransactionManager closed = mock(PlatformTransactionManager.class);
        doThrow(new IllegalStateException("tm already closed")).when(closed).getTransaction(any());
        ExceptionPersistenceService svc = newService(closed, entityManager);
        cacheOf(svc).put("k1", stats());

        assertDoesNotThrow(svc::shutdown, "关闭阶段事务管理器不可用时必须兜底，不能拖垮容器停机");
        assertFalse(cacheOf(svc).isEmpty(), "落库失败的缓存应保留");
    }

    @Test
    @DisplayName("空缓存不空转事务")
    void emptyCacheSkipsTransaction() {
        service.flushHourlyStatsSafely();

        verify(entityManager, never()).merge(any());
        assertFalse(txManager.committed);
    }

    /** 最小事务管理器：走 Spring 标准的 synchronization / 提交 / 回滚流程，无需启动 Spring 上下文 */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private boolean committed;
        private boolean rolledBack;

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(final Object transaction, final TransactionDefinition definition) {
            // 无外部资源，事务状态由父类 prepareSynchronization 激活
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(final DefaultTransactionStatus status) {
            rolledBack = true;
        }
    }
}
