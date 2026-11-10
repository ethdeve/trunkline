/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.unreal.modelrouter.persistence.jpa.entity.ApiCallHistoryEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ApiCallHistoryRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「调用观测」筛选条件集成测试（v3.5 Trunkline UI 改造）
 *
 * <p>用真实 H2 验证 {@code ApiCallHistoryRepository.findWithFilters} 的 JPQL 语法
 * （Spring Data JPA 在启动时校验 {@code @Query}，语法错误会直接导致上下文启动失败），
 * 以及 v3.5 新增筛选维度的行为：</p>
 * <ul>
 *   <li>{@code virtualModel} —— 虚拟模型（模型分组）精确匹配；</li>
 *   <li>{@code userId} —— 员工精确匹配；</li>
 *   <li>{@code keyword} —— 多字段模糊 OR 匹配（员工 / 虚拟模型 / Trace ID / 模型名 / 实例名）；</li>
 *   <li>{@code rateLimited} —— 限流标记（与 isSuccess 正交：限流时 isSuccess 仍为 true）。</li>
 * </ul>
 *
 * @author Trunkline Team
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("调用观测筛选条件集成测试")
class ApiCallHistoryFilterIntegrationTest {

    @Autowired
    private ApiCallHistoryRepository repository;

    private ApiCallHistoryEntity record(final String traceId, final String userId,
                                        final String virtualModel, final String instanceName,
                                        final String modelName,
                                        final boolean success, final boolean rateLimited) {
        final ApiCallHistoryEntity e = new ApiCallHistoryEntity();
        e.setTraceId(traceId);
        e.setRequestId(traceId + "-req");
        e.setRequestMethod("POST");
        e.setRequestPath("/v1/chat/completions");
        e.setServiceType("chat");
        e.setModelName(modelName);
        e.setVirtualModel(virtualModel);
        e.setUserId(userId);
        e.setInstanceName(instanceName);
        e.setProvider("normal");
        e.setPromptTokens(100L);
        e.setCompletionTokens(50L);
        e.setTotalTokens(150L);
        e.setResponseTimeMs(1200L);
        e.setIsSuccess(success);
        e.setRateLimited(rateLimited);
        e.setCircuitBroken(false);
        e.setRequestDate("2026-10-30");
        e.setRequestHour(10);
        e.setCreatedAt(LocalDateTime.of(2026, 10, 30, 10, 24, 18));
        return e;
    }

    @Test
    @DisplayName("虚拟模型与员工维度可精确筛选")
    void filterByVirtualModelAndUser() {
        repository.saveAll(List.of(
                record("trace-a", "王小明", "公司主力", "GPT-4o", "gpt-4o", true, false),
                record("trace-b", "李四", "便宜日常", "DeepSeek", "deepseek-chat", true, false),
                record("trace-c", "王小明", "便宜日常", "DeepSeek", "deepseek-chat", true, false)
        ));

        final Page<ApiCallHistoryEntity> byVirtual = repository.findWithFilters(
                null, null, null, "公司主力", null, null, null, null, null, null, null,
                PageRequest.of(0, 20));
        assertEquals(1, byVirtual.getTotalElements());
        assertEquals("王小明", byVirtual.getContent().get(0).getUserId());

        final Page<ApiCallHistoryEntity> byUser = repository.findWithFilters(
                null, null, null, null, "王小明", null, null, null, null, null, null,
                PageRequest.of(0, 20));
        assertEquals(2, byUser.getTotalElements());
    }

    @Test
    @DisplayName("关键字可跨员工/虚拟模型/Trace ID/模型名/实例名模糊匹配")
    void filterByKeyword() {
        repository.saveAll(List.of(
                record("trace-a", "王小明", "公司主力", "GPT-4o", "gpt-4o", true, false),
                record("trace-b", "李四", "便宜日常", "DeepSeek", "deepseek-chat", true, false)
        ));

        // 按员工
        assertEquals(1, repository.findWithFilters(null, null, null, null, null, null, null,
                null, null, null, "李四", PageRequest.of(0, 20)).getTotalElements());
        // 按虚拟模型
        assertEquals(1, repository.findWithFilters(null, null, null, null, null, null, null,
                null, null, null, "公司主力", PageRequest.of(0, 20)).getTotalElements());
        // 按 Trace ID
        assertEquals(1, repository.findWithFilters(null, null, null, null, null, null, null,
                null, null, null, "trace-b", PageRequest.of(0, 20)).getTotalElements());
        // 按模型名
        assertEquals(1, repository.findWithFilters(null, null, null, null, null, null, null,
                null, null, null, "deepseek", PageRequest.of(0, 20)).getTotalElements());
        // 按实例名
        assertEquals(1, repository.findWithFilters(null, null, null, null, null, null, null,
                null, null, null, "GPT-4o", PageRequest.of(0, 20)).getTotalElements());
    }

    @Test
    @DisplayName("限流筛选与 isSuccess 正交——限流行 isSuccess 仍为 true")
    void filterByRateLimited() {
        repository.saveAll(List.of(
                record("trace-ok", "王小明", "公司主力", "GPT-4o", "gpt-4o", true, false),
                record("trace-limited", "李四", "便宜日常", "DeepSeek", "deepseek-chat", true, true),
                record("trace-failed", "张三", "做知识库", "bge-m3", "bge-m3", false, false)
        ));

        // 限流行：isSuccess=true 但 rateLimited=true
        final Page<ApiCallHistoryEntity> limited = repository.findWithFilters(
                null, null, null, null, null, null, null, null, true, null, null,
                PageRequest.of(0, 20));
        assertEquals(1, limited.getTotalElements());
        assertEquals("trace-limited", limited.getContent().get(0).getTraceId());
        assertTrue(limited.getContent().get(0).getIsSuccess());

        // 失败行不含限流行
        final Page<ApiCallHistoryEntity> failed = repository.findWithFilters(
                null, null, null, null, null, null, null, false, null, null, null,
                PageRequest.of(0, 20));
        assertEquals(1, failed.getTotalElements());
        assertEquals("trace-failed", failed.getContent().get(0).getTraceId());
    }

    @Test
    @DisplayName("无筛选条件时返回全部")
    void noFilterReturnsAll() {
        repository.saveAll(List.of(
                record("trace-a", "王小明", "公司主力", "GPT-4o", "gpt-4o", true, false),
                record("trace-b", "李四", "便宜日常", "DeepSeek", "deepseek-chat", false, true)
        ));

        final Page<ApiCallHistoryEntity> all = repository.findWithFilters(
                null, null, null, null, null, null, null, null, null, null, null,
                PageRequest.of(0, 20));
        assertEquals(2, all.getTotalElements());
    }
}
