<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 一期占位页：菜单树已注册、组件随二~四期收编
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '功能建设')
const subtitle = computed(() => {
  const map: Record<string, string> = {
    models: '已接入模型 / 公共模型库 / 模型适配器（二期交付）',
    playground: '对话 / 向量 / 重排 / 语音 / 图像（二期交付）',
    keys: '签发 / 吊销 / 配额（二期交付）',
    observe: '调用历史 / Token / 慢查询 / 异常 / 配额 / 熔断 / 限流（三期交付）',
    tracing: '链路追踪（三期交付）',
    strategy: '路由规则 / 负载均衡 / 熔断阈值 / 响应缓存（四期交付）',
    quota: '用量配额（四期交付）',
    settings: '账号 / 权限 / 审计日志 / 配置版本 / 数据持久化（四期交付）'
  }
  return map[String(route.name)] || '该功能将随后续批次收编交付'
})
</script>

<template>
  <div>
    <PageHeader :title="title" :subtitle="subtitle" />
    <el-card shadow="never">
      <el-empty description="旧控制台（8080/admin）对应功能并行可用" />
    </el-card>
  </div>
</template>
