package org.unreal.modelrouter.config.sync.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.config.core.ConfigMergeService;
import org.unreal.modelrouter.config.core.dto.ServiceConfiguration;
import org.unreal.modelrouter.persistence.store.StoreManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StoreConfigRepository 读取链回退测试。
 *
 * <p>2026-11-21 模型分类适配器列全空 + 编辑报「服务内部错误」的回归：
 * 当前持久化配置行（v0）读不到时，findById 只直读当前行 → NotFoundException → 500；
 * 而分类目录（getAvailableServiceTypes）走 ConfigMergeService 的版本快照回退
 * 仍能列出全部分类——读分裂。修复后 findById / findAll / getCurrentConfig /
 * findRateLimitRaw 与目录同源（当前行 → 版本快照 → YAML 默认）。</p>
 */
@DisplayName("StoreConfigRepository 读取链回退测试")
@ExtendWith(MockitoExtension.class)
class StoreConfigRepositoryFallbackTest {

    @Mock
    private StoreManager storeManager;

    @Mock
    private ConfigMergeService configMergeService;

    private StoreConfigRepository newRepository() {
        return new StoreConfigRepository(storeManager, configMergeService);
    }

    private Map<String, Object> mergedConfig() {
        return Map.of("services", Map.of(
                "chat", Map.of("adapter", "normal", "instances", List.of()),
                "embedding", Map.of("adapter", "normal", "instances", List.of())));
    }

    @Test
    @DisplayName("findById 当前配置读不到时回退合并配置，chat 可读")
    void findByIdFallsBackToMergedConfig() {
        when(storeManager.getConfig("trunkline-config")).thenReturn(null);
        when(configMergeService.getPersistedConfig()).thenReturn(mergedConfig());

        final Optional<ServiceConfiguration> result = newRepository().findById("chat");

        assertThat(result).isPresent();
        assertThat(result.get().adapter()).isEqualTo("normal");
    }

    @Test
    @DisplayName("findById 当前配置可读时不触发回退")
    void findByIdUsesCurrentConfigWithoutFallback() {
        when(storeManager.getConfig("trunkline-config")).thenReturn(mergedConfig());

        final Optional<ServiceConfiguration> result = newRepository().findById("embedding");

        assertThat(result).isPresent();
        verify(configMergeService, never()).getPersistedConfig();
    }

    @Test
    @DisplayName("save 时当前行缺失基于合并配置写入，不会抹掉其他分类")
    void saveMergesWithFallbackInsteadOfWipingOthers() {
        lenient().when(storeManager.getConfig("trunkline-config")).thenReturn(null);
        when(configMergeService.getPersistedConfig()).thenReturn(mergedConfig());

        final StoreConfigRepository repository = newRepository();
        repository.save("chat", ServiceConfiguration.defaultConfig());

        org.mockito.Mockito.verify(storeManager).saveConfig(
                org.mockito.Mockito.eq("trunkline-config"),
                org.mockito.ArgumentMatchers.argThat(saved -> {
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> services = (Map<String, Object>) saved.get("services");
                    // 关键：embedding 分类不被保存操作抹掉
                    return services != null && services.containsKey("chat") && services.containsKey("embedding");
                }));
    }
}
