<script setup lang="ts">
/**
 * 接入的模型（普通用户视角：只保留「已接入模型」核心列表）
 * - 原 4 个 tab（已接入模型 / 模型分类 / 公共模型库 / 适配器设置）合在一个页面里，普通用户看不懂差异
 * - 现仅展示「已接入模型」；其余三个高级管理入口从左侧菜单「系统」组进入
 * - 内部组件继续进入 PageSkeleton 嵌入模式，统一由 PageHeader 承担页头
 */
import { provide } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import InstanceManagement from '@/views/config/InstanceManagement.vue'

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader title="接入的模型" subtitle="管理你已接入的上游模型" />

    <el-card shadow="never">
      <InstanceManagement />
    </el-card>
  </div>
</template>
