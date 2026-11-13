/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.router.adapter.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.unreal.modelrouter.monitor.callhistory.ApiCallHistoryRecorder;
import org.unreal.modelrouter.monitor.callhistory.dto.CallHistoryRecordDTO;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.persistence.repository.ModelCallStatsRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 统计/调用历史双通道收口（v3.1 上层统计重复记录）。
 *
 * <p>缺陷背景：一次成功请求里 {@code BaseAdapter.doOnSuccess} 调
 * {@code recordCompleteCallStats}，processor 层又调 {@code recordCompleteCall}，
 * 两者都会走 {@code recordStats} —— Registry（LB 在途释放 + 熔断成功）、
 * MetricsCollector、StatsRepository 全部被记两次。
 * 现约定：统计归 BaseAdapter（恰一次），processor 层只走 {@code recordCallHistoryOnly}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdapterMetricsRecorder 统计/历史双通道收口")
class AdapterMetricsRecorderStatsDedupTest {

    private static final String ADAPTER = "test";
    private static final String INSTANCE_ID = "instA";
    private static final String MODEL = "gpt-4";

    @Mock private MetricsCollector metricsCollector;
    @Mock private ModelCallStatsRepository statsRepository;
    @Mock private ModelServiceRegistry registry;
    @Mock private ApiCallHistoryRecorder historyRecorder;

    private AdapterMetricsRecorder recorder;
    private ModelRouterProperties.ModelInstance instance;

    @BeforeEach
    void setUp() throws Exception {
        instance = new ModelRouterProperties.ModelInstance();
        instance.setName(INSTANCE_ID);
        instance.setInstanceId(INSTANCE_ID);
        instance.setBaseUrl("http://host-a:8080");

        recorder = new AdapterMetricsRecorder(metricsCollector, statsRepository, registry);
        final Field field = AdapterMetricsRecorder.class.getDeclaredField("callHistoryRecorder");
        field.setAccessible(true);
        field.set(recorder, historyRecorder);
    }

    @Test
    @DisplayName("仅历史入口：Registry/MetricsCollector/StatsRepository 零交互，历史仍落库")
    void historyOnly_shouldNotTouchStats() {
        recorder.recordCallHistoryOnly(ADAPTER, INSTANCE_ID, 12L, true, null,
                MODEL, ModelServiceRegistry.ServiceType.chat, instance);

        verifyNoInteractions(registry, metricsCollector, statsRepository);
        verify(historyRecorder, times(1)).record(any(CallHistoryRecordDTO.class));
    }

    @Test
    @DisplayName("仅历史入口（含 body/traceId）：同样零统计交互")
    void historyOnlyWithBody_shouldNotTouchStats() {
        recorder.recordCallHistoryOnly(ADAPTER, INSTANCE_ID, 12L, true, null,
                MODEL, ModelServiceRegistry.ServiceType.chat, instance,
                "{\"prompt\":\"x\"}", "{\"answer\":\"y\"}", "trace-1");

        verifyNoInteractions(registry, metricsCollector, statsRepository);
        verify(historyRecorder, times(1)).record(any(CallHistoryRecordDTO.class));
    }

    @Test
    @DisplayName("成功请求正确组合：统计通道恰一次、历史恰一次")
    void successCombination_shouldRecordStatsExactlyOnce() {
        recorder.recordCompleteCallStats(ADAPTER, INSTANCE_ID, 12L, true, null,
                MODEL, ModelServiceRegistry.ServiceType.chat, instance);
        recorder.recordCallHistoryOnly(ADAPTER, INSTANCE_ID, 12L, true, null,
                MODEL, ModelServiceRegistry.ServiceType.chat, instance);

        verify(registry, times(1)).recordCallComplete(any(), eq(instance), anyLong(), eq(true));
        verify(metricsCollector, times(1)).recordBackendCall(anyString(), anyString(), anyLong(), eq(true));
        verify(statsRepository, times(1)).updateStats(anyString(), anyString(), eq(true), anyLong());
        verify(historyRecorder, times(1)).record(any(CallHistoryRecordDTO.class));
    }

    @Test
    @DisplayName("反证：processor 若误用 recordCompleteCall，统计会被记两次")
    void legacyCombination_shouldDoubleCountStats() {
        recorder.recordCompleteCallStats(ADAPTER, INSTANCE_ID, 12L, true, null,
                MODEL, ModelServiceRegistry.ServiceType.chat, instance);
        recorder.recordCompleteCall(ADAPTER, INSTANCE_ID, 12L, true, null,
                MODEL, ModelServiceRegistry.ServiceType.chat, instance);

        verify(registry, times(2)).recordCallComplete(any(), eq(instance), anyLong(), eq(true));
        verify(metricsCollector, times(2)).recordBackendCall(anyString(), anyString(), anyLong(), eq(true));
        verify(statsRepository, times(2)).updateStats(anyString(), anyString(), eq(true), anyLong());
    }

    @Test
    @DisplayName("失败路径 recordCompleteCall：统计与历史仍各记一次（未退化）")
    void failurePath_shouldKeepStatsAndHistory() {
        recorder.recordCompleteCall(ADAPTER, INSTANCE_ID, 12L, false, "503",
                MODEL, ModelServiceRegistry.ServiceType.chat, instance);

        verify(registry, times(1)).recordCallFailure(any(), eq(instance), anyLong(), eq("503"));
        verify(metricsCollector, times(1)).recordBackendCall(anyString(), anyString(), anyLong(), eq(false));
        verify(statsRepository, times(1)).updateStats(anyString(), anyString(), eq(false), anyLong());
        verify(statsRepository, times(1)).recordErrorCode(anyString(), anyString(), eq("503"));
        verify(historyRecorder, times(1)).record(any(CallHistoryRecordDTO.class));
    }

    @Test
    @DisplayName("客户端 4xx 路径：Registry 只释放在途，不记熔断失败（#647 契约未退化）")
    void clientErrorPath_shouldKeepClientErrorContract() {
        recorder.recordClientErrorCompleteCall(ADAPTER, INSTANCE_ID, 12L, "400",
                MODEL, ModelServiceRegistry.ServiceType.chat, instance);

        verify(registry, times(1)).recordClientErrorComplete(any(), eq(instance), anyLong());
        verify(registry, never()).recordCallFailure(any(), any(), anyLong(), anyString());
        verify(historyRecorder, times(1)).record(any(CallHistoryRecordDTO.class));
    }
}
