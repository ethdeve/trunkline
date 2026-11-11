package org.unreal.modelrouter.monitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.monitor.dto.ModelCallStats;
import org.unreal.modelrouter.persistence.repository.ModelCallStatsRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 问题清单 m-11 回归测试：分页参数的防御性夹逼。
 *
 * <p>原实现 {@code (page-1)*size} 用 int 乘会溢出，{@code (total+size-1)/size} 在 size=0 时除零。
 * 修复后正常值行为不变，非法值不再抛异常/返回负数页。
 */
@DisplayName("问题清单低风险修复回归：分页参数边界")
@ExtendWith(MockitoExtension.class)
class ModelCallAnalyzerPaginationTest {

    @Mock
    private ModelCallStatsRepository statsRepository;

    private ModelCallAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ModelCallAnalyzer(statsRepository);
        List<ModelCallStats> stats = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            stats.add(ModelCallStats.builder()
                    .modelName("model-" + i)
                    .serviceType("chat")
                    .totalCalls(100L - i)
                    .successCount(90L)
                    .failureCount(10L - i)
                    .build());
        }
        when(statsRepository.getAllStats()).thenReturn(stats);
        when(statsRepository.getCurrentQps(anyString(), anyString())).thenReturn(1.0d);
    }

    @Test
    @DisplayName("m-11: 正常分页行为不变")
    void normalPaginationUnchanged() {
        Map<String, Object> r = analyzer.getAllModelStats(null, 2, 2, "totalCalls", false);
        assertEquals(5, r.get("total"));
        assertEquals(2, r.get("page"));
        assertEquals(2, r.get("size"));
        assertEquals(3, r.get("totalPages"));
        assertEquals(2, ((List<?>) r.get("content")).size());
    }

    @Test
    @DisplayName("m-11: size=0 不触发除零，夹逼为 1")
    void zeroSizeNoDivideByZero() {
        assertDoesNotThrow(() -> {
            Map<String, Object> r = analyzer.getAllModelStats(null, 1, 0, "totalCalls", false);
            assertEquals(1, r.get("size"), "size 应被夹逼为 1");
            assertEquals(5, r.get("totalPages"));
        });
    }

    @Test
    @DisplayName("m-11: 负 page 夹逼为 1，不产生负索引")
    void negativePageClamped() {
        assertDoesNotThrow(() -> {
            Map<String, Object> r = analyzer.getAllModelStats(null, -5, 2, "totalCalls", false);
            assertEquals(1, r.get("page"));
            assertFalse(((List<?>) r.get("content")).isEmpty());
        });
    }

    @Test
    @DisplayName("m-11: 极大 page 不因 int 溢出崩溃，返回空页")
    void hugePageNoOverflow() {
        assertDoesNotThrow(() -> {
            Map<String, Object> r = analyzer.getAllModelStats(null, Integer.MAX_VALUE, 100, "totalCalls", false);
            assertEquals(Integer.MAX_VALUE, r.get("page"));
            assertTrue(((List<?>) r.get("content")).isEmpty(), "超大页码应返回空内容");
        });
    }

    @Test
    @DisplayName("m-11: size 超过上限夹逼为 100")
    void oversizedSizeClamped() {
        Map<String, Object> r = analyzer.getAllModelStats(null, 1, 1000, "totalCalls", false);
        assertEquals(100, r.get("size"));
    }
}
